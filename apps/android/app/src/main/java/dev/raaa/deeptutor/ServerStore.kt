package dev.raaa.deeptutor

import android.content.Context

/** 壳层唯一的持久化入口，不保存任何凭据。 */
class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences("shell_prefs", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_SERVER_URL) else putString(KEY_SERVER_URL, value)
            }.apply()
        }

    var fabX: Int
        get() = prefs.getInt(KEY_FAB_X, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_FAB_X, value).apply()

    var fabY: Int
        get() = prefs.getInt(KEY_FAB_Y, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_FAB_Y, value).apply()

    var hintShown: Boolean
        get() = prefs.getBoolean(KEY_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_HINT_SHOWN, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_FAB_X = "fab_x"
        const val KEY_FAB_Y = "fab_y"
        const val KEY_HINT_SHOWN = "connect_hint_shown"
    }
}
