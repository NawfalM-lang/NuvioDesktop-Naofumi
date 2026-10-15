package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.isDesktop
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    private var hasLoaded = false
    private var profileGeneration = 0L
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var pinPollingJob: Job? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        pinPollingJob?.cancel()
        hasLoaded = false
        profileGeneration += 1L
        storedState = SimklStoredAuthState()
        accessToken = null
        refreshToken = null
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    fun hasRequiredCredentials(): Boolean = SimklConfig.CLIENT_ID.isNotBlank()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        if (isDesktop) {
            return startPinAuthorization()
        }

        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        if (isDesktop) {
            val verificationUrl = storedState.pendingPinVerificationUrl
                ?.takeIf { storedState.hasPendingPinAuthorization }
            if (verificationUrl == null) return null
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                pinPollingJob?.cancel()
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return null
            }
            startPinPollingIfNeeded()
            return verificationUrl
        }
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        if (isDesktop) return false
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI, requireV2Issuer = true)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                val generation = profileGeneration
                scope.launch { completeAuthorization(callback, generation) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        val tokenToRevoke = refreshToken
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(error = null)
        if (tokenToRevoke != null) scope.launch {
            try {
                SimklApi.oauth2.revoke(tokenToRevoke)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SimklOAuth2Exception) {
                log.w { "Could not revoke the disconnected Simkl grant" }
            }
        }
    }

    internal fun authorizedAccessToken(): String? {
        ensureLoaded()
        val token = accessToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        if (refreshToken.isNullOrBlank() && expiresAt != null &&
            SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
        ) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return token
    }

    internal suspend fun validAccessToken(): String? {
        val token = authorizedAccessToken() ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        return if (expiresAt != null &&
            SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
        ) refreshRejectedAccessToken(token) else token
    }

    internal suspend fun refreshRejectedAccessToken(rejectedToken: String?): String? {
        // Capture before waiting: an old request must not refresh or invalidate a new profile.
        val generation = profileGeneration
        return refreshMutex.withLock {
            if (generation != profileGeneration || accessToken != rejectedToken) {
                throw SimklApiException(null, "session_changed", "Simkl session changed")
            }
            val refresh = refreshToken ?: return@withLock null
            val tokens = try {
                SimklApi.oauth2.refresh(refresh)
            } catch (error: SimklOAuth2Exception) {
                if (generation == profileGeneration && accessToken == rejectedToken &&
                    !error.isTransient && error.authError in setOf(
                        SimklAuthError.AUTHORIZATION_REVOKED,
                        SimklAuthError.INVALID_CLIENT,
                        SimklAuthError.INSUFFICIENT_SCOPE,
                    )
                ) invalidateCredentials(error.authError)
                // Network/server failures retain the grant for the next request.
                throw SimklApiException(null, "token_refresh_failed", error.message.orEmpty())
            }
            if (generation != profileGeneration || accessToken != rejectedToken) {
                throw SimklApiException(null, "session_changed", "Simkl session changed")
            }
            saveTokens(tokens)
            publish(error = null)
            tokens.accessToken
        }
    }

    internal fun onUnauthorizedResponse(rejectedToken: String?) {
        if (accessToken == rejectedToken) invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        return if (fetchAndStoreUserSettings()) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata()
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(activityWatermark)
            }
        }
    }

    private fun startPinAuthorization(): String? {
        val existingVerificationUrl = storedState.pendingPinVerificationUrl
            ?.takeIf { storedState.hasPendingPinAuthorization }
            ?.takeUnless {
                isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            }
        if (existingVerificationUrl != null) {
            publish(isLoading = false, error = null)
            startPinPollingIfNeeded()
            return existingVerificationUrl
        }

        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = true, error = null)
        val generation = profileGeneration
        scope.launch {
            requestPinAuthorization(generation)
        }
        return null
    }

    private suspend fun requestPinAuthorization(generation: Long) {
        val pending = try {
            SimklApi.oauth2.authorizeDevice()
        } catch (error: SimklOAuth2Exception) {
            if (profileGeneration != generation) return
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = error.authError)
            return
        }
        if (profileGeneration != generation) return
        val now = SimklPlatformClock.nowEpochMs()
        SimklAuthStorage.saveDeviceCode(pending.deviceCode)
        storedState = storedState.copy(
            oauthClientId = SimklConfig.CLIENT_ID,
            pendingPinUsesOAuth2 = true,
            pendingAuthorizationStartedAtEpochMs = now,
            pendingPinUserCode = pending.userCode,
            pendingPinVerificationUrl = pending.verificationUrl,
            pendingPinIntervalSeconds = pending.intervalSeconds,
            pendingPinExpiresAtEpochMs = now + pending.expiresInSeconds * 1_000L,
        )
        persistMetadata()
        publish(isLoading = false, error = null)
        startPinPollingIfNeeded()
    }

    private fun startPinPollingIfNeeded() {
        if (!isDesktop || !storedState.hasPendingPinAuthorization) return
        if (isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            expirePinAuthorization()
            return
        }
        if (pinPollingJob?.isActive == true) return
        val userCode = storedState.pendingPinUserCode ?: return
        val generation = profileGeneration
        pinPollingJob = scope.launch {
            pollPinAuthorization(userCode, generation)
        }
    }

    private suspend fun pollPinAuthorization(
        userCode: String,
        generation: Long,
    ) {
        var intervalSeconds = storedState.pendingPinIntervalSeconds?.coerceAtLeast(1) ?: 5
        while (isCurrentPinAuthorization(userCode, generation)) {
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            delay(intervalSeconds * 1_000L)
            if (!isCurrentPinAuthorization(userCode, generation)) return
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            val deviceCode = SimklAuthStorage.loadDeviceCode() ?: run {
                expirePinAuthorization()
                return
            }
            val result = try {
                SimklApi.oauth2.pollDevice(deviceCode)
            } catch (error: SimklOAuth2Exception) {
                if (!isCurrentPinAuthorization(userCode, generation)) return
                if (error.isTransient) continue
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = error.authError)
                return
            }
            if (!isCurrentPinAuthorization(userCode, generation)) return
            when (result) {
                is SimklDevicePollResult.Authorized -> {
                    completePinAuthorization(result.tokens, generation)
                    return
                }
                SimklDevicePollResult.Pending -> publish(isLoading = false, error = null)
                SimklDevicePollResult.SlowDown -> {
                    intervalSeconds = (intervalSeconds + 5).coerceAtMost(900)
                    storedState = storedState.copy(pendingPinIntervalSeconds = intervalSeconds)
                    persistMetadata()
                }
                SimklDevicePollResult.Expired -> {
                    expirePinAuthorization()
                    return
                }
            }
        }
    }

    private suspend fun completePinAuthorization(
        tokens: SimklOAuth2Tokens,
        generation: Long,
    ) = authorizationMutex.withLock {
        if (profileGeneration != generation) return@withLock
        clearPendingAuthorization()
        saveTokens(tokens)
        publish(isLoading = false, error = null)
        fetchAndStoreUserSettings()
        if (profileGeneration == generation) SimklSyncRepository.refreshAsync(
            intent = TrackingRefreshIntent.INVALIDATED,
            origin = SimklRefreshOrigin.AUTHORIZATION,
        )
    }

    private fun isCurrentPinAuthorization(
        userCode: String,
        generation: Long,
    ): Boolean = isDesktop &&
        profileGeneration == generation &&
        storedState.pendingPinUserCode == userCode &&
        storedState.hasPendingPinAuthorization &&
        accessToken.isNullOrBlank()

    private fun expirePinAuthorization() {
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
    }

    private suspend fun completeAuthorization(
        callback: SimklAuthCallback.AuthorizationCode,
        generation: Long,
    ) =
        authorizationMutex.withLock {
            if (profileGeneration != generation) return@withLock
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }

            val tokens = try {
                SimklApi.oauth2.exchangeCode(callback.code, verifier, SimklConfig.REDIRECT_URI)
            } catch (error: SimklOAuth2Exception) {
                if (profileGeneration != generation) return@withLock
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = error.authError)
                return@withLock
            }
            if (profileGeneration != generation) return@withLock
            clearPendingAuthorization()
            saveTokens(tokens)
            publish(isLoading = false, error = null)
            fetchAndStoreUserSettings()
            if (profileGeneration == generation) SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }

    private fun saveTokens(tokens: SimklOAuth2Tokens) {
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        SimklAuthStorage.saveAccessToken(tokens.accessToken)
        SimklAuthStorage.saveRefreshToken(tokens.refreshToken)
        storedState = storedState.copy(
            oauthClientId = SimklConfig.CLIENT_ID,
            tokenExpiresAtEpochMs = SimklPlatformClock.nowEpochMs() + tokens.expiresInSeconds * 1_000L,
        )
        persistMetadata()
    }

    private suspend fun fetchAndStoreUserSettings(activityWatermark: String? = null): Boolean {
        val generation = profileGeneration
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        if (generation != profileGeneration) return false
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata()
        publish(error = null)
        return true
    }

    private fun loadFromDisk() {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = SimklAuthStorage.loadAccessToken()?.takeIf(String::isNotBlank)
        refreshToken = SimklAuthStorage.loadRefreshToken()?.takeIf(String::isNotBlank)
        if (storedState.oauthClientId != null && storedState.oauthClientId != SimklConfig.CLIENT_ID) {
            invalidateCredentials(SimklAuthError.INVALID_CLIENT)
            return
        }
        if (refreshToken == null && accessToken != null && storedState.tokenExpiresAtEpochMs?.let { expiresAt ->
                SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
            } == true
        ) {
            accessToken = null
            SimklAuthStorage.saveAccessToken(null)
            storedState = SimklStoredAuthState()
            persistMetadata()
        }
        val hasWrongPlatformAuthorization = if (isDesktop) {
            !storedState.pendingAuthorizationState.isNullOrBlank()
        } else {
            storedState.hasPendingPinAuthorization
        } || (storedState.hasPendingPinAuthorization &&
            (!storedState.pendingPinUsesOAuth2 || SimklAuthStorage.loadDeviceCode().isNullOrBlank()))
        val hasExpiredAuthorization = when {
            storedState.hasPendingPinAuthorization -> isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            !storedState.pendingAuthorizationState.isNullOrBlank() -> isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            else -> false
        }
        if (hasWrongPlatformAuthorization || hasExpiredAuthorization) {
            clearPendingAuthorization()
            persistMetadata()
        }
        publish(isLoading = false, error = null)
        startPinPollingIfNeeded()
    }

    private fun invalidateCredentials(error: SimklAuthError) {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        SimklAuthStorage.saveDeviceCode(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
            pendingPinUserCode = null,
            pendingPinVerificationUrl = null,
            pendingPinIntervalSeconds = null,
            pendingPinExpiresAtEpochMs = null,
            pendingPinUsesOAuth2 = false,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            usesPinFlow = isDesktop,
            pendingPinUserCode = storedState.pendingPinUserCode,
            pendingPinVerificationUrl = storedState.pendingPinVerificationUrl,
            pendingPinExpiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
}

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
