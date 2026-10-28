package cn.edu.usst.jwgl.data.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TronClass (一网畅学) 数据模型
 */

data class TronClassTodo(
    @SerializedName("id")
    val id: Long,

    @SerializedName("title")
    val title: String,

    @SerializedName("course_id")
    val courseId: Long,

    @SerializedName("course_name")
    val courseName: String,

    @SerializedName("course_code")
    val courseCode: String? = null,

    @SerializedName("course_type")
    val courseType: Int? = null,

    @SerializedName("end_time")
    val endTime: String? = null,

    @SerializedName("type")
    val type: String? = null, // "homework", "quiz", etc.

    @SerializedName("is_locked")
    val isLocked: Boolean = false,

    @SerializedName("is_student")
    val isStudent: Boolean = true
) : Serializable {

    fun getCleanTitle(): String {
        return (title ?: "").trim().ifBlank { "未命名待办" }
    }

    fun getCleanCourseName(): String {
        // Strip trailing course code if present: e.g. "数字图像处理A(2026-2027-1)-12003270-01" -> "数字图像处理A"
        val cName = (courseName ?: "").trim()
        if (cName.isBlank()) return "未知课程"
        val regex = Regex("""^(.*?)\([0-9]{4}-[0-9]{4}-[0-9]+\)""")
        val match = regex.find(cName)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { cName } ?: cName
    }

    fun getDeadlineDate(): Date? {
        if (endTime.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(endTime)
        } catch (e: Exception) {
            try {
                val format2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format2.parse(endTime)
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun getFormattedDeadline(): String {
        val date = getDeadlineDate() ?: return "未定截止时间"
        val outFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }
        return outFormat.format(date)
    }

    fun getRemainingHours(): Long {
        val date = getDeadlineDate() ?: return Long.MAX_VALUE
        val diffMs = date.time - System.currentTimeMillis()
        return diffMs / (1000 * 60 * 60)
    }

    fun getRemainingTimeText(): String {
        val date = getDeadlineDate() ?: return "长期有效"
        val diffMs = date.time - System.currentTimeMillis()
        if (diffMs <= 0) {
            return "已逾期"
        }
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
        val date = getDeadlineDate() ?: return false
        val diffMs = date.time - System.currentTimeMillis()
        return diffMs in 1..(24 * 60 * 60 * 1000) // Less than 24 hours left
    }

    fun isOverdue(): Boolean {
        val date = getDeadlineDate() ?: return false
        return date.time < System.currentTimeMillis()
    }
}

data class TronClassCourse(
    @SerializedName("id")
    val id: Long,

    @SerializedName("name")
    val name: String,

    @SerializedName("course_code")
    val courseCode: String? = null,

    @SerializedName("cover")
    val cover: String? = null,

    @SerializedName("department")
    val department: DepartmentInfo? = null,

    @SerializedName("academic_year")
    val academicYear: AcademicYearInfo? = null,

    @SerializedName("instructors")
    val instructors: List<String> = emptyList(),

    @SerializedName("teacher")
    val teacher: String? = null
) : Serializable {

    data class DepartmentInfo(
        @SerializedName("name")
        val name: String? = null
    ) : Serializable

    data class AcademicYearInfo(
        @SerializedName("name")
        val name: String? = null
    ) : Serializable

    fun getCleanName(): String {
        val n = (name ?: "").trim()
        if (n.isBlank()) return "未命名课程"
        val regex = Regex("""^(.*?)\([0-9]{4}-[0-9]{4}-[0-9]+\)""")
        val match = regex.find(n)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { n } ?: n
    }

    fun extractSemesterCode(): String? {
        val regex = Regex("""\(?([0-9]{4}-[0-9]{4}-[0-9]+)\)?""")
        val match = regex.find(courseCode ?: "") ?: regex.find(name ?: "")
        return match?.groupValues?.get(1)
    }

    fun getFormattedSemesterName(): String {
        val code = extractSemesterCode() ?: return "其他课程"
        val parts = code.split("-")
        return if (parts.size >= 3) {
            "${parts[0]}-${parts[1]} 第${parts[2]}学期"
        } else {
            code
        }
    }
}

data class TronClassRollcall(
    @SerializedName("id")
    val id: Long,

    @SerializedName("rollcallId")
    val rollcallId: Long = 0,

    @SerializedName("course_id")
    val courseId: Long = 0,

    @SerializedName("course_name")
    val courseName: String? = null,

    @SerializedName("type")
    val type: String? = null, // "qr_code", "number", "radar", "self_registration"

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("source")
    val source: String? = null,

    @SerializedName("is_number")
    val isNumber: Boolean = false,

    @SerializedName("is_radar")
    val isRadar: Boolean = false,

    @SerializedName("created_at")
    val createdAt: String? = null
) : Serializable {
    fun getEffectiveId(): Long = if (id > 0) id else rollcallId

    fun getNormalizedType(): String {
        if (isNumber || type == "number") return "number"
        if (isRadar || type == "radar" || type == "roomis") return "radar"
        if (type == "self_registration") return "self_registration"
        if (type == "qr_code" || type == "qr") return "qr_code"
        return if (source == "manual") "number" else "qr_code"
    }

    fun getTypeDisplayName(): String {
        return when (getNormalizedType()) {
            "number" -> "数字口令签到"
            "radar" -> "雷达定位签到"
            "self_registration" -> "自主签到"
            "qr_code" -> "二维码扫码签到"
            else -> "课堂签到"
        }
    }
}

data class RollcallSubmitResult(
    val success: Boolean,
    val message: String,
    val statusName: String? = null,
    val distance: Double? = null
) : Serializable

data class TronClassStudentRollcall(
    @SerializedName("rollcall_id")
    val rollcallId: Long = 0L,

    @SerializedName("id")
    val id: Long = 0L,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("rollcall_time")
    val rollcallTime: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("type")
    val type: String? = null,

    @SerializedName("is_radar")
    val isRadar: Boolean = false,

    @SerializedName("is_number")
    val isNumber: Boolean = false,

    @SerializedName("student_status")
    val studentStatus: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("rollcall_status")
    val rollcallStatus: String? = null
) : Serializable {
    fun getEffectiveId(): Long = if (id > 0) id else rollcallId

    fun getEffectiveStatus(): String {
        return (studentStatus ?: status ?: "").lowercase().trim()
    }

    fun getStatusDisplayName(): String {
        val s = getEffectiveStatus()
        return when {
            s == "on_call" || s == "on_call_fine" || s == "present" || s == "attended" -> "已到"
            s == "on_call_arrive_late" || s == "late" -> "迟到"
            s == "on_call_leave_early" || s == "leave_early" -> "早退"
            s == "on_call_arrive_late_leave_early" -> "迟到早退"
            s == "absent" -> "缺勤"
            isLeave() -> "请假"
            s == "in_progress" -> "进行中"
            s.isNotBlank() -> s
            else -> "未知"
        }
    }

    fun isAttended(): Boolean {
        val s = getEffectiveStatus()
        return s == "on_call" || s == "on_call_fine" || s == "present" || s == "attended"
    }

    fun isLateOrEarly(): Boolean {
        val s = getEffectiveStatus()
        return s.contains("late") || s.contains("leave_early")
    }

    fun isAbsent(): Boolean {
        val s = getEffectiveStatus()
        return s == "absent"
    }

    fun isLeave(): Boolean {
        val s = getEffectiveStatus()
        return s.contains("leave") && !s.contains("leave_early")
    }

    fun getNormalizedType(): String {
        if (isNumber || type == "number") return "number"
        if (isRadar || type == "radar" || type == "roomis") return "radar"
        if (type == "self_registration") return "self_registration"
        if (type == "qr_code" || type == "qr") return "qr_code"
        return type ?: "rollcall"
    }

    fun getTypeDisplayName(): String {
        return when (getNormalizedType()) {
            "number" -> "数字口令"
            "radar" -> "雷达定位"
            "self_registration" -> "自主签到"
            "qr_code" -> "二维码"
            "manual" -> "教师点名"
            else -> "课堂签到"
        }
    }

    fun getDisplayTitle(): String {
        val t = title?.trim()
        if (!t.isNullOrBlank()) return t
        val typeName = getTypeDisplayName()
        val timeStr = getFormattedTime()
        return if (timeStr.isNotBlank()) "$typeName ($timeStr)" else typeName
    }

    fun getFormattedTime(): String {
        val raw = rollcallTime ?: createdAt ?: return ""
        return try {
            if (raw.contains("T") || raw.endsWith("Z")) {
                val clean = raw.substringBefore(".")
                val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val date = parser.parse(clean)
                if (date != null) {
                    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getDefault()
                    }
                    return formatter.format(date)
                }
            }
            raw.replace("T", " ").replace("Z", "").substringBeforeLast(".")
        } catch (_: Exception) {
            raw.replace("T", " ").replace("Z", "").substringBeforeLast(".")
        }
    }
}

data class TronClassModule(
    @SerializedName("id")
    val id: Long,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("sort")
    val sort: Int = 0,

    @SerializedName("is_hidden")
    val isHidden: Int = 0,

    @SerializedName("syllabuses")
    val syllabuses: List<TronClassSyllabus>? = null
) : Serializable {
    fun getCleanName(): String = (name ?: "").trim().ifBlank { "未命名章节" }
}

data class TronClassSyllabus(
    @SerializedName("id")
    val id: Long,

    @SerializedName("name")
    val name: String? = null
) : Serializable

data class TronClassUpload(
    @SerializedName("id")
    val id: Long,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("size")
    val size: Long = 0,

    @SerializedName("type")
    val type: String? = null, // "document", "video", etc.

    @SerializedName("reference_id")
    val referenceId: Long? = null,

    @SerializedName("allow_download")
    val allowDownload: Boolean = true,

    @SerializedName("video_src_type")
    val videoSrcType: String? = null,

    @SerializedName("videos")
    val videos: List<TronClassVideoMeta>? = null
) : Serializable {
    fun getFormattedSize(): String {
        if (size <= 0) return "未知大小"
        val kb = size / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.CHINA, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.CHINA, "%.1f KB", kb)
            else -> "$size B"
        }
    }

    fun getExtension(): String {
        val safeName = name ?: return ""
        val idx = safeName.lastIndexOf('.')
        return if (idx >= 0 && idx < safeName.length - 1) {
            safeName.substring(idx + 1).lowercase()
        } else ""
    }

    fun getDownloadUrl(): String = "https://1906.usst.edu.cn/api/uploads/$id/blob"
}

