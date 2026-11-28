package cn.edu.usst.jwgl.ui.grades

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.AcademicProgress
import cn.edu.usst.jwgl.data.model.GradeReport
import com.google.android.material.card.MaterialCardView

/**
 * 成绩与学分节点页面平板双栏 Master 列表绑定器
 */
internal class GradesMasterPaneBinder(
    private val fragment: GradesFragment
) {
    private data class MasterRowHolder(
        val itemKey: String,
        val card: MaterialCardView,
        val tvTitle: TextView,
        val tvBadge: TextView
    )

    private val context: Context? get() = fragment.context
    private val masterRowHolders = mutableListOf<MasterRowHolder>()

    fun populateGradesSemesterMasterList(report: GradeReport) {
        if (fragment.bindingOrNull == null) return
        val container = fragment.binding.llGradesSemesterMasterList
        if (fragment.lastMasterReport === report && masterRowHolders.size == report.semesters.size + 1) {
            updateMasterRowSelectionStyles()
            return
        }

        container.removeAllViews()
        masterRowHolders.clear()
        fragment.lastMasterReport = report
        val density = fragment.resources.displayMetrics.density

        fun addMasterRow(
            semesterKey: String,
            title: String,
            subtitle: String,
            gpaBadge: String,
            isSelected: Boolean
        ) {
            val ctx = fragment.requireContext()
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
                    if (fragment.selectedGradeSemesterTitle == semesterKey) return@setOnClickListener
                    fragment.selectedGradeSemesterTitle = semesterKey
                    fragment.updateGradesUI(report)
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
            isSelected = fragment.selectedGradeSemesterTitle.isEmpty()
        )

        for (sem in report.semesters) {
            val formatted = fragment.formatSemesterTitle(sem.semesterTitle)
            addMasterRow(
                semesterKey = sem.semesterTitle,
                title = formatted,
                subtitle = "${sem.courses.size} 门 · ${String.format("%.1f", sem.totalCredits)} 学分 · 均分 ${String.format("%.2f", sem.weightedAverageScore)}",
                gpaBadge = "GPA ${String.format("%.2f", sem.weightedGpa)}",
                isSelected = fragment.selectedGradeSemesterTitle == sem.semesterTitle
            )
        }
    }

    fun updateMasterRowSelectionStyles() {
        val currentKey = if (fragment.isCreditNodeMode) fragment.selectedCategoryId else fragment.selectedGradeSemesterTitle
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
    fun populateCreditNodeMasterList(progress: AcademicProgress) {
        if (fragment.bindingOrNull == null) return
        val container = fragment.binding.llGradesSemesterMasterList
        val flatList = fragment.flattenCreditCategories(progress.categories)
        if (fragment.lastMasterProgress === progress && masterRowHolders.size == flatList.size) {
            updateMasterRowSelectionStyles()
            return
        }

        container.removeAllViews()
        masterRowHolders.clear()
        fragment.lastMasterProgress = progress
        fragment.lastMasterReport = null
        val density = fragment.resources.displayMetrics.density

        for (cat in flatList) {
            val ctx = fragment.requireContext()
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
                    if (fragment.selectedCategoryId == cat.id) return@setOnClickListener
                    fragment.selectedCategoryId = cat.id
                    fragment.updateAcademicUI(progress)
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
            applyMasterRowStyle(rowCard, tvTitle, tvBadge, fragment.selectedCategoryId == cat.id)
        }
    }

    fun applyMasterRowStyle(
        rowCard: com.google.android.material.card.MaterialCardView,
        tvTitle: TextView,
        tvGpa: TextView,
        isSelected: Boolean
    ) {
        val ctx = context ?: return
        val density = fragment.resources.displayMetrics.density
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
}
