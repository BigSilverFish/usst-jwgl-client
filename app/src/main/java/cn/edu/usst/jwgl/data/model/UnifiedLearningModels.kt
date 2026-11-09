package cn.edu.usst.jwgl.data.model

import android.graphics.Color
import androidx.annotation.ColorInt
import cn.edu.usst.jwgl.ui.tronclass.TronClassHtmlHelper
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 教学平台枚举定义
 */
enum class LearningPlatform(
    val code: String,
    val displayName: String,
    val shortName: String,
    val brandColorHex: String,
    val badgeBgColorHex: String,
    val badgeTextColorHex: String
) : Serializable {
    TRONCLASS(
        code = "tronclass",
        displayName = "一网畅学",
        shortName = "畅课",
        brandColorHex = "#0D9488",     // Teal 600
        badgeBgColorHex = "#CCFBF1",   // Teal 50
        badgeTextColorHex = "#0F766E"  // Teal 700
    ),
    CHAOXING(
        code = "chaoxing",
        displayName = "超星学习通",
        shortName = "学习通",
        brandColorHex = "#EA580C",     // Orange 600
        badgeBgColorHex = "#FFEDD5",   // Orange 100
        badgeTextColorHex = "#C2410C"  // Orange 700
    ),
    DUAL(
        code = "dual",
        displayName = "双平台 (畅课 + 学习通)",
        shortName = "畅课+学习通",
        brandColorHex = "#7C3AED",     // Purple 600
        badgeBgColorHex = "#EDE9FE",   // Purple 100
        badgeTextColorHex = "#6D28D9"  // Purple 700
    );

    val isTronClass: Boolean get() = this == TRONCLASS || this == DUAL
    val isChaoXing: Boolean get() = this == CHAOXING || this == DUAL
    val isDual: Boolean get() = this == DUAL

    @ColorInt
    fun getBrandColor(): Int = Color.parseColor(brandColorHex)

    @ColorInt
    fun getBadgeBgColor(): Int = Color.parseColor(badgeBgColorHex)

    @ColorInt
    fun getBadgeTextColor(): Int = Color.parseColor(badgeTextColorHex)
}

/**
 * 双平台统一课程模型
 */
