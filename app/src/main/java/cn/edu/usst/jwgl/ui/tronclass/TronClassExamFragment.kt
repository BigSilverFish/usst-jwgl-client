package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.data.model.TronClassExamDetail
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassExamBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch

class TronClassExamFragment : Fragment() {

    private var _binding: ActivityTronclassExamBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var adapter: TronClassSubjectAdapter

    private var examId: Long = 0
    private var courseId: Long = 0
    private var examTitle: String = ""
    private var courseName: String = ""

    private var examDetail: TronClassExamDetail? = null
    private val pendingAnswers = mutableMapOf<String, Any>()

    companion object {
        private const val ARG_EXAM_ID = "arg_exam_id"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_COURSE_NAME = "arg_course_name"

        fun newInstance(examId: Long, courseId: Long, title: String, courseName: String): TronClassExamFragment {
            return TronClassExamFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EXAM_ID, examId)
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_TITLE, title)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        examId = args.getLong(ARG_EXAM_ID, 0L)
        courseId = args.getLong(ARG_COURSE_ID, 0L)
        examTitle = args.getString(ARG_TITLE) ?: "测验详情"
        courseName = args.getString(ARG_COURSE_NAME) ?: ""
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
            updateHeaderSpacing(binding.llHeaderBarContainer.height)
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
                updateHeaderSpacing(binding.llHeaderBarContainer.height)
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
        val params = binding.viewHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            binding.viewHeaderSpacing.layoutParams = params
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
        // 0. 本地缓存秒级直出
        if (targetSubmissionId == null) {
            val cached = TronClassDataCacheManager.getInstance(requireContext()).getCachedExamDetail(examId)
            if (cached != null && examDetail == null) {
                examDetail = cached
                displayExamDetail(cached)
            }
        }

        binding.swipeRefresh.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.fetchExamDetail(examId, targetSubmissionId)
            binding.swipeRefresh.isRefreshing = false

            if (res.isSuccess) {
                val detail = res.getOrNull()!!
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
        val exam = detail.exam
        binding.tvExamTitle.text = exam.getCleanTitle()

        // 1. 起止时间与时长
        val timeRange = exam.getFormattedTimeRange()
        val limitTimeText = exam.getFormattedLimitTime()
        val scheduleText = when {
            timeRange.isNotBlank() -> "$timeRange · $limitTimeText"
            else -> limitTimeText
        }
        binding.tvExamTimeRangeDetail.text = scheduleText

        // 2. 计分规则标签
        val rule = detail.scoreRule ?: exam.scoreRule
        if (!rule.isNullOrBlank()) {
            val ruleText = when (rule) {
                "highest" -> "取最高分"
                "latest" -> "取最后一次作答"
                "average" -> "取平均分"
                else -> rule
            }
            binding.tvScoreRuleBadge.visibility = View.VISIBLE
            binding.tvScoreRuleBadge.text = ruleText
        } else {
            binding.tvScoreRuleBadge.visibility = View.GONE
        }

        // 3. 得分展示 (防止 NaN)
        val currentSc = detail.currentScore ?: detail.score
        val finalSc = detail.finalScore ?: exam.score

        if (currentSc != null && !currentSc.isNaN()) {
            val scText = if (currentSc % 1.0 == 0.0) "${currentSc.toInt()} 分" else "$currentSc 分"
            binding.tvScoreValue.text = scText
            val tipPrefix = if (detail.submissions.size > 1) "当前查看作答得分" else "已完成测验"
            binding.tvExamStatusTip.text = "$tipPrefix，以下为您作答的试卷分析与标准答案"
        } else if (finalSc != null && !finalSc.isNaN()) {
            val scText = if (finalSc % 1.0 == 0.0) "${finalSc.toInt()} 分" else "$finalSc 分"
            binding.tvScoreValue.text = scText
            binding.tvExamStatusTip.text = "已完成测验，试卷已评阅"
        } else if (detail.submissionId != null || detail.submissions.isNotEmpty()) {
            binding.tvScoreValue.text = "已提交"
            binding.tvExamStatusTip.text = "试卷已成功提交，等待教师评阅打分"
        } else {
            binding.tvScoreValue.text = "未作答"
            binding.tvExamStatusTip.text = if (exam.isClosed) "该测验已截止" else "请仔细审题并在规定时限内作答"
        }

        // 4. 作答时限与题目量
        if (exam.limitTime != null && exam.limitTime > 0) {
            binding.tvTimeLimitValue.text = "${exam.limitTime} 分钟"
        } else {
            binding.tvTimeLimitValue.text = "不限时"
        }

        val count = if (detail.subjects.isNotEmpty()) detail.subjects.size else (exam.subjectsCount ?: 0)
        binding.tvQuestionsCount.text = "$count 题"

        // 5. 多次作答记录轮次切换器
        if (detail.submissions.size > 1) {
            binding.layoutSubmissionsSwitcher.visibility = View.VISIBLE
            binding.chipGroupSubmissions.removeAllViews()

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
                binding.chipGroupSubmissions.addView(chip)
            }
        } else {
            binding.layoutSubmissionsSwitcher.visibility = View.GONE
        }

        // 6. 是否处于查卷复习模式
        val isReviewMode = detail.submissionId != null || detail.submissions.isNotEmpty() || (finalSc != null && !finalSc.isNaN()) || exam.isClosed
        adapter.updateData(
            newSubjects = detail.subjects,
            newMyAnswers = detail.myAnswers,
            newCorrectAnswers = detail.correctAnswers,
            reviewMode = isReviewMode
        )

        // 7. 底部提交按钮
        if (!isReviewMode && exam.isStarted && !exam.isClosed) {
            binding.layoutBottomAction.visibility = View.VISIBLE
        } else {
            binding.layoutBottomAction.visibility = View.GONE
        }
    }

    private fun confirmSubmitExam() {
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
            client.logActivityRead(examId)
            if (isAdded) {
                Toast.makeText(requireContext(), "交卷完成！", Toast.LENGTH_SHORT).show()
                loadExamData()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
