package cn.edu.usst.jwgl.ui.pdf

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * PDF 阅读器标题与文件名解析工具类
 */
object PdfViewerUtils {

    private val GENERIC_TITLES = setOf(
        "上理教务",
        "文档查看",
        "pdf 阅览",
        "pdf阅览",
        "pdf 阅读器",
        "pdf阅读器",
        "未命名文档",
        "null",
        "application/pdf"
    )

    private val TEMP_PREFIX_REGEX = Regex("""^temp_\d+_(.+)$""")

    /**
     * 判断当前标题是否属于通用占位符，若属于则应被更具体的文件名替换
     */
    fun isGenericTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val trimmed = title.trim()
        if (GENERIC_TITLES.contains(trimmed.lowercase())) return true
        if (trimmed.startsWith("temp_", ignoreCase = true) && trimmed.endsWith(".pdf", ignoreCase = true)) {
            // 如 temp_17300000.pdf 没有真实名字
            return !TEMP_PREFIX_REGEX.matches(trimmed)
        }
        return false
    }

    /**
     * 清理并规范化文件名或标题
     */
    fun cleanFileName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var name = raw.trim()

        // 移除 URL query 与 hash
        if (name.contains("?")) {
            name = name.substringBefore("?")
        }
        if (name.contains("#")) {
            name = name.substringBefore("#")
        }

        // 提取文件名（去除路径，同时兼顾 Unix / 与 Windows \）
        if (name.contains("/")) {
            name = name.substringAfterLast("/")
        }
        if (name.contains("\\")) {
            name = name.substringAfterLast("\\")
        }

        // URL 解码
        if (name.contains("%")) {
            name = try {
                URLDecoder.decode(name, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                name
            }
        }

        // 剥离应用下载生成的 temp_1730000000_ 前缀
        val match = TEMP_PREFIX_REGEX.find(name)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank()) {
                name = candidate
            }
        }

        return name.trim()
    }

    /**
     * 从 HTTP Content-Disposition 响应头提取文件名
     */
    fun parseContentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null

        // 1. 优先匹配 RFC 5987 / RFC 6266: filename*=UTF-8''...
        val rfcMatch = Regex("""filename\*\s*=\s*(?:[^'']+''|UTF-8''|utf-8'')([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (rfcMatch != null) {
            val encodedValue = rfcMatch.groupValues[1].trim().trim('"', '\'')
            val decoded = try {
                URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                encodedValue
            }
            val cleaned = cleanFileName(decoded)
            if (cleaned.isNotBlank()) return cleaned
        }

        // 2. 匹配标准 filename="xxx" 或 filename=xxx
        val standardMatch = Regex("""filename\s*=\s*("([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE).find(header)
        if (standardMatch != null) {
            val rawName = standardMatch.groupValues[2].ifBlank { standardMatch.groupValues[3] }.trim().trim('"', '\'')
            val cleaned = cleanFileName(rawName)
            if (cleaned.isNotBlank()) return cleaned
        }

        return null
    }

    /**
     * 解析最终展示标题
     */
    fun resolveTitle(
        explicitTitle: String?,
        rawPath: String? = null,
        contentUriDisplayName: String? = null,
        contentDisposition: String? = null
    ): String {
        // 1. 显式传参且非通用占位符
        val cleanedExplicit = cleanFileName(explicitTitle)
        if (cleanedExplicit.isNotBlank() && !isGenericTitle(cleanedExplicit)) {
            return cleanedExplicit
        }

        // 2. Content-Disposition 响应头
        val headerFileName = parseContentDispositionFileName(contentDisposition)
        if (!headerFileName.isNullOrBlank() && !isGenericTitle(headerFileName)) {
            return headerFileName
        }

        // 3. content:// URI display_name
        val cleanedDisplay = cleanFileName(contentUriDisplayName)
        if (cleanedDisplay.isNotBlank() && !isGenericTitle(cleanedDisplay)) {
            return cleanedDisplay
        }

        // 4. 文件路径 / URL 本身
        val cleanedPath = cleanFileName(rawPath)
        if (cleanedPath.isNotBlank() && !isGenericTitle(cleanedPath)) {
            return cleanedPath
        }

        // 5. 兜底（如果显式标题也是通用占位符如“上理教务”，兜底必须返回“PDF 阅览”）
        return if (!isGenericTitle(cleanedExplicit)) cleanedExplicit else "PDF 阅览"
    }
}