data class UnifiedCourse(
    val unifiedId: String,                   // 唯一ID: "tc_${id}" 或 "cx_${courseId}_${classId}"
    val platform: LearningPlatform,          // 平台来源
    val originalCourseId: String,            // 畅课 courseId / 超星 courseId
    val originalClassId: String? = null,     // 超星 classId
    val name: String,                        // 原始课程名称
    val cleanName: String,                   // 清洗后的纯净课程名
    val teacherName: String,                 // 授课教师 (单人或多教师合并)
    val coverUrl: String? = null,            // 课程封面图
    val courseCode: String? = null,          // 课程代码 / 教学班编号
    val department: String? = null,          // 开课学院 / 单位
    val semesterCode: String? = null,        // 学期代码 (如 2026-2027-1)
    val formattedSemesterName: String? = null,// 学期显示名 (如 2026-2027 第1学期)
    var uncompletedTodoCount: Int = 0,       // 未完成待办/作业计数
    val rawTronClassCourse: TronClassCourse? = null, // 畅课原始对象 (向后兼容)
    val rawChaoXingCourse: ChaoXingCourse? = null    // 超星原始对象
) : Serializable {

    fun getDisplayAvatarText(): String {
        return cleanName.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "课"
    }

    companion object {
        fun fromTronClass(
            course: TronClassCourse,
            uncompletedCount: Int = 0,
            customSemesterCode: String? = null
        ): UnifiedCourse {
            val clean = course.getCleanName()
            val teachers = if (course.instructors.isNotEmpty()) {
                course.instructors.joinToString(", ")
            } else {
                course.teacher ?: ""
            }
            val originalSem = course.extractSemesterCode()
            val effectiveSem = customSemesterCode?.ifBlank { null } ?: originalSem
            return UnifiedCourse(
                unifiedId = "tc_${course.id}",
                platform = LearningPlatform.TRONCLASS,
                originalCourseId = course.id.toString(),
                name = course.name,
                cleanName = clean,
                teacherName = teachers,
                coverUrl = course.cover,
                courseCode = course.courseCode,
                department = course.department?.name,
                semesterCode = effectiveSem,
                formattedSemesterName = effectiveSem?.let { formatSemesterName(it) } ?: course.getFormattedSemesterName(),
                uncompletedTodoCount = uncompletedCount,
                rawTronClassCourse = course
            )
        }

        fun formatSemesterName(code: String): String {
            val parts = code.split("-")
            return if (parts.size >= 3) {
                "${parts[0]}-${parts[1]} 第${parts[2]}学期"
            } else {
                code
            }
        }

        fun fromChaoXing(
            course: ChaoXingCourse,
            uncompletedCount: Int = 0,
            fallbackSemesterCode: String? = null,
            customSemesterCode: String? = null
        ): UnifiedCourse {
            val rawName = course.courseName.trim()
            val semFullMatch = Regex("""\(?([0-9]{4}-[0-9]{4}-[0-9]+)\)?""").find(rawName)
            val semShortMatch = Regex("""[\(（]([0-9]{2})-([0-9]{2})-([0-9]+)[\)）]""").find(rawName)
            val extractedSem = when {
                semFullMatch != null -> semFullMatch.groupValues[1]
                semShortMatch != null -> {
                    val y1 = semShortMatch.groupValues[1]
                    val y2 = semShortMatch.groupValues[2]
                    val term = semShortMatch.groupValues[3]
                    "20$y1-20$y2-$term"
                }
                else -> null
            }

            val clean = rawName
                .replace(Regex("""[\(（][0-9]{4}-[0-9]{4}-[0-9]+[\)）]"""), "")
                .replace(Regex("""[\(（][0-9]{2}-[0-9]{2}-[0-9]+[\)）]"""), "")
                .trim()

            val semCode = customSemesterCode?.ifBlank { null } ?: extractedSem ?: fallbackSemesterCode

            return UnifiedCourse(
                unifiedId = "cx_${course.courseId}_${course.classId}",
                platform = LearningPlatform.CHAOXING,
                originalCourseId = course.courseId,
                originalClassId = course.classId,
                name = rawName,
                cleanName = clean.ifBlank { "未知课程" },
                teacherName = course.teacherName,
                coverUrl = course.coverUrl,
                courseCode = course.courseCode.ifBlank { course.classId },
                department = course.schoolName,
                semesterCode = semCode,
                formattedSemesterName = semCode?.let { formatSemesterName(it) },
                uncompletedTodoCount = uncompletedCount,
                rawChaoXingCourse = course
            )
        }

        /**
         * 智能融合双平台课程列表，消灭同一课程分两个入口的冗余
         */
        fun fuseCourses(
            tcCourses: List<TronClassCourse>,
            cxCourses: List<ChaoXingCourse>,
            fallbackSemesterCode: String? = null,
            customSemesterMap: Map<String, String> = emptyMap(),
            tcCustomSemesterMap: Map<String, String> = emptyMap(),
            manualBindings: Map<String, String> = emptyMap(),
            unboundPairs: Set<String> = emptySet()
        ): List<UnifiedCourse> {
            val uTcList = tcCourses.map { 
                val customSem = tcCustomSemesterMap[it.id.toString()]
                fromTronClass(it, customSemesterCode = customSem)
            }
            // 关键：在跨平台配对阶段，超星课程不预设 fallbackSemesterCode（保持未知学期），
            // 允许其与全量畅课课程中不同学期的同名课程配对，融合后继承畅课的准确教务学期！
            val uCxList = cxCourses.map { cx ->
                val customSem = customSemesterMap["${cx.courseId}_${cx.classId}"] ?: customSemesterMap[cx.courseId]
                fromChaoXing(cx, fallbackSemesterCode = null, customSemesterCode = customSem)
            }

            val result = mutableListOf<UnifiedCourse>()
            val matchedCxIds = mutableSetOf<String>()
            val matchedTcIds = mutableSetOf<String>()

            // Pass 0: 用户手动强制绑定 (最高优先级，绝对优先配对)
            for (tc in uTcList) {
                val boundCxId = manualBindings[tc.originalCourseId]
                if (!boundCxId.isNullOrBlank()) {
                    val matchedCx = uCxList.firstOrNull { cx ->
                        !matchedCxIds.contains(cx.unifiedId) &&
                        (cx.originalCourseId == boundCxId || "${cx.originalCourseId}_${cx.originalClassId}" == boundCxId || cx.unifiedId == boundCxId)
                    }
                    if (matchedCx != null) {
                        matchedCxIds.add(matchedCx.unifiedId)
                        matchedTcIds.add(tc.unifiedId)
                        val inheritedSemester = tc.semesterCode ?: matchedCx.semesterCode
                        result.add(
                            UnifiedCourse(
                                unifiedId = "dual_${tc.originalCourseId}_${matchedCx.originalCourseId}",
                                platform = LearningPlatform.DUAL,
                                originalCourseId = tc.originalCourseId,
                                originalClassId = matchedCx.originalClassId,
                                name = tc.name,
                                cleanName = tc.cleanName,
                                teacherName = if (tc.teacherName.isNotBlank()) tc.teacherName else matchedCx.teacherName,
                                coverUrl = tc.coverUrl ?: matchedCx.coverUrl,
                                courseCode = tc.courseCode ?: matchedCx.courseCode,
                                department = tc.department ?: matchedCx.department,
                                semesterCode = inheritedSemester,
                                formattedSemesterName = inheritedSemester?.let { formatSemesterName(it) } ?: tc.formattedSemesterName,
                                uncompletedTodoCount = tc.uncompletedTodoCount + matchedCx.uncompletedTodoCount,
                                rawTronClassCourse = tc.rawTronClassCourse,
                                rawChaoXingCourse = matchedCx.rawChaoXingCourse
                            )
                        )
                    }
                }
            }

            // 提取关键课程序数代号: (1), (2), (一), (二), (I), (II), (A), (B) 等
            fun extractOrdinalKey(name: String): String? {
                val s = name.replace('（', '(').replace('）', ')')
                val match = Regex("""\(([0-9]+|[一二三四五六七八九十]|[ivxIVX]+|[a-zA-Z])\)|(?<=\p{L})([0-9]+|[ivxIVX]+)$""").find(s)
                if (match != null) {
                    val raw = (match.groupValues[1].ifEmpty { match.groupValues[2] }).lowercase()
                    return when (raw) {
                        "一", "i" -> "1"
                        "二", "ii" -> "2"
                        "三", "iii" -> "3"
                        "四", "iv" -> "4"
                        "五", "v" -> "5"
                        else -> raw
                    }
                }
                return null
            }

            // 课程名称规范化: 保护数字/字母序数，剔除冗余学年学期与无用中括号班级代号
            fun normalize(name: String): String {
                var s = name.trim()
                    .replace('（', '(').replace('）', ')')
                    .replace('【', '[').replace('】', ']')
                    .replace('Ⅰ', '1').replace('Ⅱ', '2').replace('Ⅲ', '3').replace('Ⅳ', '4')

                // 1. 剥离学年学期格式，如 (2024-2025-1) 或 (25-26-1)
                s = s.replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                s = s.replace(Regex("""\(?[0-9]{2}-[0-9]{2}-[0-9]+\)?"""), "")

                // 2. 保护关键序数: (1), (2), (A), (B), (一), (二), (I), (II)
                s = s.replace(Regex("""\(([0-9]+|[a-zA-Z]|一|二|三|四|[ivxIVX]+)\)""")) { match ->
                    val v = match.groupValues[1].lowercase()
                    val num = when (v) {
                        "i", "一" -> "1"
                        "ii", "二" -> "2"
                        "iii", "三" -> "3"
                        "iv", "四" -> "4"
                        else -> v
                    }
                    "__ord_${num}__"
                }

                // 3. 清除其他长备注括号内容: 如 [必修], (重修), (班级代号)
                s = s.replace(Regex("""\[.*?\]|\(.*?\)|【.*?】"""), "")

                // 4. 还原序数字缀并清洗
                s = s.replace("__ord_", "").replace("__", "")

                return s.replace(Regex("""[^\p{L}\p{N}]"""), "")
                    .lowercase()
                    .trim()
            }

            // 课程兼容性强校验 (实验课互斥、序数互斥、明确学期冲突互斥、手动解除黑名单拦截)
            fun areCoursesCompatible(tc: UnifiedCourse, cx: UnifiedCourse): Boolean {
                // 黑名单检查：如果用户曾手动拆散过这门配对，坚决不自动融合
                if (unboundPairs.contains("${tc.originalCourseId}_${cx.originalCourseId}") ||
                    unboundPairs.contains("${tc.originalCourseId}_${cx.originalCourseId}_${cx.originalClassId}") ||
                    unboundPairs.contains(tc.originalCourseId)
                ) {
                    return false
                }

                val isTcLab = tc.name.contains("实验") || tc.cleanName.contains("实验")
                val isCxLab = cx.name.contains("实验") || cx.cleanName.contains("实验")
                if (isTcLab != isCxLab) return false

                // 序数互斥: 例如《大学英语(1)》与《大学英语(2)》序数不同，坚决不匹配！
                val tcOrd = extractOrdinalKey(tc.cleanName) ?: extractOrdinalKey(tc.name)
                val cxOrd = extractOrdinalKey(cx.cleanName) ?: extractOrdinalKey(cx.name)
                if (tcOrd != null && cxOrd != null && tcOrd != cxOrd) {
                    return false
                }

                // 仅在两者均有明确已知的不同学期代码时才互斥；若超星学期未知，允许配对并继承畅课权威学期
                if (!tc.semesterCode.isNullOrBlank() && !cx.semesterCode.isNullOrBlank()) {
                    if (tc.semesterCode != cx.semesterCode) {
                        return false
                    }
                }

                return true
            }

            for (tc in uTcList) {
                if (matchedTcIds.contains(tc.unifiedId)) continue

                val tcNorm = normalize(tc.cleanName)

                // Pass 1: 优先精确全称规范化匹配 (完全杜绝理论课与实验课、不同序数课被混淆)
                var matchedCx = uCxList.firstOrNull { cx ->
                    if (matchedCxIds.contains(cx.unifiedId)) return@firstOrNull false
                    if (!areCoursesCompatible(tc, cx)) return@firstOrNull false
                    val cxNorm = normalize(cx.cleanName)
                    if (tcNorm.isEmpty() || cxNorm.isEmpty()) return@firstOrNull false
                    tcNorm == cxNorm || tc.cleanName.trim().equals(cx.cleanName.trim(), ignoreCase = true)
                }

                // Pass 2: 仅在未精确匹配时，进行同类型下的模糊包含匹配 (严格保持实验课、序数与学期互斥)
                if (matchedCx == null) {
                    matchedCx = uCxList.firstOrNull { cx ->
                        if (matchedCxIds.contains(cx.unifiedId)) return@firstOrNull false
                        if (!areCoursesCompatible(tc, cx)) return@firstOrNull false
                        val cxNorm = normalize(cx.cleanName)
                        if (tcNorm.isEmpty() || cxNorm.isEmpty()) return@firstOrNull false

                        if ((tcNorm.contains(cxNorm) || cxNorm.contains(tcNorm)) && kotlin.math.abs(tcNorm.length - cxNorm.length) <= 3) {
                            if (tc.teacherName.isNotBlank() && cx.teacherName.isNotBlank()) {
                                val t1 = tc.teacherName.replace(" ", "")
                                val t2 = cx.teacherName.replace(" ", "")
                                return@firstOrNull t1.contains(t2) || t2.contains(t1)
                            }
                            return@firstOrNull true
                        }
                        false
                    }
                }

                if (matchedCx != null) {
                    matchedCxIds.add(matchedCx.unifiedId)
                    matchedTcIds.add(tc.unifiedId)
                    // 融合课程优先继承畅课精准权威的教务学期，确保历史课程准确归位对应学期！
                    val inheritedSemester = tc.semesterCode ?: matchedCx.semesterCode
                    result.add(
                        UnifiedCourse(
                            unifiedId = "dual_${tc.originalCourseId}_${matchedCx.originalCourseId}",
                            platform = LearningPlatform.DUAL,
                            originalCourseId = tc.originalCourseId,
                            originalClassId = matchedCx.originalClassId,
                            name = tc.name,
                            cleanName = tc.cleanName,
                            teacherName = if (tc.teacherName.isNotBlank()) tc.teacherName else matchedCx.teacherName,
                            coverUrl = tc.coverUrl ?: matchedCx.coverUrl,
                            courseCode = tc.courseCode ?: matchedCx.courseCode,
                            department = tc.department ?: matchedCx.department,
                            semesterCode = inheritedSemester,
                            formattedSemesterName = inheritedSemester?.let { formatSemesterName(it) } ?: tc.formattedSemesterName,
                            uncompletedTodoCount = tc.uncompletedTodoCount + matchedCx.uncompletedTodoCount,
                            rawTronClassCourse = tc.rawTronClassCourse,
                            rawChaoXingCourse = matchedCx.rawChaoXingCourse
                        )
                    )
                } else {
                    result.add(tc)
                }
            }

            // 对畅课中未匹配的落单超星课，保持其自身识别或手动配置的学期（若未知则为 null 归入【其他课程】，绝不污染本学期）
            for (cx in uCxList) {
                if (!matchedCxIds.contains(cx.unifiedId)) {
                    result.add(cx)
                }
            }

            return result
        }
    }
}

