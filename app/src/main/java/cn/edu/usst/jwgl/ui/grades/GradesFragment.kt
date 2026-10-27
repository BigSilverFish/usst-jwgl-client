package cn.edu.usst.jwgl.ui.grades

import android.graphics.Typeface
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
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentRankingInfo
import cn.edu.usst.jwgl.databinding.LayoutPageGradesBinding
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.adapter.CourseGradeAdapter
import cn.edu.usst.jwgl.util.AppException
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
    private var gradeReport: GradeReport? = null
    private var selectedGradeSemesterTitle: String = ""
    private var isTabletLandscapeMode: Boolean = false

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
        _binding = null
    }

    private fun initViews() {
        // RecyclerView 基础配置
        binding.rvGrades.adapter = gradeAdapter
        binding.rvGrades.layoutManager = LinearLayoutManager(requireContext())

        // 下拉刷新
        binding.swipeRefreshGrades.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshGrades.setOnRefreshListener {
            loadGrades(isManual = true)
        }

        // 顶栏操作按钮
        binding.btnRefreshGrades.setOnClickListener {
            loadGrades(isManual = true)
        }

        binding.btnSelectGradeSemester.setOnClickListener {
            showGradeSemesterPicker()
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
        binding.llGradesHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) updateGradesHeaderSpacing(h)
        }
        binding.llGradesHeaderContainer.post {
            if (_binding != null) {
                val h = binding.llGradesHeaderContainer.height
                if (h > 0) updateGradesHeaderSpacing(h)
            }
        }

        // 同步当前的平板/方向布局
        (activity as? MainActivity)?.let { act ->
            updateTabletLandscapeMode(act.isTabletLandscapeMode)
        }
    }

    private fun initBlurHeader() {
        val decorView = activity?.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
        if (decorView != null) {
            binding.blurViewGradesHeader.setupWith(decorView, RenderEffectBlur())
                .setBlurRadius(16f)
                .setBlurAutoUpdate(true)
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
        if (binding.viewGradesHeaderSpacing.layoutParams.height != headerHeightPx) {
            binding.viewGradesHeaderSpacing.layoutParams.height = headerHeightPx
            binding.viewGradesHeaderSpacing.requestLayout()
        }
        val density = resources.displayMetrics.density
        binding.swipeRefreshGrades.setProgressViewOffset(
            false,
            headerHeightPx,
            headerHeightPx + (56 * density).toInt()
        )
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

        if (selectedGradeSemesterTitle.isEmpty()) {
            binding.tvGradeSemester.text = "全部学期"
            binding.tvCreditsTitle.text = "累计修读学分"
            binding.tvAvgScoreTitle.text = "加权平均分"
            binding.tvGpaTitle.text = "平均学分绩点"
            binding.tvCumulativeCredits.text = String.format("%.1f", report.totalCredits)
            binding.tvCumulativeAvgScore.text = String.format("%.2f", report.cumulativeWeightedScore)
            binding.tvCumulativeGpa.text = String.format("%.2f", report.cumulativeGpa)

            val allCourses = report.semesters.flatMap { it.courses }
            gradeAdapter.submitList(allCourses)
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

                gradeAdapter.submitList(sem.courses)
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
     * 切换平板横竖屏双栏/单栏模式
     */
    fun updateTabletLandscapeMode(isTabletLandscape: Boolean) {
        isTabletLandscapeMode = isTabletLandscape
        if (_binding == null) return

        val density = resources.displayMetrics.density

        if (isTabletLandscape) {
            binding.llGradesBody.orientation = LinearLayout.HORIZONTAL
            binding.llGradesLeftPane.layoutParams = LinearLayout.LayoutParams(
                (300 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            (binding.rvGrades.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
            binding.cardGradesSemesterMaster.visibility = View.VISIBLE

            binding.rvGrades.layoutManager = GridLayoutManager(requireContext(), 2)
            gradeReport?.let { populateGradesSemesterMasterList(it) }
        } else {
            binding.llGradesBody.orientation = LinearLayout.VERTICAL
            binding.llGradesLeftPane.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            (binding.rvGrades.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * density).toInt()
            binding.cardGradesSemesterMaster.visibility = View.GONE

            binding.rvGrades.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    /**
     * 适配底部导航栏 Padding 避让
     */
    fun applyBottomPadding(bottomPaddingPx: Int) {
        if (_binding == null) return
        binding.scrollGrades.setPadding(
            binding.scrollGrades.paddingLeft,
            binding.scrollGrades.paddingTop,
            binding.scrollGrades.paddingRight,
            bottomPaddingPx
        )
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
        container.removeAllViews()
        val density = resources.displayMetrics.density

        fun addMasterRow(
            title: String,
            subtitle: String,
            gpaBadge: String,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            val ctx = requireContext()
            val rowCard = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 12 * density
                cardElevation = 0f
                strokeWidth = ((if (isSelected) 1.5f else 0.8f) * density).toInt()
                strokeColor = ContextCompat.getColor(
                    ctx,
                    if (isSelected) R.color.primary else R.color.border
                )
                setCardBackgroundColor(
                    ContextCompat.getColor(
                        ctx,
                        if (isSelected) R.color.primary_container else R.color.surface_variant
                    )
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
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
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (isSelected) R.color.primary else R.color.text_primary
                    )
                )
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
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (isSelected) R.color.primary else R.color.text_primary
                    )
                )
            }
            rowInner.addView(tvGpa)
            rowCard.addView(rowInner)
            container.addView(rowCard)
        }

        val allCount = report.semesters.sumOf { it.courses.size }
        addMasterRow(
            title = "全部学期 (汇总)",
            subtitle = "$allCount 门 · ${String.format("%.1f", report.totalCredits)} 学分 · 均分 ${String.format("%.2f", report.cumulativeWeightedScore)}",
            gpaBadge = "GPA ${String.format("%.2f", report.cumulativeGpa)}",
            isSelected = selectedGradeSemesterTitle.isEmpty()
        ) {
            selectedGradeSemesterTitle = ""
            updateGradesUI(report)
        }

        for (sem in report.semesters) {
            val formatted = formatSemesterTitle(sem.semesterTitle)
            addMasterRow(
                title = formatted,
                subtitle = "${sem.courses.size} 门 · ${String.format("%.1f", sem.totalCredits)} 学分 · 均分 ${String.format("%.2f", sem.weightedAverageScore)}",
                gpaBadge = "GPA ${String.format("%.2f", sem.weightedGpa)}",
                isSelected = selectedGradeSemesterTitle == sem.semesterTitle
            ) {
                selectedGradeSemesterTitle = sem.semesterTitle
                updateGradesUI(report)
            }
        }
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
                // 1. 下载排名证明 PDF
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
                    Toast.makeText(requireContext(), "排名查询异常: ${e.message}", Toast.LENGTH_SHORT).show()
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
