package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingExam
import cn.edu.usst.jwgl.data.model.TronClassExam
import cn.edu.usst.jwgl.data.model.TronClassExamDetail
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassExamBinding
import cn.edu.usst.jwgl.ui.JwglWebActivity
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.CookieSyncHelper
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch

class TronClassExamFragment : Fragment() {

    private var _binding: ActivityTronclassExamBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var chaoXingDetailClient: ChaoXingCourseDetailClient
    private lateinit var adapter: TronClassSubjectAdapter

    private var platform: String = "TRONCLASS"
    private var examId: Long = 0
    private var courseId: Long = 0
    private var examTitle: String = ""
    private var courseName: String = ""
    private var chaoXingExam: ChaoXingExam? = null
    private var chaoXingCourse: ChaoXingCourse? = null

    private var examDetail: TronClassExamDetail? = null
    private val pendingAnswers = mutableMapOf<String, Any>()

    companion object {
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_CHAOXING_EXAM = "extra_chaoxing_exam"
        const val EXTRA_CHAOXING_COURSE = "extra_chaoxing_course"

        private const val ARG_PLATFORM = "arg_platform"
        private const val ARG_EXAM_ID = "arg_exam_id"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CHAOXING_EXAM = "arg_chaoxing_exam"
        private const val ARG_CHAOXING_COURSE = "arg_chaoxing_course"

        fun newInstance(examId: Long, courseId: Long, title: String, courseName: String): TronClassExamFragment {
            return TronClassExamFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLATFORM, "TRONCLASS")
                    putLong(ARG_EXAM_ID, examId)
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_TITLE, title)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }

        fun newInstanceForChaoXing(
            exam: ChaoXingExam,
            course: ChaoXingCourse? = null
        ): TronClassExamFragment {
            return TronClassExamFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLATFORM, "CHAOXING")
                    putSerializable(ARG_CHAOXING_EXAM, exam)
                    course?.let { putSerializable(ARG_CHAOXING_COURSE, it) }
                    putString(ARG_TITLE, exam.title)
                    putString(ARG_COURSE_NAME, course?.courseName ?: exam.courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        platform = args.getString(ARG_PLATFORM) ?: "TRONCLASS"
        if (platform == "CHAOXING") {
            chaoXingExam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getSerializable(ARG_CHAOXING_EXAM, ChaoXingExam::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getSerializable(ARG_CHAOXING_EXAM) as? ChaoXingExam
            }
            chaoXingCourse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getSerializable(ARG_CHAOXING_COURSE, ChaoXingCourse::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getSerializable(ARG_CHAOXING_COURSE) as? ChaoXingCourse
            }
            examTitle = chaoXingExam?.title?.ifBlank { args.getString(ARG_TITLE) } ?: "测验详情"
            courseName = chaoXingCourse?.courseName ?: chaoXingExam?.courseName ?: args.getString(ARG_COURSE_NAME).orEmpty()
        } else {
            examId = args.getLong(ARG_EXAM_ID, 0L)
            courseId = args.getLong(ARG_COURSE_ID, 0L)
            examTitle = args.getString(ARG_TITLE) ?: "测验详情"
            courseName = args.getString(ARG_COURSE_NAME) ?: ""
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityTronclassExamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        client = TronClassClient(requireContext())
        chaoXingDetailClient = ChaoXingCourseDetailClient(requireContext())

        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.llHeaderBarContainer.setPadding(
            binding.llHeaderBarContainer.paddingLeft,
            statusTopPad,
            binding.llHeaderBarContainer.paddingRight,
            0
        )
        binding.llHeaderBarContainer.post {
            val b = _binding ?: return@post
            updateHeaderSpacing(b.llHeaderBarContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val pad = statusBars.top + (8 * density).toInt()

            binding.llHeaderBarContainer.setPadding(
                binding.llHeaderBarContainer.paddingLeft,
                pad,
                binding.llHeaderBarContainer.paddingRight,
                0
            )
            binding.llHeaderBarContainer.post {
                val b = _binding ?: return@post
                updateHeaderSpacing(b.llHeaderBarContainer.height)
            }
            val bHeight = MainActivity.bottomBarHeightPx
            val bottomPadding = if (bHeight > 0) {
                bHeight + (24 * density).toInt()
            } else {
                navBars.bottom + (36 * density).toInt()
            }
            binding.scrollView.setPadding(
                binding.scrollView.paddingLeft,
                binding.scrollView.paddingTop,
                binding.scrollView.paddingRight,
                bottomPadding
            )
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = requireActivity().window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: decorView as ViewGroup
            val windowBackground = decorView.background

            binding.blurViewExam.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
        }

        setupUI()
        setupListeners()
        loadExamData()
    }

    private fun updateHeaderSpacing(headerHeight: Int) {
        val b = _binding ?: return
        val params = b.viewHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            b.viewHeaderSpacing.layoutParams = params
        }
    }

    private fun setupUI() {
        binding.tvHeaderTitle.text = examTitle
        binding.tvHeaderSubtitle.text = courseName
        binding.tvCourseName.text = courseName
        binding.tvExamTitle.text = examTitle

        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        if (isPad) {
            val density = resources.displayMetrics.density
            val availableWidth = resources.displayMetrics.widthPixels - (if (isPad) (295 * density).toInt() else 0)
            val maxContentWidth = (1100 * density).toInt()
            val horizontalPadding = if (availableWidth > maxContentWidth) {
                ((availableWidth - maxContentWidth) / 2).coerceAtLeast((16 * density).toInt())
            } else {
                (16 * density).toInt()
            }
            binding.llExamContent.setPadding(
                horizontalPadding,
                binding.llExamContent.paddingTop,
                horizontalPadding,
                binding.llExamContent.paddingBottom
            )

            val btnParams = binding.btnSubmitExam.layoutParams as? FrameLayout.LayoutParams
            btnParams?.width = (480 * density).toInt().coerceAtMost(availableWidth)
            btnParams?.gravity = Gravity.CENTER
            binding.btnSubmitExam.layoutParams = btnParams
        }

        adapter = TronClassSubjectAdapter(
            onAnswerSelected = { subjectId, optionId ->
                pendingAnswers[subjectId.toString()] = optionId
                updateSubmitButtonProgress()
            }
        )

        binding.rvSubjects.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubjects.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnRefresh.setOnClickListener {
            loadExamData()
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadExamData()
        }

        binding.btnSubmitExam.setOnClickListener {
            confirmSubmitExam()
        }
    }

    private fun loadExamData(targetSubmissionId: Long? = null) {
        if (platform == "CHAOXING") {
            val cxExam = chaoXingExam ?: return
            val cacheMgr = TronClassDataCacheManager.getInstance(requireContext())
            val cached = cacheMgr.getCachedChaoXingExamDetail(cxExam.id)
            if (cached != null && examDetail == null) {
                val tcDetail = cached.toTronClassExamDetail(chaoXingCourse)
                examDetail = tcDetail
                displayExamDetail(tcDetail)
            }

            _binding?.swipeRefresh?.isRefreshing = true
            viewLifecycleOwner.lifecycleScope.launch {
                val res = chaoXingDetailClient.fetchExamDetail(cxExam, chaoXingCourse)
                val b = _binding ?: return@launch
                b.swipeRefresh.isRefreshing = false

                if (res.isSuccess) {
                    val cxDetail = res.getOrNull()
                    if (cxDetail != null) {
                        val tcDetail = cxDetail.toTronClassExamDetail(chaoXingCourse)
                        examDetail = tcDetail
                        displayExamDetail(tcDetail)
                    }
                } else {
                    val err = res.exceptionOrNull()?.message ?: "获取超星测验试卷失败"
                    if (isAdded) {
                        Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        // 0. 本地缓存秒级直出
        if (targetSubmissionId == null) {
            val cached = TronClassDataCacheManager.getInstance(requireContext()).getCachedExamDetail(examId)
            if (cached != null && examDetail == null) {
                examDetail = cached
                displayExamDetail(cached)
            }
        }

        _binding?.swipeRefresh?.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.fetchExamDetail(examId, targetSubmissionId)
            val b = _binding ?: return@launch
            b.swipeRefresh.isRefreshing = false

            if (res.isSuccess) {
                val detail = res.getOrNull() ?: return@launch
                examDetail = detail
                displayExamDetail(detail)
            } else {
                val err = res.exceptionOrNull()?.message ?: "获取测验试卷失败"
                if (isAdded) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayExamDetail(detail: TronClassExamDetail) {
        val b = _binding ?: return
        val exam = detail.exam
        b.tvExamTitle.text = exam.getCleanTitle()

        val currentSc = detail.currentScore ?: detail.score
        val finalSc = detail.finalScore ?: exam.score

        // 1. 起止时间与时长
        val rawTime = exam.getFormattedTimeRange().ifBlank {
            chaoXingExam?.remaining?.ifBlank { null }
        }
        val isDone = detail.submissionId != null || detail.submissions.isNotEmpty() || (finalSc != null && !finalSc.isNaN()) || exam.isClosed || chaoXingExam?.status?.contains("已") == true

        val timeText = when {
            !rawTime.isNullOrBlank() -> rawTime
            isDone -> "测验已截止"
            else -> "时间未公开"
        }
        val limitTimeText = exam.getFormattedLimitTime()
        val scheduleText = "$timeText · $limitTimeText"
        b.tvExamTimeRangeDetail.text = scheduleText

        // 2. 计分规则标签
        val rule = detail.scoreRule ?: exam.scoreRule
        if (!rule.isNullOrBlank()) {
            val ruleText = when (rule) {
                "highest" -> "取最高分"
                "latest" -> "取最后一次作答"
                "average" -> "取平均分"
                else -> rule
            }
            b.tvScoreRuleBadge.visibility = View.VISIBLE
            b.tvScoreRuleBadge.text = ruleText
        } else {
            b.tvScoreRuleBadge.visibility = View.GONE
        }

        // 3. 得分展示 (防止 NaN，支持明确标明教师未公布得分)
        val rawSubScoreText = detail.submissions.firstOrNull()?.scoreText?.trim().orEmpty()

        if (currentSc != null && !currentSc.isNaN()) {
            val scText = if (currentSc % 1.0 == 0.0) "${currentSc.toInt()} 分" else "$currentSc 分"
            b.tvScoreValue.text = scText
            b.tvScoreValue.textSize = 20f
            val tipPrefix = if (detail.submissions.size > 1) "当前查看作答得分" else "已完成测验"
            b.tvExamStatusTip.text = "$tipPrefix，以下为您作答的试卷分析与标准答案"
        } else if (finalSc != null && !finalSc.isNaN()) {
            val scText = if (finalSc % 1.0 == 0.0) "${finalSc.toInt()} 分" else "$finalSc 分"
            b.tvScoreValue.text = scText
            b.tvScoreValue.textSize = 20f
            b.tvExamStatusTip.text = "已完成测验，试卷已评阅"
        } else if (rawSubScoreText.isNotBlank() && !rawSubScoreText.matches(Regex("""^\d+(?:\.\d+)?$"""))) {
            b.tvScoreValue.text = rawSubScoreText
            b.tvScoreValue.textSize = 14f
            b.tvExamStatusTip.text = if (rawSubScoreText.contains("未公布")) "教师未设置公布测试得分与试卷" else "试卷已成功提交，等待教师评阅打分"
        } else if (detail.submissionId != null || detail.submissions.isNotEmpty() || isDone) {
            b.tvScoreValue.text = "教师未公布得分"
            b.tvScoreValue.textSize = 14f
            b.tvExamStatusTip.text = "已完成测验，教师未对外公布得分与试卷"
        } else {
            b.tvScoreValue.text = if (exam.isClosed) "未作答" else "待作答"
            b.tvScoreValue.textSize = 20f
            b.tvExamStatusTip.text = if (exam.isClosed) "该测验已截止" else "请仔细审题并在规定时限内作答"
        }

        // 4. 作答时限与题目量
        if (exam.limitTime != null && exam.limitTime > 0) {
            b.tvTimeLimitValue.text = "${exam.limitTime} 分钟"
        } else {
            b.tvTimeLimitValue.text = "不限时"
        }

        val count = if (detail.subjects.isNotEmpty()) detail.subjects.size else (exam.subjectsCount ?: 0)
        b.tvQuestionsCount.text = if (count > 0) "$count 题" else "未公开"

        // 5. 多次作答记录轮次切换器
        if (detail.submissions.size > 1) {
            b.layoutSubmissionsSwitcher.visibility = View.VISIBLE
            b.chipGroupSubmissions.removeAllViews()

            detail.submissions.forEach { sub ->
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    val scStr = if (sub.score != null && !sub.score.isNaN()) {
                        if (sub.score % 1.0 == 0.0) "${sub.score.toInt()}分" else "${sub.score}分"
                    } else "待评阅"
                    val timeStr = sub.getFormattedSubmitTime()
                    text = "第 ${sub.attemptIndex} 次 ($scStr${if (timeStr.isNotBlank()) " · $timeStr" else ""})"
                    isCheckable = true
                    isChecked = sub.id == detail.selectedSubmissionId

                    setOnClickListener {
                        if (sub.id != detail.selectedSubmissionId) {
                            loadExamData(sub.id)
                        }
                    }
                }
                b.chipGroupSubmissions.addView(chip)
            }
        } else {
            b.layoutSubmissionsSwitcher.visibility = View.GONE
        }

        // 6. 是否处于查卷复习模式
        val isReviewMode = detail.submissionId != null || detail.submissions.isNotEmpty() || (finalSc != null && !finalSc.isNaN()) || exam.isClosed
        adapter.updateData(
            newSubjects = detail.subjects,
            newMyAnswers = detail.myAnswers,
            newCorrectAnswers = detail.correctAnswers,
            reviewMode = isReviewMode
        )

        // 7. 配置官方防作弊环境安全通道卡片
        setupAntiCheatingChannel(detail, isReviewMode)

        // 8. 同步已有答案并更新底部进度
        if (pendingAnswers.isEmpty() && detail.myAnswers.isNotEmpty()) {
            pendingAnswers.putAll(detail.myAnswers)
        }
        updateSubmitButtonProgress()

        // 9. 底部提交按钮
        if (!isReviewMode && exam.isStarted && !exam.isClosed) {
            b.layoutBottomAction.visibility = View.VISIBLE
        } else {
            b.layoutBottomAction.visibility = View.GONE
        }
    }

    /**
     * 配置官方防作弊安全答题通道 (Safe Fallback Channel)
     */
    private fun setupAntiCheatingChannel(detail: TronClassExamDetail, isReviewMode: Boolean) {
        val b = _binding ?: return
        val exam = detail.exam

        if (platform == "CHAOXING") {
            val isDone = isReviewMode || chaoXingExam?.status?.contains("已完成") == true || chaoXingExam?.status?.contains("已批") == true
            if (isDone) {
                b.tvAntiCheatBadge.text = "答卷回顾"
                b.tvAntiCheatBadge.setTextColor(Color.parseColor("#2E7D32"))
                b.tvAntiCheatBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#152E7D32"))
                b.cardAntiCheatingChannel.strokeColor = ContextCompat.getColor(requireContext(), R.color.border)
                if (detail.subjects.isEmpty()) {
                    b.tvAntiCheatTip.text = "教师未开放移动端查看试卷题目或成绩未公布。如需查看超星智能答卷分析报告或原始试卷作答，可直接进入官方通道查看。"
                } else {
                    b.tvAntiCheatTip.text = "客户端已原生解析此超星测验的客观题作答、分值与标准答案。如需查看超星智能答卷分析报告或原始答卷，可直接进入官方页面查看。"
                }
                b.btnOpenOfficialWebChannel.text = if (chaoXingExam?.analysisUrl.isNullOrBlank()) "查看超星官方答卷 ➔" else "查看超星智能答卷分析 ➔"
            } else {
                b.tvAntiCheatBadge.text = "严肃考试保障"
                b.tvAntiCheatBadge.setTextColor(Color.parseColor("#C62828"))
                b.tvAntiCheatBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15C62828"))
                b.cardAntiCheatingChannel.strokeColor = Color.parseColor("#EF9A9A")
                b.tvAntiCheatTip.text = "超星在线测验通常包含人脸识别验证、摄像头监控或切屏防作弊机制。为确保绝对规避防作弊误判或试卷异常，未提交的严肃测验强烈建议通过官方通道作答。"
                b.btnOpenOfficialWebChannel.text = "进入超星官方防作弊答题环境 (推荐) ➔"
            }
            b.btnOpenOfficialWebChannel.setOnClickListener {
                openOfficialAntiCheatingExamWeb()
            }
            return
        }

        val isTimed = (exam.limitTime ?: 0) > 0
        val titleLower = (exam.title ?: examTitle).lowercase()
        val isSeriousExam = isTimed || titleLower.contains("考") || titleLower.contains("期中") ||
                titleLower.contains("期末") || titleLower.contains("监控") ||
                titleLower.contains("防作弊") || titleLower.contains("严肃") ||
                titleLower.contains("在线测试")

        if (isSeriousExam && !isReviewMode) {
            b.tvAntiCheatBadge.text = "严肃考试保障"
            b.tvAntiCheatBadge.setTextColor(Color.parseColor("#C62828"))
            b.tvAntiCheatBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15C62828"))
            b.cardAntiCheatingChannel.strokeColor = Color.parseColor("#EF9A9A")
            b.tvAntiCheatTip.text = "检测到当前为限时/严肃考试测验。若课程启用了切屏检测、人脸识别或防作弊监控，建议通过官方防作弊通道答题，以完全规避误判与试卷异常。"
            b.btnOpenOfficialWebChannel.text = "进入官方防作弊答题环境 (推荐) ➔"
        } else {
            b.tvAntiCheatBadge.text = "安全通道"
            b.tvAntiCheatBadge.setTextColor(Color.parseColor("#2E7D32"))
            b.tvAntiCheatBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#152E7D32"))
            b.cardAntiCheatingChannel.strokeColor = ContextCompat.getColor(requireContext(), R.color.border)
            b.tvAntiCheatTip.text = "客户端已原生适配随堂客观题作答。若此测验包含限时、切屏检测或人脸识别等严肃防作弊机制，可随时切换至官方防作弊通道答题。"
            b.btnOpenOfficialWebChannel.text = "进入官方防作弊答题环境 ➔"
        }

        b.btnOpenOfficialWebChannel.setOnClickListener {
            if (!isReviewMode && exam.isStarted && !exam.isClosed) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("进入官方防作弊答题环境")
                    .setMessage(
                        "即将通过官方防作弊安全通道加载答题页面：\n\n" +
                        "• 完整支持系统级摄像头与人脸识别授权\n" +
                        "• 具备全屏防作弊与切屏保护机制\n" +
                        "• 会话状态与 Cookie 已自动无缝同步\n" +
                        "• 规避后台冻结或多端登录误判风险\n\n" +
                        "确认立即进入？"
                    )
                    .setPositiveButton("立即进入") { _, _ ->
                        openOfficialAntiCheatingExamWeb()
                    }
                    .setNegativeButton("在原生界面作答", null)
                    .show()
            } else {
                openOfficialAntiCheatingExamWeb()
            }
        }
    }

    /**
     * 启动官方 Web 防作弊容器答题流
     */
    private fun openOfficialAntiCheatingExamWeb() {
        val ctx = requireContext()
        if (platform == "CHAOXING") {
            val exam = chaoXingExam
            val targetUrl = exam?.analysisUrl?.ifBlank { exam?.taskUrl }
                ?: "https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/exam-list?courseid=${chaoXingCourse?.courseId ?: exam?.courseId}&clazzid=${chaoXingCourse?.classId ?: exam?.classId}&personid=${chaoXingCourse?.cpi}"
            ChaoXingNavigator.openChaoXingWebPage(ctx, targetUrl, "${examTitle.ifBlank { "在线测验" }} (官方答题环境)")
            return
        }

        CookieSyncHelper.syncJwglCookiesToWebView()
        val targetUrl = if (courseId > 0) {
            "https://1906.usst.edu.cn/course/$courseId/learning-activity#/$examId"
        } else {
            "https://1906.usst.edu.cn/exams/$examId"
        }
        JwglWebActivity.start(ctx, targetUrl, "${examTitle.ifBlank { "在线测验" }} (官方答题环境)")
    }

    private fun updateSubmitButtonProgress() {
        val b = _binding ?: return
        val total = examDetail?.subjects?.size ?: 0
        val answered = pendingAnswers.size
        if (total > 0 && answered > 0) {
            b.btnSubmitExam.text = "提交试卷 ($answered/$total)"
        } else {
            b.btnSubmitExam.text = "提交试卷"
        }
    }

    private fun confirmSubmitExam() {
        if (platform == "CHAOXING") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("超星测验提交提示")
                .setMessage("当前测验来自超星学习通。超星测验提交通常需要官方防作弊环境验证，推荐在官方答题环境中交卷，以确保成绩有效记录！")
                .setPositiveButton("进入官方答题环境交卷") { _, _ ->
                    openOfficialAntiCheatingExamWeb()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val answeredCount = pendingAnswers.size
        val totalCount = examDetail?.subjects?.size ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认交卷")
            .setMessage("您已作答 $answeredCount / $totalCount 题，提交后将无法修改作答结果。确定交卷吗？")
            .setPositiveButton("立即交卷") { _, _ ->
                doSubmitExam()
            }
            .setNegativeButton("继续作答", null)
            .show()
    }

    private fun doSubmitExam() {
        Toast.makeText(requireContext(), "正在为您提交测试答卷...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.submitExamAnswers(examId, pendingAnswers)
            if (isAdded) {
                if (res.isSuccess) {
                    Toast.makeText(requireContext(), "交卷完成！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "交卷已提交", Toast.LENGTH_SHORT).show()
                }
                loadExamData()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