/**
 * 双平台统一待办作业/测验模型
 */
data class UnifiedTodo(
    val unifiedId: String,                   // 唯一ID: "tc_todo_${id}" 或 "cx_work_${workId}"
    val platform: LearningPlatform,          // 平台来源
    val originalTodoId: String,              // 原平台待办/作业ID
    val courseId: String,                    // 关联统一课程ID
    val originalCourseId: String,            // 原平台课程ID
    val originalClassId: String? = null,     // 超星专属 classId
    val courseName: String,                  // 课程名称
    val cleanCourseName: String,             // 清洗后课程名称
    val title: String,                       // 待办/作业标题
    val cleanTitle: String,                  // 清洗后标题
    val deadlineDate: Date? = null,          // 截止时间
    val deadlineMillis: Long? = null,        // 截止时间毫秒戳 (全局升序排序核心)
    val type: String = "homework",           // homework, quiz, task
    val typeDisplayName: String = "作业",    // 作业, 测验, 讨论, 任务点
    val isSubmitted: Boolean = false,        // 是否已提交
    val isLocked: Boolean = false,           // 是否已锁定/截止
    val detailUrl: String? = null,           // 超星作业详情或跳转 URL
    val rawTronClassTodo: TronClassTodo? = null // 畅课原始对象引用
) : Serializable {

    fun getFormattedDeadline(): String {
        val date = deadlineDate
        if (date != null) {
            val outFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).apply {
                timeZone = TimeZone.getDefault()
            }
            return outFormat.format(date)
        }
        return if (isLocked) "已截止" else "未定截止时间"
    }

    fun getRemainingHours(): Long {
        val ms = deadlineMillis ?: return Long.MAX_VALUE
        val diffMs = ms - System.currentTimeMillis()
        return diffMs / (1000 * 60 * 60)
    }

    fun getRemainingTimeText(): String {
        if (isLocked) return "已截止"
        val ms = deadlineMillis ?: return "长期有效"
        val diffMs = ms - System.currentTimeMillis()
        if (diffMs <= 0) return "已逾期"

        val hours = diffMs / (1000 * 60 * 60)
        val days = hours / 24
        return when {
            days > 0 -> "剩 ${days} 天 ${hours % 24} 小时"
            hours > 0 -> "剩 $hours 小时"
            else -> {
                val mins = (diffMs / (1000 * 60)).coerceAtLeast(1)
                "剩 $mins 分钟"
            }
        }
    }

    fun isUrgent(): Boolean {
        val ms = deadlineMillis ?: return false
        val diffMs = ms - System.currentTimeMillis()
        return diffMs in 1..(24 * 60 * 60 * 1000) // 24小时内紧急
    }

    fun isOverdue(): Boolean {
        if (isLocked) return true
        val ms = deadlineMillis ?: return false
        return ms < System.currentTimeMillis()
    }

    companion object {
        fun fromTronClass(todo: TronClassTodo): UnifiedTodo {
            val dDate = todo.getDeadlineDate()
            return UnifiedTodo(
                unifiedId = "tc_todo_${todo.id}",
                platform = LearningPlatform.TRONCLASS,
                originalTodoId = todo.id.toString(),
                courseId = "tc_${todo.courseId}",
                originalCourseId = todo.courseId.toString(),
                courseName = todo.courseName,
                cleanCourseName = todo.getCleanCourseName(),
                title = todo.title,
                cleanTitle = todo.getCleanTitle(),
                deadlineDate = dDate,
                deadlineMillis = dDate?.time,
                type = todo.type ?: "homework",
                typeDisplayName = if (todo.type == "quiz") "测验" else "作业",
                isSubmitted = false,
                isLocked = todo.isLocked,
                rawTronClassTodo = todo
            )
        }

        fun fromChaoXingWork(
            work: ChaoXingWork,
            course: ChaoXingCourse? = null
        ): UnifiedTodo {
            val ddlMillis = cn.edu.usst.jwgl.data.network.ChaoXingClient.parseDdlMillis(work.deadline)
            val cName = work.courseName.ifBlank { course?.courseName ?: "超星学习通课程" }
            val cId = course?.courseId ?: "0"
            val clId = course?.classId ?: ""
            val isItemLocked = work.expired || work.statusText.contains("已截止") ||
                    work.statusText.contains("已过期") || work.statusText.contains("已逾期") ||
                    (ddlMillis != null && ddlMillis < System.currentTimeMillis())
            return UnifiedTodo(
                unifiedId = "cx_work_${work.workId}",
                platform = LearningPlatform.CHAOXING,
                originalTodoId = work.workId,
                courseId = "cx_${cId}_${clId}",
                originalCourseId = cId,
                originalClassId = clId,
                courseName = cName,
                cleanCourseName = cName.replace(Regex("""^\(.*?\)"""), "").trim(),
                title = work.title,
                cleanTitle = work.title.replace(Regex("""^\(.*?\)"""), "").trim(),
                deadlineDate = ddlMillis?.let { Date(it) },
                deadlineMillis = ddlMillis,
                type = "homework",
                typeDisplayName = "作业",
                isSubmitted = work.isSubmitted(),
                isLocked = isItemLocked,
                detailUrl = work.taskUrl
            )
        }
    }
}

