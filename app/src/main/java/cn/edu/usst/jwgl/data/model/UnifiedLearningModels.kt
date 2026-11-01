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
        fun fromTronClass(course: TronClassCourse, uncompletedCount: Int = 0): UnifiedCourse {
            val clean = course.getCleanName()
            val teachers = if (course.instructors.isNotEmpty()) {
                course.instructors.joinToString(", ")
            } else {
                course.teacher ?: ""
            }
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
                semesterCode = course.extractSemesterCode(),
                formattedSemesterName = course.getFormattedSemesterName(),
                uncompletedTodoCount = uncompletedCount,
                rawTronClassCourse = course
            )
        }

        fun fromChaoXing(
            course: ChaoXingCourse,
            uncompletedCount: Int = 0,
            fallbackSemesterCode: String? = null
        ): UnifiedCourse {
            val rawName = course.courseName.trim()
            val regex = Regex("""^(.*?)\([0-9]{4}-[0-9]{4}-[0-9]+\)""")
            val clean = regex.find(rawName)?.groupValues?.get(1)?.trim() ?: rawName
            val semCode = Regex("""\(?([0-9]{4}-[0-9]{4}-[0-9]+)\)?""").find(rawName)?.groupValues?.get(1)
                ?: fallbackSemesterCode

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
                formattedSemesterName = semCode?.let { "学期 $it" },
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
            fallbackSemesterCode: String? = null
        ): List<UnifiedCourse> {
            val uTcList = tcCourses.map { fromTronClass(it) }
            val uCxList = cxCourses.map { fromChaoXing(it, fallbackSemesterCode = fallbackSemesterCode) }

            val result = mutableListOf<UnifiedCourse>()
            val matchedCxIds = mutableSetOf<String>()

            fun normalize(name: String): String {
                return name
                    .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                    .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                    .replace(Regex("""\s+"""), "")
                    .lowercase()
                    .trim()
            }

            for (tc in uTcList) {
                val tcNorm = normalize(tc.cleanName)
                val isTcLab = tc.name.contains("实验") || tc.cleanName.contains("实验")

                // Pass 1: 优先精确全称匹配 (完全杜绝理论课与实验课被包含算法混淆)
                var matchedCx = uCxList.firstOrNull { cx ->
                    if (matchedCxIds.contains(cx.unifiedId)) return@firstOrNull false
                    val isCxLab = cx.name.contains("实验") || cx.cleanName.contains("实验")
                    if (isTcLab != isCxLab) return@firstOrNull false
                    val cxNorm = normalize(cx.cleanName)
                    if (tcNorm.isEmpty() || cxNorm.isEmpty()) return@firstOrNull false
                    tcNorm == cxNorm || tc.cleanName.trim().equals(cx.cleanName.trim(), ignoreCase = true)
                }

                // Pass 2: 仅在未精确匹配时，进行同类型下的模糊包含匹配 (严格保持实验课与非实验课互斥)
                if (matchedCx == null) {
                    matchedCx = uCxList.firstOrNull { cx ->
                        if (matchedCxIds.contains(cx.unifiedId)) return@firstOrNull false
                        val isCxLab = cx.name.contains("实验") || cx.cleanName.contains("实验")
                        if (isTcLab != isCxLab) return@firstOrNull false
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
                            semesterCode = tc.semesterCode ?: matchedCx.semesterCode,
                            formattedSemesterName = tc.formattedSemesterName ?: matchedCx.formattedSemesterName,
                            uncompletedTodoCount = tc.uncompletedTodoCount + matchedCx.uncompletedTodoCount,
                            rawTronClassCourse = tc.rawTronClassCourse,
                            rawChaoXingCourse = matchedCx.rawChaoXingCourse
                        )
                    )
                } else {
                    result.add(tc)
                }
            }

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
        val date = deadlineDate ?: return "未定截止时间"
        val outFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }
        return outFormat.format(date)
    }

    fun getRemainingHours(): Long {
        val ms = deadlineMillis ?: return Long.MAX_VALUE
        val diffMs = ms - System.currentTimeMillis()
        return diffMs / (1000 * 60 * 60)
    }

    fun getRemainingTimeText(): String {
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
    val rawChaoXingSign: Pair<ChaoXingCourse, ChaoXingActiveItem>? = null
) : Serializable
