package cn.edu.usst.jwgl.ui.grades

import android.graphics.Typeface
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.AcademicCourse
import cn.edu.usst.jwgl.data.model.AcademicProgress
import cn.edu.usst.jwgl.data.model.CreditCategory
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentRankingInfo
import cn.edu.usst.jwgl.databinding.LayoutPageGradesBinding
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.adapter.AcademicCourseAdapter
import cn.edu.usst.jwgl.ui.adapter.CourseGradeAdapter
import cn.edu.usst.jwgl.util.AppException
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import cn.edu.usst.jwgl.util.RankingPdfParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 学业成绩与绩点总览独立 Fragment
 *
 * 职责：
 * 1. 成绩数据离线秒开缓存渲染与全量联网拉取
 * 2. 学分/均分/绩点核心卡片与学期筛选
 * 3. 历年学期绩点主从列表（平板横屏）与单双栏自适应切换
 * 4. 学业专业排位查询与草稿弹窗展示
 * 5. WindowInsets 顶部状态栏避让与毛玻璃 BlurView 联动
 */
class GradesFragment : Fragment() {

    private var _binding: LayoutPageGradesBinding? = null
    val binding get() = _binding!!

    private val gradeAdapter = CourseGradeAdapter()
    private val academicCourseAdapter = AcademicCourseAdapter()
    private var gradeReport: GradeReport? = null
    private var academicProgress: AcademicProgress? = null
    private var selectedGradeSemesterTitle: String = ""
    private var selectedCategoryId: String = ""
    private var isCreditNodeMode: Boolean = false
    private var lastHeaderHeightPx: Int = 0
    private var lastBottomPaddingPx: Int = 0
    private var lastMasterReport: GradeReport? = null
    private var lastMasterProgress: AcademicProgress? = null
    private val categoryCoursesMap = mutableMapOf<String, List<AcademicCourse>>()
    private val categoryLoadingSet = mutableSetOf<String>()
    private data class MasterRowHolder(
        val itemKey: String,
        val card: com.google.android.material.card.MaterialCardView,
        val tvTitle: TextView,
        val tvBadge: TextView
    )
    private val masterRowHolders = mutableListOf<MasterRowHolder>()
    private var isTabletLandscapeMode: Boolean = false
    private var gridSpacingDecoration: RecyclerView.ItemDecoration? = null

    private val cacheManager by lazy {
        DataCacheManager.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageGradesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews()
        initBlurHeader()
        applyWindowInsets()
        showOrLoadGrades()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gridSpacingDecoration?.let { binding.rvGrades.removeItemDecoration(it) }
        gridSpacingDecoration = null
        _binding = null
    }

    private fun initViews() {
        // RecyclerView 基础配置
        binding.rvGrades.adapter = gradeAdapter
        binding.rvGrades.itemAnimator = null
        updateRecyclerViewLayoutManager(getOptimalSpanCount())

        // 下拉刷新
        binding.swipeRefreshGrades.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshGrades.setOnChildScrollUpCallback { _, _ ->
            if (isTabletLandscapeMode) {
                binding.scrollGradesLeft.canScrollVertically(-1) ||
                    binding.rvGrades.canScrollVertically(-1)
            } else {
                binding.scrollGrades.canScrollVertically(-1)
            }
        }
        binding.swipeRefreshGrades.setOnRefreshListener {
            if (isCreditNodeMode) {
                loadAcademicProgress(isManual = true)
            } else {
                loadGrades(isManual = true)
            }
        }

        // 模式切换圆形按键: 按学期 <-> 按学分节点
        binding.btnToggleGradeMode.setOnClickListener {
            switchGradeMode(creditNodeMode = !isCreditNodeMode)
        }
        updateModeToggleStyles()

        // 顶栏操作按钮
        binding.btnRefreshGrades.setOnClickListener {
            if (isCreditNodeMode) {
                loadAcademicProgress(isManual = true)
            } else {
                loadGrades(isManual = true)
            }
        }

        binding.btnSelectGradeSemester.setOnClickListener {
            if (isCreditNodeMode) {
                showCreditCategoryPicker()
            } else {
                showGradeSemesterPicker()
            }
        }

        binding.btnQueryRanking.setOnClickListener {
            queryAndShowRanking()
        }

        binding.btnDownloadGradeReports.setOnClickListener {
            (activity as? MainActivity)?.switchTab(MainActivity.MainTab.GRADE_REPORTS)
        }

        binding.btnOpenDrawerGrades.setOnClickListener {
            (activity as? MainActivity)?.openNavDrawer()
        }

        // 动态计算毛玻璃 Header 高度并避让
        binding.llGradesHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            val oldH = oldBottom - oldTop
            if (h > 0 && h != oldH && h != lastHeaderHeightPx) updateGradesHeaderSpacing(h)
        }
        binding.llGradesHeaderContainer.post {
            if (_binding != null) {
                val h = binding.llGradesHeaderContainer.height
                if (h > 0 && h != lastHeaderHeightPx) updateGradesHeaderSpacing(h)
            }
        }

