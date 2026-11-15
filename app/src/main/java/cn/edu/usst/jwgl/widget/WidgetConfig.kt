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
    val showQuickSign: Boolean = true       // 是否在微件顶部展示课堂签到快捷入口
) {
    companion object {
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_TRANSPARENT = 3

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
            val list = mutableListOf<UnifiedTodo>()
            val nowMs = System.currentTimeMillis()
            try {
                val account = AuthPreferences.currentStudentId(context)
                // 1. 超星待办
                val cxTodos = ChaoXingPreferences(context).getCachedTodos(account)
                    .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                list.addAll(cxTodos)

                // 2. 畅课待办
                val tcTodos = TronClassDataCacheManager(context).getCachedTodos()
                    .filterNot { it.isOverdue() || it.isLocked }
                    .map { UnifiedTodo.fromTronClass(it) }
                list.addAll(tcTodos)
            } catch (_: Exception) {
            }

            return list.filter { it.deadlineMillis == null || it.deadlineMillis > nowMs }
                .sortedBy { it.deadlineMillis ?: Long.MAX_VALUE }
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
            .apply()
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
