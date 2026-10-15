package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimklOAuth2ClientTest {
    @Test
    fun `device authorization requests write access and prefers the complete URL`(): Unit = runBlocking {
        val engine = Engine(response(200, DEVICE))
        val pending = client(engine).authorizeDevice()
        assertEquals("secret-device", pending.deviceCode)
        assertEquals("ABCD-EFGH", pending.userCode)
        assertEquals("https://simkl.com/pin?user_code=ABCD-EFGH", pending.verificationUrl)
        assertEquals(900L, pending.expiresInSeconds)
        val request = engine.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.simkl.com/oauth2/device", request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("Nuvio/1.0", request.headers["User-Agent"])
        assertFalse("Authorization" in request.headers)
        val form = parseQueryString(request.body)
        assertEquals("client +&id", form["client_id"])
        assertEquals(SIMKL_OAUTH2_SCOPE, form["scope"])
        assertFalse("secret-device" in request.url)
    }

    @Test
    fun `poll uses device secret in form and handles documented pending states`(): Unit = runBlocking {
        val engine = Engine(
            response(400, """{"error":"authorization_pending"}"""),
            response(400, """{"error":"slow_down"}"""),
            response(400, """{"error":"expired_token"}"""),
            response(200, TOKENS),
        )
        val client = client(engine)
        assertIs<SimklDevicePollResult.Pending>(client.pollDevice("private +&device"))
        assertIs<SimklDevicePollResult.SlowDown>(client.pollDevice("private +&device"))
        assertIs<SimklDevicePollResult.Expired>(client.pollDevice("private +&device"))
        val result = assertIs<SimklDevicePollResult.Authorized>(client.pollDevice("private +&device"))
        assertEquals("test-access", result.tokens.accessToken)
        engine.requests.forEach {
            val form = parseQueryString(it.body)
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"])
            assertEquals("private +&device", form["device_code"])
            assertFalse("device_code" in it.url)
            assertFalse("user_code" in it.body)
        }
    }

    @Test
    fun `rate limits and temporary service failures preserve pending approval`(): Unit = runBlocking {
        val engine = Engine(response(429, """{"error":"rate_limit"}"""), response(503, "unavailable"))
        assertIs<SimklDevicePollResult.SlowDown>(client(engine).pollDevice("device"))
        assertIs<SimklDevicePollResult.Pending>(client(engine).pollDevice("device"))
    }

    @Test
    fun `legacy or rejected client IDs have a specific safe error`(): Unit = runBlocking {
        val engine = Engine(response(401, """{"error":"invalid_client","error_description":"secret-device"}"""))
        val error = assertFailsWith<SimklOAuth2Exception> { client(engine).authorizeDevice() }
        assertEquals(SimklAuthError.INVALID_CLIENT, error.authError)
        assertFalse(error.isTransient)
        assertFalse("secret-device" in error.toString())
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `read only and unknown scopes cannot silently disable scrobbling`(): Unit = runBlocking {
        for (scope in listOf("media:read", "media:read media:typo", "")) {
            val engine = Engine(response(200, TOKENS.replace(SIMKL_OAUTH2_SCOPE, scope)))
            val error = assertFailsWith<SimklOAuth2Exception> { client(engine).pollDevice("device") }
            assertEquals(SimklAuthError.INSUFFICIENT_SCOPE, error.authError)
        }
    }

    @Test
    fun `refresh stores returned pair and accepts a non rotating refresh token`(): Unit = runBlocking {
        val engine = Engine(response(200, TOKENS))
        val tokens = client(engine).refresh("test-refresh")
        assertEquals("test-access", tokens.accessToken)
        assertEquals("test-refresh", tokens.refreshToken)
        assertEquals(604800L, tokens.expiresInSeconds)
        val form = parseQueryString(engine.requests.single().body)
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("test-refresh", form["refresh_token"])
        assertFalse("client_secret" in form.names())
    }

    @Test
    fun `invalid grant is permanent but server and transport failures are transient`(): Unit = runBlocking {
        val invalid = assertFailsWith<SimklOAuth2Exception> {
            client(Engine(response(400, """{"error":"invalid_grant"}"""))).refresh("refresh")
        }
        assertEquals(SimklAuthError.AUTHORIZATION_REVOKED, invalid.authError)
        assertFalse(invalid.isTransient)
        val server = assertFailsWith<SimklOAuth2Exception> {
            client(Engine(response(503, "unavailable"))).refresh("refresh")
        }
        assertTrue(server.isTransient)
        val transport = assertFailsWith<SimklOAuth2Exception> {
            client(SimklHttpEngine { _, _, _, _ -> error("private-refresh") }).refresh("refresh")
        }
        assertTrue(transport.isTransient)
        assertEquals(SimklAuthError.CONNECTION_FAILED, transport.authError)
        assertFalse("private-refresh" in transport.toString())
    }

    @Test
    fun `malformed and non bearer token responses are rejected`(): Unit = runBlocking {
        for (body in listOf("not json", "{}", TOKENS.replace("Bearer", "Basic"), TOKENS.replace("604800", "0"))) {
            val error = assertFailsWith<SimklOAuth2Exception> {
                client(Engine(response(200, body))).refresh("refresh")
            }
            assertEquals(SimklAuthError.INVALID_TOKEN_RESPONSE, error.authError)
        }
    }

    @Test
    fun `authorization code exchange is form encoded with PKCE and exact redirect`(): Unit = runBlocking {
        val engine = Engine(response(200, TOKENS))
        client(engine).exchangeCode("code +&", "verifier +&", "nuvio://auth/simkl")
        val form = parseQueryString(engine.requests.single().body)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code +&", form["code"])
        assertEquals("verifier +&", form["code_verifier"])
        assertEquals("nuvio://auth/simkl", form["redirect_uri"])
    }

    @Test
    fun `V2 callback requires the exact Simkl issuer`() {
        for (issuer in listOf("", "&iss=https%3A%2F%2Fevil.example", "&iss=https%3A%2F%2Fsimkl.com.evil")) {
            assertIs<SimklAuthCallback.Invalid>(
                parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=b$issuer", "nuvio://auth/simkl", true),
            )
        }
        assertIs<SimklAuthCallback.AuthorizationCode>(
            parseSimklAuthCallback(
                "nuvio://auth/simkl?code=a&state=b&iss=https%3A%2F%2Fsimkl.com", "nuvio://auth/simkl", true,
            ),
        )
    }

    @Test
    fun `disconnect revokes the captured grant without bearer headers`(): Unit = runBlocking {
        val engine = Engine(response(200, "{}"))
        client(engine).revoke("refresh +&")
        val request = engine.requests.single()
        assertEquals("https://api.simkl.com/oauth2/revoke", request.url)
        assertEquals("refresh +&", parseQueryString(request.body)["token"])
        assertFalse("Authorization" in request.headers)
    }

    @Test
    fun `cancellation is preserved`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            client(SimklHttpEngine { _, _, _, _ -> throw CancellationException() }).authorizeDevice()
        }
    }

    private class Engine(vararg responses: RawHttpResponse) : SimklHttpEngine {
        val requests = mutableListOf<Request>()
        private val queue = responses.toMutableList()
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String): RawHttpResponse {
            requests += Request(method, url, headers, body)
            return queue.removeAt(0)
        }
    }

    private data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    private fun client(engine: SimklHttpEngine) = SimklOAuth2Client(engine, "client +&id", "Nuvio/1.0")
    private fun response(status: Int, body: String) = RawHttpResponse(status, "", "https://api.simkl.com/", body, emptyMap())

    private companion object {
        const val DEVICE = """{"device_code":"secret-device","user_code":"ABCD-EFGH","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin?user_code=ABCD-EFGH","expires_in":900,"interval":5}"""
        const val TOKENS = """{"access_token":"test-access","refresh_token":"test-refresh","token_type":"Bearer","expires_in":604800,"scope":"media:read media:write"}"""
    }
}
