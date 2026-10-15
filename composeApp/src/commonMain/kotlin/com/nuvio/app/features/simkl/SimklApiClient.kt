package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random

private const val SIMKL_MAX_RESPONSE_BODY_BYTES = 8 * 1024 * 1024
private val simklSyncLog = Logger.withTag("SimklSync")

internal enum class SimklHttpMethod {
    GET,
    POST,
    DELETE,
}

internal enum class SimklRetryPolicy {
    TRANSIENT_FAILURES,
    SYNC_WRITE,
    NEVER,
}

internal data class SimklApiRequest(
    val method: SimklHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val requiresAuthentication: Boolean = true,
    val retryPolicy: SimklRetryPolicy = SimklRetryPolicy.TRANSIENT_FAILURES,
    val scrobbleStopConflictIsSuccess: Boolean = false,
)

internal data class SimklApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
    val isSoftSuccess: Boolean = false,
)

internal class SimklApiException(
    val status: Int?,
    val errorCode: String?,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun interface SimklHttpEngine {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RawHttpResponse
}

internal class SimklApiClient(
    private val engine: SimklHttpEngine,
    private val accessToken: suspend () -> String?,
    private val onUnauthorized: (String?) -> Unit,
    private val refreshAccessToken: suspend (String?) -> String? = { null },
    private val nowEpochMs: () -> Long = SimklPlatformClock::nowEpochMs,
    private val sleep: suspend (Long) -> Unit = { delayMs -> delay(delayMs) },
    private val retryJitterMs: () -> Long = { Random.nextLong(RETRY_JITTER_BOUND_MS + 1L) },
) {
    private val requestMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextGetAtEpochMs = 0L
    private var nextPostAtEpochMs = 0L

    suspend fun execute(request: SimklApiRequest): SimklApiResponse = requestMutex.withLock {
        var token = if (request.requiresAuthentication) {
            accessToken()?.takeIf(String::isNotBlank)
                ?: throw SimklApiException(
                    status = 401,
                    errorCode = "authentication_required",
                    message = "Simkl authentication is required",
                )
        } else {
            null
        }

        val maxAttempts = when (request.retryPolicy) {
            SimklRetryPolicy.TRANSIENT_FAILURES,
            SimklRetryPolicy.SYNC_WRITE,
            -> MAX_ATTEMPTS
            SimklRetryPolicy.NEVER -> 1
        }
        var authorizationRetried = false
        var syncWriteLockRetried = false
        for (attempt in 0 until maxAttempts) {
            var response = try {
                executeRateLimited(request.method) {
                    engine.execute(
                        method = request.method.name,
                        url = buildSimklApiUrl(request.path, request.query),
                        headers = simklRequestHeaders(
                            accessToken = token,
                            contentTypeJson = request.method == SimklHttpMethod.POST,
                        ),
                        body = request.body,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt == maxAttempts - 1) {
                    throw SimklApiException(
                        status = null,
                        errorCode = "transport_failure",
                        message = "Simkl request failed",
                        cause = error,
                    )
                }
                sleep(retryDelayMs(attempt, retryAfterHeader = null, retryJitterMs()))
                continue
            }

            if (response.status == 401 && request.requiresAuthentication && !authorizationRetried) {
                authorizationRetried = true
                val renewed = refreshAccessToken(token)?.takeIf(String::isNotBlank)
                if (renewed != null) {
                    token = renewed
                    // A definitive 401 did not apply the original request. Retry once, including
                    // requests whose transport retry policy is NEVER; never loop on a second 401.
                    response = try {
                        executeRateLimited(request.method) {
                            engine.execute(
                                method = request.method.name,
                                url = buildSimklApiUrl(request.path, request.query),
                                headers = simklRequestHeaders(token, request.method == SimklHttpMethod.POST),
                                body = request.body,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        throw SimklApiException(null, "transport_failure", "Simkl request failed")
                    }
                }
            }

            logSyncReadResponse(
                request = request,
                response = response,
                attempt = attempt + 1,
            )

            if (
                request.retryPolicy == SimklRetryPolicy.SYNC_WRITE &&
                response.status == 400 &&
                !syncWriteLockRetried &&
                response.errorCode(json) == "rate_limit" &&
                attempt < maxAttempts - 1
            ) {
                syncWriteLockRetried = true
                sleep(SYNC_WRITE_LOCK_RETRY_DELAY_MS)
                continue
            }

            when (classifySimklResponse(response.status, request.scrobbleStopConflictIsSuccess)) {
                SimklResponseAction.SUCCESS -> return@withLock response.toApiResponse()
                SimklResponseAction.SOFT_SUCCESS -> {
                    return@withLock response.toApiResponse(isSoftSuccess = true)
                }
                SimklResponseAction.REAUTHENTICATE -> {
                    if (request.requiresAuthentication) onUnauthorized(token)
                    throw response.toApiException(json)
                }
                SimklResponseAction.FAIL -> throw response.toApiException(json)
                SimklResponseAction.RETRY -> {
                    if (attempt == maxAttempts - 1) throw response.toApiException(json)
                    sleep(
                        retryDelayMs(
                            attempt = attempt,
                            retryAfterHeader = response.headers.headerValue("retry-after"),
                            jitterMs = retryJitterMs(),
                        ),
                    )
                }
            }
        }

        error("Simkl request loop completed without a response")
    }

    private suspend fun <T> executeRateLimited(
        method: SimklHttpMethod,
        block: suspend () -> T,
    ): T {
        awaitRateLimit(method)
        return try {
            block()
        } finally {
            recordRateLimitCompletion(method)
        }
    }

    private suspend fun awaitRateLimit(method: SimklHttpMethod) {
        val now = nowEpochMs()
        val scheduledAt = when (method) {
            SimklHttpMethod.GET -> nextGetAtEpochMs
            SimklHttpMethod.POST, SimklHttpMethod.DELETE -> nextPostAtEpochMs
        }
        if (scheduledAt > now) sleep(scheduledAt - now)
    }

    private fun recordRateLimitCompletion(method: SimklHttpMethod) {
        val completedAt = nowEpochMs()
        when (method) {
            SimklHttpMethod.GET -> {
                nextGetAtEpochMs = max(nextGetAtEpochMs, completedAt + GET_INTERVAL_MS)
            }
            SimklHttpMethod.POST, SimklHttpMethod.DELETE -> {
                nextPostAtEpochMs = max(nextPostAtEpochMs, completedAt + POST_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val GET_INTERVAL_MS = 100L
        const val POST_INTERVAL_MS = 1_000L
        const val MAX_ATTEMPTS = 5
        const val SYNC_WRITE_LOCK_RETRY_DELAY_MS = 3_000L
        const val RETRY_JITTER_BOUND_MS = 1_000L
    }
}

private fun logSyncReadResponse(
    request: SimklApiRequest,
    response: RawHttpResponse,
    attempt: Int,
) {
    if (request.method != SimklHttpMethod.GET || !request.path.startsWith("/sync/")) return

    simklSyncLog.d { simklSyncResponseLogMessage(request, response, attempt) }
}

internal fun simklSyncResponseLogMessage(
    request: SimklApiRequest,
    response: RawHttpResponse,
    attempt: Int,
): String {
    val queryKeys = request.query.keys.sorted().joinToString(prefix = "[", postfix = "]")
    val contentType = response.headers.headerValue("content-type") ?: "<missing>"
    return "Simkl HTTP response: method=${request.method} path=${request.path} " +
        "queryKeys=$queryKeys status=${response.status} attempt=$attempt " +
        "contentType=$contentType bodyChars=${response.body.length}"
}

internal object SimklApi {
    val oauth2: SimklOAuth2Client by lazy {
        SimklOAuth2Client(
            engine = SimklHttpEngine { method, url, headers, body ->
                httpRequestRaw(method, url, headers, body, maxResponseBodyBytes = 64 * 1024)
            },
            clientId = SimklConfig.CLIENT_ID,
            userAgent = "${SimklConfig.APP_NAME}/$simklAppVersion",
        )
    }

    val client: SimklApiClient by lazy {
        SimklApiClient(
            engine = SimklHttpEngine { method, url, headers, body ->
                httpRequestRaw(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    maxResponseBodyBytes = SIMKL_MAX_RESPONSE_BODY_BYTES,
                )
            },
            accessToken = SimklAuthRepository::validAccessToken,
            onUnauthorized = SimklAuthRepository::onUnauthorizedResponse,
            refreshAccessToken = SimklAuthRepository::refreshRejectedAccessToken,
        )
    }
}

internal enum class SimklResponseAction {
    SUCCESS,
    SOFT_SUCCESS,
    REAUTHENTICATE,
    RETRY,
    FAIL,
}

internal fun classifySimklResponse(
    status: Int,
    scrobbleStopConflictIsSuccess: Boolean = false,
): SimklResponseAction = when {
    status in 200..299 -> SimklResponseAction.SUCCESS
    status == 409 && scrobbleStopConflictIsSuccess -> SimklResponseAction.SOFT_SUCCESS
    status == 401 -> SimklResponseAction.REAUTHENTICATE
    status == 429 || status == 500 || status == 502 || status == 503 -> SimklResponseAction.RETRY
    else -> SimklResponseAction.FAIL
}

internal fun retryDelayMs(
    attempt: Int,
    retryAfterHeader: String?,
    jitterMs: Long,
): Long {
    require(attempt in 0..4) { "Retry attempt must be between 0 and 4" }
    val exponentialDelayMs = 1_000L shl attempt
    val retryAfterMs = retryAfterHeader
        ?.substringBefore(',')
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?.times(1_000L)
        ?: 0L
    return (max(exponentialDelayMs, retryAfterMs) + jitterMs.coerceIn(0L, 1_000L))
        .coerceAtMost(60_000L)
}

private fun RawHttpResponse.toApiResponse(isSoftSuccess: Boolean = false): SimklApiResponse =
    SimklApiResponse(
        status = status,
        body = body,
        headers = headers,
        isSoftSuccess = isSoftSuccess,
    )

private fun RawHttpResponse.toApiException(json: Json): SimklApiException {
    val envelope = errorEnvelope(json)
    return SimklApiException(
        status = status,
        errorCode = envelope?.error,
        message = envelope?.message?.takeIf(String::isNotBlank)
            ?: envelope?.errorDescription?.takeIf(String::isNotBlank)
            ?: envelope?.error?.takeIf(String::isNotBlank)
            ?: "Simkl request failed with HTTP $status",
    )
}

private fun RawHttpResponse.errorCode(json: Json): String? = errorEnvelope(json)?.error

private fun RawHttpResponse.errorEnvelope(json: Json): SimklErrorEnvelope? =
    body.takeIf(String::isNotBlank)?.let { payload ->
        runCatching { json.decodeFromString<SimklErrorEnvelope>(payload) }.getOrNull()
    }

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

@Serializable
private data class SimklErrorEnvelope(
    val error: String? = null,
    val code: Int? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