data class TronClassActivityItem(
    @SerializedName("id")
    val id: Long,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("type")
    val type: String? = null, // "material", "online_video", "homework"

    @SerializedName("course_id")
    val courseId: Long = 0,

    @SerializedName("module_id")
    val moduleId: Long? = null,

    @SerializedName("syllabus_id")
    val syllabusId: Long? = null,

    @SerializedName("start_time")
    val startTime: String? = null,

    @SerializedName("end_time")
    val endTime: String? = null,

    @SerializedName("uploads")
    val uploads: List<TronClassUpload>? = null,

    @SerializedName("data")
    val data: ActivityDetailData? = null,

    var completeness: String? = null
) : Serializable {

    data class ActivityDetailData(
        @SerializedName("description")
        val description: String? = null,

        @SerializedName("allow_download")
        val allowDownload: Boolean = true
    ) : Serializable

    fun getFirstUpload(): TronClassUpload? = uploads?.firstOrNull()

    fun getCleanTitle(): String = (title ?: "").trim().ifBlank { "未命名课件或活动" }

    fun getVideoDuration(): Double {
        uploads?.forEach { up ->
            up.videos?.forEach { v ->
                if (v.duration != null && v.duration > 0) {
                    return v.duration
                }
            }
        }
        return 300.0
    }
}

