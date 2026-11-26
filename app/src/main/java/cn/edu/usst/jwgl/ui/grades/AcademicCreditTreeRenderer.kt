package cn.edu.usst.jwgl.ui.grades

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.AcademicCourse
import cn.edu.usst.jwgl.data.model.AcademicProgress
import cn.edu.usst.jwgl.data.model.CreditCategory
import cn.edu.usst.jwgl.data.network.JwglClient
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * 学业进度培养方案学分树动态渲染与按需加载器
 */
internal class AcademicCreditTreeRenderer(
    private val fragment: GradesFragment
) {
    private val context: Context? get() = fragment.context
    private val expandedCategoryIds get() = fragment.expandedCategoryIds
    private val treeNodeViewsMap get() = fragment.treeNodeViewsMap
    private val isTabletLandscapeMode get() = fragment.isTabletLandscapeMode
    private val isCreditNodeMode get() = fragment.isCreditNodeMode
    private var academicProgress: AcademicProgress?
        get() = fragment.academicProgress
        set(value) { fragment.academicProgress = value }
    private fun updateAcademicUI(progress: AcademicProgress) = fragment.updateAcademicUI(progress)

    fun renderAcademicTree(progress: AcademicProgress) {
        if (fragment.bindingOrNull == null) return
        val container = fragment.binding.llAcademicTreeContainer
        container.removeAllViews()
        treeNodeViewsMap.clear()
        val ctx = fragment.requireContext()
        val density = fragment.resources.displayMetrics.density

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
                        if (contentContainer.childCount == 0 || fragment.categoryCoursesMap[cat.id].isNullOrEmpty()) {
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

    fun populateLeafCoursesContainer(container: LinearLayout, categoryId: String, density: Float) {
        val ctx = container.context
        container.removeAllViews()
        val courses = fragment.categoryCoursesMap[categoryId]
        if (courses.isNullOrEmpty()) {
            if (fragment.categoryLoadingSet.contains(categoryId)) {
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
                        fragment.categoryCoursesMap.remove(categoryId)
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

    fun buildTreeCourseRow(ctx: android.content.Context, course: AcademicCourse, density: Float): View {
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

    fun flattenCreditCategories(categories: List<CreditCategory>): List<CreditCategory> {
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

    fun loadCategoryCourses(categoryId: String) {
        val cached = fragment.categoryCoursesMap[categoryId]
        if (!cached.isNullOrEmpty()) {
            fragment.academicCourseAdapter.submitList(cached) {
                if (fragment.bindingOrNull != null && isTabletLandscapeMode) {
                    fragment.binding.rvGrades.scrollToPosition(0)
                }
            }
            fragment.binding.tvGradesCountSummary.text = "本节点 ${cached.size} 门课程"
            return
        }

        if (fragment.categoryLoadingSet.contains(categoryId)) return
        fragment.categoryLoadingSet.add(categoryId)
        fragment.binding.gradesProgressBar.visibility = View.VISIBLE

        val studentId = AuthPreferences(fragment.requireContext()).getStudentId()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = JwglClient.fetchCreditCategoryCourses(categoryId, studentId, context = fragment.requireContext())
            if (fragment.bindingOrNull == null) return@launch
            fragment.categoryLoadingSet.remove(categoryId)
            fragment.binding.gradesProgressBar.visibility = View.GONE

            result.onSuccess { courses ->
                fragment.categoryCoursesMap[categoryId] = courses
                if (courses.isNotEmpty()) {
                    academicProgress?.let { prog ->
                        val updated = prog.copy(coursesByCategory = fragment.categoryCoursesMap.filterValues { it.isNotEmpty() })
                        academicProgress = updated
                        fragment.cacheManager.saveAcademicProgress(updated)
                    }
                }
                if (isCreditNodeMode) {
                    if (isTabletLandscapeMode && fragment.selectedCategoryId == categoryId) {
                        fragment.academicCourseAdapter.submitList(courses) {
                            if (fragment.bindingOrNull != null && isTabletLandscapeMode) {
                                fragment.binding.rvGrades.scrollToPosition(0)
                            }
                        }
                        fragment.binding.tvGradesCountSummary.text = "本节点 ${courses.size} 门课程"
                    } else if (!isTabletLandscapeMode) {
                        val container = treeNodeViewsMap[categoryId]
                        if (container != null) {
                            val density = fragment.resources.displayMetrics.density
                            populateLeafCoursesContainer(container, categoryId, density)
                        } else {
                            academicProgress?.let { renderAcademicTree(it) }
                        }
                    }
                }
            }.onFailure { err ->
                fragment.categoryCoursesMap.remove(categoryId)
                if (!isTabletLandscapeMode) {
                    val container = treeNodeViewsMap[categoryId]
                    if (container != null) {
                        val density = fragment.resources.displayMetrics.density
                        populateLeafCoursesContainer(container, categoryId, density)
                    }
                }
                if (fragment.isAdded) {
                    Toast.makeText(fragment.requireContext(), "课程明细拉取失败: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 弹出学分节点选择对话框（竖屏手机模式下点击顶栏触发）
     */
}
