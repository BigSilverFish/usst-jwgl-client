package cn.edu.usst.jwgl.ui.tronclass

import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TronClassHtmlHelper {

    private const val TRONCLASS_BASE = "https://1906.usst.edu.cn"

    /**
     * 从富文本 HTML 中提取所有的图片直连 URL 列表
     */
    fun extractImageUrls(html: String?): List<String> {
        if (html.isNullOrBlank()) return emptyList()
        return try {
            val doc = Jsoup.parse(html)
            val imgElements = doc.select("img")
            imgElements.mapNotNull { img ->
                var src = img.attr("src").trim()
                if (src.isEmpty()) {
                    src = img.attr("data-src").trim()
                }
                if (src.isEmpty()) return@mapNotNull null

                if (src.startsWith("/")) {
                    "$TRONCLASS_BASE$src"
                } else if (!src.startsWith("http://") && !src.startsWith("https://")) {
                    "$TRONCLASS_BASE/$src"
                } else {
                    src
                }
            }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 清理 HTML 获得纯文本摘要，剔除 img 标签、空行及特殊替换符号 \uFFFC
     */
    fun getCleanText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return try {
            val doc = Jsoup.parse(html)
            // 移除所有 img 标签
            doc.select("img").remove()
            // 将 br 和 p 转换为标准换行
            doc.select("br").append("\\n")
            doc.select("p").prepend("\\n\\n")

            val rawText = doc.text().replace("\\n", "\n")
            // 剔除 Object Replacement Character \uFFFC 及其他不可见字符
            val clean = rawText
                .replace("\uFFFC", "")
                .replace("\u00A0", " ") // non-breaking space
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
            clean
        } catch (e: Exception) {
            html.replace(Regex("<[^>]*>"), "").replace("\uFFFC", "").trim()
        }
    }

    /**
     * 清理 HTML 并在深色主题下正确展示：
     * 1. 移除 <img> 标签（避免原生 TextView 显示异常绿块，图片单独提取为原生控件加载）；
     * 2. 清除所有 style 中的 color 属性（如 color: black, color: #000000），使文字颜色自动跟随 TextView 的 text_primary（高亮浅色）；
     * 3. 清除所有 background, background-color, font-family 等强制内联样式；
     * 4. 移除 font[color] 属性；
     * 5. 过滤 \uFFFC。
     */
    fun cleanHtmlForDarkTheme(rawHtml: String?): String {
        if (rawHtml.isNullOrBlank()) return ""
        return try {
            val doc = Jsoup.parse(rawHtml)
            doc.select("img").remove()
            doc.select("[style]").forEach { el ->
                var style = el.attr("style")
                style = style.replace(Regex("""(?i)color\s*:\s*[^;]+;?"""), "")
                style = style.replace(Regex("""(?i)background(-color)?\s*:\s*[^;]+;?"""), "")
                style = style.replace(Regex("""(?i)font-family\s*:\s*[^;]+;?"""), "")
                if (style.isBlank()) {
                    el.removeAttr("style")
                } else {
                    el.attr("style", style.trim())
                }
            }
            doc.select("font[color]").removeAttr("color")
            val html = doc.body().html()
            html.replace("\uFFFC", "").trim()
        } catch (e: Exception) {
            rawHtml.replace(Regex("""(?i)color\s*:\s*[^;"]+;?"""), "").replace("\uFFFC", "").trim()
        }
    }

    /**
     * 包装生成适合夜间深色主题的高清响应式 HTML5 页面，用于 WebView 渲染（向后兼容）
     */
    fun buildDarkHtmlDocument(title: String, rawHtml: String?): String {
        return buildThemedHtmlDocument(title, rawHtml, isDark = true)
    }

    /**
     * 包装生成自适应当前深色/浅色主题的高清响应式 HTML5 页面，用于 WebView 渲染
     */
    fun buildThemedHtmlDocument(title: String, rawHtml: String?, isDark: Boolean): String {
        val safeContent = rawHtml ?: "<p>暂无内容</p>"

        // 颜色参数根据浅色/深色模式自适应
        val bgColor = if (isDark) "#1B1C20" else "#FFFFFF"
        val textColor = if (isDark) "#F1F5F9" else "#1D2939"
        val linkColor = if (isDark) "#60A5FA" else "#1E40AF"
        val imgBg = if (isDark) "#24262C" else "#F8F9FA"
        val imgShadow = if (isDark) "rgba(0, 0, 0, 0.45)" else "rgba(0, 0, 0, 0.08)"
        val tableBorder = if (isDark) "#2E333D" else "#E4E7EC"
        val tableCellBg = if (isDark) "#24262C" else "#FFFFFF"
        val tableHeaderBg = if (isDark) "#1B1C20" else "#F8F9FA"
        val tableHeaderColor = if (isDark) "#93C5FD" else "#1D2939"
        val quoteBorder = if (isDark) "#FFB4AB" else "#8C1D27"
        val quoteBg = if (isDark) "#24262C" else "#F8F9FA"
        val quoteColor = if (isDark) "#CBD5E1" else "#475467"

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    * {
                        box-sizing: border-box;
                        -webkit-tap-highlight-color: transparent;
                    }
                    body {
                        background-color: $bgColor;
                        color: $textColor;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                        font-size: 15px;
                        line-height: 1.7;
                        padding: 16px;
                        margin: 0;
                        word-break: break-word;
                    }
                    p, div, span {
                        margin-top: 0;
                        margin-bottom: 12px;
                        color: inherit !important;
                        background-color: transparent !important;
                        font-family: inherit !important;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 12px auto;
                        border-radius: 10px;
                        box-shadow: 0 4px 14px $imgShadow;
                        background-color: $imgBg;
                        cursor: pointer;
                        transition: transform 0.15s ease;
                    }
                    img:active {
                        transform: scale(0.98);
                        opacity: 0.9;
                    }
                    a {
                        color: $linkColor;
                        text-decoration: underline;
                    }
                    table {
                        width: 100% !important;
                        border-collapse: collapse;
                        margin: 14px 0;
                        border-radius: 8px;
                        overflow: hidden;
                    }
                    th, td {
                        border: 1px solid $tableBorder;
                        padding: 10px 12px;
                        text-align: left;
                        background-color: $tableCellBg;
                        color: inherit;
                    }
                    th {
                        background-color: $tableHeaderBg;
                        color: $tableHeaderColor;
                        font-weight: 600;
                    }
                    blockquote {
                        border-left: 4px solid $quoteBorder;
                        margin: 12px 0;
                        padding: 8px 16px;
                        background-color: $quoteBg;
                        color: $quoteColor;
                        border-radius: 0 8px 8px 0;
                    }
                </style>
                <script>
                    function onImageClick(url) {
                        if (window.AndroidBridge && window.AndroidBridge.openImage) {
                            window.AndroidBridge.openImage(url);
                        }
                    }
                    document.addEventListener("DOMContentLoaded", function() {
                        var imgs = document.querySelectorAll("img");
                        for (var i = 0; i < imgs.length; i++) {
                            imgs[i].onclick = function() {
                                onImageClick(this.src);
                            };
                        }
                    });
                </script>
            </head>
            <body>
                $safeContent
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * 格式化 ISO 8601 时间字符串
     */
    fun formatIsoDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = inFormat.parse(iso)
            if (date != null) {
                val outFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                outFormat.format(date)
            } else {
                iso.substringBefore("T")
            }
        } catch (e: Exception) {
            try {
                iso.substringBefore("T")
            } catch (ex: Exception) {
                ""
            }
        }
    }
}
