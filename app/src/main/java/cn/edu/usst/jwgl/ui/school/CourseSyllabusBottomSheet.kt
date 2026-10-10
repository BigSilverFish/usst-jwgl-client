package cn.edu.usst.jwgl.ui.school

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.CourseBasicInfo
import cn.edu.usst.jwgl.data.network.CourseCatalogService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.io.File

class CourseSyllabusBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_COURSE_CODE = "arg_course_code"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_DEPARTMENT = "arg_department"
        private const val ARG_CREDIT = "arg_credit"

        fun newInstance(
            courseCode: String,
            courseName: String,
            department: String = "",
            credit: String = ""
        ): CourseSyllabusBottomSheet {
            val sheet = CourseSyllabusBottomSheet()
            val args = Bundle()
            args.putString(ARG_COURSE_CODE, courseCode)
            args.putString(ARG_COURSE_NAME, courseName)
            args.putString(ARG_DEPARTMENT, department)
            args.putString(ARG_CREDIT, credit)
            sheet.arguments = args
            return sheet
        }
    }

    private var courseCode: String = ""
    private var courseName: String = ""
    private var department: String = ""
    private var credit: String = ""
    private var syllabusFileName: String = ""
    private var hasAttachment: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseCode = arguments?.getString(ARG_COURSE_CODE) ?: ""
        courseName = arguments?.getString(ARG_COURSE_NAME) ?: ""
        department = arguments?.getString(ARG_DEPARTMENT) ?: ""
        credit = arguments?.getString(ARG_CREDIT) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_bottom_sheet_syllabus, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = (resources.displayMetrics.heightPixels * 0.82).toInt()
                it.requestLayout()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvSyllabusTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvSyllabusSubtitle)
        val btnClose = view.findViewById<View>(R.id.btnCloseSyllabus)
        val badgeCredit = view.findViewById<TextView>(R.id.badgeCredit)
        val badgeHours = view.findViewById<TextView>(R.id.badgeHours)
        val badgeNature = view.findViewById<TextView>(R.id.badgeNature)

        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarSyllabus)
        val scrollContent = view.findViewById<NestedScrollView>(R.id.scrollContent)

        val cardAttachment = view.findViewById<MaterialCardView>(R.id.cardOfficialAttachment)
        val tvAttachmentFileName = view.findViewById<TextView>(R.id.tvAttachmentFileName)
        val badgeOfficialTag = view.findViewById<TextView>(R.id.badgeOfficialTag)
        val ivFileIcon = view.findViewById<ImageView>(R.id.ivFileIcon)
        val btnQuickOpen = view.findViewById<MaterialButton>(R.id.btnQuickOpen)

        val cardNoAttachment = view.findViewById<MaterialCardView>(R.id.cardNoAttachment)

        val tvDetailCourseCode = view.findViewById<TextView>(R.id.tvDetailCourseCode)
        val tvDetailCourseName = view.findViewById<TextView>(R.id.tvDetailCourseName)
        val tvDetailEnglishName = view.findViewById<TextView>(R.id.tvDetailEnglishName)
        val tvDetailDepartment = view.findViewById<TextView>(R.id.tvDetailDepartment)
        val tvDetailCreditHours = view.findViewById<TextView>(R.id.tvDetailCreditHours)
        val tvDetailNature = view.findViewById<TextView>(R.id.tvDetailNature)

        val btnDownload = view.findViewById<MaterialButton>(R.id.btnDownloadSyllabus)

        // Init default views
        tvTitle.text = courseName
        tvSubtitle.text = "课程代码: $courseCode" + if (department.isNotEmpty()) " · $department" else ""
        if (credit.isNotEmpty()) {
            badgeCredit.text = "$credit 学分"
            badgeCredit.visibility = View.VISIBLE
        } else {
            badgeCredit.visibility = View.GONE
        }
        badgeHours.visibility = View.GONE
        badgeNature.visibility = View.GONE

        tvDetailCourseCode.text = courseCode
        tvDetailCourseName.text = courseName

        btnClose.setOnClickListener { dismiss() }

        // Load details and syllabus attachment from JWGL
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            scrollContent.visibility = View.GONE

            val ctx = requireContext()

            // 1. 查询课程基本信息库
            val infoRes = CourseCatalogService.queryCourseByCode(ctx, courseCode)
            val info = infoRes.getOrNull()
            if (info != null) {
                tvTitle.text = info.courseName
                val dep = info.department.ifEmpty { department }
                tvSubtitle.text = "课程代码: ${info.courseCode}" + if (dep.isNotEmpty()) " · $dep" else ""
                badgeCredit.text = "${info.credit} 学分"
                badgeCredit.visibility = View.VISIBLE

                if (info.totalHours > 0) {
                    badgeHours.text = "${info.totalHours} 学时 (理论${info.lectureHours}/实验${info.labHours})"
                    badgeHours.visibility = View.VISIBLE
                }
                if (info.courseNature.isNotEmpty()) {
                    badgeNature.text = info.courseNature
                    badgeNature.visibility = View.VISIBLE
                }

                tvDetailCourseCode.text = info.courseCode
                tvDetailCourseName.text = info.courseName
                tvDetailEnglishName.text = info.englishName.ifEmpty { "暂无" }
                tvDetailDepartment.text = dep.ifEmpty { "上海理工大学" }
                tvDetailCreditHours.text = "${info.credit} 学分 / ${info.totalHours} 学时" +
                        if (info.totalHours > 0) " (讲课${info.lectureHours} + 上机实验${info.labHours})" else ""
                tvDetailNature.text = info.courseNature.ifEmpty { "通识必修" }
            } else {
                tvDetailEnglishName.text = "暂无"
                tvDetailDepartment.text = department.ifEmpty { "上海理工大学" }
                tvDetailCreditHours.text = if (credit.isNotEmpty()) "$credit 学分" else "待定"
                tvDetailNature.text = "必修/选修"
            }

            // 2. 查询正方真实大纲附件 (KindEditor FTP)
            val syllabusRes = CourseCatalogService.fetchSyllabusInfo(ctx, courseCode)
            progressBar.visibility = View.GONE
            scrollContent.visibility = View.VISIBLE

            if (syllabusRes.isSuccess) {
                val (_, fileName) = syllabusRes.getOrNull()!!
                hasAttachment = true
                syllabusFileName = fileName
                cardAttachment.visibility = View.VISIBLE
                cardNoAttachment.visibility = View.GONE
                tvAttachmentFileName.text = fileName.ifEmpty { "${courseName}_官方教学大纲.pdf" }

                val isDocx = fileName.endsWith(".docx", ignoreCase = true) || fileName.endsWith(".doc", ignoreCase = true)
                if (isDocx) {
                    badgeOfficialTag.text = "Word 原件"
                } else {
                    badgeOfficialTag.text = "PDF 原件"
                }
                btnDownload.text = "📥 下载官方大纲原件 (${if (isDocx) "DOCX" else "PDF"})"
            } else {
                hasAttachment = false
                cardAttachment.visibility = View.GONE
                cardNoAttachment.visibility = View.VISIBLE
                btnDownload.text = "📥 导出课程基本规范到手机"
            }
        }

        // Setup Download and Open click handlers
        btnQuickOpen.setOnClickListener {
            downloadAndOpen(openAfterDownload = true)
        }

        btnDownload.setOnClickListener {
            downloadAndOpen(openAfterDownload = true)
        }
    }

    private fun downloadAndOpen(openAfterDownload: Boolean) {
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val btnDownload = view?.findViewById<MaterialButton>(R.id.btnDownloadSyllabus)
            val btnQuickOpen = view?.findViewById<MaterialButton>(R.id.btnQuickOpen)
            btnDownload?.isEnabled = false
            btnQuickOpen?.isEnabled = false
            val originalText = btnDownload?.text ?: ""
            btnDownload?.text = "⏳ 正在下载官方原件..."
            btnQuickOpen?.text = "⏳ 正在下载中..."

            try {
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "USST_CourseSyllabus"
                )
                if (!downloadDir.exists()) downloadDir.mkdirs()

                val result = CourseCatalogService.downloadSyllabusFile(ctx, courseCode, courseName, downloadDir)
                if (result.isSuccess) {
                    val file = result.getOrNull()!!
                    Toast.makeText(ctx, "大纲已成功保存至:\n${file.name}", Toast.LENGTH_LONG).show()

                    if (openAfterDownload) {
                        try {
                            val uri: Uri = FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                file
                            )
                            val mimeType = when {
                                file.name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                                file.name.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                file.name.endsWith(".doc", ignoreCase = true) -> "application/msword"
                                else -> "text/html"
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "打开课程大纲"))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "文件保存在: ${file.path}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(ctx, "下载大纲失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "保存异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                btnDownload?.isEnabled = true
                btnQuickOpen?.isEnabled = true
                btnDownload?.text = originalText
                btnQuickOpen?.text = "📖 立即下载并在手机中打开"
            }
        }
    }
}
