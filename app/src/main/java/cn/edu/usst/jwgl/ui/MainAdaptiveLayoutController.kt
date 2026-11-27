package cn.edu.usst.jwgl.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.LayoutPageMoreFeaturesBinding
import cn.edu.usst.jwgl.ui.grades.GradesFragment
import cn.edu.usst.jwgl.ui.tronclass.TronClassFragment
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.ui.MainActivity.Companion.bottomBarHeightPx
import cn.edu.usst.jwgl.ui.MainActivity.MainTab
import cn.edu.usst.jwgl.util.ThemeManager
import cn.edu.usst.jwgl.util.TimetableSettingHelper

/**
 * 主界面手机底部导航/抽屉模式与平板横屏双栏自适应布局控制器
 */
internal class MainAdaptiveLayoutController(
    private val activity: MainActivity
) {
    private val binding get() = activity.binding
    private val isGuestMode get() = activity.isGuestMode
    private var isTabletLandscapeMode
        get() = activity.isTabletLandscapeMode
        set(value) { activity.isTabletLandscapeMode = value }
    private val lastStatusTopPadPx get() = activity.lastStatusTopPadPx
    private val lastNavBarBottomInsetPx get() = activity.lastNavBarBottomInsetPx
    private val currentTab get() = activity.currentTab
    private val currentWeek get() = activity.currentWeek
    private val currentTable get() = activity.currentTable
    private val scheduleAdapter get() = activity.scheduleAdapter
    private val lastStatusBarTopPx get() = activity.lastStatusBarTopPx
    private var timetableLandscapeExtraPadPx
        get() = activity.timetableLandscapeExtraPadPx
        set(value) { activity.timetableLandscapeExtraPadPx = value }

    fun isPhoneBottomBarMode(): Boolean {
        return !isTabletLandscapeMode && !isGuestMode &&
                TimetableSettingHelper.getPhoneNavMode(activity) == TimetableSettingHelper.NAV_MODE_BOTTOM
    }

    fun applyContentBottomPadding() {
        val density = activity.resources.displayMetrics.density
        val isBottomMode = isPhoneBottomBarMode()
        val bottomNavBarHeight = if (isBottomMode) (64 * density).toInt() else 0
        bottomBarHeightPx = lastNavBarBottomInsetPx + bottomNavBarHeight + (16 * density).toInt()
        val statusTopPad = lastStatusTopPadPx.takeIf { it > 0 } ?: (32 * density).toInt()

        activity.getProfileSettingsFragment()?.applyBottomPadding(bottomBarHeightPx)
        activity.moreFeaturesBinding?.let { mb ->
            mb.scrollMoreFeatures.setPadding(
                mb.scrollMoreFeatures.paddingLeft,
                mb.scrollMoreFeatures.paddingTop,
                mb.scrollMoreFeatures.paddingRight,
                bottomBarHeightPx
            )
        }
        (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.applyBottomPadding(bottomBarHeightPx)

        activity.supportFragmentManager.fragments.filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
            .forEach { it.applyContentPadding() }
    }

    fun syncMoreFeaturesPageAdaptiveState(mb: LayoutPageMoreFeaturesBinding) {
        mb.scrollMoreFeatures.setPadding(
            mb.scrollMoreFeatures.paddingLeft,
            mb.scrollMoreFeatures.paddingTop,
            mb.scrollMoreFeatures.paddingRight,
            bottomBarHeightPx
        )
        mb.btnOpenDrawerMoreFeatures.visibility = if (!isGuestMode && !isTabletLandscapeMode && !isPhoneBottomBarMode()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    fun applyPhoneNavigationMode() {
        if (isGuestMode) {
            closeNavDrawer()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.GONE
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
            (activity.supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment)?.updateDrawerButtonVisibility(false)
            activity.getProfileSettingsFragment()?.syncAdaptiveState()
            activity.moreFeaturesBinding?.let { syncMoreFeaturesPageAdaptiveState(it) }
            applyContentBottomPadding()
            return
        }

        if (isTabletLandscapeMode) {
            binding.blurViewBottomNav.visibility = View.GONE
            activity.getProfileSettingsFragment()?.syncAdaptiveState()
            activity.moreFeaturesBinding?.let { syncMoreFeaturesPageAdaptiveState(it) }
            return
        }

        val navMode = TimetableSettingHelper.getPhoneNavMode(activity)
        val isBottomMode = navMode == TimetableSettingHelper.NAV_MODE_BOTTOM

        if (isBottomMode) {
            closeNavDrawer()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.VISIBLE
            activity.ensureBottomNavBlur()

            // Hide top-left drawer hamburger icons on the main bottom-bar pages
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
            (activity.supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment)?.updateDrawerButtonVisibility(false)
        } else {
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
            binding.blurViewBottomNav.visibility = View.GONE

            binding.btnOpenDrawerTimetable.visibility = View.VISIBLE
            (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(true)
            (activity.supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment)?.updateDrawerButtonVisibility(true)

            if (currentTab == MainTab.MORE_FEATURES) {
                activity.switchTab(MainTab.TIMETABLE)
            }
        }

        activity.getProfileSettingsFragment()?.syncAdaptiveState()
        activity.moreFeaturesBinding?.let { syncMoreFeaturesPageAdaptiveState(it) }
        applyContentBottomPadding()
    }

    fun onTimetableEmptySpaceDoubleTapped(): Boolean {
        if (isTabletLandscapeMode) return false
        if (TimetableSettingHelper.getPhoneNavMode(activity) != TimetableSettingHelper.NAV_MODE_DRAWER) return false
        if (!TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(activity)) return false
        openNavDrawer()
        return true
    }

    fun openNavDrawer() {
        if (!isTabletLandscapeMode &&
            !isPhoneBottomBarMode() &&
            binding.layoutDrawer.root.parent == binding.drawerLayout
        ) {
            val drawerView = binding.layoutDrawer.root
            if (drawerView.visibility != View.VISIBLE) {
                drawerView.visibility = View.VISIBLE
            }
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    fun closeNavDrawer() {
        if (!isTabletLandscapeMode && !isPhoneBottomBarMode() && binding.layoutDrawer.root.parent == binding.drawerLayout) {
            binding.drawerLayout.closeDrawer(GravityCompat.START, false)
        }
    }

    fun isNavDrawerOpen(): Boolean {
        return !isTabletLandscapeMode &&
                binding.layoutDrawer.root.parent == binding.drawerLayout &&
                binding.drawerLayout.isDrawerVisible(GravityCompat.START)
    }

    fun setupTabletLandscapeLayout(config: Configuration? = null) {
        isTabletLandscapeMode = TabletLayoutHelper.isTabletLandscape(activity, config)
        if (!isTabletLandscapeMode) return

        val dp = { v: Float -> TabletLayoutHelper.dpToPx(activity, v) }

        // 若抽屉处于展开态，先无动画闭合
        binding.drawerLayout.closeDrawer(GravityCompat.START, false)

        // 1. 常驻左侧导航栏 (Permanent Left Side Navigation)
        val drawerView = binding.layoutDrawer.root
        (drawerView.parent as? ViewGroup)?.removeView(drawerView)

        // 彻底复位 DrawerLayout 残留的所有滑动偏移、平移与可见性状态，杜绝侧栏空白
        drawerView.visibility = View.VISIBLE
        drawerView.translationX = 0f
        drawerView.translationY = 0f
        drawerView.alpha = 1f

        binding.permanentSidebarContainer.removeAllViews()
        binding.permanentSidebarContainer.addView(
            drawerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.permanentSidebarContainer.visibility = View.VISIBLE
        binding.viewPermanentSidebarDivider.visibility = View.VISIBLE
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.permanentSidebarContainer.requestLayout()
        drawerView.requestLayout()

        // 隐藏各页面顶栏左上角汉堡包菜单图标
        binding.btnOpenDrawerTimetable.visibility = View.GONE
        (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
        (activity.supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment)?.updateDrawerButtonVisibility(false)

        // 3. 双栏主从视图 (Two-Pane Master-Detail Layouts)
        // 3a. 学业情况查询 (委托给 GradesFragment)
        (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.updateTabletLandscapeMode(true)

        // 3b. 个人与设置 (左：学生名片 + 学籍档案 + 退出登录；右：设置分组列表)
        activity.getProfileSettingsFragment()?.updateTabletLandscapeMode(true)

        binding.headerTimetableContainer.post {
            alignLandscapeTimetableTopBarWithSidebar()
        }
    }

    /**
     * 将横屏平板模式下课表顶栏底部边界对齐至左侧栏用户信息下方分割线。
     */
    fun alignLandscapeTimetableTopBarWithSidebar() {
        val density = activity.resources.displayMetrics.density
        val baseTopPad = lastStatusBarTopPx + (8 * density).toInt()
        val baseBottomPad = (6 * density).toInt()

        if (!isTabletLandscapeMode ||
            binding.layoutDrawer.cardDrawerProfileHeader.visibility != View.VISIBLE ||
            binding.layoutDrawer.dividerDrawerProfileHeader.visibility != View.VISIBLE
        ) {
            if (timetableLandscapeExtraPadPx != 0) {
                timetableLandscapeExtraPadPx = 0
                binding.headerTimetable.setPadding(
                    binding.headerTimetable.paddingLeft,
                    baseTopPad,
                    binding.headerTimetable.paddingRight,
                    baseBottomPad
                )
            }
            return
        }

        val sidebarDividerBottom = binding.layoutDrawer.dividerDrawerProfileHeader.bottom
        val currentHeaderHeight = binding.headerTimetableContainer.height
        if (sidebarDividerBottom <= 0 || currentHeaderHeight <= 0) return

        val naturalHeaderHeight = currentHeaderHeight - timetableLandscapeExtraPadPx
        val desiredExtra = (sidebarDividerBottom - naturalHeaderHeight).coerceAtLeast(0)
        if (desiredExtra != timetableLandscapeExtraPadPx ||
            binding.headerTimetable.paddingBottom != baseBottomPad + desiredExtra
        ) {
            timetableLandscapeExtraPadPx = desiredExtra
            binding.headerTimetable.setPadding(
                binding.headerTimetable.paddingLeft,
                baseTopPad,
                binding.headerTimetable.paddingRight,
                baseBottomPad + desiredExtra
            )
        }
    }

    /**
     * 屏幕旋转或侧栏显隐导致 ViewPager2 宽度变化时，强制将内部 RecyclerView 精准对齐当前周页，避免卡在半页位置。
     */
    fun snapSchedulePagerToCurrentItem(targetPos: Int = binding.vpSchedule.currentItem) {
        val pager = binding.vpSchedule
        if (pager.adapter == null || pager.width <= 0) return
        val count = pager.adapter?.itemCount ?: 0
        if (count <= 0) return
        val clampedPos = targetPos.coerceIn(0, count - 1)
        if (pager.currentItem != clampedPos) {
            pager.setCurrentItem(clampedPos, false)
        }
        val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(clampedPos, 0)
        pager.requestTransform()
    }

    fun restorePortraitLayout() {
        isTabletLandscapeMode = false
        val dp = { v: Float -> TabletLayoutHelper.dpToPx(activity, v) }

        // 1. 恢复侧边栏至抽屉
        val drawerView = binding.layoutDrawer.root
        (drawerView.parent as? ViewGroup)?.removeView(drawerView)

        val drawerWidthPx = (310 * activity.resources.displayMetrics.density).toInt()
        val screenHeight = activity.resources.displayMetrics.heightPixels

        // 强制重设 drawerView 测量尺寸与初始布局，将其完整置于屏幕左外侧 (-drawerWidthPx, 0, 0, height)
        // 消除从 permanentSidebarContainer (265dp) 带出的残留尺寸 530px 与旧坐标
        val widthSpec = View.MeasureSpec.makeMeasureSpec(drawerWidthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY)
        drawerView.measure(widthSpec, heightSpec)
        drawerView.layout(-drawerWidthPx, 0, 0, screenHeight)

        drawerView.translationX = 0f
        drawerView.translationY = 0f
        drawerView.alpha = 1f
        val isBottomMode = isPhoneBottomBarMode()
        if (isBottomMode) {
            drawerView.visibility = View.GONE
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            // 设置为 INVISIBLE 使 DrawerLayout 在初始测量与排版时计算其尺寸并将 left 放置在屏幕左侧外 (-drawerWidthPx)，排版后彻底不可见
            drawerView.visibility = View.INVISIBLE
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
        }

        val lp = androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
            drawerWidthPx,
            ViewGroup.LayoutParams.MATCH_PARENT,
            GravityCompat.START
        )
        binding.drawerLayout.addView(drawerView, lp)
        binding.permanentSidebarContainer.visibility = View.GONE
        binding.viewPermanentSidebarDivider.visibility = View.GONE

        if (!isBottomMode) {
            // 仅在抽屉模式下调用 closeDrawer，避免 closeDrawer 将底栏模式下设为 GONE 的视图覆盖为 INVISIBLE
            binding.drawerLayout.closeDrawer(GravityCompat.START, false)
        }

        drawerView.post {
            if (!isTabletLandscapeMode && binding.layoutDrawer.root.parent == binding.drawerLayout) {
                if (isBottomMode) {
                    drawerView.visibility = View.GONE
                } else {
                    binding.drawerLayout.closeDrawer(GravityCompat.START, false)
                    if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START)) {
                        drawerView.visibility = View.GONE
                    }
                }
            }
        }

        /* 3. Restore two-pane to vertical */
        (activity.supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.updateTabletLandscapeMode(false)

        activity.getProfileSettingsFragment()?.updateTabletLandscapeMode(false)
        timetableLandscapeExtraPadPx = 0
        val density = activity.resources.displayMetrics.density
        binding.headerTimetable.setPadding(
            binding.headerTimetable.paddingLeft,
            lastStatusBarTopPx + (8 * density).toInt(),
            binding.headerTimetable.paddingRight,
            (6 * density).toInt()
        )
    }


    fun updateBottomNavActiveTab(tab: MainTab) {
        val primaryCol = ContextCompat.getColor(activity, R.color.primary)
        val secondaryCol = ContextCompat.getColor(activity, R.color.text_secondary)
        val primaryTint = android.content.res.ColorStateList.valueOf(primaryCol)
        val secondaryTint = android.content.res.ColorStateList.valueOf(secondaryCol)

        fun setBottomTabStyle(pill: FrameLayout, icon: ImageView, label: TextView, isActive: Boolean) {
            val wasActive = pill.tag as? Boolean
            if (wasActive == isActive) return // 状态未改变时直接跳过，杜绝多余的 requestLayout 和背景重置
            pill.tag = isActive

            if (isActive) {
                pill.setBackgroundResource(R.drawable.bg_bottom_nav_pill)
                icon.imageTintList = primaryTint
                label.setTextColor(primaryCol)
                label.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                pill.background = null
                icon.imageTintList = secondaryTint
                label.setTextColor(secondaryCol)
                label.typeface = android.graphics.Typeface.DEFAULT
            }
        }

        val isMoreSection = tab == MainTab.MORE_FEATURES ||
                tab == MainTab.COURSE_SEARCH ||
                tab == MainTab.FREE_CLASSROOM ||
                tab == MainTab.EXAM_QUERY ||
                tab == MainTab.GRADE_REPORTS

        setBottomTabStyle(
            binding.flBottomPillTimetable,
            binding.ivBottomTimetable,
            binding.tvBottomTimetable,
            tab == MainTab.TIMETABLE
        )
        setBottomTabStyle(
            binding.flBottomPillGrades,
            binding.ivBottomGrades,
            binding.tvBottomGrades,
            tab == MainTab.GRADES
        )
        setBottomTabStyle(
            binding.flBottomPillTronClass,
            binding.ivBottomTronClass,
            binding.tvBottomTronClass,
            tab == MainTab.TRONCLASS
        )
        setBottomTabStyle(
            binding.flBottomPillMore,
            binding.ivBottomMore,
            binding.tvBottomMore,
            isMoreSection
        )
        setBottomTabStyle(
            binding.flBottomPillProfile,
            binding.ivBottomProfile,
            binding.tvBottomProfile,
            tab == MainTab.PROFILE
        )
    }

    fun updateDrawerActiveTab(tab: MainTab) {
        val typedValue = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        val defaultBg = typedValue.resourceId
        val primaryCol = ContextCompat.getColor(activity, R.color.primary)
        val textPrimaryCol = ContextCompat.getColor(activity, R.color.text_primary)
        val textSecondaryCol = ContextCompat.getColor(activity, R.color.text_secondary)
        val primaryTint = android.content.res.ColorStateList.valueOf(primaryCol)
        val secondaryTint = android.content.res.ColorStateList.valueOf(textSecondaryCol)

        fun setNavActive(layout: LinearLayout, icon: ImageView, text: TextView, isActive: Boolean) {
            val wasActive = layout.tag as? Boolean
            if (wasActive == isActive) return
            layout.tag = isActive

            if (isActive) {
                layout.setBackgroundResource(R.drawable.bg_drawer_active)
                text.setTextColor(primaryCol)
                text.typeface = android.graphics.Typeface.DEFAULT_BOLD
                icon.imageTintList = primaryTint
            } else {
                layout.setBackgroundResource(defaultBg)
                text.setTextColor(textPrimaryCol)
                text.typeface = android.graphics.Typeface.DEFAULT
                icon.imageTintList = secondaryTint
            }
        }

        setNavActive(
            binding.layoutDrawer.drawerNavTimetable,
            binding.layoutDrawer.ivNavTimetable,
            binding.layoutDrawer.tvNavTimetable,
            tab == MainTab.TIMETABLE
        )
        setNavActive(
            binding.layoutDrawer.drawerNavGrades,
            binding.layoutDrawer.ivNavGrades,
            binding.layoutDrawer.tvNavGrades,
            tab == MainTab.GRADES
        )
        setNavActive(
            binding.layoutDrawer.drawerNavTronClass,
            binding.layoutDrawer.ivNavTronClass,
            binding.layoutDrawer.tvNavTronClass,
            tab == MainTab.TRONCLASS
        )
        setNavActive(
            binding.layoutDrawer.drawerItemSchoolSearch,
            binding.layoutDrawer.ivDrawerSchoolSearch,
            binding.layoutDrawer.tvDrawerSchoolSearchTitle,
            tab == MainTab.COURSE_SEARCH
        )
        setNavActive(
            binding.layoutDrawer.drawerItemFreeClassroom,
            binding.layoutDrawer.ivDrawerFreeClassroom,
            binding.layoutDrawer.tvDrawerFreeClassroomTitle,
            tab == MainTab.FREE_CLASSROOM
        )
        setNavActive(
            binding.layoutDrawer.drawerItemExamQuery,
            binding.layoutDrawer.ivDrawerExamQuery,
            binding.layoutDrawer.tvDrawerExamQueryTitle,
            tab == MainTab.EXAM_QUERY
        )
        setNavActive(
            binding.layoutDrawer.drawerItemGradeReports,
            binding.layoutDrawer.ivDrawerGradeReports,
            binding.layoutDrawer.tvDrawerGradeReportsTitle,
            tab == MainTab.GRADE_REPORTS
        )
        setNavActive(
            binding.layoutDrawer.drawerNavProfile,
            binding.layoutDrawer.ivNavProfile,
            binding.layoutDrawer.tvNavProfile,
            tab == MainTab.PROFILE
        )
    }

}