data class TronClassVideoMeta(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("duration")
    val duration: Double? = null,
    @SerializedName("resolution")
    val resolution: String? = null
) : Serializable

data class TronClassBulletin(
    @SerializedName("id")
    val id: Long,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("uploads")
    val uploads: List<TronClassUpload>? = null,

    @SerializedName("attachments")
    val attachments: List<TronClassUpload>? = null,

    var courseId: Long = 0L,
    var courseName: String? = null
) : Serializable {
    fun getAllAttachments(): List<TronClassUpload> {
        val list = mutableListOf<TronClassUpload>()
        uploads?.let { list.addAll(it) }
        attachments?.let { list.addAll(it) }
        return list.distinctBy { it.id }
    }
}

data class TronClassSubmission(
    @SerializedName("id")
    val id: Long? = null,

    @SerializedName("activity_id")
    val activityId: Long = 0,

    @SerializedName("comment")
    val comment: String? = null,

    @SerializedName("submitted_at")
    val submittedAt: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("score")
    val score: Double? = null,

    @SerializedName("final_score")
    val finalScore: Double? = null,

    @SerializedName("score_at")
    val scoreAt: String? = null,

    @SerializedName("instructor_comment")
    val instructorComment: String? = null,

    @SerializedName("marked_submitted")
    val markedSubmitted: Boolean = false,

    @SerializedName("is_latest_version")
    val isLatestVersion: Boolean = true,

    @SerializedName("uploads")
    val uploads: List<TronClassUpload> = emptyList(),

    @SerializedName("submission_correct")
    val submissionCorrect: TronClassSubmissionCorrect? = null
) : Serializable {
    fun isSubmitted(): Boolean = !submittedAt.isNullOrBlank() || markedSubmitted || id != null
    fun isGraded(): Boolean = score != null || finalScore != null
    fun getEffectiveScore(): Double? = score ?: finalScore
    fun getDisplayScore(): String? {
        val s = getEffectiveScore() ?: return null
        return if (s % 1.0 == 0.0) s.toInt().toString() else s.toString()
    }
    fun getFormattedSubmittedTime(): String? {
        val st = submittedAt ?: return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val out = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
                timeZone = TimeZone.getDefault()
            }
            parser.parse(st)?.let { out.format(it) } ?: st
        } catch (e: Exception) {
            st
        }
    }
}

