package cn.edu.usst.jwgl.util

import android.util.Log
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.model.StudentRankingInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 解析 USST 学生排名证明 PDF 文件的轻量级高性能解析器
 * 原理：下载排名证明 PDF -> 解压内容流与 CMap -> 还原中英文字符 -> 正则提取排名/总人数/专业/百分比 -> 返回结构化数据
 */
object RankingPdfParser {

    private const val TAG = "RankingPdfParser"

    fun parse(file: File, profile: StudentProfile? = null): Result<StudentRankingInfo> {
        return try {
            if (!file.exists() || file.length() == 0L) {
                return Result.failure(IllegalArgumentException("PDF 文件不存在或为空"))
            }

            val bytes = file.readBytes()

            // 1. 优先检查内嵌元数据标记 (例如 DocumentPdfGenerator 生成的 %USST_RANK:...)
            val metadataInfo = parseMetadataTag(bytes, profile)
            if (metadataInfo != null && metadataInfo.rank > 0 && metadataInfo.total > 0) {
                Log.d(TAG, "Successfully parsed ranking info from metadata tag: $metadataInfo")
                return Result.success(metadataInfo)
            }

            // 2. 高性能线性解压并提取 PDF 纯文本 (支持 FlateDecode + CMap 2字节 CID 映射)
            val extractedText = extractTextFromPdf(bytes)
            Log.d(TAG, "Extracted text from PDF (length=${extractedText.length}):\n${extractedText.take(500)}")

            // 3. 正则解析排名、总人数、专业与年级 (中英文双模适配)
            val parsedInfo = parseTextContent(extractedText, profile)
            if (parsedInfo.rank > 0 && parsedInfo.total > 0) {
                Log.d(TAG, "Successfully parsed ranking info from PDF content: $parsedInfo")
                Result.success(parsedInfo)
            } else {
                Result.failure(IllegalStateException("未能从排名证明 PDF 中解析出有效的排名与总人数数据"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ranking PDF", e)
            Result.failure(e)
        }
    }

    /**
     * 检查并提取 PDF 注释元数据标记
     */
    private fun parseMetadataTag(bytes: ByteArray, profile: StudentProfile?): StudentRankingInfo? {
        val rawContent = String(bytes, Charsets.UTF_8)
        val metaRegex = Regex("""%USST_RANK:major=(.*?);grade=(.*?);rank=(\d+);total=(\d+)(?:;percentile=(\d+))?%""")
        val match = metaRegex.find(rawContent) ?: return null

        val major = match.groupValues[1].ifEmpty { profile?.major ?: "计算机科学与技术" }
        val grade = match.groupValues[2].ifEmpty { profile?.grade ?: "2024" }
        val rank = match.groupValues[3].toIntOrNull() ?: 0
        val total = match.groupValues[4].toIntOrNull() ?: 0
        val percentileRaw = match.groupValues.getOrNull(5)?.toIntOrNull()
        val percentile = if (percentileRaw != null && percentileRaw > 0) {
            percentileRaw
        } else if (total > 0) {
            Math.round(rank.toDouble() / total.toDouble() * 100).toInt()
        } else 0

        return StudentRankingInfo(
            major = major,
            grade = grade,
            rank = rank,
            total = total,
            percentile = percentile
        )
    }

    /**
     * 高性能提取 PDF 纯文本
     * 避免大文件正则回溯，通过线性字节扫描提取所有 stream，解压 CMap 与文本内容流
     */
    fun extractTextFromPdf(bytes: ByteArray): String {
        val streams = scanPdfStreams(bytes)
        val allCMaps = mutableMapOf<Int, Char>()
        val textStreams = mutableListOf<ByteArray>()

        // 阶段 1：解压流并收集所有 CMap (CID -> Unicode 映射)
        for (rawStream in streams) {
            val decompressed = decompressFlate(rawStream)
            val targetBytes = if (decompressed.isNotEmpty()) decompressed else rawStream

            // 检查是否包含 CMap
            if (containsAsciiSequence(targetBytes, "beginbfrange") || containsAsciiSequence(targetBytes, "beginbfchar")) {
                parseCMapStream(targetBytes, allCMaps)
            }

            // 检查是否为文本内容流 (包含 BT ... ET)
            if (containsAsciiSequence(targetBytes, "BT") && containsAsciiSequence(targetBytes, "ET")) {
                textStreams.add(targetBytes)
            }
        }

        // 阶段 2：解析文本内容流中的字符字面量与 CIDs
        val sb = StringBuilder()
        for (stream in textStreams) {
            val streamText = decodeContentStream(stream, allCMaps)
            if (streamText.isNotBlank()) {
                sb.append(streamText).append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * 线性字节扫描获取所有 stream ... endstream 区间
     */
    private fun scanPdfStreams(data: ByteArray): List<ByteArray> {
        val streams = mutableListOf<ByteArray>()
        val streamKeyword = "stream".toByteArray(Charsets.US_ASCII)
        val endKeyword = "endstream".toByteArray(Charsets.US_ASCII)

        var i = 0
        val limit = data.size - 6
        while (i < limit) {
            // 匹配 "stream"
            var matched = true
            for (k in streamKeyword.indices) {
                if (data[i + k] != streamKeyword[k]) {
                    matched = false
                    break
                }
            }
            if (!matched) {
                i++
                continue
            }

            var start = i + 6
            if (start < data.size && data[start] == '\r'.code.toByte()) start++
            if (start < data.size && data[start] == '\n'.code.toByte()) start++

            // 匹配 "endstream"
            var end = start
            val endLimit = data.size - 9
            var foundEnd = false
            while (end < endLimit) {
                if (data[end] == 'e'.code.toByte()) {
                    var endMatched = true
                    for (k in endKeyword.indices) {
                        if (data[end + k] != endKeyword[k]) {
                            endMatched = false
                            break
                        }
                    }
                    if (endMatched) {
                        foundEnd = true
                        break
                    }
                }
                end++
            }

            if (!foundEnd) break

            val len = end - start
            if (len > 0) {
                val streamBytes = ByteArray(len)
                System.arraycopy(data, start, streamBytes, 0, len)
                streams.add(streamBytes)
            }

            i = end + 9
        }

        return streams
    }

    /**
     * 解压 zlib / FlateDecode 数据流
     */
    private fun decompressFlate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)

        // 尝试标准 zlib 解压
        try {
            InflaterInputStream(ByteArrayInputStream(data), Inflater(false)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    out.write(buffer, 0, len)
                }
                val res = out.toByteArray()
                if (res.isNotEmpty()) return res
            }
        } catch (_: Exception) {}

        // 尝试 raw deflate 解压
        try {
            InflaterInputStream(ByteArrayInputStream(data), Inflater(true)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    out.write(buffer, 0, len)
                }
                val res = out.toByteArray()
                if (res.isNotEmpty()) return res
            }
        } catch (_: Exception) {}

        return ByteArray(0)
    }

    /**
     * 解析 ToUnicode CMap 流中的 beginbfrange 与 beginbfchar
     * 必须严格在对应的块内匹配，杜绝跨块误匹配导致字符被错误覆盖
     */
    private fun parseCMapStream(bytes: ByteArray, cMap: MutableMap<Int, Char>) {
        val s = String(bytes, Charsets.ISO_8859_1)

        // 1. 严格仅在 beginbfrange ... endbfrange 块内提取范围映射
        val rangeBlockPattern = Pattern.compile("beginbfrange([\\s\\S]*?)endbfrange")
        val rangeBlockMatcher = rangeBlockPattern.matcher(s)
        while (rangeBlockMatcher.find()) {
            val block = rangeBlockMatcher.group(1) ?: continue
            val rangePattern = Pattern.compile("<([0-9a-fA-F]+)>\\s*<([0-9a-fA-F]+)>\\s*<([0-9a-fA-F]+)>")
            val rangeMatcher = rangePattern.matcher(block)
            while (rangeMatcher.find()) {
                val startHex = rangeMatcher.group(1) ?: continue
                val endHex = rangeMatcher.group(2) ?: continue
                val destHex = rangeMatcher.group(3) ?: continue

                val start = startHex.toIntOrNull(16) ?: continue
                val end = endHex.toIntOrNull(16) ?: continue
                val dest = destHex.toIntOrNull(16) ?: continue

                for (k in 0..(end - start)) {
                    cMap[start + k] = (dest + k).toChar()
                }
            }
        }

        // 2. 严格仅在 beginbfchar ... endbfchar 块内提取单字符映射
        val charBlockPattern = Pattern.compile("beginbfchar([\\s\\S]*?)endbfchar")
        val charBlockMatcher = charBlockPattern.matcher(s)
        while (charBlockMatcher.find()) {
            val block = charBlockMatcher.group(1) ?: continue
            val charPattern = Pattern.compile("<([0-9a-fA-F]+)>\\s*<([0-9a-fA-F]+)>")
            val charMatcher = charPattern.matcher(block)
            while (charMatcher.find()) {
                val srcHex = charMatcher.group(1) ?: continue
                val destHex = charMatcher.group(2) ?: continue

                val src = srcHex.toIntOrNull(16) ?: continue
                val dest = destHex.toIntOrNull(16) ?: continue
                cMap[src] = dest.toChar()
            }
        }
    }

    /**
     * 解析内容流中括号 () 内的字符串或 CID 序列
     */
    private fun decodeContentStream(stream: ByteArray, cMap: Map<Int, Char>): String {
        val sb = StringBuilder()
        var idx = 0

        while (idx < stream.size) {
            if (stream[idx] == '('.code.toByte()) {
                idx++
                val tokenBytes = mutableListOf<Byte>()

                while (idx < stream.size && stream[idx] != ')'.code.toByte()) {
                    if (stream[idx] == '\\'.code.toByte()) {
                        idx++
                        if (idx >= stream.size) break
                        val c = stream[idx]
                        if (c in '0'.code.toByte()..'7'.code.toByte()) {
                            var oct = "" + c.toInt().toChar()
                            if (idx + 1 < stream.size && stream[idx + 1] in '0'.code.toByte()..'7'.code.toByte()) {
                                idx++
                                oct += stream[idx].toInt().toChar()
                                if (idx + 1 < stream.size && stream[idx + 1] in '0'.code.toByte()..'7'.code.toByte()) {
                                    idx++
                                    oct += stream[idx].toInt().toChar()
                                }
                            }
                            tokenBytes.add((oct.toIntOrNull(8) ?: 0).toByte())
                        } else {
                            when (c) {
                                'n'.code.toByte() -> tokenBytes.add('\n'.code.toByte())
                                'r'.code.toByte() -> tokenBytes.add('\r'.code.toByte())
                                't'.code.toByte() -> tokenBytes.add('\t'.code.toByte())
                                'b'.code.toByte() -> tokenBytes.add(8.toByte())
                                'f'.code.toByte() -> tokenBytes.add(12.toByte())
                                else -> tokenBytes.add(c)
                            }
                        }
                    } else {
                        tokenBytes.add(stream[idx])
                    }
                    idx++
                }
                idx++ // 跳过 ')'

                // 检查是否为 2 字节 CID 映射
                if (cMap.isNotEmpty() && tokenBytes.size >= 2) {
                    var k = 0
                    var matchedCidCount = 0
                    while (k + 1 < tokenBytes.size) {
                        val cid = ((tokenBytes[k].toInt() and 0xFF) shl 8) or (tokenBytes[k + 1].toInt() and 0xFF)
                        if (cMap.containsKey(cid)) {
                            matchedCidCount++
                        }
                        k += 2
                    }

                    // 若 2 字节 CID 匹配率较高，则按 CID 解码
                    if (matchedCidCount > 0) {
                        var m = 0
                        while (m + 1 < tokenBytes.size) {
                            val cid = ((tokenBytes[m].toInt() and 0xFF) shl 8) or (tokenBytes[m + 1].toInt() and 0xFF)
                            if (cMap.containsKey(cid)) {
                                sb.append(cMap[cid])
                            }
                            m += 2
                        }
                        sb.append(" ")
                        continue
                    }
                }

                // 否则按普通单字节 ASCII / UTF-8 / GBK 字符串解码 (英文文档或直接文本)
                val rawArr = tokenBytes.toByteArray()
                val utf8Str = String(rawArr, Charsets.UTF_8)
                if (!utf8Str.contains("\uFFFD")) {
                    sb.append(utf8Str).append(" ")
                } else {
                    sb.append(String(rawArr, Charsets.ISO_8859_1)).append(" ")
                }
            } else {
                idx++
            }
        }

        return sb.toString()
    }

    /**
     * 高速检查字节数组中是否包含某 ASCII 子串
     */
    private fun containsAsciiSequence(data: ByteArray, target: String): Boolean {
        val targetBytes = target.toByteArray(Charsets.US_ASCII)
        if (targetBytes.isEmpty() || data.size < targetBytes.size) return false

        val limit = data.size - targetBytes.size
        for (i in 0..limit) {
            var match = true
            for (j in targetBytes.indices) {
                if (data[i + j] != targetBytes[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    /**
     * 正则匹配并提取结构化排名数据 (全面支持中英文正方教务排名证明格式)
     */
    fun parseTextContent(text: String, profile: StudentProfile?): StudentRankingInfo {
        var rank = 0
        var total = 0
        var percentile = 0
        var major = profile?.major?.trim() ?: ""
        var grade = profile?.grade?.trim() ?: ""

        // 清理多余空格但保留基本标点与换行
        val normalized = text.replace(Regex("[\\t\\r]+"), " ")

        // 1. 中文官方证明标准句式匹配
        // 句式 1 (USST 官方原版): "该生在该专业现排名为第 82 名，该专业共有学生 97 名。"
        val patternOfficial = Regex("""现排名为第\s*(\d+)\s*名[，,].*?共有学生\s*(\d+)\s*名""")
        patternOfficial.find(normalized)?.let {
            rank = it.groupValues[1].toIntOrNull() ?: 0
            total = it.groupValues[2].toIntOrNull() ?: 0
        }

        // 句式 2: "在 计算机科学与技术 专业 2024 级共 97 人中，综合成绩排名第 82 名"
        if (rank == 0 || total == 0) {
            val patternA = Regex("""共\s*(\d+)\s*人[^\d]*?第\s*(\d+)\s*名""")
            patternA.find(normalized)?.let {
                total = it.groupValues[1].toIntOrNull() ?: 0
                rank = it.groupValues[2].toIntOrNull() ?: 0
            }
        }

        // 句式 3: "第 82 名，共 97 人" 或 "第 82 名/共 97 人"
        if (rank == 0 || total == 0) {
            val patternB = Regex("""第\s*(\d+)\s*名[^\d]*?共\s*(\d+)\s*人""")
            patternB.find(normalized)?.let {
                rank = it.groupValues[1].toIntOrNull() ?: 0
                total = it.groupValues[2].toIntOrNull() ?: 0
            }
        }

        // 句式 4: "排名第 82 名，共 97 人"
        if (rank == 0 || total == 0) {
            val patternC = Regex("""排名[^\d]*?第?\s*(\d+)\s*名?[^\d]*?共?\s*(\d+)\s*人""")
            patternC.find(normalized)?.let {
                rank = it.groupValues[1].toIntOrNull() ?: 0
                total = it.groupValues[2].toIntOrNull() ?: 0
            }
        }

        // 句式 5 (英文证明模式): "Ranked 82 out of 97" 或 "Rank: 82 / 97" 或 "ranking is 82 of 97"
        if (rank == 0 || total == 0) {
            val patternEnglish = Regex("""(?i)(?:rank|ranked|ranking)\s*(?:is|:)?\s*(?:no\.?)?\s*(\d+)\s*(?:out of|of|\/)\s*(\d+)""")
            patternEnglish.find(normalized)?.let {
                rank = it.groupValues[1].toIntOrNull() ?: 0
                total = it.groupValues[2].toIntOrNull() ?: 0
            }
        }

        // 句式 6: "排名：82 / 97" (严格要求必须带有“排名”前缀或“名”后缀，杜绝误判任意小数或时间)
        if (rank == 0 || total == 0) {
            val patternD = Regex("""(?:排名[：:\s]+)(\d+)\s*[\/／]\s*(\d+)|(\d+)\s*[\/／]\s*(\d+)\s*名""")
            patternD.find(normalized)?.let {
                if (it.groupValues[1].isNotEmpty()) {
                    rank = it.groupValues[1].toIntOrNull() ?: 0
                    total = it.groupValues[2].toIntOrNull() ?: 0
                } else if (it.groupValues[3].isNotEmpty()) {
                    rank = it.groupValues[3].toIntOrNull() ?: 0
                    total = it.groupValues[4].toIntOrNull() ?: 0
                }
            }
        }

        // 句式 7: "在 97 人中排名第 82 名"
        if (rank == 0 || total == 0) {
            val patternG = Regex("""在[^\d]*?(\d+)\s*人中[^\d]*?第?\s*(\d+)\s*名""")
            patternG.find(normalized)?.let {
                total = it.groupValues[1].toIntOrNull() ?: 0
                rank = it.groupValues[2].toIntOrNull() ?: 0
            }
        }

        // 2. 提取专业与年级 (如 "2024级 ... 计算机科学与技术专业" 或 "计算机科学与技术 专业 2024 级")
        val gradeMajorPattern = Regex("""([0-9]{4})\s*级[^\w\u4e00-\u9fa5]*?([^\s，,（(]+?(?:技术|工程|科学|管理|设计|语言|经济|金融|法学|专业))""")
        gradeMajorPattern.find(normalized)?.let {
            if (grade.isEmpty()) grade = it.groupValues[1].trim()
            if (major.isEmpty()) major = it.groupValues[2].removeSuffix("专业").trim()
        }

        if (major.isEmpty() || grade.isEmpty()) {
            val majorGradePattern = Regex("""([^\s，,（(]+?(?:技术|工程|科学|管理|设计|语言|经济|金融|法学|专业))\s*(?:专业)?\s*([0-9]{4})\s*级""")
            majorGradePattern.find(normalized)?.let {
                if (major.isEmpty()) major = it.groupValues[1].removeSuffix("专业").trim()
                if (grade.isEmpty()) grade = it.groupValues[2].trim()
            }
        }

        // 3. 计算或提取百分比排位
        val percentilePattern = Regex("""百分比排位[为是：:\s]*(\d+)%?""")
        percentilePattern.find(normalized)?.let {
            percentile = it.groupValues[1].toIntOrNull() ?: 0
        }

        if (percentile == 0 && rank > 0 && total > 0) {
            percentile = Math.round((rank.toDouble() / total.toDouble()) * 100).toInt()
        }

        // 默认保底字段填充
        if (major.isEmpty()) {
            major = profile?.major?.ifEmpty { "计算机科学与技术" } ?: "计算机科学与技术"
        }
        if (grade.isEmpty()) {
            grade = profile?.grade?.ifEmpty { "2024" } ?: "2024"
        }

        return StudentRankingInfo(
            major = major,
            grade = grade,
            rank = rank,
            total = total,
            percentile = percentile
        )
    }
}
