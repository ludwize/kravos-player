package net.kravos.player.auth

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kravos.player.data.Prefs
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Google Drive authorization via Google Play services (AuthorizationClient).
 * No client secret or client ID lives in the app: Google matches the Android OAuth client
 * by package name + signing certificate SHA-1.
 */
class GoogleAuth(private val context: Context, private val prefs: Prefs) {

    private val client = Identity.getAuthorizationClient(context)

    val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY)))
        .build()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt: Long = 0L
    private val lock = Any()

    private val _needsReauth = MutableStateFlow(false)
    /** True when a background call found that the user has to go through the consent UI again. */
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Starts authorization; the caller launches the resolution PendingIntent when needed. */
    fun authorize(): Task<AuthorizationResult> = client.authorize(request)

    fun authorizationResultFromIntent(data: android.content.Intent?): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data)

    fun onAuthorized(result: AuthorizationResult) {
        val t = result.accessToken
        if (t != null) {
            synchronized(lock) {
                token = t
                tokenAt = System.currentTimeMillis()
            }
            prefs.setConnected(true)
            _needsReauth.value = false
            _lastError.value = null
        }
    }

    fun onError(message: String) {
        _lastError.value = message
    }

    /** Returns a valid access token, or null when not connected / consent is required. Never call on the main thread. */
    @WorkerThread
    fun tokenBlocking(): String? {
        if (!prefs.connected.value) return null
        val now = System.currentTimeMillis()
        token?.let { if (now - tokenAt < TOKEN_TTL_MS) return it }
        synchronized(lock) {
            token?.let { if (System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return it }
            return try {
                val result = Tasks.await(client.authorize(request), 30, TimeUnit.SECONDS)
                if (result.hasResolution()) {
                    _needsReauth.value = true
                    null
                } else {
                    token = result.accessToken
                    tokenAt = System.currentTimeMillis()
                    _needsReauth.value = false
                    token
                }
            } catch (e: Exception) {
                Log.w(TAG, "authorize failed", e)
                _lastError.value = e.message ?: e.javaClass.simpleName
                null
            }
        }
    }

    /** Called after a 401: drop the cached token so the next call fetches a fresh one. */
    @WorkerThread
    fun invalidate(bad: String) {
        synchronized(lock) {
            if (token == bad) {
                token = null
                tokenAt = 0
            }
        }
        runCatching { GoogleAuthUtil.clearToken(context, bad) }
    }

    /** Revokes the grant at Google and forgets the local state. */
    @WorkerThread
    fun disconnect(http: OkHttpClient) {
        val t = token ?: runCatching { tokenBlocking() }.getOrNull()
        if (t != null) {
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("https://oauth2.googleapis.com/revoke")
                        .post(FormBody.Builder().add("token", t).build())
                        .build(),
                ).execute().close()
            }
            runCatching { GoogleAuthUtil.clearToken(context, t) }
        }
        synchronized(lock) {
            token = null
            tokenAt = 0
        }
        prefs.setConnected(false)
        prefs.setAccount(null)
    }

    companion object {
        private const val TAG = "GoogleAuth"
        const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_TTL_MS = 40L * 60 * 1000
    }
}