/**
 * 双平台统一课程公告模型
 */
data class UnifiedBulletin(
    val unifiedId: String,                   // 唯一ID: "tc_bulletin_${id}" 或 "cx_notice_${id}"
    val platform: LearningPlatform,          // 平台来源
    val originalBulletinId: String,          // 原平台公告ID
    val courseId: String,                    // 关联统一课程ID
    val originalCourseId: String,            // 原平台课程ID
    val courseName: String,                  // 课程名称
    val title: String,                       // 公告标题
    val content: String,                     // 公告富文本正文
    val cleanContent: String,                // 纯文本摘要
    val publishTimeText: String? = null,     // 格式化展示时间 (如 2026-10-25 15:30)
    val publishMillis: Long = 0L,            // 发布时间戳 (用于全局降序混排)
    val imageUrls: List<String> = emptyList(), // 提取的公告附图
    val rawTronClassBulletin: TronClassBulletin? = null
) : Serializable {

    companion object {
        fun fromTronClass(bulletin: TronClassBulletin): UnifiedBulletin {
            val raw = bulletin.content ?: ""
            val clean = TronClassHtmlHelper.getCleanText(raw).ifBlank { "（包含图片或表格内容，点击查看详情）" }
            val imgs = TronClassHtmlHelper.extractImageUrls(raw)

            var millis = 0L
            val timeText = bulletin.createdAt?.let { rawTime ->
                try {
                    val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdfIn.parse(rawTime.substringBefore("Z").substringBefore("."))
                    if (date != null) {
                        millis = date.time
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(date)
                    } else rawTime
                } catch (_: Exception) {
                    rawTime
                }
            } ?: ""

            return UnifiedBulletin(
                unifiedId = "tc_bulletin_${bulletin.id}",
                platform = LearningPlatform.TRONCLASS,
                originalBulletinId = bulletin.id.toString(),
                courseId = "tc_${bulletin.courseId}",
                originalCourseId = bulletin.courseId.toString(),
                courseName = bulletin.courseName ?: "一网畅学课程",
                title = bulletin.title?.trim()?.ifBlank { "课程公告" } ?: "课程公告",
                content = raw,
                cleanContent = clean,
                publishTimeText = timeText,
                publishMillis = millis,
                imageUrls = imgs,
                rawTronClassBulletin = bulletin
            )
        }
    }
}

