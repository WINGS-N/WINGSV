package be.mygod.vpnhotspot

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.Size
import androidx.browser.customtabs.CustomTabsIntent
import androidx.preference.PreferenceManager
import be.mygod.vpnhotspot.util.Services

class App private constructor(base: Context) : ContextWrapper(base.applicationContext) {
    class ParametersBuilder {
        fun param(@Suppress("UNUSED_PARAMETER") key: String, @Suppress("UNUSED_PARAMETER") value: String) = Unit
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var app: App
            private set

        @JvmStatic
        fun ensureInitialized(context: Context): App {
            if (!Companion::app.isInitialized) {
                app = App(context.applicationContext)
                Services.init { app }
            }
            return app
        }
    }

    val deviceStorage: Context
        get() = this

    val pref by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    val hasTouch by lazy { packageManager.hasSystemFeature("android.hardware.faketouch") }
    val customTabsIntent by lazy { CustomTabsIntent.Builder().build() }

    fun logEvent(@Size(min = 1L, max = 40L) @Suppress("UNUSED_PARAMETER") event: String,
                 block: ParametersBuilder.() -> Unit = { }) {
        block(ParametersBuilder())
    }
}
