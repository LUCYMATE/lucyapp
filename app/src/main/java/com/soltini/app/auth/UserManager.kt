package com.soltini.app.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * LUCY User Authentication Manager for Android
 *
 * Connects directly to the LUCY Vercel Backend (https://lucymate.vercel.app)
 * and Neon PostgreSQL Database, maintaining full feature and session parity
 * with the LUCY Desktop App.
 */
/**
 * Represents a detected tier downgrade event (e.g. Pro -> Free).
 */
data class TierDowngradeNotice(
    val previousTier: String,
    val newTier: String
)

class UserManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UserManager"
        private const val PREFS_NAME = "soltini_user_prefs"
        const val DEFAULT_SERVER_URL = "https://lucymate.vercel.app"
        const val DEFAULT_SERVER_CLIENT_ID = "874715228704-0rg59ort6gs9ufdtkfoa8f5tm2kn7f6j.apps.googleusercontent.com"
        private const val KEY_SESSION_JSON = "soltini_user_session"
        private const val KEY_PENDING_SESSION_ID = "pending_session_id"
        private const val KEY_PENDING_CODE_VERIFIER = "pending_code_verifier"
        private const val KEY_LAST_KNOWN_TIER = "last_known_tier"
        private const val KEY_PENDING_DOWNGRADE_NOTICE = "pending_downgrade_notice"

        @Volatile
        private var INSTANCE: UserManager? = null

        fun getInstance(context: Context): UserManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    val currentUser: StateFlow<UserSession?> = _currentUser.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _tierDowngradeNotice = MutableStateFlow<TierDowngradeNotice?>(null)
    val tierDowngradeNotice: StateFlow<TierDowngradeNotice?> = _tierDowngradeNotice.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadSavedSession()
    }

    // ── 1. Load & Persist Sessions ─────────────────────────────────────────────

    private fun loadSavedSession() {
        val json = prefs.getString(KEY_SESSION_JSON, null) ?: return
        try {
            val obj = JSONObject(json)
            val session = UserSession(
                email = obj.getString("email"),
                name = obj.optString("name", "LUCY User"),
                avatarUrl = if (obj.has("avatarUrl") && !obj.isNull("avatarUrl")) obj.getString("avatarUrl") else null,
                tier = obj.optString("tier", "free"),
                status = obj.optString("status", "active"),
                token = obj.getString("token"),
                expiresAt = obj.optLong("expiresAt", System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000),
                isTrial = obj.optBoolean("isTrial", false),
                daysRemaining = obj.optInt("daysRemaining", 0),
                periodEnd = if (obj.has("periodEnd") && !obj.isNull("periodEnd")) obj.getString("periodEnd") else null
            )

            if (!session.isExpired && !session.isRevoked && !session.email.endsWith("@soltini.local") && !session.email.endsWith("@lucy.local")) {
                _currentUser.value = session
                if (prefs.getBoolean(KEY_PENDING_DOWNGRADE_NOTICE, false) && session.isFree) {
                    val prevTier = prefs.getString(KEY_LAST_KNOWN_TIER, "pro") ?: "pro"
                    _tierDowngradeNotice.value = TierDowngradeNotice(prevTier, session.tier)
                }
                // Background sync latest status from Neon DB
                scope.launch {
                    verifyAndRefreshSession()
                }
            } else {
                clearSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved session", e)
            clearSession()
        }
    }

    private fun saveSession(session: UserSession) {
        try {
            val prevTier = prefs.getString(KEY_LAST_KNOWN_TIER, null)
            val wasPro = prevTier != null && (
                prevTier.equals("mobile_pro", ignoreCase = true) ||
                prevTier.equals("bundle_pro", ignoreCase = true) ||
                prevTier.equals("pro", ignoreCase = true)
            )
            val nowFree = session.isFree

            if (wasPro && nowFree) {
                Log.w(TAG, "User downgraded from Pro to Free: $prevTier -> ${session.tier}")
                prefs.edit().putBoolean(KEY_PENDING_DOWNGRADE_NOTICE, true).apply()
                _tierDowngradeNotice.value = TierDowngradeNotice(previousTier = prevTier, newTier = session.tier)
            }

            prefs.edit().putString(KEY_LAST_KNOWN_TIER, session.tier).apply()

            val obj = JSONObject().apply {
                put("email", session.email)
                put("name", session.name)
                put("avatarUrl", session.avatarUrl)
                put("tier", session.tier)
                put("status", session.status)
                put("token", session.token)
                put("expiresAt", session.expiresAt)
                put("isTrial", session.isTrial)
                put("daysRemaining", session.daysRemaining)
                put("periodEnd", session.periodEnd)
            }
            prefs.edit().putString(KEY_SESSION_JSON, obj.toString()).apply()
            _currentUser.value = session
            _authError.value = null
            Log.i(TAG, "Session saved for: ${session.email} (${session.tier}, trial=${session.isTrial})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
        }
    }

    fun dismissDowngradeNotice() {
        prefs.edit().remove(KEY_PENDING_DOWNGRADE_NOTICE).apply()
        _tierDowngradeNotice.value = null
    }

    fun getUpgradeCheckoutUrl(): String {
        val current = _currentUser.value
        val email = current?.email
        val targetPlan = if (current?.isPcOnlyPro == true) "bundle_pro" else "mobile_pro"
        return if (!email.isNullOrBlank()) {
            "$DEFAULT_SERVER_URL/checkout?plan=$targetPlan&email=${Uri.encode(email)}"
        } else {
            "$DEFAULT_SERVER_URL/pricing"
        }
    }

    fun getBundleCheckoutUrl(): String {
        val email = _currentUser.value?.email
        return if (!email.isNullOrBlank()) {
            "$DEFAULT_SERVER_URL/checkout?plan=bundle_pro&email=${Uri.encode(email)}"
        } else {
            "$DEFAULT_SERVER_URL/pricing"
        }
    }

    fun getPricingUrl(): String = "$DEFAULT_SERVER_URL/pricing"

    fun getAppUpdateUrl(): String = "$DEFAULT_SERVER_URL/#ecosystem"

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_JSON)
            .remove(KEY_PENDING_SESSION_ID)
            .remove(KEY_PENDING_CODE_VERIFIER)
            .remove(KEY_PENDING_DOWNGRADE_NOTICE)
            .remove("guest_session_active")
            .apply()
        _currentUser.value = null
        _tierDowngradeNotice.value = null
    }

    /**
     * Checks if a genuine, non-expired, and non-revoked Google account session exists.
     * Guest logins are strictly rejected.
     */
    fun hasValidSession(): Boolean {
        val session = _currentUser.value ?: return false
        return !session.isExpired && !session.isRevoked && !session.email.endsWith("@soltini.local")
    }

    // ── 2. Token Decoding & Validation ─────────────────────────────────────────

    private fun decodeBase64Payload(input: String): String {
        val trimmed = input.trim().trim('"', '\'')
        return try {
            val bytes = Base64.decode(trimmed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                var padded = trimmed.replace('-', '+').replace('_', '/')
                while (padded.length % 4 != 0) {
                    padded += "="
                }
                val bytes = Base64.decode(padded, Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to decode base64 payload", e2)
                ""
            }
        }
    }

    /**
     * Parses the 3-part Ed25519 JWT or base64 token generated by Soltini Server.
     */
    fun parseAndApplyToken(rawToken: String): Boolean {
        try {
            val cleanToken = rawToken.trim().trim('"', '\'')
            val parts = cleanToken.split(".")
            val payloadStr = when (parts.size) {
                3, 2 -> decodeBase64Payload(parts[1])
                1 -> decodeBase64Payload(parts[0])
                else -> {
                    Log.e(TAG, "Invalid JWT format, unexpected parts count: ${parts.size}")
                    return false
                }
            }

            if (payloadStr.isBlank()) {
                Log.e(TAG, "Decoded token payload is empty")
                return false
            }

            val json = JSONObject(payloadStr)
            val email = json.optString("email", "").ifBlank {
                json.optJSONObject("user")?.optString("email", "") ?: ""
            }
            if (email.isBlank()) {
                Log.e(TAG, "Token payload missing email")
                return false
            }

            val name = json.optString("name", "").ifBlank {
                json.optJSONObject("user")?.optString("name", "LUCY User") ?: "LUCY User"
            }
            val avatarUrl = json.optString("avatar_url", "").ifBlank {
                json.optString("avatarUrl", "").ifBlank {
                    json.optJSONObject("user")?.optString("avatar_url", "")?.ifBlank { null }
                }
            }
            val tier = json.optString("tier", "").ifBlank {
                json.optJSONObject("user")?.optString("tier", "")?.ifBlank {
                    json.optString("actual_tier", "free")
                } ?: "free"
            }
            val status = json.optString("status", "active")
            val rawExp = json.optLong("expiresAt", json.optLong("exp", 0L))
            val expiresAt = when {
                rawExp <= 0L -> System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
                rawExp < 10_000_000_000L -> rawExp * 1000L // convert seconds to ms
                else -> rawExp
            }

            val isTrial = json.optBoolean("is_trial", false) ||
                    (json.optJSONObject("user")?.optBoolean("is_trial", false) ?: false) ||
                    (json.optJSONObject("trial_info")?.optBoolean("is_trial", false) ?: false)
            val daysRemaining = json.optInt("days_remaining",
                json.optJSONObject("trial_info")?.optInt("days_remaining", 0) ?: 0
            )
            val periodEnd = json.optString("period_end", "").ifBlank {
                json.optJSONObject("trial_info")?.optString("period_end", "")?.ifBlank { null }
            }

            val session = UserSession(
                email = email,
                name = name,
                avatarUrl = avatarUrl,
                tier = tier,
                status = status,
                token = cleanToken,
                expiresAt = expiresAt,
                isTrial = isTrial,
                daysRemaining = daysRemaining,
                periodEnd = periodEnd
            )

            saveSession(session)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JWT token", e)
            return false
        }
    }

    // ── 3. Immediate Out-of-the-Box Flow (Web OAuth + Deep Link) ───────────────

    /**
     * Starts Google OAuth via Chrome Custom Tab or External Browser.
     * Works immediately with the current live server and redirects to `soltini://`.
     */
    fun startWebSignIn(context: Context, serverUrl: String = DEFAULT_SERVER_URL) {
        _isSigningIn.value = true
        _authError.value = null

        val sessionId = UUID.randomUUID().toString()

        // Generate PKCE code verifier and challenge
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val codeVerifier = Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        prefs.edit()
            .putString(KEY_PENDING_SESSION_ID, sessionId)
            .putString(KEY_PENDING_CODE_VERIFIER, codeVerifier)
            .apply()

        val authUrl = "$serverUrl/auth/google?session_id=$sessionId&code_challenge=$codeChallenge&state=$sessionId&platform=android"
        Log.i(TAG, "Opening Web OAuth URL: $authUrl")

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser for OAuth", e)
            _authError.value = "Could not open browser for sign-in."
            _isSigningIn.value = false
            return
        }

        // Start session polling as fallback in case deep link is delayed or blocked
        startSessionPolling(sessionId, codeVerifier, serverUrl)
    }

    /**
     * Polls the server session endpoint in the background (identical to PC app).
     */
    private fun startSessionPolling(sessionId: String, codeVerifier: String, serverUrl: String) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var attempts = 0
            val maxAttempts = 24
            while (attempts < maxAttempts && _currentUser.value == null) {
                delay(2500)
                try {
                    val url = "$serverUrl/api/auth/session?id=$sessionId&code_verifier=$codeVerifier"
                    val request = Request.Builder().url(url).get().build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()

                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        if (json.optBoolean("success") && json.optString("status") == "authenticated") {
                            val token = json.optString("token")
                            if (token.isNotBlank()) {
                                parseAndApplyToken(token)
                                _isSigningIn.value = false
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Polling session check failed: ${e.message}")
                }
                attempts++
            }
            _isSigningIn.value = false
        }
    }

    /**
     * Handles `soltini://` deep link when browser redirects after login.
     * URI format: soltini://?token=<jwt>&state=<state>
     */
    fun handleAuthDeepLink(uri: Uri): Boolean {
        if (uri.scheme != "soltini") return false

        var token = uri.getQueryParameter("token")
        if (token.isNullOrBlank() && !uri.fragment.isNullOrBlank()) {
            val fragment = uri.fragment ?: ""
            token = fragment.split("&")
                .mapNotNull { param ->
                    val idx = param.indexOf('=')
                    if (idx != -1 && param.substring(0, idx).trim() == "token") {
                        param.substring(idx + 1).trim()
                    } else null
                }
                .firstOrNull()
        }

        if (token.isNullOrBlank()) {
            val error = uri.getQueryParameter("error") ?: "Sign-in cancelled"
            _authError.value = error
            _isSigningIn.value = false
            return false
        }

        pollingJob?.cancel()
        val success = parseAndApplyToken(token)
        _isSigningIn.value = false
        if (success) {
            scope.launch {
                verifyAndRefreshSession()
            }
        } else {
            _authError.value = "Failed to parse authentication token."
        }
        return success
    }

    // ── 4. Native 1-Tap Google Sign-In (Credential Manager) ────────────────────

    /**
     * Prompts the native Android Google Sign-In bottom sheet via Credential Manager.
     * If serverClientId is not configured or native sign-in is unavailable, falls back to Web OAuth.
     */
    fun signInWithGoogle(
        activity: Activity,
        serverClientId: String? = DEFAULT_SERVER_CLIENT_ID,
        serverUrl: String = DEFAULT_SERVER_URL,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (serverClientId.isNullOrBlank()) {
            Log.e(TAG, "No serverClientId configured for native Google Sign-In.")
            _authError.value = "Google Client ID is missing. Please check server configuration."
            onComplete?.invoke(false, "No serverClientId")
            return
        }

        _isSigningIn.value = true
        _authError.value = null

        scope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = withContext(Dispatchers.Main) {
                    credentialManager.getCredential(activity, request)
                }

                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    // Exchange Google ID Token with Soltini Backend
                    val success = exchangeIdTokenWithBackend(idToken, serverUrl)
                    withContext(Dispatchers.Main) {
                        _isSigningIn.value = false
                        if (success) {
                            onComplete?.invoke(true, null)
                        } else {
                            val err = "Sign-in verification failed. Please check network connection and try again."
                            _authError.value = err
                            android.widget.Toast.makeText(activity, err, android.widget.Toast.LENGTH_LONG).show()
                            onComplete?.invoke(false, "Server token exchange failed")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _isSigningIn.value = false
                        val err = "Unrecognized credential received from Google: ${credential.type}"
                        Log.w(TAG, err)
                        _authError.value = err
                        onComplete?.invoke(false, err)
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In was cancelled by user")
                withContext(Dispatchers.Main) {
                    _isSigningIn.value = false
                    onComplete?.invoke(false, "Cancelled")
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager error [type=${e.type}]: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _isSigningIn.value = false
                    val msg = e.message ?: e.type
                    val is28444 = msg.contains("28444") || msg.contains("Developer console", ignoreCase = true)
                    val err = if (is28444) {
                        "Google Play error [28444]: Console setup incomplete. Use 'Sign in via browser' below."
                    } else {
                        "Google Sign-In failed: $msg"
                    }
                    _authError.value = err
                    android.widget.Toast.makeText(activity, err, android.widget.Toast.LENGTH_LONG).show()
                    onComplete?.invoke(false, err)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during native sign in: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _isSigningIn.value = false
                    val err = "Google Sign-In error: ${e.message ?: "Unknown error"}"
                    _authError.value = err
                    android.widget.Toast.makeText(activity, err, android.widget.Toast.LENGTH_LONG).show()
                    onComplete?.invoke(false, err)
                }
            }
        }
    }

    private suspend fun exchangeIdTokenWithBackend(idToken: String, serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("idToken", idToken)
            }.toString()

            val request = Request.Builder()
                .url("$serverUrl/api/auth/google/mobile")
                .addHeader("X-Platform", "android")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            Log.i(TAG, "Native exchange endpoint response code=${response.code}, body=$body")

            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                if (json.optBoolean("success")) {
                    val token = json.optString("token")
                    if (token.isNotBlank()) {
                        return@withContext parseAndApplyToken(token)
                    }
                }
            }
            Log.w(TAG, "Native exchange endpoint failed with code: ${response.code}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call /api/auth/google/mobile: ${e.message}", e)
            false
        }
    }

    // ── 5. Verify & Refresh with Neon Database ─────────────────────────────────

    /**
     * Synchronizes current user status with Neon DB via `/api/auth/verify`.
     */
    suspend fun verifyAndRefreshSession(serverUrl: String = DEFAULT_SERVER_URL): Boolean = withContext(Dispatchers.IO) {
        val current = _currentUser.value ?: return@withContext false
        if (current.email.endsWith("@soltini.local")) return@withContext true

        try {
            val jsonBody = JSONObject().apply {
                put("email", current.email)
                put("token", current.token)
                put("platform", "android")
            }.toString()

            val request = Request.Builder()
                .url("$serverUrl/api/auth/verify")
                .addHeader("X-Platform", "android")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                if (json.optBoolean("success")) {
                    val userObj = json.optJSONObject("user")
                    if (userObj != null) {
                        val status = userObj.optString("status", "active")
                        if (status == "revoked") {
                            _currentUser.value = current.copy(status = "revoked")
                            _authError.value = "Your account access has been revoked."
                            return@withContext false
                        }

                        val trialInfo = json.optJSONObject("trial_info")
                        val isTrial = trialInfo?.optBoolean("is_trial", false)
                            ?: userObj.optBoolean("is_trial", false)
                        val daysRemaining = trialInfo?.optInt("days_remaining", 0)
                            ?: userObj.optInt("days_remaining", 0)
                        val periodEnd = trialInfo?.optString("period_end", "")?.ifBlank { null }
                            ?: userObj.optString("trial_ends_at", "").ifBlank { null }

                        val updatedSession = current.copy(
                            name = userObj.optString("name", current.name),
                            avatarUrl = userObj.optString("avatar_url", "").ifBlank { current.avatarUrl },
                            tier = userObj.optString("tier", current.tier),
                            status = status,
                            isTrial = isTrial,
                            daysRemaining = daysRemaining,
                            periodEnd = periodEnd
                        )
                        saveSession(updatedSession)
                        return@withContext true
                    }
                }
            } else if (response.code == 401 || response.code == 403) {
                clearSession()
                _authError.value = "Session expired. Please sign in again."
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verify session network error: ${e.message}")
        }
        false
    }

    // ── 6. Logout ──────────────────────────────────────────────────────────────

    fun logout(serverUrl: String = DEFAULT_SERVER_URL) {
        val current = _currentUser.value
        clearSession()

        if (current != null && current.token.isNotBlank()) {
            scope.launch {
                try {
                    val jsonBody = JSONObject().apply {
                        put("token", current.token)
                    }.toString()

                    val request = Request.Builder()
                        .url("$serverUrl/api/auth/logout")
                        .addHeader("Authorization", "Bearer ${current.token}")
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "Logout API error: ${e.message}")
                }
            }
        }
    }
}
