package cn.edu.usst.jwgl.widget

import android.content.Context
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.UnifiedTodo
import com.google.gson.Gson

data class WidgetConfig(
    val bgAlpha: Int = 90,                  // 0 ~ 100
    val themeMode: Int = THEME_AUTO,        // 0: 跟随系统, 1: 浅色明亮, 2: 深色暗黑, 3: 全透明极简
    val showTeacher: Boolean = true,        // 是否显示授课教师
    val showRoom: Boolean = true,           // 是否显示教室
    val showTimeRange: Boolean = true,      // 是否显示具体时间段(如 08:00-09:35)
    val hidePastCourses: Boolean = false,   // 是否隐藏已结束课程
    val allowDateSwitching: Boolean = true, // 是否允许在微件上切换日期(前一天/后一天)
    val showBorder: Boolean = true,         // 是否开启精致边缘描边(增强各壁纸下的辨识度)
    val showPortalTodos: Boolean = true,    // 是否在微件中展示课程门户待交作业提示
    val showQuickSign: Boolean = true,      // 是否在微件顶部展示课堂签到快捷入口
    val syncIntervalHours: Int = 2,         // 后台定时刷新周期 (1/2/3/4/6/8/12/18/24 小时)
    val feedDaysWindow: Int = 7             // 待办/通知时间窗口 (默认7天)
) {
    companion object {
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_TRANSPARENT = 3

        const val TAB_SCHEDULE = 0
        const val TAB_TODOS = 1
        const val TAB_NOTICES = 2

        val SYNC_INTERVAL_OPTIONS = listOf(1, 2, 3, 4, 6, 8, 12, 18, 24)

        fun matchesCourseName(todoCourseName: String, tableCourseName: String): Boolean {
            val clean1 = todoCourseName.replace(Regex("[（(].*?[）)]"), "")
                .replace(Regex("[0-9一二三四五六七八九十]+$"), "")
                .replace(" ", "").trim()
            val clean2 = tableCourseName.replace(Regex("[（(].*?[）)]"), "")
                .replace(Regex("[0-9一二三四五六七八九十]+$"), "")
                .replace(" ", "").trim()
            return clean1.isNotEmpty() && clean2.isNotEmpty() && (clean1.contains(clean2) || clean2.contains(clean1))
        }

        fun loadActivePortalTodos(context: Context): List<UnifiedTodo> {
            return WidgetFeedRepository.loadActiveTodos(context)
        }
    }

    fun getBackgroundResId(isDark: Boolean): Int {
        return when (themeMode) {
            THEME_TRANSPARENT -> {
                if (showBorder) R.drawable.bg_widget_clear_stroke else R.drawable.bg_widget_clear
            }
            THEME_DARK -> {
                when {
                    bgAlpha >= 95 -> R.drawable.bg_widget_dark_solid
                    bgAlpha >= 60 -> R.drawable.bg_widget_dark_translucent
                    bgAlpha >= 20 -> R.drawable.bg_widget_dark_half
                    else -> if (showBorder) R.drawable.bg_widget_clear_stroke else R.drawable.bg_widget_clear
                }
            }
            THEME_LIGHT -> {
                when {
                    bgAlpha >= 95 -> R.drawable.bg_widget_light_solid
                    bgAlpha >= 60 -> R.drawable.bg_widget_light_translucent
                    bgAlpha >= 20 -> R.drawable.bg_widget_light_half
                    else -> if (showBorder) R.drawable.bg_widget_clear_stroke else R.drawable.bg_widget_clear
                }
            }
            else -> { // THEME_AUTO
                if (isDark) {
                    when {
                        bgAlpha >= 95 -> R.drawable.bg_widget_dark_solid
                        bgAlpha >= 60 -> R.drawable.bg_widget_dark_translucent
                        bgAlpha >= 20 -> R.drawable.bg_widget_dark_half
                        else -> if (showBorder) R.drawable.bg_widget_clear_stroke else R.drawable.bg_widget_clear
                    }
                } else {
                    when {
                        bgAlpha >= 95 -> R.drawable.bg_widget_light_solid
                        bgAlpha >= 60 -> R.drawable.bg_widget_light_translucent
                        bgAlpha >= 20 -> R.drawable.bg_widget_light_half
                        else -> if (showBorder) R.drawable.bg_widget_clear_stroke else R.drawable.bg_widget_clear
                    }
                }
            }
        }
    }
}

object WidgetConfigManager {
    private const val PREF_NAME = "usst_widget_preferences"
    private const val KEY_PREFIX_WIDGET = "config_widget_"
    private const val KEY_PREFIX_OFFSET = "offset_widget_"
    private const val KEY_PREFIX_TAB = "tab_widget_"
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
        sp.edit()
            .remove(KEY_PREFIX_WIDGET + appWidgetId)
            .remove(KEY_PREFIX_OFFSET + appWidgetId)
            .remove(KEY_PREFIX_TAB + appWidgetId)
            .apply()
    }

    fun getActiveTab(context: Context, appWidgetId: Int, defaultTab: Int): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_PREFIX_TAB + appWidgetId, defaultTab)
    }

    fun setActiveTab(context: Context, appWidgetId: Int, tab: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_PREFIX_TAB + appWidgetId, tab).apply()
    }

    fun getSyncIntervalHours(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_GLOBAL_DEFAULT, null)
        val raw = if (!json.isNullOrEmpty()) {
            try {
                gson.fromJson(json, WidgetConfig::class.java)?.syncIntervalHours ?: 2
            } catch (_: Exception) {
                2
            }
        } else {
            2
        }
        return if (raw in WidgetConfig.SYNC_INTERVAL_OPTIONS) raw else 2
    }

    fun getDayOffset(context: Context, appWidgetId: Int): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_PREFIX_OFFSET + appWidgetId, 0)
    }

    fun setDayOffset(context: Context, appWidgetId: Int, offset: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_PREFIX_OFFSET + appWidgetId, offset).apply()
    }

    fun resetAllDayOffsets(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()
        for (key in sp.all.keys) {
            if (key.startsWith(KEY_PREFIX_OFFSET)) {
                editor.putInt(key, 0)
            }
        }
        editor.apply()
    }
}
