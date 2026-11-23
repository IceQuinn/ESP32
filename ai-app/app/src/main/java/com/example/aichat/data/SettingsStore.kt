package com.example.aichat.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置存储：API Key 与上次选择的模型。
 *
 * 说明：这里使用普通的 SharedPreferences，数据位于应用私有目录，
 * 其他应用无法读取；但如果设备已被 root，理论上可以读取。
 * 本版本为了不引入额外依赖，没有使用 EncryptedSharedPreferences。
 * 安全建议见 README：不要在共享设备上保存主账号的 Key。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, Models.DEFAULT_MODEL_ID)
            ?: Models.DEFAULT_MODEL_ID
        set(value) = prefs.edit().putString(KEY_MODEL_ID, value).apply()

    /** 首次启动时未配置 Key，需要引导用户进入设置页。 */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "ai_chat_settings"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL_ID = "model_id"
    }
}
