package com.google.firebase.crashlytics

/**
 * Заглушка вместо настоящей крашлитики: из моста firebase вырезан, а апстримный
 * RootManager пишет туда учётные ключи рут-процесса. Ключи просто проглатываются.
 */
class FirebaseCrashlytics private constructor() {
    fun setCustomKey(key: String, value: Boolean) {}

    fun setCustomKey(key: String, value: Double) {}

    fun setCustomKey(key: String, value: Float) {}

    fun setCustomKey(key: String, value: Int) {}

    fun setCustomKey(key: String, value: Long) {}

    fun setCustomKey(key: String, value: String) {}

    fun recordException(throwable: Throwable) {}

    fun log(message: String) {}

    companion object {
        private val instance = FirebaseCrashlytics()

        @JvmStatic
        fun getInstance() = instance
    }
}