/**
 * 双平台统一正在进行中的签到活动模型
 */
data class UnifiedActiveRollcall(
    val unifiedId: String,
    val platform: LearningPlatform,
    val courseName: String,
    val typeDisplayName: String,
    val effectiveId: Long,
    val rawTronClassRollcall: TronClassRollcall? = null,
    val rawChaoXingSign: Pair<ChaoXingCourse, ChaoXingActiveItem>? = null,
    val chaoXingSignDetail: cn.edu.usst.jwgl.data.model.ChaoXingSignDetail? = null
) : Serializable

/**
 * 双平台统一历史考勤记录模型
 */
data class UnifiedStudentRollcall(
    val unifiedId: String,
    val platform: LearningPlatform,
    val title: String,
    val typeDisplayName: String,
    val timeText: String,
    val timeMillis: Long = 0L,
    val status: RollcallStatus = RollcallStatus.UNKNOWN,
    val statusDisplayName: String = "未知",
    val rawTronClassRollcall: TronClassStudentRollcall? = null,
    val rawChaoXingItem: ChaoXingActiveItem? = null
) : Serializable {

    enum class RollcallStatus {
        ATTENDED,    // 已出勤 / 已到
        LATE,        // 迟到
        LEAVE_EARLY, // 早退
        ABSENT,      // 缺勤 / 未参与
        LEAVE,       // 请假
        IN_PROGRESS, // 进行中
        UNKNOWN      // 未知
    }

    fun isAttended(): Boolean = status == RollcallStatus.ATTENDED

    fun isLateOrEarly(): Boolean = status == RollcallStatus.LATE || status == RollcallStatus.LEAVE_EARLY

    fun isAbsent(): Boolean = status == RollcallStatus.ABSENT

    fun isLeave(): Boolean = status == RollcallStatus.LEAVE

    fun isInProgress(): Boolean = status == RollcallStatus.IN_PROGRESS

    companion object {
        fun fromTronClass(item: TronClassStudentRollcall): UnifiedStudentRollcall {
            val status = when {
                item.isAttended() -> RollcallStatus.ATTENDED
                item.isLateOrEarly() -> RollcallStatus.LATE
                item.isAbsent() -> RollcallStatus.ABSENT
                item.isLeave() -> RollcallStatus.LEAVE
                item.getEffectiveStatus() == "in_progress" -> RollcallStatus.IN_PROGRESS
                else -> RollcallStatus.UNKNOWN
            }

            var millis = 0L
            val rawTime = item.rollcallTime ?: item.createdAt
            if (!rawTime.isNullOrBlank()) {
                try {
                    if (rawTime.contains("T") || rawTime.endsWith("Z")) {
                        val clean = rawTime.substringBefore(".")
                        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        millis = parser.parse(clean)?.time ?: 0L
                    } else {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        millis = sdf.parse(rawTime)?.time ?: 0L
                    }
                } catch (_: Exception) {}
            }

            return UnifiedStudentRollcall(
                unifiedId = "tc_${item.getEffectiveId()}",
                platform = LearningPlatform.TRONCLASS,
                title = item.getDisplayTitle(),
                typeDisplayName = item.getTypeDisplayName(),
                timeText = item.getFormattedTime(),
                timeMillis = millis,
                status = status,
                statusDisplayName = item.getStatusDisplayName(),
                rawTronClassRollcall = item
            )
        }

        fun fromChaoXing(
            item: ChaoXingActiveItem,
            detail: ChaoXingSignDetail?
        ): UnifiedStudentRollcall {
            // 超星出勤状态权威判据 (通过 preSign 落地页精确识别):
            // 1. isAlreadySigned: 页面包含 zsign_success / 签到成功 / 教师代签
            // 2. isLate: 包含迟到
            // 3. isLeave: 包含请假 / 早退
            // 4. 若活动仍在进行中 -> IN_PROGRESS
            // 5. 若已结束且未出勤 -> ABSENT (缺勤 / 未参与)
            val isAttended = detail?.isAlreadySigned == true
            val isLate = detail?.isLate == true
            val isLeave = detail?.isLeave == true
            val isActive = item.isActive()

            val status = when {
                isAttended -> RollcallStatus.ATTENDED
                isLate -> RollcallStatus.LATE
                isLeave -> RollcallStatus.LEAVE
                isActive -> RollcallStatus.IN_PROGRESS
                else -> RollcallStatus.ABSENT // 已结束且未出勤，权威判定为缺勤
            }

            val statusDisplayName = when (status) {
                RollcallStatus.ATTENDED -> "已出勤"
                RollcallStatus.IN_PROGRESS -> "进行中"
                RollcallStatus.ABSENT -> detail?.statusText?.takeIf { it.isNotBlank() } ?: "未参与"
                RollcallStatus.LATE -> "迟到"
                RollcallStatus.LEAVE_EARLY -> "早退"
                RollcallStatus.LEAVE -> "请假"
                RollcallStatus.UNKNOWN -> "未知"
            }

            val typeName = detail?.signTypeDisplayName ?: item.resolvedSignTypeName

            val effectiveMillis = if (item.startTime > 0L) item.startTime else item.createtime
            val timeStr = if (effectiveMillis > 0L) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(effectiveMillis))
            } else {
                item.nameFour?.trim().orEmpty()
            }

            val title = item.nameOne?.trim().takeIf { !it.isNullOrBlank() } ?: typeName

            return UnifiedStudentRollcall(
                unifiedId = "cx_${item.id}",
                platform = LearningPlatform.CHAOXING,
                title = title,
                typeDisplayName = typeName,
                timeText = timeStr,
                timeMillis = effectiveMillis,
                status = status,
                statusDisplayName = statusDisplayName,
                rawChaoXingItem = item
            )
        }
    }
}
