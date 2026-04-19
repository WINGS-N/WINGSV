package com.google.firebase.crashlytics

class FirebaseCrashlytics private constructor() {
    companion object {
        private val instance = FirebaseCrashlytics()

        @JvmStatic
        fun getInstance(): FirebaseCrashlytics = instance
    }

    fun setCustomKey(@Suppress("UNUSED_PARAMETER") key: String,
                     @Suppress("UNUSED_PARAMETER") value: Boolean) = Unit

    fun setCustomKey(@Suppress("UNUSED_PARAMETER") key: String,
                     @Suppress("UNUSED_PARAMETER") value: String?) = Unit

    fun recordException(@Suppress("UNUSED_PARAMETER") throwable: Throwable) = Unit
    fun log(@Suppress("UNUSED_PARAMETER") message: String) = Unit
}
