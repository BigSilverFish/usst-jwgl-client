package cn.edu.usst.jwgl.widget

import android.content.Context
import com.google.gson.Gson

data class WidgetConfig(
    val bgAlpha: Int = 90,                  // 0 ~ 100
    val themeMode: Int = THEME_AUTO,        // 0: 跟随系统, 1: 浅色明亮, 2: 深色暗黑, 3: 全透明极简
    val showTeacher: Boolean = true,        // 是否显示授课教师
    val showRoom: Boolean = true,           // 是否显示教室
    val showTimeRange: Boolean = true,      // 是否显示具体时间段(如 08:00-09:35)
    val hidePastCourses: Boolean = false    // 是否隐藏已结束课程
) {
    companion object {
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_TRANSPARENT = 3
    }
}

object WidgetConfigManager {
    private const val PREF_NAME = "usst_widget_preferences"
    private const val KEY_PREFIX_WIDGET = "config_widget_"
    private const val KEY_GLOBAL_DEFAULT = "config_global_default"
    private val gson = Gson()

    fun getConfig(context: Context, appWidgetId: Int): WidgetConfig {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_PREFIX_WIDGET + appWidgetId, null)
            ?: sp.getString(KEY_GLOBAL_DEFAULT, null)
        return if (!json.isNullOrEmpty()) {
            try {
                gson.fromJson(json, WidgetConfig::class.java) ?: WidgetConfig()
            } catch (_: Exception) {
                WidgetConfig()
            }
        } else {
            WidgetConfig()
        }
    }

    fun saveConfig(context: Context, appWidgetId: Int, config: WidgetConfig) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(config)
        sp.edit()
            .putString(KEY_PREFIX_WIDGET + appWidgetId, json)
            .putString(KEY_GLOBAL_DEFAULT, json) // 同步作为之后新增组件的默认预设
            .apply()
    }

    fun deleteConfig(context: Context, appWidgetId: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().remove(KEY_PREFIX_WIDGET + appWidgetId).apply()
    }
}
