package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.util.Log
import android.view.Gravity

import android.Manifest
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassSubmission
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.data.model.ChaoXingWork
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingHomeworkDetail
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.ChaoXingQuestionItem
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.ui.JwglWebActivity
import cn.edu.usst.jwgl.databinding.ActivityTronclassHomeworkBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassAttachmentBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassOptionCardBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TronClassHomeworkFragment : Fragment() {

    private var _binding: ActivityTronclassHomeworkBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var cacheManager: TronClassCacheManager
    private lateinit var chaoXingDetailClient: ChaoXingCourseDetailClient

    private var platform: String = "TRONCLASS"
    private var chaoXingWork: ChaoXingWork? = null
    private var chaoXingCourse: ChaoXingCourse? = null
    private var chaoXingHomeworkDetail: ChaoXingHomeworkDetail? = null

    private var activityId: Long = 0L
    private var courseId: Long = 0L
    private var titleText: String = ""
    private var courseNameText: String = ""

    private var activityDetail: TronClassActivityItem? = null
    private var submissionDetail: TronClassSubmission? = null

    private data class LocalPendingFile(
        val file: File,
        val displayName: String,
        val size: Long,
        var uploadResult: TronClassUpload? = null,
        var chaoXingUploadResult: ChaoXingCourseAttachment? = null
    )

    private val pendingLocalFiles = mutableListOf<LocalPendingFile>()
    private var isResubmitMode: Boolean = false
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handlePickedFileUri(uri)
        }
    }

    private var currentPhotoFile: File? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val photoFile = currentPhotoFile
        if (success && photoFile != null && photoFile.exists() && photoFile.length() > 0L) {
            val pending = LocalPendingFile(
                file = photoFile,
                displayName = photoFile.name,
                size = photoFile.length()
            )
            pendingLocalFiles.add(pending)
            renderPendingFiles()
            Toast.makeText(context, "拍照成功，已添加为作业附件", Toast.LENGTH_SHORT).show()
        } else {
            photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(context, "未授予相机权限，无法拍照", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val ctx = context ?: return
        try {
            val tempDir = File(ctx.cacheDir, "pending_uploads").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(tempDir, "IMG_${timeStamp}.jpg")
            currentPhotoFile = photoFile
            val photoUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(ctx, "调起相机失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "TronClassHomework"
        private const val ARG_ACTIVITY_ID = "arg_activity_id"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_PLATFORM = "arg_platform"
        private const val ARG_CHAOXING_WORK = "arg_chaoxing_work"
        private const val ARG_CHAOXING_COURSE = "arg_chaoxing_course"

        private const val ARG_CHAOXING_WORK_JSON = "arg_chaoxing_work_json"
        private const val ARG_CHAOXING_COURSE_JSON = "arg_chaoxing_course_json"

        fun newInstance(activityId: Long, courseId: Long, title: String, courseName: String): TronClassHomeworkFragment {
            return TronClassHomeworkFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLATFORM, "TRONCLASS")
                    putLong(ARG_ACTIVITY_ID, activityId)
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_TITLE, title)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }

        fun newInstanceForChaoXing(work: ChaoXingWork, course: ChaoXingCourse? = null): TronClassHomeworkFragment {
            return TronClassHomeworkFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLATFORM, "CHAOXING")
                    putSerializable(ARG_CHAOXING_WORK, work)
                    putString(ARG_CHAOXING_WORK_JSON, com.google.gson.Gson().toJson(work))
                    course?.let {
                        putSerializable(ARG_CHAOXING_COURSE, it)
                        putString(ARG_CHAOXING_COURSE_JSON, com.google.gson.Gson().toJson(it))
                    }
                    putString(ARG_TITLE, work.title)
                    putString(ARG_COURSE_NAME, course?.courseName ?: work.courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        platform = args.getString(ARG_PLATFORM, "TRONCLASS")
        activityId = args.getLong(ARG_ACTIVITY_ID, 0L)
        courseId = args.getLong(ARG_COURSE_ID, 0L)
        titleText = args.getString(ARG_TITLE) ?: "作业详情"
        courseNameText = args.getString(ARG_COURSE_NAME) ?: ""

        chaoXingWork = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getSerializable(ARG_CHAOXING_WORK, ChaoXingWork::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getSerializable(ARG_CHAOXING_WORK) as? ChaoXingWork
            }
        } catch (_: Exception) { null } ?: args.getString(ARG_CHAOXING_WORK_JSON)?.let {
            try { com.google.gson.Gson().fromJson(it, ChaoXingWork::class.java) } catch (_: Exception) { null }
        }

        chaoXingCourse = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getSerializable(ARG_CHAOXING_COURSE, ChaoXingCourse::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getSerializable(ARG_CHAOXING_COURSE) as? ChaoXingCourse
            }
        } catch (_: Exception) { null } ?: args.getString(ARG_CHAOXING_COURSE_JSON)?.let {
            try { com.google.gson.Gson().fromJson(it, ChaoXingCourse::class.java) } catch (_: Exception) { null }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityTronclassHomeworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        client = TronClassClient(context)
        cacheManager = TronClassCacheManager(context)
        chaoXingDetailClient = ChaoXingCourseDetailClient(context)

        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusBarTop = mainAct?.getStatusBarTop() ?: (24 * density).toInt()
        val initialPad = statusBarTop + (2 * density).toInt()

        binding.llHeaderBarContainer.setPadding(
            binding.llHeaderBarContainer.paddingLeft,
            initialPad,
            binding.llHeaderBarContainer.paddingRight,
            0
        )
        binding.llHeaderBarContainer.post {
            val b = _binding ?: return@post
            updateHeaderSpacing(b.llHeaderBarContainer.height)
        }

        fun updateScrollViewBottomPadding(keypadHeight: Int) {
            val b = _binding ?: return
            val navBars = ViewCompat.getRootWindowInsets(b.root)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

            val bottomPadding = if (keypadHeight > 0) {
                keypadHeight + (24 * density).toInt()
            } else {
                val bHeight = MainActivity.bottomBarHeightPx
                if (bHeight > 0) {
                    bHeight + (24 * density).toInt()
                } else {
                    navBars + (36 * density).toInt()
                }
            }

            if (b.scrollView.paddingBottom != bottomPadding) {
                b.scrollView.setPadding(
                    b.scrollView.paddingLeft,
                    b.scrollView.paddingTop,
                    b.scrollView.paddingRight,
                    bottomPadding
                )
            }
        }

        updateScrollViewBottomPadding(0)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val pad = statusBars.top + (2 * density).toInt()

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

            val imeHeight = ime.bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) || imeHeight > 0
            val keyboardHeight = if (isImeVisible) imeHeight else 0
            updateScrollViewBottomPadding(keyboardHeight)

            if (keyboardHeight > 0) {
                binding.scrollView.post {
                    val b = _binding ?: return@post
                    b.scrollView.smoothScrollTo(0, b.llHomeworkRootContainer.bottom)
                }
            }

            insets
        }

        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val b = _binding ?: return@OnGlobalLayoutListener
            val rect = Rect()
            b.root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = b.root.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val isKeyboardOpen = keypadHeight > screenHeight * 0.15
            val effectiveKeypad = if (isKeyboardOpen) keypadHeight else 0
            updateScrollViewBottomPadding(effectiveKeypad)
            if (isKeyboardOpen && (isResubmitMode || b.etSubmissionComment.hasFocus())) {
                b.scrollView.post {
                    val currentB = _binding ?: return@post
                    currentB.scrollView.smoothScrollTo(0, currentB.llHomeworkRootContainer.bottom)
                }
            }
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = requireActivity().window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: decorView as ViewGroup
            val windowBackground = decorView.background

            binding.blurViewHomework.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
        }

        setupViews()
        loadData(forceRefresh = false)
    }

    private fun updateHeaderSpacing(headerHeight: Int) {
        val b = _binding ?: return
        val params = b.viewHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            b.viewHeaderSpacing.layoutParams = params
        }
        val density = resources.displayMetrics.density
        val startOffset = headerHeight
        val endOffset = headerHeight + (56 * density).toInt()
        b.swipeRefresh.setProgressViewOffset(true, startOffset, endOffset)
    }

    private fun setupViews() {
        binding.tvHeaderTitle.text = "作业详情"
        binding.tvHeaderSubtitle.text = courseNameText
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnCopyTitle.setOnClickListener {
            val title = binding.tvHomeworkTitle.text?.toString()?.trim().orEmpty()
            val reqDesc = binding.tvRequirementDesc.text?.toString()?.trim().orEmpty()
            val copyText = if (reqDesc.isNotBlank() && reqDesc != "教师未提供文字说明") {
                "【题目】$title\n\n【要求说明】\n$reqDesc"
            } else {
                title
            }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("作业题目", copyText))
            Toast.makeText(requireContext(), "已复制作业题目与要求至剪贴板", Toast.LENGTH_SHORT).show()
        }

        binding.btnWebPortal.setOnClickListener {
            if (platform == "CHAOXING") {
                openChaoXingOnlinePortal()
            } else {
                val actId = activityId
                val cId = courseId
                val url = "https://tronclass.usst.edu.cn/course/$cId/learning-activity#/$actId"
                JwglWebActivity.start(requireContext(), url, titleText.ifBlank { "一网畅学作业" })
            }
        }

        updateColumnsLayout()

        binding.btnRefresh.setOnClickListener {
            loadData(forceRefresh = true)
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            loadData(forceRefresh = true)
        }

        binding.tvHomeworkTitle.text = titleText
        binding.tvCourseName.text = courseNameText

        if (platform == "CHAOXING") {
            setupChaoXingViews()
        } else {
            setupTronClassViews()
        }
    }

    private fun setupChaoXingViews() {
        // 首帧 0ms 瞬间显示已知元数据
        renderChaoXingBasicUi(chaoXingWork)

        binding.btnAddAttachment.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.btnCameraSubmit.setOnClickListener {
            takePhoto()
        }

        binding.btnSubmitHomework.setOnClickListener {
            performChaoXingSubmission()
        }

        binding.tilSubmissionComment.hint = "请输入作答说明、实验结论或文字解答..."

        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.btnToggleResubmit.setOnClickListener {
            isResubmitMode = !isResubmitMode
            binding.cardSubmitAction.visibility = if (isResubmitMode) View.VISIBLE else View.GONE
            binding.btnToggleResubmit.text = if (isResubmitMode) "收起重新提交面板" else "重新提交 / 补充附件"

            if (isPad) {
                val submitParams = binding.cardSubmitAction.layoutParams as? ViewGroup.MarginLayoutParams
                if (binding.cardSubmissionRecord.visibility == View.VISIBLE) {
                    submitParams?.topMargin = TabletLayoutHelper.dpToPx(requireContext(), 14f)
                } else {
                    submitParams?.topMargin = 0
                }
                binding.cardSubmitAction.layoutParams = submitParams
            }

            if (isResubmitMode) {
                if (binding.etSubmissionComment.text.isNullOrBlank()) {
                    val prevComment = chaoXingHomeworkDetail?.cleanMyAnswer?.trim()
                    if (!prevComment.isNullOrBlank()) {
                        binding.etSubmissionComment.setText(prevComment)
                        binding.etSubmissionComment.setSelection(prevComment.length)
                    }
                }
                binding.scrollView.postDelayed({
                    val b = _binding ?: return@postDelayed
                    b.scrollView.smoothScrollTo(0, b.llHomeworkRootContainer.bottom)
                    b.etSubmissionComment.requestFocus()
                }, 100)
            }
        }
    }

    private fun openChaoXingOnlinePortal() {
        val cx = chaoXingWork ?: return
        val targetUrl = chaoXingHomeworkDetail?.taskUrl?.ifBlank { cx.taskUrl } ?: cx.taskUrl
        ChaoXingNavigator.openHomework(
            context = requireContext(),
            courseId = chaoXingCourse?.courseId ?: "",
            classId = chaoXingCourse?.classId,
            workId = cx.workId,
            title = cx.title,
            detailUrl = targetUrl
        )
    }

    private fun setupTronClassViews() {
        binding.btnAddAttachment.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.btnCameraSubmit.setOnClickListener {
            takePhoto()
        }

        binding.btnSubmitHomework.setOnClickListener {
            performHomeworkSubmission()
        }

        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.btnToggleResubmit.setOnClickListener {
            isResubmitMode = !isResubmitMode
            binding.cardSubmitAction.visibility = if (isResubmitMode) View.VISIBLE else View.GONE
            binding.btnToggleResubmit.text = if (isResubmitMode) "收起重新提交面板" else "重新提交 / 补充附件"

            if (isPad) {
                val submitParams = binding.cardSubmitAction.layoutParams as? ViewGroup.MarginLayoutParams
                if (binding.cardSubmissionRecord.visibility == View.VISIBLE) {
                    submitParams?.topMargin = TabletLayoutHelper.dpToPx(requireContext(), 14f)
                } else {
                    submitParams?.topMargin = 0
                }
                binding.cardSubmitAction.layoutParams = submitParams
            }

            if (isResubmitMode) {
                if (binding.etSubmissionComment.text.isNullOrBlank()) {
                    val prevComment = submissionDetail?.comment?.trim()
                    if (!prevComment.isNullOrBlank()) {
                        val cleanPrev = HtmlCompat.fromHtml(
                            prevComment.replace("\r\n", "<br/>").replace("\n", "<br/>"),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        ).toString().trim()
                        binding.etSubmissionComment.setText(cleanPrev)
                        binding.etSubmissionComment.setSelection(cleanPrev.length)
                    }
                }
                binding.scrollView.postDelayed({
                    val b = _binding ?: return@postDelayed
                    b.scrollView.smoothScrollTo(0, b.llHomeworkRootContainer.bottom)
                    b.etSubmissionComment.requestFocus()
                }, 100)
            }
        }
    }

    private fun loadData(forceRefresh: Boolean) {
        if (platform == "CHAOXING") {
            loadChaoXingData(forceRefresh)
        } else {
            loadTronClassData(forceRefresh)
        }
    }

    private fun loadChaoXingData(forceRefresh: Boolean) {
        val cx = chaoXingWork
        if (cx == null) {
            Log.e(TAG, "loadChaoXingData: chaoXingWork is NULL! platform=$platform, argsKeys=${arguments?.keySet()}")
            return
        }
        Log.w(TAG, "loadChaoXingData: workId=${cx.workId}, title=${cx.title}, forceRefresh=$forceRefresh")
        val cacheMgr = TronClassDataCacheManager.getInstance(requireContext())

        // 1. 本地缓存秒级直出 (0ms 离线可用)
        if (!forceRefresh) {
            val cached = try {
                cacheMgr.getCachedChaoXingHomeworkDetail(cx.workId)
            } catch (e: Exception) {
                null
            }
            if (cached != null) {
                val cachedQuestions = cached.rawQuestions ?: emptyList()
                val isStaleLegacyCache = cachedQuestions.isEmpty() && (cached.contentHtml.orEmpty().contains("第1题") || cached.cleanContent.orEmpty().contains("第1题"))
                val hasMissingOptions = cachedQuestions.isEmpty() || cachedQuestions.any {
                    (it.typeName.orEmpty().contains("选") || it.typeName.orEmpty().contains("判断")) && it.safeOptions().isEmpty()
                }
                val isCorrupted = cached.contentHtml == null || cached.cleanContent == null || cached.rawQuestions == null
                if (!isStaleLegacyCache && !hasMissingOptions && !isCorrupted) {
                    try {
                        chaoXingHomeworkDetail = cached
                        renderChaoXingDetailUi(cached)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to render cached homework: ${e.message}", e)
                    }
                }
            }
        }

        _binding?.swipeRefresh?.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingDetailClient.fetchHomeworkDetail(cx, chaoXingCourse)
            _binding?.swipeRefresh?.isRefreshing = false

            if (res.isSuccess) {
                val detail = res.getOrNull()
                Log.e(TAG, "fetchHomeworkDetail SUCCESS: ${detail?.title}, qCount=${detail?.rawQuestions?.size}")
                if (detail != null) {
                    chaoXingHomeworkDetail = detail
                    renderChaoXingDetailUi(detail)
                }
            } else {
                Log.e(TAG, "fetchHomeworkDetail FAILED: ${res.exceptionOrNull()?.message}", res.exceptionOrNull())
                if (chaoXingHomeworkDetail == null) {
                    binding.tvRequirementDesc.text = "（题目说明拉取超时或网络波动，可点击底部按钮直接在超星作答界面查看）"
                }
            }
        }
    }

    private fun loadTronClassData(forceRefresh: Boolean) {
        _binding?.swipeRefresh?.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val detailDeferred = async { client.fetchActivityDetail(activityId) }
            val subDeferred = async { client.fetchSubmissionDetail(activityId) }

            val detailRes = detailDeferred.await()
            val subRes = subDeferred.await()

            val b = _binding ?: return@launch
            b.swipeRefresh.isRefreshing = false

            if (detailRes.isSuccess) {
                activityDetail = detailRes.getOrNull()
            }
            if (subRes.isSuccess) {
                submissionDetail = subRes.getOrNull()
            }

            renderUi()
        }
    }

    private fun renderUi() {
        if (_binding == null) return
        val act = activityDetail
        val sub = submissionDetail
        val context = context ?: return

        // 1. Meta / Hero
        val title = act?.getCleanTitle() ?: titleText
        binding.tvHomeworkTitle.text = title

        // Deadline
        val endTime = act?.endTime
        if (!endTime.isNullOrBlank()) {
            val date = parseIsoDate(endTime)
            val diffMs = (date?.time ?: 0L) - System.currentTimeMillis()
            val remainingStr = when {
                diffMs <= 0 -> "已逾期"
                else -> {
                    val days = diffMs / (1000 * 60 * 60 * 24)
                    val hours = (diffMs / (1000 * 60 * 60)) % 24
                    if (days > 0) "剩 $days 天 $hours 小时" else "剩 $hours 小时"
                }
            }
            val formattedDate = formatDate(date)
            binding.tvDeadline.text = "截止时间：$formattedDate ($remainingStr)"
        } else {
            binding.tvDeadline.text = "截止时间：未设定"
        }

        // Score weight
        binding.tvScoreWeight.text = "分值说明：按教学班评分标准计算"

        // Status Badge
        val isSubmitted = sub?.isSubmitted() == true
        val isGraded = sub?.isGraded() == true

        when {
            isGraded -> {
                val score = sub?.getDisplayScore() ?: ""
                binding.tvStatusBadge.text = "已批改 · ${score}分"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            isSubmitted -> {
                binding.tvStatusBadge.text = "已提交 · 待批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            else -> {
                binding.tvStatusBadge.text = "未提交"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_variant)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }

        // 2. Requirement Description & Attachments
        val descHtml = act?.data?.description ?: "教师未提供文字说明"
        val cleanedDesc = TronClassHtmlHelper.cleanHtmlForDarkTheme(descHtml)
        val descSpanned = HtmlCompat.fromHtml(cleanedDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.tvRequirementDesc.text = if (descSpanned.isNotBlank()) descSpanned.trim() else "教师未提供文字说明"

        val imageUrls = TronClassHtmlHelper.extractImageUrls(descHtml)
        binding.llRequirementImages.removeAllViews()
        if (imageUrls.isNotEmpty()) {
            binding.llRequirementImages.visibility = View.VISIBLE
            for (imgUrl in imageUrls) {
                val iv = createRequirementImageView(imgUrl)
                binding.llRequirementImages.addView(iv)
            }
        } else {
            binding.llRequirementImages.visibility = View.GONE
        }

        val teacherUploads = act?.uploads ?: emptyList()
        binding.llTeacherAttachments.removeAllViews()
        if (teacherUploads.isNotEmpty()) {
            binding.tvTeacherAttachmentsHeader.visibility = View.VISIBLE
            for (u in teacherUploads) {
                val itemView = createAttachmentItemView(u, isDeletable = false)
                binding.llTeacherAttachments.addView(itemView)
            }
        } else {
            binding.tvTeacherAttachmentsHeader.visibility = View.GONE
        }

        // 3. Teacher Feedback (if graded or has feedback)
        val teacherScore = sub?.getDisplayScore()
        val teacherComment = sub?.instructorComment?.trim()
        val corrUploads = sub?.submissionCorrect?.uploads ?: emptyList()

        if (isGraded || !teacherComment.isNullOrBlank() || corrUploads.isNotEmpty()) {
            binding.cardFeedback.visibility = View.VISIBLE
            binding.tvTeacherScore.text = if (!teacherScore.isNullOrBlank()) "${teacherScore} 分" else "已批阅"
            binding.tvTeacherComment.text = if (!teacherComment.isNullOrBlank()) {
                val cleanedComment = TronClassHtmlHelper.cleanHtmlForDarkTheme(teacherComment)
                val commentSpanned = HtmlCompat.fromHtml(cleanedComment, HtmlCompat.FROM_HTML_MODE_LEGACY)
                "教师评语：${commentSpanned.trim()}"
            } else {
                "教师未留下文字评语"
            }

            binding.llCorrectionAttachments.removeAllViews()
            if (corrUploads.isNotEmpty()) {
                val header = TextView(context).apply {
                    text = "教师批注/反馈附件："
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, 8, 0, 4)
                }
                binding.llCorrectionAttachments.addView(header)
                for (u in corrUploads) {
                    binding.llCorrectionAttachments.addView(createAttachmentItemView(u, isDeletable = false))
                }
            }
        } else {
            binding.cardFeedback.visibility = View.GONE
        }

        // 4. Submission Record
        if (isSubmitted) {
            binding.cardSubmissionRecord.visibility = View.VISIBLE
            binding.tvSubmittedAt.text = "提交于: " + (sub?.getFormattedSubmittedTime() ?: "已完成提交")

            val studentComment = sub?.comment?.trim()
            if (!studentComment.isNullOrBlank()) {
                val formattedComment = studentComment.replace("\r\n", "<br/>").replace("\n", "<br/>")
                val spannedStudent = HtmlCompat.fromHtml(formattedComment, HtmlCompat.FROM_HTML_MODE_LEGACY)
                val trimmed = spannedStudent.trim()
                binding.tvStudentComment.text = SpannableStringBuilder().apply {
                    append("提交说明：")
                    if (trimmed.contains("\n")) {
                        append("\n")
                    }
                    append(trimmed)
                }
                binding.tvStudentComment.visibility = View.VISIBLE
            } else {
                binding.tvStudentComment.visibility = View.GONE
            }

            binding.llStudentUploads.removeAllViews()
            val studentUploads = sub?.uploads ?: emptyList()
            if (studentUploads.isNotEmpty()) {
                for (u in studentUploads) {
                    binding.llStudentUploads.addView(createAttachmentItemView(u, isDeletable = false))
                }
            } else {
                val emptyTv = TextView(context).apply {
                    text = "（纯文本提交，无附件）"
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                binding.llStudentUploads.addView(emptyTv)
            }

            // If already submitted, hide submit action card by default unless in resubmit mode
            binding.cardSubmitAction.visibility = if (isResubmitMode) View.VISIBLE else View.GONE
            binding.btnToggleResubmit.visibility = View.VISIBLE
            binding.tvSubmitHeader.text = "重新提交作业"
        } else {
            binding.cardSubmissionRecord.visibility = View.GONE
            binding.cardSubmitAction.visibility = View.VISIBLE
            binding.btnToggleResubmit.visibility = View.GONE
            binding.tvSubmitHeader.text = "提交作业与附件"
        }

        val isPad = TabletLayoutHelper.isTabletLandscape(context)
        if (isPad) {
            val subParams = binding.cardSubmissionRecord.layoutParams as? ViewGroup.MarginLayoutParams
            subParams?.topMargin = 0
            binding.cardSubmissionRecord.layoutParams = subParams

            val submitParams = binding.cardSubmitAction.layoutParams as? ViewGroup.MarginLayoutParams
            if (binding.cardSubmissionRecord.visibility == View.VISIBLE) {
                submitParams?.topMargin = TabletLayoutHelper.dpToPx(context, 14f)
            } else {
                submitParams?.topMargin = 0
            }
            binding.cardSubmitAction.layoutParams = submitParams
        }
    }

    private fun renderChaoXingBasicUi(work: ChaoXingWork?) {
        if (work == null || _binding == null) return
        val context = context ?: return
        binding.tvHomeworkTitle.text = work.title
        binding.tvCourseName.text = work.courseName.ifBlank { courseNameText }

        val cleanDdl = cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient.cleanDeadline(work.deadline)
        if (cleanDdl.isNotBlank()) {
            val ddlMs = cn.edu.usst.jwgl.data.network.ChaoXingClient.parseDdlMillis(cleanDdl)
            val remainStr = if (ddlMs != null) {
                val diff = ddlMs - System.currentTimeMillis()
                if (diff <= 0) "已截止" else {
                    val days = diff / (1000 * 60 * 60 * 24)
                    val hours = (diff / (1000 * 60 * 60)) % 24
                    if (days > 0) "剩 $days 天 $hours 小时" else "剩 $hours 小时"
                }
            } else if (work.expired) "已截止" else ""
            binding.tvDeadline.text = "截止时间：$cleanDdl ${if (remainStr.isNotBlank()) "($remainStr)" else ""}".trim()
        } else {
            binding.tvDeadline.text = "截止时间：未设定"
        }

        binding.tvScoreWeight.text = "平台来源：超星学习通 · 原生移动端详情"

        when {
            work.score.isNotBlank() -> {
                binding.tvStatusBadge.text = "已批改 · ${work.score}分"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            work.statusText.contains("待批") || work.statusText.contains("待评") || work.statusText.contains("未批") -> {
                binding.tvStatusBadge.text = "已提交 · 待批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            work.statusText.contains("已完成") || work.statusText.contains("已批") -> {
                binding.tvStatusBadge.text = "已批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            work.statusText.contains("已交") || work.statusText.contains("已提交") -> {
                binding.tvStatusBadge.text = "已提交 · 待批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            else -> {
                val st = work.statusText.ifBlank { "未提交" }
                binding.tvStatusBadge.text = st
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_variant)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }
    }

    private fun renderChaoXingDetailUi(detail: ChaoXingHomeworkDetail) {
        if (_binding == null) return
        val context = context ?: return

        // 1. Meta / Hero
        binding.tvHomeworkTitle.text = detail.title.orEmpty()
        binding.tvCourseName.text = detail.courseName.orEmpty().ifBlank { courseNameText }
        val cleanDetailDdl = cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient.cleanDeadline(detail.deadline.orEmpty())
        if (cleanDetailDdl.isNotBlank()) {
            val ddlMs = cn.edu.usst.jwgl.data.network.ChaoXingClient.parseDdlMillis(cleanDetailDdl)
            val remainStr = if (ddlMs != null) {
                val diff = ddlMs - System.currentTimeMillis()
                if (diff <= 0) "已截止" else {
                    val days = diff / (1000 * 60 * 60 * 24)
                    val hours = (diff / (1000 * 60 * 60)) % 24
                    if (days > 0) "剩 $days 天 $hours 小时" else "剩 $hours 小时"
                }
            } else ""
            binding.tvDeadline.text = "截止时间：$cleanDetailDdl ${if (remainStr.isNotBlank()) "($remainStr)" else ""}".trim()
        } else if (chaoXingWork?.deadline?.isNotBlank() == true) {
            val workDdl = cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient.cleanDeadline(chaoXingWork?.deadline)
            val ddlMs = cn.edu.usst.jwgl.data.network.ChaoXingClient.parseDdlMillis(workDdl)
            val remainStr = if (ddlMs != null) {
                val diff = ddlMs - System.currentTimeMillis()
                if (diff <= 0) "已截止" else {
                    val days = diff / (1000 * 60 * 60 * 24)
                    val hours = (diff / (1000 * 60 * 60)) % 24
                    if (days > 0) "剩 $days 天 $hours 小时" else "剩 $hours 小时"
                }
            } else if (chaoXingWork?.expired == true) "已截止" else ""
            binding.tvDeadline.text = "截止时间：$workDdl ${if (remainStr.isNotBlank()) "($remainStr)" else ""}".trim()
        } else if (chaoXingWork?.expired == true || detail.statusText.orEmpty().contains("已截止")) {
            binding.tvDeadline.text = "截止时间：已截止"
        } else {
            binding.tvDeadline.text = "截止时间：未设定"
        }
        if (detail.questionsCountText.orEmpty().isNotBlank()) {
            binding.tvScoreWeight.text = "题目概况：${detail.questionsCountText} · 来源：超星学习通"
        }

        // Status Badge
        val statusText = detail.statusText.orEmpty()
        when {
            detail.score.orEmpty().isNotBlank() -> {
                binding.tvStatusBadge.text = "已批改 · ${detail.score}分"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            statusText.contains("待批") || statusText.contains("待评") || statusText.contains("未批") -> {
                binding.tvStatusBadge.text = "已提交 · 待批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            detail.isGraded -> {
                binding.tvStatusBadge.text = "已批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            detail.isSubmitted -> {
                binding.tvStatusBadge.text = "已提交 · 待批阅"
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_container)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }
            else -> {
                binding.tvStatusBadge.text = statusText.ifBlank { "未提交" }
                binding.tvStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_variant)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }

        // 2. Requirement Description & Attachments
        val rawHtml = detail.contentHtml.orEmpty().ifBlank { detail.cleanContent.orEmpty() }
        val rawQuestions = detail.rawQuestions ?: emptyList()
        val effectiveQuestions = if (rawQuestions.isNotEmpty()) {
            rawQuestions
        } else if (rawHtml.contains("<div") || rawHtml.contains("<ul") || rawHtml.contains("singleQuesId") || rawHtml.contains("TiMu") || rawHtml.contains("Zy_TItle")) {
            chaoXingDetailClient.parseQuestionsFromHtml(rawHtml)
        } else {
            emptyList()
        }

        if (effectiveQuestions.isNotEmpty()) {
            renderChaoXingQuestions(effectiveQuestions)
        } else {
            binding.llChaoXingQuestions.visibility = View.GONE
            binding.tvRequirementDesc.visibility = View.VISIBLE
            if (rawHtml.isNotBlank()) {
                val cleanedDesc = TronClassHtmlHelper.cleanHtmlForDarkTheme(rawHtml)
                val descSpanned = HtmlCompat.fromHtml(cleanedDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
                binding.tvRequirementDesc.text = if (descSpanned.isNotBlank()) descSpanned.trim() else detail.cleanContent
            } else {
                binding.tvRequirementDesc.text = "教师未提供文字说明"
            }
        }

        // Images in requirement
        val imageUrls = TronClassHtmlHelper.extractImageUrls(rawHtml)
        binding.llRequirementImages.removeAllViews()
        if (imageUrls.isNotEmpty()) {
            binding.llRequirementImages.visibility = View.VISIBLE
            for (imgUrl in imageUrls) {
                val iv = createRequirementImageView(imgUrl)
                binding.llRequirementImages.addView(iv)
            }
        } else {
            binding.llRequirementImages.visibility = View.GONE
        }

        // Teacher Attachments
        binding.llTeacherAttachments.removeAllViews()
        if (detail.teacherAttachments.isNotEmpty()) {
            binding.tvTeacherAttachmentsHeader.visibility = View.VISIBLE
            for (att in detail.teacherAttachments) {
                binding.llTeacherAttachments.addView(createChaoXingAttachmentItemView(att))
            }
        } else {
            binding.tvTeacherAttachmentsHeader.visibility = View.GONE
        }

        // 3. Feedback Card (教师批改与成绩反馈)
        val hasScore = detail.score.isNotBlank()
        val cleanComment = cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient.cleanTeacherCommentText(detail.teacherComment)
        val hasComment = cleanComment.isNotBlank()
        val isPending = detail.statusText.contains("待批") || detail.statusText.contains("未批") || detail.statusText.contains("待评")
        val isReallyGraded = (detail.isGraded || hasScore) && !isPending

        if (isReallyGraded && (hasScore || hasComment)) {
            binding.cardFeedback.visibility = View.VISIBLE
            binding.tvTeacherScore.text = if (hasScore) "${detail.score} 分" else "已批阅"
            binding.tvTeacherComment.text = if (hasComment) "教师评语：$cleanComment" else "教师未留下文字评语"
        } else if (hasComment && !isPending) {
            binding.cardFeedback.visibility = View.VISIBLE
            binding.tvTeacherScore.text = "待评分"
            binding.tvTeacherComment.text = "教师评语：$cleanComment"
        } else {
            binding.cardFeedback.visibility = View.GONE
        }

        // 4. Submission Record Card
        if (detail.isSubmitted || detail.cleanMyAnswer.isNotBlank() || detail.studentAttachments.isNotEmpty()) {
            binding.cardSubmissionRecord.visibility = View.VISIBLE
            binding.tvSubmittedAt.text = "状态: " + detail.statusText
            if (detail.cleanMyAnswer.isNotBlank()) {
                binding.tvStudentComment.text = detail.cleanMyAnswer
                binding.tvStudentComment.visibility = View.VISIBLE
            } else {
                binding.tvStudentComment.visibility = View.GONE
            }

            binding.llStudentUploads.removeAllViews()
            if (detail.studentAttachments.isNotEmpty()) {
                for (att in detail.studentAttachments) {
                    binding.llStudentUploads.addView(createChaoXingAttachmentItemView(att))
                }
            } else {
                val emptyTv = TextView(context).apply {
                    text = "（答卷已提交至超星系统）"
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                binding.llStudentUploads.addView(emptyTv)
            }
            binding.btnToggleResubmit.visibility = View.VISIBLE
            binding.cardSubmitAction.visibility = if (isResubmitMode) View.VISIBLE else View.GONE
            binding.tvSubmitHeader.text = "重新提交作业"
            binding.btnSubmitHomework.text = "重新提交至超星"
        } else {
            binding.cardSubmissionRecord.visibility = View.GONE
            binding.btnToggleResubmit.visibility = View.GONE
            binding.cardSubmitAction.visibility = View.VISIBLE
            binding.tvSubmitHeader.text = "提交作业与附件"
            binding.btnSubmitHomework.text = "确认提交至超星"
        }

        binding.btnSubmitHomework.setOnClickListener {
            performChaoXingSubmission()
        }

        val isPad = TabletLayoutHelper.isTabletLandscape(context)
        if (isPad) {
            val subParams = binding.cardSubmissionRecord.layoutParams as? ViewGroup.MarginLayoutParams
            subParams?.topMargin = 0
            binding.cardSubmissionRecord.layoutParams = subParams

            val submitParams = binding.cardSubmitAction.layoutParams as? ViewGroup.MarginLayoutParams
            if (binding.cardSubmissionRecord.visibility == View.VISIBLE) {
                submitParams?.topMargin = TabletLayoutHelper.dpToPx(context, 14f)
            } else {
                submitParams?.topMargin = 0
            }
            binding.cardSubmitAction.layoutParams = submitParams
        }
    }

    /**
     * 动态原生渲染超星作业/小测客观题卡片与解析
     */
    private fun renderChaoXingQuestions(questions: List<ChaoXingQuestionItem>) {
        val b = _binding ?: return
        val context = context ?: return
        val density = resources.displayMetrics.density

        b.llChaoXingQuestions.removeAllViews()
        b.llChaoXingQuestions.visibility = View.VISIBLE
        b.tvRequirementDesc.visibility = View.GONE

        val primaryColor = ContextCompat.getColor(context, R.color.primary)
        val textPrimaryColor = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
        val textTertiaryColor = ContextCompat.getColor(context, R.color.text_tertiary)
        val surfaceColor = ContextCompat.getColor(context, R.color.surface)
        val borderColor = ContextCompat.getColor(context, R.color.border)
        val surfaceContainerHigh = ContextCompat.getColor(context, R.color.surface_container_high)

        for ((idx, q) in questions.withIndex()) {
            val qRoot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = (16 * density).toInt()
                }
            }

            // 1. 顶部 Header (题号、题型 Badge、分值)
            val headerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 题型 Badge
            val typeBadge = TextView(context).apply {
                text = q.typeName
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
                setBackgroundResource(R.drawable.badge_bg)
                when (q.typeName) {
                    "单选题", "判断题" -> {
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#158C1D27"))
                        setTextColor(Color.parseColor("#8C1D27"))
                    }
                    "多选题" -> {
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#150284C7"))
                        setTextColor(Color.parseColor("#0284C7"))
                    }
                    else -> {
                        backgroundTintList = ColorStateList.valueOf(surfaceContainerHigh)
                        setTextColor(textSecondaryColor)
                    }
                }
            }
            headerLayout.addView(typeBadge)

            // 题号
            val indexTv = TextView(context).apply {
                text = "第 ${q.index} 题"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondaryColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * density).toInt()
                }
            }
            headerLayout.addView(indexTv)

            // 弹性空白
            headerLayout.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            // 分值
            val scoreStr = q.scoreText.orEmpty().trim()
            if (scoreStr.isNotBlank()) {
                val pointTv = TextView(context).apply {
                    text = scoreStr
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(primaryColor)
                }
                headerLayout.addView(pointTv)
            }
            qRoot.addView(headerLayout)

            // 2. 题干 Stem (富文本解析)
            val stemTv = TextView(context).apply {
                val stemText = q.cleanStem.orEmpty().ifBlank { q.stemHtml.orEmpty() }
                val spanned = try {
                    val imgGetter = TronClassHtmlImageGetter(this)
                    Html.fromHtml(stemText, Html.FROM_HTML_MODE_LEGACY, imgGetter, null).trimEnd()
                } catch (_: Exception) {
                    stemText
                }
                text = spanned
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimaryColor)
                setTextIsSelectable(true)
                setLineSpacing(3f * density, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            }
            qRoot.addView(stemTv)

            // 3. 选项卡片 (若为客观选择题)
            val qOptions = q.safeOptions()
            if (qOptions.isNotEmpty()) {
                val optionsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                }

                val isMulti = q.typeCode == "1" || q.typeName == "多选题"
                val myAnsClean = q.cleanMyAnswer.orEmpty().trim()
                val rightAnsClean = q.correctAnswer.orEmpty().trim()

                for (opt in qOptions) {
                    val optBinding = ItemTronclassOptionCardBinding.inflate(
                        LayoutInflater.from(context),
                        optionsContainer,
                        false
                    )

                    optBinding.tvOptionBadge.text = opt.letter
                    val optSpanned = try {
                        val imgGetter = TronClassHtmlImageGetter(optBinding.tvOptionContent)
                        Html.fromHtml(opt.content, Html.FROM_HTML_MODE_LEGACY, imgGetter, null).trimEnd()
                    } catch (_: Exception) {
                        opt.content
                    }
                    optBinding.tvOptionContent.text = optSpanned

                    if (isMulti) {
                        optBinding.rbOption.visibility = View.GONE
                        optBinding.cbOption.visibility = View.VISIBLE
                    } else {
                        optBinding.rbOption.visibility = View.VISIBLE
                        optBinding.cbOption.visibility = View.GONE
                    }

                    // 选项判定与复习模式样式
                    val isMy = opt.isSelected || (myAnsClean.contains(opt.letter) && opt.letter.isNotBlank())
                    val isCor = opt.isCorrect || (rightAnsClean.contains(opt.letter) && opt.letter.isNotBlank())

                    when {
                        isCor && isMy -> {
                            // 答对！学生选择且是标准答案
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#2E7D32")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✓ 您的选择 (正确)"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C8E6C9"))
                        }
                        isCor -> {
                            // 标准答案 (学生未选)
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#4CAF50")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✓ 标准答案"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C8E6C9"))
                        }
                        isMy -> {
                            // 答错！学生选择但非标准答案
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#C62828")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#C62828"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✕ 您的选择"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#C62828"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFCDD2"))
                        }
                        else -> {
                            // 普通未选项
                            optBinding.cardOptionRoot.setCardBackgroundColor(surfaceColor)
                            optBinding.cardOptionRoot.strokeColor = borderColor
                            optBinding.cardOptionRoot.strokeWidth = (1.0f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = false
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(textTertiaryColor)
                            } else {
                                optBinding.rbOption.isChecked = false
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(textTertiaryColor)
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(surfaceContainerHigh)
                            optBinding.tvOptionBadge.setTextColor(textSecondaryColor)
                            optBinding.tvOptionContent.setTextColor(textPrimaryColor)
                            optBinding.tvOptionContent.setTypeface(null, Typeface.NORMAL)
                            optBinding.tvOptionReviewTag.visibility = View.GONE
                        }
                    }

                    optionsContainer.addView(optBinding.root)
                }
                qRoot.addView(optionsContainer)
            }

            // 4. 底部作答与解析结果卡片 (若有作答或标准答案)
            val hasAnswerInfo = q.cleanMyAnswer.orEmpty().isNotBlank() || q.correctAnswer.orEmpty().isNotBlank() || q.explanation.orEmpty().isNotBlank()
            if (hasAnswerInfo) {
                val resultBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (10 * density).toInt()
                    }
                }

                val rowAnswers = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                if (q.cleanMyAnswer.orEmpty().isNotBlank()) {
                    val myAnsTv = TextView(context).apply {
                        text = "我的作答: ${q.cleanMyAnswer}"
                        textSize = 13f
                        setTextColor(textPrimaryColor)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    rowAnswers.addView(myAnsTv)
                }

                if (q.correctAnswer.orEmpty().isNotBlank()) {
                    val rightAnsTv = TextView(context).apply {
                        text = "标准答案: ${q.correctAnswer}"
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#2E7D32"))
                        setTextIsSelectable(true)
                    }
                    rowAnswers.addView(rightAnsTv)
                }

                if (rowAnswers.childCount > 0) {
                    resultBox.addView(rowAnswers)
                }

                if (q.explanation.isNotBlank()) {
                    val expTv = TextView(context).apply {
                        text = "解析: ${q.explanation}"
                        textSize = 12f
                        setTextColor(textSecondaryColor)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (6 * density).toInt()
                        }
                    }
                    resultBox.addView(expTv)
                }

                qRoot.addView(resultBox)
            }

            // 5. 题目分割线 (除最后一题外)
            if (idx < questions.size - 1) {
                val divider = View(context).apply {
                    setBackgroundColor(borderColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt().coerceAtLeast(1)
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                }
                qRoot.addView(divider)
            }

            b.llChaoXingQuestions.addView(qRoot)
        }
    }

    private fun createChaoXingAttachmentItemView(attachment: ChaoXingCourseAttachment): View {
        val itemBinding = ItemTronclassAttachmentBinding.inflate(layoutInflater, null, false)
        itemBinding.tvFileName.text = attachment.name
        val sizeStr = attachment.getFormattedSize()
        itemBinding.tvFileSize.text = if (sizeStr.isNotBlank()) "$sizeStr · 超星附件" else "超星附件"
        itemBinding.btnFileAction.text = "查看/下载"
        itemBinding.btnFileRemove.visibility = View.GONE

        itemBinding.btnFileAction.setOnClickListener {
            handleChaoXingAttachmentAction(attachment)
        }
        itemBinding.root.setOnClickListener {
            handleChaoXingAttachmentAction(attachment)
        }
        return itemBinding.root
    }

    private fun handleChaoXingAttachmentAction(attachment: ChaoXingCourseAttachment) {
        val ctx = context ?: return
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在解析「${attachment.name}」...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingDetailClient.fetchYunFile(attachment.objectId)
            progress.dismiss()
            if (res.isSuccess) {
                val yun = res.getOrNull()
                val lowerName = attachment.name.lowercase(Locale.ROOT)
                val isVideo = attachment.isVideo || lowerName.endsWith(".mp4") || lowerName.endsWith(".flv") || lowerName.endsWith(".wmv") || lowerName.endsWith(".avi")

                if (isVideo) {
                    val stream = yun?.streamUrl ?: ""
                    if (stream.isNotBlank()) {
                        TronClassVideoPlayerActivity.start(
                            context = ctx,
                            title = attachment.name,
                            courseName = courseNameText,
                            videoUrl = stream
                        )
                    } else {
                        Toast.makeText(ctx, "该视频暂无可播放流地址", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val pdfUrl = yun?.pdfUrl ?: ""
                    val downloadUrl = yun?.downloadUrl ?: ""
                    val targetUrl = pdfUrl.ifBlank { downloadUrl }

                    if (targetUrl.isNotBlank()) {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(attachment.name)
                            .setMessage("附件大小：${attachment.getFormattedSize()}\n\n该文件已成功转换为移动端格式，可选择在线预览或复制下载链接。")
                            .setPositiveButton("在线预览") { _, _ ->
                                if (targetUrl.endsWith(".pdf", ignoreCase = true) || pdfUrl.isNotBlank()) {
                                    cn.edu.usst.jwgl.ui.pdf.PdfViewerActivity.start(ctx, targetUrl, attachment.name)
                                } else {
                                    JwglWebActivity.start(ctx, targetUrl, attachment.name)
                                }
                            }
                            .setNeutralButton("复制下载链接") { _, _ ->
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("下载链接", targetUrl))
                                Toast.makeText(ctx, "下载直链已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        Toast.makeText(ctx, "该附件暂无可下载直链", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "解析附件失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createAttachmentItemView(upload: TronClassUpload, isDeletable: Boolean, onRemove: (() -> Unit)? = null): View {
        val itemBinding = ItemTronclassAttachmentBinding.inflate(layoutInflater, null, false)

        itemBinding.tvFileName.text = upload.name
        val sizeStr = upload.getFormattedSize()
        val isCached = cacheManager.isCached(upload.id, upload.name)

        if (isCached) {
            itemBinding.tvFileSize.text = "$sizeStr · 已缓存到本地"
            itemBinding.btnFileAction.text = "打开"
        } else {
            itemBinding.tvFileSize.text = "$sizeStr · 点击下载"
            itemBinding.btnFileAction.text = "下载"
        }

        itemBinding.btnFileAction.setOnClickListener {
            handleAttachmentAction(upload, itemBinding)
        }

        if (isDeletable && onRemove != null) {
            itemBinding.btnFileRemove.visibility = View.VISIBLE
            itemBinding.btnFileRemove.setOnClickListener { onRemove() }
        } else {
            itemBinding.btnFileRemove.visibility = View.GONE
        }

        return itemBinding.root
    }

    private fun handleAttachmentAction(upload: TronClassUpload, itemBinding: ItemTronclassAttachmentBinding) {
        val context = context ?: return
        val existing = cacheManager.getExistingFile(upload.id, upload.name)
        if (existing != null) {
            cacheManager.openFile(existing)
            return
        }

        val destFile = cacheManager.getFile(upload.id, upload.name)
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("下载文件")
            setMessage("${upload.name}\n正在下载...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.downloadAttachment(upload.id, destFile) { percent ->
                progressDialog.progress = percent
            }
            progressDialog.dismiss()

            if (res.isSuccess) {
                TronClassDownloadManager.saveDownloadedItem(
                    context,
                    TronClassDownloadedItem(
                        fileId = upload.id.toString(),
                        fileName = upload.name ?: "附件",
                        courseId = courseId,
                        courseName = courseNameText,
                        filePath = destFile.absolutePath,
                        fileSize = if (destFile.length() > 0) destFile.length() else upload.size
                    )
                )
                itemBinding.tvFileSize.text = "${upload.getFormattedSize()} · 已缓存到本地"
                itemBinding.btnFileAction.text = "打开"
                Toast.makeText(context, "下载完成", Toast.LENGTH_SHORT).show()
                cacheManager.openFile(destFile)
            } else {
                val err = res.exceptionOrNull()?.message ?: "下载失败"
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePickedFileUri(uri: Uri) {
        val context = context ?: return
        try {
            var fileName = "attachment"
            var fileSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // Copy to app cache
            val tempDir = File(context.cacheDir, "pending_uploads").apply { mkdirs() }
            val cleanName = cacheManager.sanitizeFileName(fileName)
            val destFile = File(tempDir, "${System.currentTimeMillis()}_$cleanName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val pending = LocalPendingFile(
                file = destFile,
                displayName = fileName,
                size = if (fileSize > 0L) fileSize else destFile.length()
            )

            pendingLocalFiles.add(pending)
            renderPendingFiles()
        } catch (e: Exception) {
            Toast.makeText(context, "读取选择的文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderPendingFiles() {
        binding.llPendingFiles.removeAllViews()
        for ((index, item) in pendingLocalFiles.withIndex()) {
            val dummyUpload = TronClassUpload(
                id = 0,
                name = item.displayName,
                size = item.size,
                type = "document"
            )
            val itemView = createAttachmentItemView(dummyUpload, isDeletable = true) {
                pendingLocalFiles.removeAt(index)
                renderPendingFiles()
            }
            binding.llPendingFiles.addView(itemView)
        }
    }

    private fun performHomeworkSubmission() {
        val context = context ?: return
        val rawComment = binding.etSubmissionComment.text?.toString()?.trim() ?: ""

        if (pendingLocalFiles.isEmpty() && rawComment.isBlank()) {
            Toast.makeText(context, "请选择至少一个附件文件或输入提交说明", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("确认提交作业？")
            .setMessage("将上传 ${pendingLocalFiles.size} 个附件文件并提交至一网畅学平台。")
            .setPositiveButton("立即提交") { _, _ ->
                val formattedComment = if (rawComment.isNotBlank()) {
                    rawComment.split("\r\n", "\n").joinToString("<br/>") { line ->
                        line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    }
                } else ""
                executeUploadAndSubmit(formattedComment)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeUploadAndSubmit(comment: String) {
        val context = context ?: return
        binding.llUploadProgress.visibility = View.VISIBLE
        binding.btnSubmitHomework.isEnabled = false
        binding.btnAddAttachment.isEnabled = false
        binding.btnCameraSubmit.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val uploadedList = mutableListOf<TronClassUpload>()

            // 1. Upload each pending file
            for ((idx, pf) in pendingLocalFiles.withIndex()) {
                val existingUp = pf.uploadResult
                if (existingUp != null) {
                    uploadedList.add(existingUp)
                    continue
                }

                val b1 = _binding ?: return@launch
                b1.tvUploadStatusText.text = "正在上传 (${idx + 1}/${pendingLocalFiles.size}): ${pf.displayName}"
                b1.progressBarUpload.progress = 0
                b1.tvUploadProgressPercent.text = "0%"

                val upRes = client.uploadFile(pf.file, pf.displayName) { percent ->
                    activity?.runOnUiThread {
                        val pb = _binding ?: return@runOnUiThread
                        pb.progressBarUpload.progress = percent
                        pb.tvUploadProgressPercent.text = "$percent%"
                    }
                }

                val b2 = _binding ?: return@launch
                if (upRes.isSuccess) {
                    val upItem = upRes.getOrNull() ?: run {
                        b2.llUploadProgress.visibility = View.GONE
                        b2.btnSubmitHomework.isEnabled = true
                        b2.btnAddAttachment.isEnabled = true
                        b2.btnCameraSubmit.isEnabled = true
                        return@launch
                    }
                    pf.uploadResult = upItem
                    uploadedList.add(upItem)
                } else {
                    b2.llUploadProgress.visibility = View.GONE
                    b2.btnSubmitHomework.isEnabled = true
                    b2.btnAddAttachment.isEnabled = true
                    b2.btnCameraSubmit.isEnabled = true

                    val err = upRes.exceptionOrNull()?.message ?: "文件上传失败"
                    MaterialAlertDialogBuilder(context)
                        .setTitle("附件上传失败")
                        .setMessage("文件 ${pf.displayName} 上传未成功：\n$err")
                        .setPositiveButton("重试", null)
                        .show()
                    return@launch
                }
            }

            // 2. Submit homework payload
            val b3 = _binding ?: return@launch
            b3.tvUploadStatusText.text = "正在提交作业数据..."
            val isResubmit = submissionDetail?.isSubmitted() == true
            val submissionId = submissionDetail?.id

            val submitRes = client.submitHomework(
                activityId = activityId,
                comment = comment,
                uploads = uploadedList,
                isResubmit = isResubmit,
                submissionId = submissionId
            )

            val b4 = _binding ?: return@launch
            b4.llUploadProgress.visibility = View.GONE
            b4.btnSubmitHomework.isEnabled = true
            b4.btnAddAttachment.isEnabled = true
            b4.btnCameraSubmit.isEnabled = true

            if (submitRes.isSuccess) {
                pendingLocalFiles.clear()
                binding.etSubmissionComment.setText("")
                renderPendingFiles()
                isResubmitMode = false

                MaterialAlertDialogBuilder(context)
                    .setTitle("🎉 作业提交成功！")
                    .setMessage("您的作业与附件已成功同步至上海理工大学一网畅学教学平台！")
                    .setPositiveButton("太棒了") { _, _ ->
                        loadData(forceRefresh = true)
                    }
                    .show()
            } else {
                val err = submitRes.exceptionOrNull()?.message ?: "作业提交失败"
                MaterialAlertDialogBuilder(context)
                    .setTitle("作业提交未完成")
                    .setMessage(err)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun performChaoXingSubmission() {
        val context = context ?: return
        val rawComment = binding.etSubmissionComment.text?.toString()?.trim() ?: ""

        if (pendingLocalFiles.isEmpty() && rawComment.isBlank()) {
            Toast.makeText(context, "请选择至少一个附件文件或输入作答说明", Toast.LENGTH_SHORT).show()
            return
        }

        val actionDesc = if (isResubmitMode) "重新作答" else "作答与附件"
        MaterialAlertDialogBuilder(context)
            .setTitle("确认提交至超星学习通？")
            .setMessage("即将将您的${actionDesc}（包含 ${pendingLocalFiles.size} 个待传附件）同步至超星系统。请选择操作：")
            .setPositiveButton("立即正式提交") { _, _ ->
                executeChaoXingUploadAndSubmit(rawComment, isSubmit = true)
            }
            .setNeutralButton("暂存草稿") { _, _ ->
                executeChaoXingUploadAndSubmit(rawComment, isSubmit = false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeChaoXingUploadAndSubmit(comment: String, isSubmit: Boolean) {
        val context = context ?: return
        val cx = chaoXingWork ?: return

        binding.llUploadProgress.visibility = View.VISIBLE
        binding.btnSubmitHomework.isEnabled = false
        binding.btnAddAttachment.isEnabled = false
        binding.btnCameraSubmit.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val uploadedList = mutableListOf<ChaoXingCourseAttachment>()

            // 1. 上传各待提交文件至超星云盘
            for ((idx, pf) in pendingLocalFiles.withIndex()) {
                val existingUp = pf.chaoXingUploadResult
                if (existingUp != null) {
                    uploadedList.add(existingUp)
                    continue
                }

                val b1 = _binding ?: return@launch
                b1.tvUploadStatusText.text = "正在上传超星云盘 (${idx + 1}/${pendingLocalFiles.size}): ${pf.displayName}"
                b1.progressBarUpload.progress = 0
                b1.tvUploadProgressPercent.text = "0%"

                val upRes = chaoXingDetailClient.uploadAttachmentToPan(pf.file, pf.displayName) { percent ->
                    activity?.runOnUiThread {
                        val pb = _binding ?: return@runOnUiThread
                        pb.progressBarUpload.progress = percent
                        pb.tvUploadProgressPercent.text = "$percent%"
                    }
                }

                val b2 = _binding ?: return@launch
                if (upRes.isSuccess) {
                    val attItem = upRes.getOrNull() ?: run {
                        b2.llUploadProgress.visibility = View.GONE
                        b2.btnSubmitHomework.isEnabled = true
                        b2.btnAddAttachment.isEnabled = true
                        b2.btnCameraSubmit.isEnabled = true
                        return@launch
                    }
                    pf.chaoXingUploadResult = attItem
                    uploadedList.add(attItem)
                } else {
                    b2.llUploadProgress.visibility = View.GONE
                    b2.btnSubmitHomework.isEnabled = true
                    b2.btnAddAttachment.isEnabled = true
                    b2.btnCameraSubmit.isEnabled = true

                    val err = upRes.exceptionOrNull()?.message ?: "超星云盘上传失败"
                    MaterialAlertDialogBuilder(context)
                        .setTitle("附件上传失败")
                        .setMessage("文件 ${pf.displayName} 上传至超星云盘未成功：\n$err")
                        .setPositiveButton("重试", null)
                        .show()
                    return@launch
                }
            }

            // 2. 提交作业及作答数据
            val b3 = _binding ?: return@launch
            b3.tvUploadStatusText.text = if (isSubmit) "正在提交作业数据至超星..." else "正在暂存作业草稿至超星..."

            val submitRes = chaoXingDetailClient.submitHomework(
                work = cx,
                course = chaoXingCourse,
                comment = comment,
                attachments = uploadedList,
                isSubmit = isSubmit
            )

            val b4 = _binding ?: return@launch
            b4.llUploadProgress.visibility = View.GONE
            b4.btnSubmitHomework.isEnabled = true
            b4.btnAddAttachment.isEnabled = true
            b4.btnCameraSubmit.isEnabled = true

            if (submitRes.isSuccess) {
                val resObj = submitRes.getOrNull()
                pendingLocalFiles.clear()
                binding.etSubmissionComment.setText("")
                renderPendingFiles()
                isResubmitMode = false

                MaterialAlertDialogBuilder(context)
                    .setTitle(if (isSubmit) "🎉 超星作业提交成功！" else "💾 草稿暂存成功！")
                    .setMessage(resObj?.message ?: "您的作答与附件已成功同步至超星系统。")
                    .setPositiveButton("太棒了") { _, _ ->
                        loadData(forceRefresh = true)
                    }
                    .show()
            } else {
                val err = submitRes.exceptionOrNull()?.message ?: "作业提交失败"
                MaterialAlertDialogBuilder(context)
                    .setTitle("作业提交未完成")
                    .setMessage(err)
                    .setPositiveButton("我知道了", null)
                    .setNeutralButton("前往网页端查看") { _, _ ->
                        openChaoXingOnlinePortal()
                    }
                    .show()
            }
        }
    }

    private fun parseIsoDate(isoStr: String?): Date? {
        if (isoStr.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(isoStr)
        } catch (e: Exception) {
            try {
                val format2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format2.parse(isoStr)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun formatDate(date: Date?): String {
        if (date == null) return ""
        val out = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }
        return out.format(date)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createRequirementImageView(imgUrl: String): View {
        val context = requireContext()
        val card = com.google.android.material.card.MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
                bottomMargin = dpToPx(6)
            }
            radius = dpToPx(10).toFloat()
            cardElevation = dpToPx(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant))
            strokeWidth = 0
            isClickable = true
            isFocusable = true
        }

        val imageView = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }

        card.addView(imageView)

        TronClassImageLoader.load(imageView, imgUrl, context)

        card.setOnClickListener {
            showFullScreenImagePreview(imgUrl)
        }

        card.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            TronClassImageHelper.showImageOptionsDialog(
                context,
                imgUrl,
                title = titleText.ifBlank { "作业附图" },
                courseId = courseId,
                courseName = courseNameText
            ) {
                showFullScreenImagePreview(imgUrl)
            }
            true
        }

        return card
    }

    private fun showFullScreenImagePreview(imageUrl: String) {
        val context = context ?: return
        TronClassImageHelper.showFullScreenPreview(
            context,
            imageUrl,
            title = titleText.ifBlank { "作业附图" },
            courseId = courseId,
            courseName = courseNameText
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateColumnsLayout(newConfig)
        _binding?.let { b ->
            ViewCompat.requestApplyInsets(b.root)
        }
    }

    private fun updateColumnsLayout(config: Configuration? = null) {
        val ctx = context ?: return
        val b = _binding ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(ctx, config)
        if (isPad) {
            b.llHomeworkColumns.orientation = LinearLayout.HORIZONTAL
            val margin10 = TabletLayoutHelper.dpToPx(ctx, 10f)
            val leftParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f).apply {
                marginEnd = margin10
            }
            val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f).apply {
                marginStart = margin10
            }
            b.llHomeworkColLeft.layoutParams = leftParams
            b.llHomeworkColRight.layoutParams = rightParams
        } else {
            b.llHomeworkColumns.orientation = LinearLayout.VERTICAL
            val fullParamsLeft = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 0
            }
            val fullParamsRight = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 0
            }
            b.llHomeworkColLeft.layoutParams = fullParamsLeft
            b.llHomeworkColRight.layoutParams = fullParamsRight
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        globalLayoutListener?.let {
            _binding?.root?.viewTreeObserver?.removeOnGlobalLayoutListener(it)
        }
        globalLayoutListener = null
        _binding = null
    }
}
