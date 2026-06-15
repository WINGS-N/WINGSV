package wings.v.vk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Xml
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import org.xmlpull.v1.XmlPullParser
import wings.v.BuildConfig

object VkIdManager {
    private const val OAUTH_AUTHORIZE_URL = "https://oauth.vk.com/authorize"
    private const val OAUTH_REDIRECT_URI = "https://oauth.vk.com/blank.html"
    private const val LEGACY_SCOPE = "1073737727"
    private const val OAUTH_DISPLAY = "page"
    private const val OAUTH_RESPONSE_TYPE = "token"
    private const val OAUTH_REVOKE = "1"
    private const val PUBLIC_OAUTH_URL_PATTERN =
        "https://oauth.vk.com/authorize?client_id=%s&scope=1073737727&redirect_uri=" +
            "https://oauth.vk.com/blank.html&display=page&response_type=token&revoke=1"
    private const val PREFS_NAME = "vk_api_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_STATE = "state"
    private val callbackRef = AtomicReference<Callback?>()
    private val secureRandom = SecureRandom()

    interface Callback {
        fun onSuccess(summary: String)
        fun onError(message: String)
    }

    @JvmStatic
    fun initialize(context: Context) {
        AppContextHolder.context = context.applicationContext
    }

    @JvmStatic
    fun isConfigured(): Boolean = BuildConfig.VK_ID_CONFIGURED

    @JvmStatic
    fun isAuthorized(): Boolean {
        if (!isConfigured()) {
            return false
        }
        val token = prefs(AppContextHolder.context).getString(KEY_ACCESS_TOKEN, "").orEmpty()
        return token.isNotEmpty() && !isExpired(AppContextHolder.context)
    }

    @JvmStatic
    fun accountSummary(): String? {
        val userId = prefs(AppContextHolder.context).getString(KEY_USER_ID, "").orEmpty()
        return if (userId.isBlank()) null else "VK #$userId"
    }

    @JvmStatic
    fun authorize(context: Context, callback: Callback) {
        if (!isConfigured()) {
            callback.onError("VK API не настроен в сборке")
            return
        }
        AppContextHolder.context = context.applicationContext
        val state = newState()
        prefs(context).edit().putString(KEY_STATE, state).apply()
        callbackRef.set(callback)
        val authUri = Uri.parse(OAUTH_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.VK_CLIENT_ID)
            .appendQueryParameter("scope", LEGACY_SCOPE)
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .appendQueryParameter("display", OAUTH_DISPLAY)
            .appendQueryParameter("response_type", OAUTH_RESPONSE_TYPE)
            .appendQueryParameter("revoke", OAUTH_REVOKE)
            .appendQueryParameter("state", state)
            .build()
        context.startActivity(VkOAuthActivity.createIntent(context, authUri.toString()))
    }

    @JvmStatic
    fun logout(context: Context, callback: Callback) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_STATE)
            .apply()
        callback.onSuccess("")
    }

    @JvmStatic
    fun handleRedirect(context: Context, uri: Uri) {
        AppContextHolder.context = context.applicationContext
        val params = parseParams(uri)
        val callback = callbackRef.getAndSet(null)
        val expectedState = prefs(context).getString(KEY_STATE, "").orEmpty()
        val actualState = params["state"].orEmpty()
        val error = params["error"].orEmpty()
        if (error.isNotEmpty()) {
            callback?.onError(params["error_description"].orEmpty().ifEmpty { error })
            return
        }
        if (expectedState.isNotEmpty() && actualState != expectedState) {
            callback?.onError("VK OAuth вернул неверный state")
            return
        }
        val token = params["access_token"].orEmpty()
        if (token.isBlank()) {
            callback?.onError("VK OAuth не вернул access_token")
            return
        }
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L
        val userId = params["user_id"].orEmpty()
        val expiresAt = if (expiresIn <= 0L) Long.MAX_VALUE else currentSeconds() + expiresIn
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, userId)
            .remove(KEY_STATE)
            .apply()
        callback?.onSuccess(accountSummary() ?: "VK API")
    }

    @JvmStatic
    fun accessTokenForApiBlocking(): String {
        return accessTokenForApiBlocking(AppContextHolder.context)
    }

    @JvmStatic
    fun accessTokenForApiBlocking(context: Context): String {
        AppContextHolder.context = context.applicationContext
        val auth = readPersistedAuth(context)
        if (auth.accessToken.isBlank()) {
            throw IllegalStateException("Войдите в VK в настройках VK TURN")
        }
        if (isExpired(auth.expiresAt)) {
            throw IllegalStateException("VK access_token истек, войдите заново")
        }
        return auth.accessToken
    }

    private fun isExpired(context: Context): Boolean {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        return isExpired(expiresAt)
    }

    private fun isExpired(expiresAt: Long): Boolean {
        return expiresAt != Long.MAX_VALUE && expiresAt <= currentSeconds() + 60L
    }

    private fun readPersistedAuth(context: Context): StoredAuth {
        val diskAuth = readPersistedAuthFromDisk(context)
        if (diskAuth.accessToken.isNotBlank()) {
            return diskAuth
        }
        val preferences = prefs(context)
        return StoredAuth(
            preferences.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
            preferences.getLong(KEY_EXPIRES_AT, 0L),
            preferences.getString(KEY_USER_ID, "").orEmpty()
        )
    }

    private fun readPersistedAuthFromDisk(context: Context): StoredAuth {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
        if (!file.isFile) {
            return StoredAuth()
        }
        var accessToken = ""
        var expiresAt = 0L
        var userId = ""
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, StandardCharsets.UTF_8.name())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.getAttributeValue(null, "name")) {
                        KEY_ACCESS_TOKEN -> accessToken = parser.nextText().orEmpty()
                        KEY_EXPIRES_AT -> expiresAt = parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                        KEY_USER_ID -> userId = parser.nextText().orEmpty()
                    }
                }
                event = parser.next()
            }
        }
        return StoredAuth(accessToken, expiresAt, userId)
    }

    private fun parseParams(uri: Uri): Map<String, String> {
        val result = linkedMapOf<String, String>()
        parseParamString(uri.encodedQuery.orEmpty(), result)
        parseParamString(uri.encodedFragment.orEmpty(), result)
        return result
    }

    private fun parseParamString(value: String, result: MutableMap<String, String>) {
        value.split('&')
            .asSequence()
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val separator = pair.indexOf('=')
                val key = if (separator < 0) pair else pair.substring(0, separator)
                val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
                result[decode(key)] = decode(rawValue)
            }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun newState(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) } + SystemClock.elapsedRealtime()
    }

    private fun currentSeconds(): Long = System.currentTimeMillis() / 1000L

    @Suppress("unused")
    private fun publicOauthUrlForDocs(): String = PUBLIC_OAUTH_URL_PATTERN.format(BuildConfig.VK_CLIENT_ID)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private object AppContextHolder {
        lateinit var context: Context
    }

    private data class StoredAuth(
        val accessToken: String = "",
        val expiresAt: Long = 0L,
        val userId: String = ""
    )
}
