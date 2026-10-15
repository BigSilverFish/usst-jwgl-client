package cn.edu.usst.jwgl.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.LabReservationWorkbookImporter
import cn.edu.usst.jwgl.data.local.LabReservationFileImporter
import cn.edu.usst.jwgl.data.local.XlsxCleaner
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.databinding.ActivityExcelCleanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ExcelCleanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExcelCleanBinding
    private var report: XlsxCleaner.Report? = null
    private var importPreview: LabReservationWorkbookImporter.ImportPreview? = null
    private var selectedName = "workbook.xlsx"

    private data class Inspection(
        val report: XlsxCleaner.Report?,
        val preview: LabReservationWorkbookImporter.ImportPreview,
        val description: String
    )

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectFile(uri)
    }

    private val createOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri ->
        if (uri != null) saveCopy(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExcelCleanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, binding.root).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnChooseFile.setOnClickListener {
            openFile.launch(arrayOf(XLSX_MIME, XLS_MIME, "text/csv", "text/html", "application/zip", "application/octet-stream"))
        }
        binding.btnSaveCopy.setOnClickListener {
            createOutput.launch(cleanedName(selectedName))
        }
        binding.btnImport.setOnClickListener { confirmImport() }
    }

    private fun inspectFile(uri: Uri) {
        report?.cleanedFile?.delete()
        report = null
        importPreview = null
        selectedName = queryDisplayName(uri).ifBlank { "workbook.xlsx" }
        binding.tvFileName.text = selectedName
        binding.tvResult.text = "正在检查并读取预约数据..."
        binding.btnChooseFile.isEnabled = false
        binding.btnSaveCopy.isEnabled = false
        binding.btnImport.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use(::readBounded)
                        ?: throw IOException("无法读取所选文件")
                    inspectBytes(bytes)
                }
            }
            binding.progressBar.visibility = View.GONE
            binding.btnChooseFile.isEnabled = true
            result.onSuccess { inspected ->
                report = inspected.report
                importPreview = inspected.preview
                binding.btnImport.isEnabled = true
                binding.btnSaveCopy.isEnabled = inspected.report != null
                binding.tvResult.text = buildString {
                    appendLine("已识别 ${inspected.preview.reservations.size} 条预约，可导入默认课表")
                    appendLine(inspected.description)
                    if (inspected.preview.skippedBlankRows > 0) appendLine("已忽略 ${inspected.preview.skippedBlankRows} 行空白数据")
                    if (inspected.preview.skippedDuplicateRows > 0) appendLine("已合并 ${inspected.preview.skippedDuplicateRows} 条重复预约")
                    append("等待确认导入")
                }
            }.onFailure { error ->
                binding.tvResult.text = error.message ?: "文件检查失败"
            }
        }
    }

    private fun inspectBytes(bytes: ByteArray): Inspection {
        val db = AppDatabase.getDatabase(applicationContext)
        val table = db.tableDao.getDefaultTable()
        val inspected = LabReservationFileImporter.inspect(
            bytes.inputStream(), cacheDir, table.startDate, table.maxWeek, table.nodes,
            db.timeDetailDao.getTimeDetails(table.timeTable)
        )
        val description = inspected.report?.let { checked ->
            buildString {
                append(if (checked.removedUtf8Bom) "已修复文件头 BOM，工作簿校验通过" else "工作簿校验通过")
                append("，包含 ${checked.worksheetCount} 张工作表")
            }
        } ?: "已按旧版 Excel/CSV 导出格式读取，并在导入前完成格式清洗"
        return Inspection(inspected.report, inspected.preview, description)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_INPUT_BYTES) throw IOException("文件超过 30 MB 限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun confirmImport() {
        val preview = importPreview ?: return
        val sample = preview.reservations.take(8).joinToString("\n") { item ->
            val dayName = "一二三四五六日".getOrNull(item.day - 1) ?: '?'
            "第${item.week}周 周$dayName ${item.startNode}-${item.startNode + item.step - 1}节 · ${item.name} · ${item.room}"
        }
        val more = preview.reservations.size - 8
        val message = buildString {
            append("共 ${preview.reservations.size} 条预约，将替换本应用内已导入的实验预约，不影响普通课程。")
            appendLine()
            appendLine()
            append(sample)
            if (more > 0) appendLine("\n另有 $more 条预约")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入预约到默认课表")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ -> importReservations(preview) }
            .show()
    }

    private fun importReservations(preview: LabReservationWorkbookImporter.ImportPreview) {
        setActionsEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val table = db.tableDao.getDefaultTable()
                    db.replaceAllLabReservations(table.id, preview.reservations)
                }
            }
            binding.progressBar.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(this@ExcelCleanActivity, "已导入 ${preview.reservations.size} 条预约", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                setActionsEnabled(true)
                binding.tvResult.text = error.message ?: "导入失败，课表未更新"
            }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnChooseFile.isEnabled = enabled
        binding.btnImport.isEnabled = enabled && importPreview != null
        binding.btnSaveCopy.isEnabled = enabled && report != null
    }

    private fun saveCopy(uri: Uri) {
        val cleaned = report?.cleanedFile ?: return
        setActionsEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("无法创建目标文件")
                    output.use { target -> cleaned.inputStream().use { it.copyTo(target) } }
                }
            }
            binding.progressBar.visibility = View.GONE
            setActionsEnabled(true)
            result.onSuccess {
                Toast.makeText(this@ExcelCleanActivity, "修复副本已保存", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@ExcelCleanActivity, it.message ?: "保存失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()

    private fun cleanedName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "workbook" }
        return "${base}_repaired.xlsx"
    }

    override fun onDestroy() {
        report?.cleanedFile?.delete()
        super.onDestroy()
    }

    companion object {
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val XLS_MIME = "application/vnd.ms-excel"
        private const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    }
}
