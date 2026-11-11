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

    /** 数学公式键盘入口：关闭后隐藏页面上的 Σ 悬浮按钮。 */
    var mathKeyboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_MATH_KEYBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_MATH_KEYBOARD, value).apply()

    /** 沉浸模式：隐藏状态栏，从屏幕边缘下滑可临时调出。 */
    var immersive: Boolean
        get() = prefs.getBoolean(KEY_IMMERSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_IMMERSIVE, value).apply()

    /** 自定义注入层的目录地址，需包含 custom.css / custom.js；为空表示不启用。 */
    var customLayerUrl: String?
        get() = prefs.getString(KEY_CUSTOM_LAYER_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_CUSTOM_LAYER_URL) else putString(KEY_CUSTOM_LAYER_URL, value)
            }.apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_IMMERSIVE = "immersive"
        const val KEY_CUSTOM_LAYER_URL = "custom_layer_url"
        const val KEY_MATH_KEYBOARD = "math_keyboard_enabled"
    }
}