data class TronClassSubmissionCorrect(
    @SerializedName("id")
    val id: Long = 0,

    @SerializedName("comment")
    val comment: String? = null,

    @SerializedName("instructor_score")
    val instructorScore: String? = null,

    @SerializedName("score")
    val score: Double? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("uploads")
    val uploads: List<TronClassUpload> = emptyList()
) : Serializable



/**
 * 班级成员模型
 */
data class TronClassMember(
    val id: Long,
    val name: String? = null,
    val userNo: String? = null,
    val role: String = "student", // "instructor", "ta", "student"
    val roleName: String = "学生", // "任课教师", "助教", "学生"
    val department: String? = null,
    val klass: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null
) : Serializable {
    fun isInstructor(): Boolean = role == "instructor"
    fun isTA(): Boolean = role == "ta"
    fun getDisplayName(): String = (name ?: "").trim().ifBlank { "未知成员" }
}

/**
 * 学习进度状态
 */
data class TronClassActivityRead(
    val activityId: Long,
    val completeness: String = "none", // "full", "complete", "part", "none"
    val lastVisitedAt: String? = null
) : Serializable {
    fun isCompleted(): Boolean = completeness == "full" || completeness == "complete"
    fun isPartiallyCompleted(): Boolean = completeness == "part"
}

/**
 * 测验 / 考试模型
 */
