package cn.edu.usst.jwgl.data.local

import org.w3c.dom.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

object XlsxCleaner {
    private const val MAX_INPUT_BYTES = 30L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024
    private const val MAX_ENTRIES = 2_000
    private const val MAX_XML_BYTES = 12L * 1024 * 1024
    private val sheetEntry = Regex("xl/worksheets/sheet\\d+\\.xml")
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    data class Report(
        val cleanedFile: File,
        val removedUtf8Bom: Boolean,
        val worksheetCount: Int,
        val entryCount: Int
    )

    @Throws(IOException::class)
    fun clean(input: InputStream, cacheDir: File): Report {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw IOException("无法创建临时目录")
        }

        val source = File.createTempFile("xlsx-source-", ".tmp", cacheDir)
        val output = File.createTempFile("xlsx-clean-", ".xlsx", cacheDir)
        try {
            input.use { copyBounded(it, FileOutputStream(source), MAX_INPUT_BYTES) }
            val hasBom = hasUtf8Bom(source)
            FileInputStream(source).use { sourceStream ->
                if (hasBom) sourceStream.skip(3)
                FileOutputStream(output).use { copyBounded(sourceStream, it, MAX_INPUT_BYTES) }
            }
            val validated = validate(output)
            return Report(output, hasBom, validated.worksheetCount, validated.entryCount)
        } catch (e: IOException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            output.delete()
            throw IOException("文件无法安全读取：${e.message ?: "未知错误"}", e)
        } finally {
            source.delete()
        }
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long) {
        output.use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("文件超过 30 MB 限制")
                out.write(buffer, 0, read)
            }
        }
    }

    private fun hasUtf8Bom(file: File): Boolean = FileInputStream(file).use {
        it.read() == 0xEF && it.read() == 0xBB && it.read() == 0xBF
    }

    private data class Validation(val worksheetCount: Int, val entryCount: Int)

    private fun validate(file: File): Validation {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw IOException("不是有效的 XLSX 压缩包，或文件已截断", e)
        }

        zip.use { archive ->
            val entries = archive.entries().toList()
            if (entries.isEmpty() || entries.size > MAX_ENTRIES) {
                throw IOException("工作簿条目数量异常")
            }

            val names = HashSet<String>(entries.size)
            val coreXml = HashMap<String, Document>()
            var uncompressed = 0L
            var xmlParts = 0
            for (entry in entries) {
                if (!names.add(entry.name)) throw IOException("压缩包包含重复条目")
                if (entry.isDirectory) continue
                if (entry.size > MAX_UNCOMPRESSED_BYTES) throw IOException("工作簿内部文件过大")

                val xml = entry.name.endsWith(".xml", ignoreCase = true) ||
                    entry.name.endsWith(".rels", ignoreCase = true)
                val bytes = if (xml) ByteArrayOutputStream() else null
                val crc = CRC32()
                var size = 0L
                archive.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        uncompressed += read
                        if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                            throw IOException("工作簿解压后超过 100 MB 限制")
                        }
                        crc.update(buffer, 0, read)
                        if (bytes != null) {
                            if (size > MAX_XML_BYTES) throw IOException("XML 部件过大")
                            bytes.write(buffer, 0, read)
                        }
                    }
                }
                if (size != entry.size || crc.value != entry.crc) {
                    throw IOException("压缩包条目校验失败：${entry.name}")
                }
                if (bytes != null) {
                    val document = WorkbookXml.parse(bytes.toByteArray(), entry.name)
                    if (entry.name in CORE_XML_PARTS) coreXml[entry.name] = document
                    xmlParts++
                }
            }

            val required = listOf(
                "[Content_Types].xml",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels"
            )
            if (!names.containsAll(required)) throw IOException("缺少 XLSX 工作簿必要部件")
            if (xmlParts == 0) throw IOException("工作簿中未找到有效工作表")
            val sheetCount = validateWorkbookReferences(names, coreXml)
            return Validation(sheetCount, entries.size)
        }
    }

    private fun validateWorkbookReferences(names: Set<String>, documents: Map<String, Document>): Int {
        val contentTypes = documents["[Content_Types].xml"]?.documentElement
        val workbook = documents["xl/workbook.xml"]?.documentElement
        val relationships = documents["xl/_rels/workbook.xml.rels"]?.documentElement
        if (contentTypes?.localName != "Types" || workbook?.localName != "workbook" || relationships?.localName != "Relationships") {
            throw IOException("XLSX 核心 XML 部件格式不正确")
        }

        val targetsById = HashMap<String, String>()
        val relationNodes = relationships.getElementsByTagNameNS(REL_NS, "Relationship")
        for (index in 0 until relationNodes.length) {
            val relation = relationNodes.item(index) as? org.w3c.dom.Element ?: continue
            if (relation.getAttribute("Type").endsWith("/worksheet") && relation.getAttribute("TargetMode") != "External") {
                targetsById[relation.getAttribute("Id")] = relation.getAttribute("Target")
            }
        }

        val sheetNodes = workbook.getElementsByTagNameNS(MAIN_NS, "sheet")
        if (sheetNodes.length == 0) throw IOException("工作簿未声明任何工作表")
        for (index in 0 until sheetNodes.length) {
            val sheet = sheetNodes.item(index) as? org.w3c.dom.Element
                ?: throw IOException("工作表声明格式不正确")
            val relationshipId = sheet.getAttributeNS(OFFICE_REL_NS, "id")
            val target = targetsById[relationshipId]
                ?: throw IOException("工作表引用缺少对应关系")
            val targetUri = try {
                URI(target)
            } catch (e: Exception) {
                throw IOException("工作表关系路径无效", e)
            }
            if (targetUri.isAbsolute || targetUri.rawAuthority != null || targetUri.rawQuery != null || targetUri.rawFragment != null) {
                throw IOException("工作表关系路径无效")
            }
            val partName = if (target.startsWith('/')) {
                target.drop(1)
            } else {
                URI("xl/workbook.xml").resolve(targetUri).normalize().path.trimStart('/')
            }
            if (!sheetEntry.matches(partName) || partName !in names) {
                throw IOException("找不到工作表部件：$partName")
            }
        }
        return sheetNodes.length
    }

    private val CORE_XML_PARTS = setOf(
        "[Content_Types].xml",
        "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels"
    )
}
