package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
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
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassHomeworkBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassAttachmentBinding
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
        var uploadResult: TronClassUpload? = null
    )

    private val pendingLocalFiles = mutableListOf<LocalPendingFile>()
    private var isResubmitMode: Boolean = false
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handlePickedFileUri(uri)
        }
    }

    companion object {
        private const val ARG_ACTIVITY_ID = "arg_activity_id"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_COURSE_NAME = "arg_course_name"

        fun newInstance(activityId: Long, courseId: Long, title: String, courseName: String): TronClassHomeworkFragment {
            return TronClassHomeworkFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ACTIVITY_ID, activityId)
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
        activityId = args.getLong(ARG_ACTIVITY_ID, 0L)
        courseId = args.getLong(ARG_COURSE_ID, 0L)
        titleText = args.getString(ARG_TITLE) ?: "作业详情"
        courseNameText = args.getString(ARG_COURSE_NAME) ?: ""
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
    }

    private fun setupViews() {
        binding.tvHeaderTitle.text = "作业详情"
        binding.tvHeaderSubtitle.text = courseNameText
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        if (isPad) {
            binding.llHomeworkColumns.orientation = LinearLayout.HORIZONTAL
            val margin10 = TabletLayoutHelper.dpToPx(requireContext(), 10f)
            val leftParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f).apply {
                marginEnd = margin10
            }
            val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f).apply {
                marginStart = margin10
            }
            binding.llHomeworkColLeft.layoutParams = leftParams
            binding.llHomeworkColRight.layoutParams = rightParams
        }

        binding.btnRefresh.setOnClickListener {
            loadData(forceRefresh = true)
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            loadData(forceRefresh = true)
        }

        binding.tvHomeworkTitle.text = titleText
        binding.tvCourseName.text = courseNameText

        binding.btnAddAttachment.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.btnSubmitHomework.setOnClickListener {
            performHomeworkSubmission()
        }

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
                        return@launch
                    }
                    pf.uploadResult = upItem
                    uploadedList.add(upItem)
                } else {
                    b2.llUploadProgress.visibility = View.GONE
                    b2.btnSubmitHomework.isEnabled = true
                    b2.btnAddAttachment.isEnabled = true

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

        return card
    }

    private fun showFullScreenImagePreview(imageUrl: String) {
        val context = context ?: return
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        val iv = ImageView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val closeBtn = android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x66000000)
            layoutParams = android.widget.FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dpToPx(36)
                rightMargin = dpToPx(20)
            }
            setOnClickListener { dialog.dismiss() }
        }

        frame.addView(iv)
        frame.addView(closeBtn)
        dialog.setContentView(frame)

        TronClassImageLoader.load(iv, imageUrl, context)

        frame.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
