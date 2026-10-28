package cn.edu.usst.jwgl.ui.school

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.LayoutPageGradeReportsBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GradeReportsFragment : Fragment() {

    private var _binding: LayoutPageGradeReportsBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(): GradeReportsFragment = GradeReportsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageGradeReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.layoutGradeReports.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupNavigationButton()
        applyAdaptiveLayout()
        setupStudentProfileHeader()
        setupDocumentButtons()
        refreshDownloadedFilesList()
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.headerGradeReports.setPadding(
            binding.headerGradeReports.paddingLeft,
            statusTopPad,
            binding.headerGradeReports.paddingRight,
            (10 * density).toInt()
        )

        binding.llGradeReportsHeaderContainer.post {
            val b = _binding ?: return@post
            updateHeaderAndBottomSpacing(b.llGradeReportsHeaderContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val pad = statusBars.top + (8 * density).toInt()
            binding.headerGradeReports.setPadding(
                binding.headerGradeReports.paddingLeft,
                pad,
                binding.headerGradeReports.paddingRight,
                (10 * density).toInt()
            )
            binding.llGradeReportsHeaderContainer.post {
                val b = _binding ?: return@post
                updateHeaderAndBottomSpacing(b.llGradeReportsHeaderContainer.height)
            }
            insets
        }
    }

    private fun updateHeaderAndBottomSpacing(headerHeight: Int) {
        val b = _binding ?: return
        if (headerHeight <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeight + (6 * density).toInt()

        val mainAct = activity as? MainActivity
        val bHeight = MainActivity.bottomBarHeightPx
        val bottomPadding = if (mainAct != null && bHeight > 0) {
            bHeight + (16 * density).toInt()
        } else if (mainAct != null) {
            mainAct.getBottomInset() + (80 * density).toInt()
        } else {
            (24 * density).toInt()
        }

        b.scrollGradeReports.setPadding(
            b.scrollGradeReports.paddingLeft,
            topPad,
            b.scrollGradeReports.paddingRight,
            bottomPadding
        )
    }

    private fun setupBlurView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewGradeReports.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as ViewGroup)
                val windowBackground = decorView.background
                try {
                    b.blurViewGradeReports.setupWith(rootView, RenderEffectBlur())
                        .setFrameClearDrawable(windowBackground)
                        .setBlurRadius(20f)
                } catch (_: Exception) {}
            }
        }
    }

    private fun setupNavigationButton() {
        val mainAct = activity as? MainActivity
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())

        if (isPad) {
            binding.btnOpenDrawerGradeReports.visibility = View.GONE
        } else {
            binding.btnOpenDrawerGradeReports.visibility = View.VISIBLE
            val isBottomMode = mainAct?.isBottomBarModeActive() ?: true
            if (isBottomMode) {
                binding.btnOpenDrawerGradeReports.setImageResource(R.drawable.ic_arrow_back)
            } else {
                binding.btnOpenDrawerGradeReports.setImageResource(R.drawable.ic_menu)
            }
        }

        binding.btnOpenDrawerGradeReports.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            if (act.isGuestModeActive()) {
                act.finish()
            } else if (act.isBottomBarModeActive()) {
                act.switchTab(MainActivity.MainTab.MORE_FEATURES)
            } else {
                act.openNavDrawer()
            }
        }
    }

    private fun applyAdaptiveLayout() {
        val b = _binding ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        val density = resources.displayMetrics.density

        if (isPad) {
            b.llReportsBody.orientation = LinearLayout.HORIZONTAL
            b.llReportsLeftPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.54f
            ).apply {
                marginEnd = (16 * density).toInt()
            }
            b.llReportsRightPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.46f
            )
        } else {
            b.llReportsBody.orientation = LinearLayout.VERTICAL
            b.llReportsLeftPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 0
            }
            b.llReportsRightPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun setupStudentProfileHeader() {
        val context = requireContext()
        val auth = AuthPreferences(context)
        val sId = auth.getStudentId()
        val profile = DataCacheManager(context).getProfile()
        val sName = profile?.name?.ifEmpty { "同学" } ?: "同学"
        val sCollege = profile?.college ?: "上海理工大学"
        val sMajor = profile?.major ?: ""

        binding.tvReportsStudentName.text = "$sName · $sId"
        binding.tvReportsStudentAvatar.text = if (sName.isNotEmpty()) sName.take(1) else "理"
        binding.tvReportsStudentInfo.text = if (sMajor.isNotEmpty()) "$sCollege · $sMajor" else sCollege
    }

    private fun setupDocumentButtons() {
        binding.btnDownloadChineseWeighted.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.CHINESE_WEIGHTED_SCORE)
        }
        binding.btnDownloadEnglishWeighted.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.ENGLISH_WEIGHTED_SCORE)
        }
        binding.btnDownloadRanking.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.RANKING_CERTIFICATE)
        }
        binding.btnDownloadChineseTranscript.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.CHINESE_TRANSCRIPT)
        }
        binding.btnDownloadEnglishTranscript.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.ENGLISH_TRANSCRIPT)
        }
        binding.tvRefreshDownloadedFiles.setOnClickListener {
            refreshDownloadedFilesList()
        }
    }

    fun refreshDownloadedFilesList() {
        val b = _binding ?: return
        val context = context ?: return
        val targetDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val files = targetDir.listFiles { file -> file.isFile && file.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        val container = b.llDownloadedFilesContainer
        container.removeAllViews()

        if (files.isEmpty()) {
            b.tvNoDownloadedFiles.visibility = View.VISIBLE
        } else {
            b.tvNoDownloadedFiles.visibility = View.GONE
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val density = resources.displayMetrics.density

            for (file in files) {
                val card = MaterialCardView(context).apply {
                    radius = 12 * density
                    strokeColor = ContextCompat.getColor(context, R.color.border)
                    strokeWidth = (0.8 * density).toInt()
                    setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    layoutParams = lp
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = (12 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                val tvIcon = TextView(context).apply {
                    text = "📕"
                    textSize = 20f
                }
                row.addView(tvIcon)

                val textContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (10 * density).toInt()
                    }
                    layoutParams = lp
                }

                val tvName = TextView(context).apply {
                    text = file.name
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val tvDetails = TextView(context).apply {
                    text = "${String.format("%.1f", file.length() / 1024f)} KB · ${sdf.format(Date(file.lastModified()))}"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 11.5f
                }
                textContainer.addView(tvName)
                textContainer.addView(tvDetails)
                row.addView(textContainer)

                val btnOpen = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "打开"
                    textSize = 12f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    val lp = LinearLayout.LayoutParams((58 * density).toInt(), (32 * density).toInt())
                    layoutParams = lp
                    setOnClickListener { openPdfFile(file) }
                }
                val btnShare = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "分享"
                    textSize = 12f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    val lp = LinearLayout.LayoutParams((58 * density).toInt(), (32 * density).toInt()).apply {
                        marginStart = (6 * density).toInt()
                    }
                    layoutParams = lp
                    setOnClickListener { sharePdfFile(file) }
                }
                row.addView(btnOpen)
                row.addView(btnShare)

                card.addView(row)
                container.addView(card)
            }
        }
    }

    private fun downloadAndOpenDocument(docType: GradeDocumentType) {
        val context = context ?: return
        val progressDialog = MaterialAlertDialogBuilder(context)
            .setTitle("正在下载 ${docType.displayName}")
            .setMessage("正在向教务系统请求并生成 PDF 文件，请稍候...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val targetDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads").apply { mkdirs() }
                val targetFile = File(targetDir, docType.defaultFileName)
                val auth = AuthPreferences(context)
                val studentId = auth.getStudentId()
                cn.edu.usst.jwgl.util.CookieSyncHelper.syncWebViewCookiesToJwgl()
                val result = JwglClient.downloadGradeDocument(context, docType, targetFile, studentId)
                progressDialog.dismiss()

                result.onSuccess { downloadedFile ->
                    showDownloadSuccessDialog(docType, downloadedFile)
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(context)
                        .setTitle("下载失败")
                        .setMessage("未能成功下载 ${docType.displayName}：${error.message ?: "网络异常"}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(context, "下载异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDownloadSuccessDialog(docType: GradeDocumentType, file: File) {
        val context = context ?: return
        refreshDownloadedFilesList()
        MaterialAlertDialogBuilder(context)
            .setTitle("下载完成")
            .setMessage("${docType.displayName} 已生成并成功保存！\n\n文件路径: ${file.name}\n大小: ${file.length() / 1024} KB")
            .setPositiveButton("立即打开") { _, _ ->
                openPdfFile(file)
            }
            .setNeutralButton("分享文件") { _, _ ->
                sharePdfFile(file)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openPdfFile(file: File) {
        val context = context ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "打开 PDF 文件"))
        } catch (e: Exception) {
            Toast.makeText(context, "打开 PDF 失败: ${e.message}，请安装支持 PDF 的阅读器应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdfFile(file: File) {
        val context = context ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享 PDF 文件"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享 PDF 失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveLayout()
        setupNavigationButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