data class TronClassExam(
    @SerializedName("id")
    val id: Long,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("type")
    val type: String? = "exam",

    @SerializedName("is_started")
    val isStarted: Boolean = false,

    @SerializedName("is_closed")
    val isClosed: Boolean = false,

    @SerializedName("start_time")
    val startTime: String? = null,

    @SerializedName("end_time")
    val endTime: String? = null,

    @SerializedName("limit_time")
    val limitTime: Int? = null,

    @SerializedName("total_points")
    val totalPoints: Double? = null,

    @SerializedName("subjects_count")
    val subjectsCount: Int? = null,

    @SerializedName("submitted_times")
    var submittedTimes: Int? = 0,

    var score: Double? = null,
    var scoreRule: String? = null
) : Serializable {
    fun getCleanTitle(): String = (title ?: "").trim().ifBlank { "未命名测验" }
    fun hasSubmitted(): Boolean = (submittedTimes ?: 0) > 0 || (score?.isNaN() == false)

    fun getFormattedLimitTime(): String {
        return if (limitTime != null && limitTime > 0) "限时 ${limitTime} 分钟" else "不限时"
    }

    fun getFormattedTimeRange(): String {
        val st = formatIsoDateTime(startTime)
        val et = formatIsoDateTime(endTime)
        return when {
            st.isNotBlank() && et.isNotBlank() -> "$st 至 $et"
            et.isNotBlank() -> "截止: $et"
            st.isNotBlank() -> "开始: $st"
            else -> ""
        }
    }

    private fun formatIsoDateTime(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val sdfOut = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val clean = raw.substringBefore("Z").substringBefore(".")
            val date = sdfIn.parse(clean)
            if (date != null) sdfOut.format(date) else raw
        } catch (_: Exception) {
            raw
        }
    }
}

/**
 * 测验作答记录 (多次尝试)
 */
data class TronClassExamSubmission(
    val id: Long,
    val attemptIndex: Int,
    val score: Double?,
    val scoreText: String?,
    val submittedAt: String?,
    val submitMethodText: String? = null
) : Serializable {
    fun getFormattedSubmitTime(): String {
        if (submittedAt.isNullOrBlank()) return ""
        return try {
            val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val sdfOut = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val clean = submittedAt.substringBefore("Z").substringBefore(".")
            val date = sdfIn.parse(clean)
            if (date != null) sdfOut.format(date) else submittedAt
        } catch (_: Exception) {
            submittedAt
        }
    }
}

/**
 * 题目模型
 */
data class TronClassSubject(
    val id: Long,
    val title: String? = null,
    val type: String? = "single_selection", // "single_selection", "multiple_selection", "true_false", "fill_in_blank", "short_answer"
    val point: Double? = null,
    val options: List<TronClassSubjectOption> = emptyList(),
    val myAnswer: Any? = null,
    val correctAnswer: Any? = null,
    val score: Double? = null,
    val explanation: String? = null,
    val description: String? = null,
    val userScore: Double? = null,
    val isCorrect: Boolean? = null,
    val teacherComment: String? = null
) : Serializable {
    fun getCleanTitle(): String {
        val raw = if (!description.isNullOrBlank()) description else (title ?: "")
        return raw.trim().ifBlank { "题目" }
    }

    fun getTypeName(): String = when (type) {
        "single_selection" -> "单选题"
        "multiple_selection" -> "多选题"
        "true_false" -> "判断题"
        "fill_in_blank" -> "填空题"
        "short_answer" -> "简答题"
        else -> "问答题"
    }
}

/**
 * 题目选项
 */
data class TronClassSubjectOption(
    val id: Long,
    val content: String? = null,
    val isAnswer: Boolean = false
) : Serializable {
    fun getCleanContent(): String = (content ?: "").trim()
}

/**
 * 考试详情数据结构
 */
