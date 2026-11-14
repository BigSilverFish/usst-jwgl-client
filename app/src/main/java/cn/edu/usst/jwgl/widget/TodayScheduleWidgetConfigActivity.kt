package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.ActivityTodayScheduleWidgetConfigBinding

class TodayScheduleWidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodayScheduleWidgetConfigBinding
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private var currentConfig = WidgetConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认返回 CANCELED，防止用户在配置流程中按物理返回键导致桌面残留未配置的微件
        setResult(RESULT_CANCELED)

        binding = ActivityTodayScheduleWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传递过来的 AppWidgetId
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 读取已有配置或默认配置
        currentConfig = WidgetConfigManager.getConfig(this, appWidgetId)

        initToolbar()
        initViews()
        updatePreview()
    }

    private fun initToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initViews() {
        // 1. 色彩风格 ToggleGroup
        when (currentConfig.themeMode) {
            WidgetConfig.THEME_LIGHT -> binding.btnThemeLight.isChecked = true
            WidgetConfig.THEME_DARK -> binding.btnThemeDark.isChecked = true
            WidgetConfig.THEME_TRANSPARENT -> binding.btnThemeTransparent.isChecked = true
            else -> binding.btnThemeAuto.isChecked = true
        }

        binding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentConfig = when (checkedId) {
                    R.id.btnThemeLight -> currentConfig.copy(themeMode = WidgetConfig.THEME_LIGHT)
                    R.id.btnThemeDark -> currentConfig.copy(themeMode = WidgetConfig.THEME_DARK)
                    R.id.btnThemeTransparent -> currentConfig.copy(themeMode = WidgetConfig.THEME_TRANSPARENT)
                    else -> currentConfig.copy(themeMode = WidgetConfig.THEME_AUTO)
                }
                updatePreview()
            }
        }

        // 2. 透明度 Slider
        binding.sliderAlpha.value = currentConfig.bgAlpha.toFloat()
        binding.tvAlphaValue.text = "${currentConfig.bgAlpha}%"
        binding.sliderAlpha.addOnChangeListener { _, value, _ ->
            val alpha = value.toInt()
            currentConfig = currentConfig.copy(bgAlpha = alpha)
            binding.tvAlphaValue.text = "$alpha%"
            updatePreview()
        }

        // 3. 内容显隐 Switches
        binding.switchShowTeacher.isChecked = currentConfig.showTeacher
        binding.switchShowTeacher.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showTeacher = isChecked)
            updatePreview()
        }

        binding.switchShowRoom.isChecked = currentConfig.showRoom
        binding.switchShowRoom.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showRoom = isChecked)
            updatePreview()
        }

        binding.switchShowTimeRange.isChecked = currentConfig.showTimeRange
        binding.switchShowTimeRange.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showTimeRange = isChecked)
            updatePreview()
        }

        binding.switchHidePastCourses.isChecked = currentConfig.hidePastCourses
        binding.switchHidePastCourses.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(hidePastCourses = isChecked)
            updatePreview()
        }

        binding.switchAllowDateSwitching.isChecked = currentConfig.allowDateSwitching
        binding.switchAllowDateSwitching.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(allowDateSwitching = isChecked)
            updatePreview()
        }

        // 4. 保存按钮
        binding.btnSave.setOnClickListener {
            saveAndApply()
        }
    }

    private fun updatePreview() {
        val isSystemNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val isDark = when (currentConfig.themeMode) {
            WidgetConfig.THEME_DARK -> true
            WidgetConfig.THEME_LIGHT -> false
            WidgetConfig.THEME_TRANSPARENT -> false
            else -> isSystemNight
        }

        val isTransparent = currentConfig.themeMode == WidgetConfig.THEME_TRANSPARENT
        val alphaPercent = if (isTransparent) 0 else currentConfig.bgAlpha
        val alphaInt = (alphaPercent * 255 / 100).coerceIn(0, 255)

        val cardBgColor = if (isDark) {
            Color.argb(alphaInt, 30, 30, 30)
        } else {
            Color.argb(alphaInt, 255, 255, 255)
        }

        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
        val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")
        val dividerColor = if (isDark) Color.parseColor("#374151") else Color.parseColor("#E4E7EC")

        // 动态绘制带圆角卡片背景
        val cornerRadiusPx = dpToPx(16f)
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(cardBgColor)
            if (alphaPercent < 20 || isTransparent) {
                // 当完全透明或接近透明时增加淡淡的白色微描边，防止在深浅壁纸上不可见
                setStroke(dpToPx(1f).toInt(), Color.argb(40, 255, 255, 255))
            }
        }
        binding.llPreviewWidgetCard.background = bgDrawable

        // 更新文本颜色
        binding.tvPreviewHeaderDate.setTextColor(primaryTextColor)
        binding.viewPreviewDivider.setBackgroundColor(dividerColor)

        binding.tvPreviewCourse1Name.setTextColor(primaryTextColor)
        binding.tvPreviewCourse1Time.setTextColor(secondaryTextColor)
        binding.tvPreviewCourse1Room.setTextColor(secondaryTextColor)
        binding.tvPreviewCourse1Teacher.setTextColor(tertiaryTextColor)

        binding.tvPreviewCourse2Name.setTextColor(primaryTextColor)
        binding.tvPreviewCourse2Time.setTextColor(secondaryTextColor)
        binding.tvPreviewCourse2Room.setTextColor(secondaryTextColor)
        binding.tvPreviewCourse2Teacher.setTextColor(tertiaryTextColor)

        // 开关控制视图显隐
        binding.tvPreviewCourse1Teacher.visibility = if (currentConfig.showTeacher) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Teacher.visibility = if (currentConfig.showTeacher) View.VISIBLE else View.GONE

        binding.tvPreviewCourse1Room.visibility = if (currentConfig.showRoom) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Room.visibility = if (currentConfig.showRoom) View.VISIBLE else View.GONE

        binding.tvPreviewCourse1Time.visibility = if (currentConfig.showTimeRange) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Time.visibility = if (currentConfig.showTimeRange) View.VISIBLE else View.GONE

        binding.llPreviewDateNav.visibility = if (currentConfig.allowDateSwitching) View.VISIBLE else View.GONE
    }

    private fun saveAndApply() {
        WidgetConfigManager.saveConfig(this, appWidgetId, currentConfig)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            TodayScheduleWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
            NextCourseWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)
            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
        } else {
            // 全局设置生效：刷新所有桌面微件
            TodayScheduleWidgetProvider.updateAllWidgets(this)
            NextCourseWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, "设置已保存，桌面微件已同步更新", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
