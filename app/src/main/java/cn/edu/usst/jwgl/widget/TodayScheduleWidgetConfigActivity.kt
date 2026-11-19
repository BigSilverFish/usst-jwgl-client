package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.ActivityTodayScheduleWidgetConfigBinding

class TodayScheduleWidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodayScheduleWidgetConfigBinding
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private var currentConfig = WidgetConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTodayScheduleWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传递过来的 AppWidgetId
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 默认预设 RESULT_OK，避免因系统后台拦截或误按返回键导致桌面将微件误判为取消并直接删除
        val defaultResult = Intent().apply {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        }
        setResult(RESULT_OK, defaultResult)

        // 读取已有配置或默认配置
        currentConfig = WidgetConfigManager.getConfig(this, appWidgetId)

        initToolbar()
        initBackHandler()
        initViews()
        updatePreview()
    }

    private fun initToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            saveAndApply()
        }
    }

    private fun initBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndApply()
            }
        })
    }

    private fun initViews() {
        // 0. 预览形态切换 (今日课表 / 下一节课)
        binding.btnPreviewToday.isChecked = true
        binding.togglePreviewType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnPreviewNext) {
                    binding.llPreviewWidgetCard.visibility = View.GONE
                    binding.llPreviewNextCourseCard.visibility = View.VISIBLE
                } else {
                    binding.llPreviewWidgetCard.visibility = View.VISIBLE
                    binding.llPreviewNextCourseCard.visibility = View.GONE
                }
            }
        }

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
        val safeAlpha = (Math.round(currentConfig.bgAlpha.coerceIn(0, 100) / 5.0) * 5).toFloat()
        binding.sliderAlpha.value = safeAlpha
        binding.tvAlphaValue.text = "${safeAlpha.toInt()}%"
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

        binding.switchShowBorder.isChecked = currentConfig.showBorder
        binding.switchShowBorder.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showBorder = isChecked)
            updatePreview()
        }

        binding.switchShowPortalTodos.isChecked = currentConfig.showPortalTodos
        binding.switchShowPortalTodos.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showPortalTodos = isChecked)
            updatePreview()
        }

        binding.switchShowQuickSign.isChecked = currentConfig.showQuickSign
        binding.switchShowQuickSign.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(showQuickSign = isChecked)
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

        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
        val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")
        val dividerColor = if (isDark) Color.parseColor("#374151") else Color.parseColor("#E4E7EC")

        // 动态加载带圆角与描边的预设 Shape Drawable
        val bgResId = currentConfig.getBackgroundResId(isDark)
        binding.llPreviewWidgetCard.setBackgroundResource(bgResId)
        binding.llPreviewNextCourseCard.setBackgroundResource(bgResId)

        // ===== 1. 今日课表形态预览 =====
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

        binding.tvPreviewCourse1Teacher.visibility = if (currentConfig.showTeacher) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Teacher.visibility = if (currentConfig.showTeacher) View.VISIBLE else View.GONE

        binding.tvPreviewCourse1Room.visibility = if (currentConfig.showRoom) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Room.visibility = if (currentConfig.showRoom) View.VISIBLE else View.GONE

        binding.tvPreviewCourse1Time.visibility = if (currentConfig.showTimeRange) View.VISIBLE else View.GONE
        binding.tvPreviewCourse2Time.visibility = if (currentConfig.showTimeRange) View.VISIBLE else View.GONE

        binding.llPreviewDateNav.visibility = if (currentConfig.allowDateSwitching) View.VISIBLE else View.GONE
        binding.tvPreviewQuickSign.visibility = if (currentConfig.showQuickSign) View.VISIBLE else View.GONE
        binding.tvPreviewCourse1TodoBadge.visibility = if (currentConfig.showPortalTodos) View.VISIBLE else View.GONE

        // ===== 2. 下一节课形态预览 =====
        binding.viewPreviewNextDivider.setBackgroundColor(dividerColor)
        binding.tvPreviewNextCourseName.setTextColor(primaryTextColor)
        binding.tvPreviewNextTime.setTextColor(secondaryTextColor)
        binding.tvPreviewNextTeacher.setTextColor(tertiaryTextColor)

        binding.llPreviewNextRoomRow.visibility = if (currentConfig.showRoom) View.VISIBLE else View.GONE
        binding.tvPreviewNextTime.visibility = if (currentConfig.showTimeRange) View.VISIBLE else View.GONE
        binding.tvPreviewNextTeacher.visibility = if (currentConfig.showTeacher) View.VISIBLE else View.GONE
        binding.tvPreviewNextQuickSign.visibility = if (currentConfig.showQuickSign) View.VISIBLE else View.GONE
        binding.llPreviewNextTodoRow.visibility = if (currentConfig.showPortalTodos) View.VISIBLE else View.GONE
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
}