data class TronClassExamDetail(
    val exam: TronClassExam,
    val submissions: List<TronClassExamSubmission> = emptyList(),
    val selectedSubmissionId: Long? = null,
    val scoreRule: String? = null,
    val finalScore: Double? = null,
    val currentScore: Double? = null,
    val subjects: List<TronClassSubject> = emptyList(),
    val myAnswers: Map<String, Any> = emptyMap(),
    val correctAnswers: Map<String, Any> = emptyMap(),
    val isCorrectMap: Map<String, Boolean?> = emptyMap(),
    val subjectScores: Map<String, Double> = emptyMap(),
    val subjectExplanations: Map<String, String> = emptyMap(),
    // 兼容原属性
    val submissionId: Long? = selectedSubmissionId,
    val score: Double? = currentScore ?: finalScore
) : Serializable

/**
 * 课堂讨论模型
 */
data class TronClassDiscussion(
    @SerializedName("id")
    val id: Long,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("replies_count")
    val repliesCount: Int = 0,

    @SerializedName("views_count")
    val viewsCount: Int = 0,

    @SerializedName("user")
    val user: TronClassDiscussionUser? = null,

    @SerializedName("creator")
    val creator: TronClassDiscussionUser? = null,

    // 关联的课程活动对象（当讨论对应 forum 类型的活动时）
    val activityItem: TronClassActivityItem? = null
) : Serializable {
    data class TronClassDiscussionUser(
        @SerializedName("id")
        val id: Long = 0,

        @SerializedName("name")
        val name: String? = null,

        @SerializedName("avatar_url")
        val avatarUrl: String? = null
    ) : Serializable

    fun getAuthorName(): String {
        return (user?.name ?: creator?.name ?: "").trim().ifBlank {
            if (activityItem != null) "课程教师" else "匿名同学"
        }
    }

    fun getCleanTitle(): String = (title ?: activityItem?.getCleanTitle() ?: "").trim().ifBlank { "课堂讨论主题" }

    fun getFormattedTime(): String {
        val iso = createdAt ?: activityItem?.startTime ?: activityItem?.endTime ?: return ""
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
        } catch (_: Exception) {
            iso.take(16).replace("T", " ")
        }
    }

    companion object {
        fun fromActivity(act: TronClassActivityItem): TronClassDiscussion {
            return TronClassDiscussion(
                id = act.id,
                title = act.getCleanTitle(),
                content = act.data?.description,
                createdAt = act.startTime ?: act.endTime,
                repliesCount = 0,
                viewsCount = 0,
                creator = TronClassDiscussionUser(name = "课程教师"),
                activityItem = act
            )
        }
    }
}

/**
 * 课堂讨论回复/发言实体类
 */
data class TronClassDiscussionPost(
    @SerializedName("id")
    val id: Long = 0L,

    @SerializedName("topic_id")
    val topicId: Long? = null,

    @SerializedName("activity_id")
    val activityId: Long? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("user")
    val user: TronClassDiscussion.TronClassDiscussionUser? = null,

    @SerializedName("creator")
    val creator: TronClassDiscussion.TronClassDiscussionUser? = null,

    @SerializedName("likes_count")
    var likesCount: Int = 0,

    @SerializedName("is_liked")
    var isLiked: Boolean = false,

    // 是否为本设备当前用户本人发送的回复
    var isSelf: Boolean = false
) : Serializable {

    fun getAuthorName(): String {
        val name = (user?.name ?: creator?.name ?: "").trim()
        return if (name.isNotBlank()) {
            if (isSelf && !name.contains("我")) "$name (我)" else name
        } else {
            if (isSelf) "我" else "同学"
        }
    }

    fun getFormattedTime(): String {
        val iso = createdAt ?: return "刚刚"
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = inFormat.parse(iso)
            if (date != null) {
                val outFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                outFormat.format(date)
            } else {
                iso.substringBefore("T")
            }
        } catch (_: Exception) {
            iso.take(16).replace("T", " ")
        }
    }
}

