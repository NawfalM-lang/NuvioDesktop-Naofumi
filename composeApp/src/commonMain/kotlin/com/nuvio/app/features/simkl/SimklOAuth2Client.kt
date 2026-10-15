package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SIMKL_OAUTH2_SCOPE = "media:read media:write"

// These classes deliberately do not generate toString() methods containing credentials.
internal class SimklOAuth2Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

internal class SimklDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Long,
)

internal sealed interface SimklDevicePollResult {
    class Authorized(val tokens: SimklOAuth2Tokens) : SimklDevicePollResult
    data object Pending : SimklDevicePollResult
    data object SlowDown : SimklDevicePollResult
    data object Expired : SimklDevicePollResult
}

internal class SimklOAuth2Exception(
    val authError: SimklAuthError,
    val isTransient: Boolean = false,
) : Exception("Simkl authentication failed: ${authError.name}")

/** Separate transport from the API queue: obtaining a token must never re-enter that queue. */
internal class SimklOAuth2Client(
    private val engine: SimklHttpEngine,
    private val clientId: String,
    private val userAgent: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authorizeDevice(): SimklDeviceAuthorization {
        val response = post("/oauth2/device", mapOf("scope" to SIMKL_OAUTH2_SCOPE))
        requireSuccess(response)
        val value = decode(response)
        val deviceCode = value.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = value.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val url = (value.verificationUriComplete ?: value.verificationUri)
            ?.takeIf { it.startsWith("https://simkl.com/") } ?: invalidResponse()
        val expiresIn = value.expiresIn?.takeIf { it in 1..86_400L } ?: invalidResponse()
        return SimklDeviceAuthorization(
            deviceCode, userCode, url,
            (value.interval ?: 5).coerceIn(1, 900), expiresIn,
        )
    }

    suspend fun pollDevice(deviceCode: String): SimklDevicePollResult {
        val response = post(
            "/oauth2/token",
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode,
            ),
        )
        if (response.status in 200..299) return SimklDevicePollResult.Authorized(tokens(response))
        return when {
            response.status == 400 && decode(response).error == "authorization_pending" ->
                SimklDevicePollResult.Pending
            response.status == 400 && decode(response).error == "slow_down" ->
                SimklDevicePollResult.SlowDown
            response.status == 400 && decode(response).error == "expired_token" ->
                SimklDevicePollResult.Expired
            response.status == 429 -> SimklDevicePollResult.SlowDown
            response.status in 500..599 -> SimklDevicePollResult.Pending
            else -> { requireSuccess(response); invalidResponse() }
        }
    }

    suspend fun exchangeCode(code: String, verifier: String, redirectUri: String): SimklOAuth2Tokens {
        val response = post(
            "/oauth2/token",
            mapOf(
                "grant_type" to "authorization_code", "code" to code,
                "code_verifier" to verifier, "redirect_uri" to redirectUri,
            ),
        )
        requireSuccess(response)
        return tokens(response)
    }

    suspend fun refresh(refreshToken: String): SimklOAuth2Tokens {
        val response = post(
            "/oauth2/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        )
        requireSuccess(response)
        return tokens(response)
    }

    suspend fun revoke(token: String) {
        requireSuccess(post("/oauth2/revoke", mapOf("token" to token)))
    }

    private suspend fun post(path: String, fields: Map<String, String>): RawHttpResponse = try {
        engine.execute(
            method = "POST",
            url = SIMKL_API_BASE_URL + path,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            body = (mapOf("client_id" to clientId) + fields).entries.joinToString("&") {
                "${it.key.encodeURLParameter()}=${it.value.encodeURLParameter()}"
            },
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // Transport exceptions may contain URLs or bodies. Do not propagate them to logs/UI.
        throw SimklOAuth2Exception(SimklAuthError.CONNECTION_FAILED, isTransient = true)
    }

    private fun requireSuccess(response: RawHttpResponse) {
        if (response.status in 200..299) return
        val errorCode = runCatching { decode(response).error }.getOrNull()
        val authError = when (errorCode) {
            "invalid_client", "unauthorized_client" -> SimklAuthError.INVALID_CLIENT
            "invalid_scope", "insufficient_scope" -> SimklAuthError.INSUFFICIENT_SCOPE
            "invalid_grant", "expired_token", "access_denied" -> SimklAuthError.AUTHORIZATION_REVOKED
            else -> SimklAuthError.TOKEN_EXCHANGE_FAILED
        }
        throw SimklOAuth2Exception(
            authError, isTransient = response.status == 429 || response.status in 500..599,
        )
    }

    private fun decode(response: RawHttpResponse): OAuth2Envelope =
        runCatching { json.decodeFromString<OAuth2Envelope>(response.body) }.getOrNull()
            ?: invalidResponse()

    private fun tokens(response: RawHttpResponse): SimklOAuth2Tokens {
        val value = decode(response)
        val accessToken = value.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refreshToken = value.refreshToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val expiresIn = value.expiresIn?.takeIf { it in 1..31_536_000L } ?: invalidResponse()
        if (!value.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        val scopes = value.scope.orEmpty().split(' ').filter(String::isNotBlank).toSet()
        if (!scopes.containsAll(setOf("media:read", "media:write"))) {
            throw SimklOAuth2Exception(SimklAuthError.INSUFFICIENT_SCOPE)
        }
        return SimklOAuth2Tokens(accessToken, refreshToken, expiresIn)
    }

    private fun invalidResponse(): Nothing =
        throw SimklOAuth2Exception(SimklAuthError.INVALID_TOKEN_RESPONSE)
}

@Serializable
private class OAuth2Envelope(
    val error: String? = null,
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    val interval: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
)