        // 同步当前的平板/方向布局与导航侧栏按键状态
        (activity as? MainActivity)?.let { act ->
            updateTabletLandscapeMode(act.isTabletLandscapeMode)
        }
        syncDrawerButtonVisibility()
    }

    override fun onResume() {
        super.onResume()
        syncDrawerButtonVisibility()
    }

    private fun initBlurHeader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = activity?.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            if (decorView != null) {
                try {
                    binding.blurViewGradesHeader.setupWith(decorView, RenderEffectBlur())
                        .setBlurRadius(16f)
                        .setBlurAutoUpdate(true)
                } catch (_: Exception) {}
            }
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val density = resources.displayMetrics.density
            binding.headerGrades.setPadding(
                binding.headerGrades.paddingLeft,
                statusBarInsets.top + (8 * density).toInt(),
                binding.headerGrades.paddingRight,
                binding.headerGrades.paddingBottom
            )
            insets
        }
    }

    private fun updateGradesHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0 || _binding == null) return
        if (lastHeaderHeightPx == headerHeightPx) return
        lastHeaderHeightPx = headerHeightPx
        applyPanePaddings()
        val density = resources.displayMetrics.density
        binding.swipeRefreshGrades.setProgressViewOffset(
            false,
            headerHeightPx,
            headerHeightPx + (56 * density).toInt()
        )
    }

    private val expandedCategoryIds = mutableSetOf<String>()

    private fun switchGradeMode(creditNodeMode: Boolean) {
        if (isCreditNodeMode == creditNodeMode) return
        isCreditNodeMode = creditNodeMode
        updateModeToggleStyles()

        if (isCreditNodeMode) {
            binding.tvSemesterMasterHeader.text = "培养方案学分节点 · 点击筛选"
            updateCreditNodeVisibility()
            showOrLoadAcademicProgress()
        } else {
            binding.rvGrades.adapter = gradeAdapter
            binding.rvGrades.visibility = View.VISIBLE
            binding.llAcademicTreeContainer.visibility = View.GONE
            binding.tvSemesterMasterHeader.text = "历年学期绩点总览 · 点击筛选"
            showOrLoadGrades()
        }
    }

    private fun updateCreditNodeVisibility() {
        if (_binding == null) return
        if (isCreditNodeMode) {
            if (isTabletLandscapeMode) {
                binding.rvGrades.adapter = academicCourseAdapter
                binding.rvGrades.visibility = View.VISIBLE
                binding.llAcademicTreeContainer.visibility = View.GONE
            } else {
                binding.rvGrades.visibility = View.GONE
                binding.llAcademicTreeContainer.visibility = View.VISIBLE
            }
        } else {
            binding.rvGrades.adapter = gradeAdapter
            binding.rvGrades.visibility = View.VISIBLE
            binding.llAcademicTreeContainer.visibility = View.GONE
        }
    }

    private fun updateModeToggleStyles() {
        if (_binding == null) return
        val ctx = requireContext()
        if (isCreditNodeMode) {
            // 当前为学分节点视图，按键高亮提示点击可切回学期成绩
            binding.btnToggleGradeMode.setIconResource(R.drawable.ic_nav_grades)
            binding.btnToggleGradeMode.contentDescription = "当前为学分节点视图，点击切换为按学期成绩"
            binding.btnToggleGradeMode.iconTint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.primary)
            )
        } else {
            // 当前为学期成绩视图，按键提示点击可切换到树状学分节点
            binding.btnToggleGradeMode.setIconResource(R.drawable.ic_account_tree)
            binding.btnToggleGradeMode.contentDescription = "当前为学期成绩视图，点击切换为学分节点"
            binding.btnToggleGradeMode.iconTint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.text_secondary)
            )
        }
    }
    private fun applyPanePaddings() {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val horizontalPad = (16 * density).toInt()
        if (isTabletLandscapeMode) {
            binding.flGradesSwipeContainer.setPadding(horizontalPad, 0, horizontalPad, 0)
            val topPad = (if (lastHeaderHeightPx > 0) lastHeaderHeightPx else (115 * density).toInt()) + (12 * density).toInt()
            val bottomPad = lastBottomPaddingPx + (16 * density).toInt()
            binding.scrollGradesLeft.setPadding(0, topPad, 0, bottomPad)
            binding.rvGrades.setPadding(0, topPad, 0, bottomPad)
        } else {
            binding.flGradesSwipeContainer.setPadding(0, 0, 0, 0)
            binding.viewGradesHeaderSpacing.visibility = View.VISIBLE
            if (lastHeaderHeightPx > 0 && binding.viewGradesHeaderSpacing.layoutParams.height != lastHeaderHeightPx) {
                binding.viewGradesHeaderSpacing.layoutParams.height = lastHeaderHeightPx
                binding.viewGradesHeaderSpacing.requestLayout()
            }
            binding.scrollGrades.setPadding(horizontalPad, (12 * density).toInt(), horizontalPad, lastBottomPaddingPx)
            binding.scrollGradesLeft.setPadding(0, 0, 0, 0)
            binding.rvGrades.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * 加载缓存学业情况或联网拉取
     */
    fun showOrLoadAcademicProgress() {
        val inMemory = academicProgress
        if (inMemory != null) {
            displayAcademicProgress(inMemory)
            val updateTime = cacheManager.getAcademicProgressUpdateTime()
            binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                cacheManager.getAcademicProgress()
            }
            if (_binding == null) return@launch
            if (cached != null) {
                academicProgress = cached
                if (cached.coursesByCategory.isNotEmpty()) {
                    categoryCoursesMap.putAll(cached.coursesByCategory.filterValues { it.isNotEmpty() })
                }
                displayAcademicProgress(cached)
                val updateTime = cacheManager.getAcademicProgressUpdateTime()
                binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
            } else {
                loadAcademicProgress(isManual = false)
            }
        }
    }

    /**
     * 联网刷新学业情况
     */
    fun loadAcademicProgress(isManual: Boolean = false) {
        if (_binding == null) return

        if (isManual) {
            categoryCoursesMap.clear()
            binding.swipeRefreshGrades.isRefreshing = true
        } else if (academicProgress == null) {
            binding.gradesProgressBar.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = JwglClient.fetchAcademicProgress(context = requireContext())
            if (_binding == null) return@launch

            binding.gradesProgressBar.visibility = View.GONE
            binding.swipeRefreshGrades.isRefreshing = false

            result.onSuccess { progress ->
                val validCoursesMap = categoryCoursesMap.filterValues { it.isNotEmpty() }
                val fullProgress = progress.copy(coursesByCategory = validCoursesMap)
                academicProgress = fullProgress
                cacheManager.saveAcademicProgress(fullProgress)
                displayAcademicProgress(fullProgress)
                val updateTime = System.currentTimeMillis()
                binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
                if (isManual && isAdded) {
                    Toast.makeText(requireContext(), "学分节点已联网刷新", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                if (!isAdded) return@launch
                val reason = error.getFriendlyErrorMessage("未知网络异常")
                Toast.makeText(requireContext(), "学分节点刷新失败: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayAcademicProgress(progress: AcademicProgress) {
        updateAcademicUI(progress)
    }

    private fun updateAcademicUI(progress: AcademicProgress) {
        if (_binding == null) return

        binding.tvCreditsTitle.text = "方案要求学分"
        binding.tvAvgScoreTitle.text = "已修读学分"
        binding.tvGpaTitle.text = "尚缺学分"

        binding.tvCumulativeCredits.text = String.format("%.1f", progress.required)
        binding.tvCumulativeAvgScore.text = String.format("%.1f", progress.earned)
        binding.tvCumulativeGpa.text = String.format("%.1f", progress.missing)

        updateCreditNodeVisibility()

        if (isTabletLandscapeMode) {
            val flattenedCategories = flattenCreditCategories(progress.categories)
            if (selectedCategoryId.isEmpty() && flattenedCategories.isNotEmpty()) {
                selectedCategoryId = flattenedCategories.first().id
            }

            val currentCat = flattenedCategories.find { it.id == selectedCategoryId }
            binding.tvGradeSemester.text = currentCat?.name ?: "全部分类"

            if (currentCat != null) {
                loadCategoryCourses(currentCat.id)
            } else {
                academicCourseAdapter.submitList(emptyList())
                binding.tvGradesCountSummary.text = "共 0 门课程"
            }

            populateCreditNodeMasterList(progress)
        } else {
            binding.tvGradeSemester.text = if (progress.majorName.isNotEmpty()) progress.majorName else "培养方案学分"
            val totalCourses = categoryCoursesMap.values.sumOf { it.size }
            binding.tvGradesCountSummary.text = "共 ${progress.categories.size} 个模块"
            renderAcademicTree(progress)
        }
    }

    /**
     * 手机竖屏模式：一级一级可展开/折叠的培养方案树状图
     */
    private val treeNodeViewsMap = mutableMapOf<String, LinearLayout>()

    private fun renderAcademicTree(progress: AcademicProgress) {
        if (_binding == null) return
        val container = binding.llAcademicTreeContainer
        container.removeAllViews()
        treeNodeViewsMap.clear()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        // 默认展开顶层大类
        if (expandedCategoryIds.isEmpty()) {
            progress.categories.forEach { expandedCategoryIds.add(it.id) }
        }

        fun buildNodeView(cat: CreditCategory, depth: Int): View {
            val nodeLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = (if (depth == 0) 14 else 10) * density
                cardElevation = if (depth == 0) (1 * density) else 0f
                strokeWidth = (1 * density).toInt()
                strokeColor = ContextCompat.getColor(ctx, R.color.border)
                setCardBackgroundColor(
                    ContextCompat.getColor(
                        ctx,
                        if (depth == 0) R.color.surface else R.color.surface_variant
                    )
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (if (depth == 0) 8 else 6) * density.toInt()
                    bottomMargin = 2 * density.toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }

            val cardInner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pVer = (if (depth == 0) 14 else 10) * density.toInt()
                val pHor = (if (depth == 0) 14 else 12) * density.toInt()
                setPadding(pHor, pVer, pHor, pVer)
            }

            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(ctx).apply {
                text = cat.name
                textSize = if (depth == 0) 14.5f else 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerRow.addView(tvTitle)

            if (cat.passed) {
                val tvPassed = TextView(ctx).apply {
                    text = "已达标"
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(ctx, R.color.grade_green_text))
                    setBackgroundResource(R.drawable.badge_bg)
                    setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                }
                headerRow.addView(tvPassed)
            } else if (cat.missing > 0) {
                val tvMissing = TextView(ctx).apply {
                    text = "缺 ${String.format("%.1f", cat.missing)}"
                    textSize = 10.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                    setBackgroundResource(R.drawable.badge_bg)
                    setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                }
                headerRow.addView(tvMissing)
            }

            val ivArrow = android.widget.ImageView(ctx).apply {
                val isExp = expandedCategoryIds.contains(cat.id)
                setImageResource(R.drawable.ic_arrow_drop_down)
                rotation = if (isExp) 180f else 0f
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                    marginStart = (4 * density).toInt()
                }
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            headerRow.addView(ivArrow)
            cardInner.addView(headerRow)

            // 进度指示条
            val progressView = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (5 * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                max = 100
                setProgress((cat.progress * 100).toInt())
                progressTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        ctx,
                        if (cat.passed) R.color.grade_green_text else R.color.primary
                    )
                )
            }
            cardInner.addView(progressView)

            val tvSub = TextView(ctx).apply {
                text = "已修 ${String.format("%.1f", cat.earned)} / 要求 ${String.format("%.1f", cat.required)} 学分"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (5 * density).toInt(), 0, 0)
            }
            cardInner.addView(tvSub)

            if (cat.isLeaf && cat.id.isNotEmpty()) {
                val isExp = expandedCategoryIds.contains(cat.id)
                val tvLeafAction = TextView(ctx).apply {
                    tag = "action_${cat.id}"
                    text = if (isExp) "收起课程明细" else "展开课程明细"
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }
                cardInner.addView(tvLeafAction)
            }

            card.addView(cardInner)
            nodeLayout.addView(card)

            val contentContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                tag = "content_${cat.id}"
            }
            nodeLayout.addView(contentContainer)
            treeNodeViewsMap[cat.id] = contentContainer

            if (!cat.isLeaf) {
                contentContainer.setPadding((12 * density).toInt(), 0, 0, 0)
                for (child in cat.children) {
                    contentContainer.addView(buildNodeView(child, depth + 1))
                }
            } else if (cat.id.isNotEmpty()) {
                contentContainer.setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                if (expandedCategoryIds.contains(cat.id)) {
                    populateLeafCoursesContainer(contentContainer, cat.id, density)
                }
            }

            contentContainer.visibility = if (expandedCategoryIds.contains(cat.id)) View.VISIBLE else View.GONE

            card.setOnClickListener {
                val wasExpanded = expandedCategoryIds.contains(cat.id)
                if (wasExpanded) {
                    expandedCategoryIds.remove(cat.id)
                    ivArrow.rotation = 0f
                    contentContainer.visibility = View.GONE
                    if (cat.isLeaf) {
                        cardInner.findViewWithTag<TextView>("action_${cat.id}")?.text = "展开课程明细"
                    }
                } else {
                    expandedCategoryIds.add(cat.id)
                    ivArrow.rotation = 180f
                    contentContainer.visibility = View.VISIBLE
                    if (cat.isLeaf) {
                        cardInner.findViewWithTag<TextView>("action_${cat.id}")?.text = "收起课程明细"
                        if (contentContainer.childCount == 0 || categoryCoursesMap[cat.id].isNullOrEmpty()) {
                            populateLeafCoursesContainer(contentContainer, cat.id, density)
                        }
                    }
                }
            }

            return nodeLayout
        }

        for (category in progress.categories) {
            container.addView(buildNodeView(category, depth = 0))
        }
    }

    private fun populateLeafCoursesContainer(container: LinearLayout, categoryId: String, density: Float) {
        val ctx = container.context
        container.removeAllViews()
        val courses = categoryCoursesMap[categoryId]
        if (courses.isNullOrEmpty()) {
            if (categoryLoadingSet.contains(categoryId)) {
                val pb = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        topMargin = (8 * density).toInt()
                        bottomMargin = (8 * density).toInt()
                    }
                }
                container.addView(pb)
            } else if (courses != null && courses.isEmpty()) {
                val emptyTv = TextView(ctx).apply {
                    text = "该分类下暂无课程明细（点击重新拉取）"
                    textSize = 11.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                    setOnClickListener {
                        categoryCoursesMap.remove(categoryId)
                        populateLeafCoursesContainer(container, categoryId, density)
                    }
                }
                container.addView(emptyTv)
            } else {
                val pb = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        topMargin = (8 * density).toInt()
                        bottomMargin = (8 * density).toInt()
                    }
                }
                container.addView(pb)
                loadCategoryCourses(categoryId)
            }
        } else {
            for (course in courses) {
                container.addView(buildTreeCourseRow(ctx, course, density))
            }
        }
    }

    private fun buildTreeCourseRow(ctx: android.content.Context, course: AcademicCourse, density: Float): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvName = TextView(ctx).apply {
            text = course.name
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(tvName)

        val detailText = listOfNotNull(
            course.code.takeIf { it.isNotEmpty() },
            course.term.takeIf { it.isNotEmpty() },
            if (course.score.isNotEmpty()) "成绩: ${course.score}" else null
        ).joinToString(" · ")

        if (detailText.isNotEmpty()) {
            val tvDetail = TextView(ctx).apply {
                text = detailText
                textSize = 10.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            col.addView(tvDetail)
        }
        row.addView(col)

        val tvCredits = TextView(ctx).apply {
            text = "${course.credits}学分"
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, 0, (8 * density).toInt(), 0)
        }
        row.addView(tvCredits)

        val badge = TextView(ctx).apply {
            text = course.statusLabel
            textSize = 10.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val (bgColor, textColor) = when {
                course.isPassed -> Pair(ContextCompat.getColor(ctx, R.color.grade_green_bg), ContextCompat.getColor(ctx, R.color.grade_green_text))
                course.isInProgress -> Pair(ContextCompat.getColor(ctx, R.color.primary_container), ContextCompat.getColor(ctx, R.color.primary))
                course.isFailed -> Pair(ContextCompat.getColor(ctx, R.color.grade_red_bg), ContextCompat.getColor(ctx, R.color.grade_red_text))
                course.isSubstituted -> Pair(ContextCompat.getColor(ctx, R.color.grade_blue_bg), ContextCompat.getColor(ctx, R.color.grade_blue_text))
                else -> Pair(ContextCompat.getColor(ctx, R.color.surface_variant), ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            val d = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 6 * density
            }
            background = d
            setTextColor(textColor)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        }
        row.addView(badge)
        return row
    }

    private fun flattenCreditCategories(categories: List<CreditCategory>): List<CreditCategory> {
        val out = mutableListOf<CreditCategory>()
        fun collect(cat: CreditCategory) {
            if (cat.isLeaf) {
                out.add(cat)
            } else {
                cat.children.forEach { collect(it) }
            }
        }
        categories.forEach { collect(it) }
        return out
    }

    private fun loadCategoryCourses(categoryId: String) {
        val cached = categoryCoursesMap[categoryId]
        if (!cached.isNullOrEmpty()) {
            academicCourseAdapter.submitList(cached) {
                if (_binding != null && isTabletLandscapeMode) {
                    binding.rvGrades.scrollToPosition(0)
                }
            }
            binding.tvGradesCountSummary.text = "本节点 ${cached.size} 门课程"
            return
        }

        if (categoryLoadingSet.contains(categoryId)) return
        categoryLoadingSet.add(categoryId)
        binding.gradesProgressBar.visibility = View.VISIBLE

        val studentId = AuthPreferences(requireContext()).getStudentId()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = JwglClient.fetchCreditCategoryCourses(categoryId, studentId, context = requireContext())
            if (_binding == null) return@launch
            categoryLoadingSet.remove(categoryId)
            binding.gradesProgressBar.visibility = View.GONE

            result.onSuccess { courses ->
                categoryCoursesMap[categoryId] = courses
                if (courses.isNotEmpty()) {
                    academicProgress?.let { prog ->
                        val updated = prog.copy(coursesByCategory = categoryCoursesMap.filterValues { it.isNotEmpty() })
                        academicProgress = updated
                        cacheManager.saveAcademicProgress(updated)
                    }
                }
                if (isCreditNodeMode) {
                    if (isTabletLandscapeMode && selectedCategoryId == categoryId) {
                        academicCourseAdapter.submitList(courses) {
                            if (_binding != null && isTabletLandscapeMode) {
                                binding.rvGrades.scrollToPosition(0)
                            }
                        }
                        binding.tvGradesCountSummary.text = "本节点 ${courses.size} 门课程"
                    } else if (!isTabletLandscapeMode) {
                        val container = treeNodeViewsMap[categoryId]
                        if (container != null) {
                            val density = resources.displayMetrics.density
                            populateLeafCoursesContainer(container, categoryId, density)
                        } else {
                            academicProgress?.let { renderAcademicTree(it) }
                        }
                    }
                }
            }.onFailure { err ->
                categoryCoursesMap.remove(categoryId)
                if (!isTabletLandscapeMode) {
                    val container = treeNodeViewsMap[categoryId]
                    if (container != null) {
                        val density = resources.displayMetrics.density
                        populateLeafCoursesContainer(container, categoryId, density)
                    }
                }
                if (isAdded) {
                    Toast.makeText(requireContext(), "课程明细拉取失败: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 弹出学分节点选择对话框（竖屏手机模式下点击顶栏触发）
     */
    fun showCreditCategoryPicker() {
        val progress = academicProgress ?: return
        val list = flattenCreditCategories(progress.categories)
        if (list.isEmpty()) {
            if (isAdded) Toast.makeText(requireContext(), "暂无学分节点数据", Toast.LENGTH_SHORT).show()
            return
        }

        val names = list.map { "${it.name} (${String.format("%.1f", it.earned)}/${String.format("%.1f", it.required)})" }
        val checkedIndex = list.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择培养方案节点")
            .setSingleChoiceItems(names.toTypedArray(), checkedIndex) { dialog, which ->
                selectedCategoryId = list[which].id
                updateAcademicUI(progress)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    /**
     * 加载缓存成绩或发起网络拉取
     */
    fun showOrLoadGrades() {
        val inMemory = gradeReport
        if (inMemory != null) {
            displayGrades(inMemory)
            val updateTime = cacheManager.getGradesUpdateTime()
            binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                cacheManager.getGrades()
            }
            if (_binding == null) return@launch
            if (cached != null) {
                gradeReport = cached
                displayGrades(cached)
                val updateTime = cacheManager.getGradesUpdateTime()
                binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
            } else {
                loadGrades(isManual = false)
            }
        }
    }

    /**
     * 联网刷新成绩
     */
    fun loadGrades(isManual: Boolean = false) {
        if (_binding == null) return

        if (isManual) {
            binding.swipeRefreshGrades.isRefreshing = true
        } else if (gradeReport == null) {
            binding.gradesProgressBar.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = JwglClient.fetchGrades(context = requireContext())
            if (_binding == null) return@launch

            binding.gradesProgressBar.visibility = View.GONE
            binding.swipeRefreshGrades.isRefreshing = false

            result.onSuccess { report ->
                gradeReport = report
                cacheManager.saveGrades(report)
                displayGrades(report)
                val updateTime = System.currentTimeMillis()
                binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
                if (isManual && isAdded) {
                    Toast.makeText(requireContext(), "成绩单已联网刷新", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                if (!isAdded) return@launch
                val reason = error.getFriendlyErrorMessage("未知网络异常")
                Toast.makeText(requireContext(), "成绩联网刷新失败: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayGrades(report: GradeReport) {
        updateGradesUI(report)
    }

    private fun updateGradesUI(report: GradeReport) {
        if (_binding == null) return
        updateCreditNodeVisibility()

        if (selectedGradeSemesterTitle.isEmpty()) {
            binding.tvGradeSemester.text = "全部学期"
            binding.tvCreditsTitle.text = "累计修读学分"
            binding.tvAvgScoreTitle.text = "加权平均分"
            binding.tvGpaTitle.text = "平均学分绩点"
            binding.tvCumulativeCredits.text = String.format("%.1f", report.totalCredits)
            binding.tvCumulativeAvgScore.text = String.format("%.2f", report.cumulativeWeightedScore)
            binding.tvCumulativeGpa.text = String.format("%.2f", report.cumulativeGpa)

            val allCourses = report.semesters.flatMap { it.courses }
            gradeAdapter.submitList(allCourses) {
                if (_binding != null && isTabletLandscapeMode) {
                    binding.rvGrades.scrollToPosition(0)
                }
            }
            binding.tvGradesCountSummary.text = "共 ${allCourses.size} 门课程"
        } else {
            val sem = report.semesters.find { it.semesterTitle == selectedGradeSemesterTitle }
            if (sem != null) {
                binding.tvGradeSemester.text = formatSemesterTitle(sem.semesterTitle)
                binding.tvCreditsTitle.text = "学期修读学分"
                binding.tvAvgScoreTitle.text = "学期加权均分"
                binding.tvGpaTitle.text = "学期学分绩点"
                binding.tvCumulativeCredits.text = String.format("%.1f", sem.totalCredits)
                binding.tvCumulativeAvgScore.text = String.format("%.2f", sem.weightedAverageScore)
                binding.tvCumulativeGpa.text = String.format("%.2f", sem.weightedGpa)

                gradeAdapter.submitList(sem.courses) {
                    if (_binding != null && isTabletLandscapeMode) {
                        binding.rvGrades.scrollToPosition(0)
                    }
                }
                binding.tvGradesCountSummary.text = "本学期 ${sem.courses.size} 门课程"
            } else {
                selectedGradeSemesterTitle = ""
                updateGradesUI(report)
                return
            }
        }

        if (isTabletLandscapeMode) {
            populateGradesSemesterMasterList(report)
        }
    }

    /**
     * 弹出学期筛选对话框
     */
    fun showGradeSemesterPicker() {
        val report = gradeReport ?: return
        val semesters = report.semesters
        if (semesters.isEmpty()) {
            if (isAdded) Toast.makeText(requireContext(), "暂无学期成绩数据", Toast.LENGTH_SHORT).show()
            return
        }

        val options = mutableListOf<String>()
        options.add("全部学期 (汇总)")
        for (sem in semesters) {
            options.add(sem.semesterTitle)
        }

        val checkedIndex = if (selectedGradeSemesterTitle.isEmpty()) {
            0
        } else {
            val idx = semesters.indexOfFirst { it.semesterTitle == selectedGradeSemesterTitle }
            if (idx >= 0) idx + 1 else 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择成绩学期")
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                if (which == 0) {
                    selectedGradeSemesterTitle = ""
                } else {
                    selectedGradeSemesterTitle = semesters[which - 1].semesterTitle
                }
                updateGradesUI(report)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    /**
     * 由外部或 Intent 指定学期标题进行快速筛选
     */
    fun filterSemester(gradeSem: String) {
        val report = gradeReport
        selectedGradeSemesterTitle = when (gradeSem) {
            "ALL" -> ""
            "FIRST" -> report?.semesters?.firstOrNull()?.semesterTitle ?: ""
            else -> report?.semesters?.find { it.semesterTitle.contains(gradeSem) }?.semesterTitle ?: gradeSem
        }
        report?.let { updateGradesUI(it) }
    }

    /**
     * 根据当前屏幕可用宽度与平板横屏状态，自适应计算最优分栏数
     */
    private fun getOptimalSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return if (isTabletLandscapeMode) {
            // 平板横屏：常驻左侧栏(265dp) + 内容内边距(32dp) + 左侧总览/学期主面板(~260-290dp)
            // 右侧课程列表可用宽度约为 (screenWidthDp - 587dp)
            // 小尺寸平板(如 Nexus 9, screenWidthDp=1024dp)右侧可用约 437dp：
            // 若分 2 列则单卡片仅 213dp，且全屏形成 3 栏极度拥挤；自适应为 1 列时卡片宽 437dp，舒展大方
            // 中大尺寸平板(>= 1200dp)右侧可用 >= 600dp，分 2 列
            // 超大平板或桌面模式(>= 1600dp)分 3 列
            when {
                screenWidthDp >= 1600 -> 3
                screenWidthDp >= 1200 -> 2
                else -> 1
            }
        } else {
            // 竖屏或手机模式（垂直堆叠布局，无常驻侧栏与主从面板）：
            // 手机(<600dp)采用单列；平板竖屏(>=600dp)分 2 列；大屏竖屏(>=900dp)分 3 列
            when {
                screenWidthDp >= 900 -> 3
                screenWidthDp >= 600 -> 2
                else -> 1
            }
        }
    }

    /**
     * 根据计算出的最优分栏数动态更新 RecyclerView 的 LayoutManager 与间距装饰
     */
    private fun updateRecyclerViewLayoutManager(spanCount: Int) {
        val density = resources.displayMetrics.density
        val spacingPx = (10 * density).toInt()

        gridSpacingDecoration?.let { binding.rvGrades.removeItemDecoration(it) }

        if (spanCount <= 1) {
            binding.rvGrades.layoutManager = LinearLayoutManager(requireContext())
            gridSpacingDecoration = null
        } else {
            binding.rvGrades.layoutManager = GridLayoutManager(requireContext(), spanCount)
            val decoration = object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == RecyclerView.NO_POSITION) return
                    val column = position % spanCount
                    outRect.left = column * spacingPx / spanCount
                    outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
                }
            }
            binding.rvGrades.addItemDecoration(decoration)
            gridSpacingDecoration = decoration
        }
    }

    /**
     * 切换平板横竖屏双栏/单栏模式
     */
    fun updateTabletLandscapeMode(isTabletLandscape: Boolean) {
        isTabletLandscapeMode = isTabletLandscape
        if (_binding == null) return

        val density = resources.displayMetrics.density
        val screenWidthDp = resources.configuration.screenWidthDp

        if (isTabletLandscape) {
            val leftPaneWidthDp = if (screenWidthDp < 960) 260 else 290
            binding.scrollGrades.visibility = View.GONE
            if (binding.llGradesBody.parent !== binding.flGradesSwipeContainer) {
                (binding.llGradesBody.parent as? ViewGroup)?.removeView(binding.llGradesBody)
                binding.flGradesSwipeContainer.addView(
                    binding.llGradesBody,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                binding.llGradesBody.layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            binding.llGradesBody.orientation = LinearLayout.HORIZONTAL
            binding.scrollGradesLeft.layoutParams = LinearLayout.LayoutParams(
                (leftPaneWidthDp * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = (18 * density).toInt()
            }
            binding.scrollGradesLeft.isNestedScrollingEnabled = true
            binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            binding.rvGrades.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 0
            }
            binding.rvGrades.isNestedScrollingEnabled = true
            binding.tvGradesWatermark.visibility = View.GONE
            binding.cardGradesSemesterMaster.visibility = View.VISIBLE
            binding.ivGradeSemesterDropdown.visibility = View.GONE
            binding.btnSelectGradeSemester.isClickable = false
            binding.btnSelectGradeSemester.isFocusable = false

            applyPanePaddings()
            updateRecyclerViewLayoutManager(getOptimalSpanCount())
            updateCreditNodeVisibility()
            if (isCreditNodeMode) {
                academicProgress?.let { populateCreditNodeMasterList(it) }
            } else {
                gradeReport?.let { populateGradesSemesterMasterList(it) }
            }
        } else {
            binding.scrollGrades.visibility = View.VISIBLE
            updateCreditNodeVisibility()
            if (binding.llGradesBody.parent !== binding.llGradesContent) {
                (binding.llGradesBody.parent as? ViewGroup)?.removeView(binding.llGradesBody)
                binding.llGradesContent.addView(
                    binding.llGradesBody,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                binding.llGradesBody.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            binding.llGradesBody.orientation = LinearLayout.VERTICAL
            binding.scrollGradesLeft.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            binding.scrollGradesLeft.isNestedScrollingEnabled = false
            binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            binding.rvGrades.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
            binding.rvGrades.isNestedScrollingEnabled = false
            binding.tvGradesWatermark.visibility = View.VISIBLE
            binding.cardGradesSemesterMaster.visibility = View.GONE
            binding.ivGradeSemesterDropdown.visibility = View.VISIBLE
            binding.btnSelectGradeSemester.isClickable = true
            binding.btnSelectGradeSemester.isFocusable = true

            applyPanePaddings()
            updateRecyclerViewLayoutManager(getOptimalSpanCount())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isTabletLandscape = (activity as? MainActivity)?.isTabletLandscapeModeActive()
            ?: TabletLayoutHelper.isTabletLandscape(requireContext(), newConfig)
        updateTabletLandscapeMode(isTabletLandscape)
    }

    /**
     * 适配底部导航栏 Padding 避让
     */
    fun applyBottomPadding(bottomPaddingPx: Int) {
        lastBottomPaddingPx = bottomPaddingPx
        if (_binding == null) return
        applyPanePaddings()
    }

    /**
     * 根据当前导航模式与布局同步侧栏按钮显示状态
     */
    fun syncDrawerButtonVisibility() {
        val act = activity as? MainActivity
        val showDrawerBtn = if (act != null) {
            !act.isBottomBarModeActive() && !act.isGuestModeActive() && !act.isTabletLandscapeModeActive()
        } else {
            false
        }
        updateDrawerButtonVisibility(showDrawerBtn)
    }

    /**
     * 更新汉堡包侧边栏按纽可见性
     */
    fun updateDrawerButtonVisibility(visible: Boolean) {
        if (_binding == null) return
        binding.btnOpenDrawerGrades.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 平板横屏左侧主从学期选择卡片填充
     */
    private fun populateGradesSemesterMasterList(report: GradeReport) {
        if (_binding == null) return
        val container = binding.llGradesSemesterMasterList
        if (lastMasterReport === report && masterRowHolders.size == report.semesters.size + 1) {
            updateMasterRowSelectionStyles()
            return
        }

        container.removeAllViews()
        masterRowHolders.clear()
        lastMasterReport = report
        val density = resources.displayMetrics.density

        fun addMasterRow(
            semesterKey: String,
            title: String,
            subtitle: String,
            gpaBadge: String,
            isSelected: Boolean
        ) {
            val ctx = requireContext()
            val rowCard = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 12 * density
                cardElevation = 0f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedGradeSemesterTitle == semesterKey) return@setOnClickListener
                    selectedGradeSemesterTitle = semesterKey
                    updateGradesUI(report)
                }
            }

            val rowInner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val hPad = (12 * density).toInt()
                val vPad = (10 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(ctx).apply {
                text = title
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val tvSub = TextView(ctx).apply {
                text = subtitle
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (2 * density).toInt(), 0, 0)
            }

            textCol.addView(tvTitle)
            textCol.addView(tvSub)
            rowInner.addView(textCol)

            val tvGpa = TextView(ctx).apply {
                text = gpaBadge
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rowInner.addView(tvGpa)
            rowCard.addView(rowInner)
            container.addView(rowCard)
            masterRowHolders.add(MasterRowHolder(semesterKey, rowCard, tvTitle, tvGpa))
            applyMasterRowStyle(rowCard, tvTitle, tvGpa, isSelected)
        }

        val allCount = report.semesters.sumOf { it.courses.size }
        addMasterRow(
            semesterKey = "",
            title = "全部学期 (汇总)",
            subtitle = "$allCount 门 · ${String.format("%.1f", report.totalCredits)} 学分 · 均分 ${String.format("%.2f", report.cumulativeWeightedScore)}",
            gpaBadge = "GPA ${String.format("%.2f", report.cumulativeGpa)}",
            isSelected = selectedGradeSemesterTitle.isEmpty()
        )

        for (sem in report.semesters) {
            val formatted = formatSemesterTitle(sem.semesterTitle)
            addMasterRow(
                semesterKey = sem.semesterTitle,
                title = formatted,
                subtitle = "${sem.courses.size} 门 · ${String.format("%.1f", sem.totalCredits)} 学分 · 均分 ${String.format("%.2f", sem.weightedAverageScore)}",
                gpaBadge = "GPA ${String.format("%.2f", sem.weightedGpa)}",
                isSelected = selectedGradeSemesterTitle == sem.semesterTitle
            )
        }
    }

    private fun updateMasterRowSelectionStyles() {
        val currentKey = if (isCreditNodeMode) selectedCategoryId else selectedGradeSemesterTitle
        for (holder in masterRowHolders) {
            applyMasterRowStyle(
                holder.card,
                holder.tvTitle,
                holder.tvBadge,
                currentKey == holder.itemKey
            )
        }
    }

    /**
     * 平板横屏左侧学分节点列表填充
     */
    private fun populateCreditNodeMasterList(progress: AcademicProgress) {
        if (_binding == null) return
        val container = binding.llGradesSemesterMasterList
        val flatList = flattenCreditCategories(progress.categories)
        if (lastMasterProgress === progress && masterRowHolders.size == flatList.size) {
            updateMasterRowSelectionStyles()
            return
        }

        container.removeAllViews()
        masterRowHolders.clear()
        lastMasterProgress = progress
        lastMasterReport = null
        val density = resources.displayMetrics.density

        for (cat in flatList) {
            val ctx = requireContext()
            val rowCard = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 12 * density
                cardElevation = 0f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedCategoryId == cat.id) return@setOnClickListener
                    selectedCategoryId = cat.id
                    updateAcademicUI(progress)
                }
            }

            val rowInner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val hPad = (12 * density).toInt()
                val vPad = (10 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(ctx).apply {
                text = cat.name
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(tvTitle)

            val tvBadge = TextView(ctx).apply {
                text = if (cat.passed) "达标" else if (cat.missing > 0) "缺${String.format("%.1f", cat.missing)}" else "进行中"
                textSize = 11.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (cat.passed) R.color.grade_green_text else R.color.primary
                    )
                )
            }
            topRow.addView(tvBadge)
            rowInner.addView(topRow)

            // 模块修读进度条
            val pb = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (4 * density).toInt()
                ).apply {
                    topMargin = (6 * density).toInt()
                }
                max = 100
                setProgress((cat.progress * 100).toInt())
                progressTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        ctx,
                        if (cat.passed) R.color.grade_green_text else R.color.primary
                    )
                )
            }
            rowInner.addView(pb)

            val tvSub = TextView(ctx).apply {
                text = "已修 ${String.format("%.1f", cat.earned)} / 要求 ${String.format("%.1f", cat.required)} 学分"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            rowInner.addView(tvSub)

            rowCard.addView(rowInner)
            container.addView(rowCard)
            masterRowHolders.add(MasterRowHolder(cat.id, rowCard, tvTitle, tvBadge))
            applyMasterRowStyle(rowCard, tvTitle, tvBadge, selectedCategoryId == cat.id)
        }
    }

    private fun applyMasterRowStyle(
        rowCard: com.google.android.material.card.MaterialCardView,
        tvTitle: TextView,
        tvGpa: TextView,
        isSelected: Boolean
    ) {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        rowCard.strokeWidth = ((if (isSelected) 1.5f else 0.8f) * density).toInt()
        rowCard.strokeColor = ContextCompat.getColor(
            ctx,
            if (isSelected) R.color.primary else R.color.border
        )
        rowCard.setCardBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (isSelected) R.color.primary_container else R.color.surface_variant
            )
        )
        val textColor = ContextCompat.getColor(
            ctx,
            if (isSelected) R.color.primary else R.color.text_primary
        )
        tvTitle.setTextColor(textColor)
        tvGpa.setTextColor(textColor)
    }

    /**
     * 学业排名查询
     * 原理：下载排名证明 PDF -> 解析内容 -> 删除临时 PDF -> 呈现草稿样式对话框
     */
    private fun queryAndShowRanking() {
        if (!isAdded) return
        val auth = AuthPreferences(requireContext())
        val studentId = auth.getStudentId()

        if (studentId.isEmpty()) {
            Toast.makeText(requireContext(), "未检测到学号，请重新登录", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("正在查询排名")
            .setMessage("正在向教务系统请求排名证明并解析，请稍候...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val targetDir = requireContext().cacheDir
            val tempFile = File(targetDir, "temp_ranking_${System.currentTimeMillis()}.pdf")

            try {
                // 1. 下载排名证明 PDF (确保同步最新 WebView 会话凭证)
                cn.edu.usst.jwgl.util.CookieSyncHelper.syncWebViewCookiesToJwgl()
                val result = JwglClient.downloadGradeDocument(
                    context = requireContext(),
                    docType = GradeDocumentType.RANKING_CERTIFICATE,
                    destinationFile = tempFile,
                    studentId = studentId
                )

                if (result.isFailure) {
                    progressDialog.dismiss()
                    val errorMsg = result.exceptionOrNull()?.message ?: "网络连接异常"
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("查询失败")
                        .setMessage("未能成功获取排名证明：$errorMsg")
                        .setPositiveButton("确定", null)
                        .safeShow()
                    return@launch
                }

                val downloadedFile = result.getOrNull() ?: tempFile

                // 2. 解析 PDF 内容
                val profile = cacheManager.getProfile()
                val parseResult = RankingPdfParser.parse(downloadedFile, profile)

                progressDialog.dismiss()

                parseResult.onSuccess { rankingInfo ->
                    showRankingResultDialog(rankingInfo)
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("解析失败")
                        .setMessage("排名证明获取成功，但未能解析出排位信息：${error.message ?: "未匹配到排名数据"}")
                        .setPositiveButton("确定", null)
                        .safeShow()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                if (isAdded) {
                    Toast.makeText(requireContext(), e.getFriendlyErrorMessage("排名查询失败，请稍后重试"), Toast.LENGTH_SHORT).show()
                }
            } finally {
                // 3. 严格遵循原理：解析完毕后立即删除临时 PDF 文件
                try {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 展示排名查询结果对话框 (草稿设计样式)
     */
    private fun showRankingResultDialog(rankingInfo: StudentRankingInfo) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed || !isAdded) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_ranking_result, null)
        val tvMajorGrade = dialogView.findViewById<TextView>(R.id.tvRankingMajorGrade)
        val tvRankingNum = dialogView.findViewById<TextView>(R.id.tvRankingNum)
        val tvRankingTotal = dialogView.findViewById<TextView>(R.id.tvRankingTotal)
        val tvRankingPercentile = dialogView.findViewById<TextView>(R.id.tvRankingPercentile)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnRankingConfirm)

        tvMajorGrade.text = rankingInfo.majorAndGrade
        tvRankingNum.text = rankingInfo.rank.toString()
        tvRankingTotal.text = "/${rankingInfo.total}"

        // "百分比排位为 85 %" (其中 85 加粗展示)
        val percentileStr = rankingInfo.percentile.toString()
        val fullPercentileText = "百分比排位为 $percentileStr %"
        val spannable = SpannableString(fullPercentileText)
        val startIdx = fullPercentileText.indexOf(percentileStr)
        if (startIdx >= 0) {
            val endIdx = startIdx + percentileStr.length
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                startIdx,
                endIdx,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                RelativeSizeSpan(1.15f),
                startIdx,
                endIdx,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvRankingPercentile.text = spannable

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnConfirm.setOnClickListener {
            dialog.dismiss()
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e("GradesFragment", "Failed to show ranking dialog", e)
        }
    }

    private fun formatSemesterTitle(title: String): String {
        val singleLine = title.replace("\r\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val regex = Regex("(\\d{4}-\\d{4}学年)\\s*(第.+?学期)")
        return if (regex.containsMatchIn(singleLine)) {
            singleLine.replace(regex, "$1 $2")
        } else {
            singleLine.replace("学年", "学年 ")
        }
    }

    private fun MaterialAlertDialogBuilder.safeShow() {
        val act = activity ?: return
        if (!act.isFinishing && !act.isDestroyed && isAdded) {
            try {
                show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        fun newInstance(): GradesFragment = GradesFragment()
    }
}
