package cn.edu.usst.jwgl.data.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * 超星学习通 (Chaoxing / Fanya) 数据模型
 */

/**
 * 超星机构登录与通用登录响应
 */
data class ChaoXingLoginResponse(
    @SerializedName("status")
    val status: Boolean = false,

    @SerializedName("msg2")
    val msg: String? = null,

    @SerializedName("mes")
    val mes: String? = null,

    @SerializedName("url")
    val url: String? = null
) : Serializable

/**
 * 超星用户信息
 */
data class ChaoXingUserInfo(
    val uid: String = "",
    val name: String = "",
    val studentNo: String = "",
    val schoolId: String = "207",
    val schoolName: String = "上海理工大学",
    val avatarUrl: String = ""
) : Serializable

/**
 * 超星课程实体 (对应 backclazzdata 中的课程单元)
 */
data class ChaoXingCourse(
    val courseId: String,
    val classId: String,
    val courseName: String,
    val teacherName: String = "",
    val coverUrl: String = "",
    val courseCode: String = "",
    val schoolName: String = "上海理工大学",
    val rawJson: String = "",
    val cpi: String = ""
) : Serializable

/**
 * backclazzdata 响应包装
 */
data class ChaoXingClazzDataResponse(
    @SerializedName("result")
    val result: Int = 0,

    @SerializedName("msg")
    val msg: String? = null,

    @SerializedName("channelList")
    val channelList: List<ChaoXingChannelItem>? = null
) : Serializable

data class ChaoXingChannelItem(
    @SerializedName("content")
    val content: ChaoXingCourseContent? = null,

    @SerializedName("cpi")
    val cpi: String? = null
) : Serializable

data class ChaoXingCourseContent(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("course")
    val course: ChaoXingCourseDetail? = null
) : Serializable

data class ChaoXingCourseDetail(
    @SerializedName("data")
    val data: List<ChaoXingCourseData>? = null
) : Serializable

data class ChaoXingCourseData(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("imageurl")
    val imageurl: String? = null,

    @SerializedName("teacherfactor")
    val teacherfactor: String? = null
) : Serializable

/**
 * 超星课程通知/公告响应
 */
data class ChaoXingNoticeResponse(
    @SerializedName("result")
    val result: Int = 0,

    @SerializedName("msg")
    val msg: String? = null,

    @SerializedName("notices")
    val notices: ChaoXingNoticeData? = null
) : Serializable

data class ChaoXingNoticeData(
    @SerializedName("lastGetId")
    val lastGetId: String? = null,

    @SerializedName("list")
    val list: List<ChaoXingNoticeItem>? = null
) : Serializable

data class ChaoXingNoticeItem(
    @SerializedName("id")
    private val rawId: com.google.gson.JsonElement? = null,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("createrName")
    val createrName: String? = null,

    @SerializedName("completeTime")
    private val rawCompleteTime: com.google.gson.JsonElement? = null,

    @SerializedName("isread")
    val isread: Int = 0,

    @SerializedName("sourceName")
    val sourceName: String? = null,

    @SerializedName("sourceId")
    private val rawSourceId: com.google.gson.JsonElement? = null
) : Serializable {

    val id: Long
        get() = try {
            rawId?.let { if (it.isJsonPrimitive) it.asString.toLongOrNull() else null } ?: 0L
        } catch (_: Exception) { 0L }

    val completeTime: Long
        get() {
            val el = rawCompleteTime ?: return 0L
            if (el.isJsonNull) return 0L
            return try {
                if (el.isJsonPrimitive) {
                    val p = el.asJsonPrimitive
                    if (p.isNumber) {
                        p.asLong
                    } else {
                        val s = p.asString.trim()
                        s.toLongOrNull() ?: try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                            sdf.parse(s)?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }
                } else 0L
            } catch (_: Exception) { 0L }
        }

    val sourceId: Long
        get() = try {
            rawSourceId?.let { if (it.isJsonPrimitive) it.asString.toLongOrNull() else null } ?: 0L
        } catch (_: Exception) { 0L }
}

/**
 * 超星活动/签到列表响应
 */
data class ChaoXingActiveListResponse(
    @SerializedName("result")
    val result: Int = 0,

    @SerializedName("msg")
    val msg: String? = null,

    @SerializedName("data")
    val data: ChaoXingActiveData? = null
) : Serializable

data class ChaoXingActiveData(
    @SerializedName("activeList")
    val activeList: List<ChaoXingActiveItem>? = null
) : Serializable

data class ChaoXingActiveItem(
    @SerializedName("id")
    private val rawId: com.google.gson.JsonElement? = null,

    @SerializedName("activeType")
    private val rawActiveType: com.google.gson.JsonElement? = null,

    @SerializedName("status")
    private val rawStatus: com.google.gson.JsonElement? = null,

    @SerializedName("userStatus")
    private val rawUserStatus: com.google.gson.JsonElement? = null,

    @SerializedName("nameOne")
    val nameOne: String? = null, // "二维码签到" / "签到"

    @SerializedName("nameTwo")
    val nameTwo: String? = null, // "[普通签到]" / "[手势签到]" / "[二维码签到]" / "[位置签到]"

    @SerializedName("nameFour")
    val nameFour: String? = null, // "11-04 15:11" / "进行中"

    @SerializedName("startTime")
    private val rawStartTime: com.google.gson.JsonElement? = null,

    @SerializedName("endTime")
    private val rawEndTime: com.google.gson.JsonElement? = null,

    @SerializedName("createtime")
    private val rawCreatetime: com.google.gson.JsonElement? = null,

    @SerializedName("otherId")
    private val rawOtherId: com.google.gson.JsonElement? = null
) : Serializable {

    val activeType: Int
        get() = try {
            rawActiveType?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asInt else p.asString.trim().toIntOrNull() ?: 0
                } else 0
            } ?: 0
        } catch (_: Exception) { 0 }

    val status: Int
        get() = try {
            rawStatus?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asInt else p.asString.trim().toIntOrNull() ?: 0
                } else 0
            } ?: 0
        } catch (_: Exception) { 0 }

    val userStatus: Int
        get() = try {
            rawUserStatus?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asInt else p.asString.trim().toIntOrNull() ?: 0
                } else 0
            } ?: 0
        } catch (_: Exception) { 0 }

    val startTime: Long
        get() = try {
            rawStartTime?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asLong else p.asString.trim().toLongOrNull() ?: 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }

    val endTime: Long
        get() = try {
            rawEndTime?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asLong else p.asString.trim().toLongOrNull() ?: 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }

    val otherId: Int
        get() = try {
            rawOtherId?.let {
                if (it.isJsonPrimitive) {
                    val p = it.asJsonPrimitive
                    if (p.isNumber) p.asInt else p.asString.trim().toIntOrNull() ?: 0
                } else 0
            } ?: 0
        } catch (_: Exception) { 0 }

    @Transient
    var signDetail: ChaoXingSignDetail? = null

    val resolvedSignTypeName: String
        get() {
            val d = signDetail?.signTypeDisplayName
            if (!d.isNullOrBlank()) return d
            val n1 = nameOne?.trim()
            if (!n1.isNullOrBlank() && n1.contains("签到") && n1 != "签到") {
                return if (n1.contains("扫码") || n1.contains("二维码")) "二维码签到" else n1
            }
            val n2 = nameTwo?.replace("[", "")?.replace("]", "")?.trim()
            if (!n2.isNullOrBlank() && n2 != "签到") {
                return if (n2.contains("扫码") || n2.contains("二维码")) "二维码签到" else n2
            }
            return when (otherId) {
                2 -> "二维码签到"
                3 -> "手势签到"
                4 -> "位置签到"
                5 -> "口令签到"
                else -> "普通签到"
            }
        }

    val id: Long
        get() = try {
            rawId?.let { if (it.isJsonPrimitive) it.asString.toLongOrNull() else null } ?: 0L
        } catch (_: Exception) { 0L }

    val createtime: Long
        get() {
            if (startTime > 0L) return startTime
            val el = rawCreatetime ?: return 0L
            if (el.isJsonNull) return 0L
            return try {
                if (el.isJsonPrimitive) {
                    val p = el.asJsonPrimitive
                    if (p.isNumber) {
                        p.asLong
                    } else {
                        val s = p.asString.trim()
                        s.toLongOrNull() ?: try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                            sdf.parse(s)?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }
                } else 0L
            } catch (_: Exception) { 0L }
        }

    fun isSignActivity(): Boolean = activeType == 2

    fun isActive(): Boolean = status == 1

    fun isUserAttended(): Boolean = userStatus == 1

    fun isUserLate(): Boolean = userStatus == 2

    fun isUserLeaveOrEarly(): Boolean = userStatus == 3
}

/**
 * 超星签到详细配置与深度探测结果
 */
data class ChaoXingSignDetail(
    val activeId: Long,
    val otherId: Int = 0,
    val signTypeDisplayName: String = "普通签到",
    val signCode: String? = null,
    val ifPhoto: Boolean = false,
    val locationText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isAlreadySigned: Boolean = false,
    val isLate: Boolean = false,
    val isLeave: Boolean = false,
    val isExpired: Boolean = false,
    val statusText: String? = null,
    val rawJson: String? = null
) : Serializable

/**
 * 签到提交结果包装
 */
data class ChaoXingSignResult(
    val isSuccess: Boolean,
    val message: String,
    val activeId: Long = 0L,
    val signType: String = "普通签到"
) : Serializable

/**
 * 超星课程章节单元
 */
data class ChaoXingChapterUnit(
    val title: String,
    val chapters: List<ChaoXingChapter>
) : Serializable

/**
 * 超星课程章节条目
 */
data class ChaoXingChapter(
    val id: String,
    val title: String,
    val number: String = "",
    val completed: Boolean = false
) : Serializable

/**
 * 超星章节课件/附件 (PPT, Word, PDF, Video)
 */
data class ChaoXingCourseAttachment(
    val objectId: String,
    val name: String,
    val size: Long = 0L,
    val isVideo: Boolean = false
) : Serializable {
    fun getFormattedSize(): String {
        if (size <= 0) return ""
        val kb = size / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(java.util.Locale.CHINA, "%.2f MB", mb)
        } else {
            String.format(java.util.Locale.CHINA, "%.1f KB", kb)
        }
    }
}

/**
 * 超星云盘转换结果直链 (PDF预览、下载直链、视频流)
 */
data class ChaoXingYunFile(
    val pdfUrl: String = "",
    val downloadUrl: String = "",
    val streamUrl: String = ""
) : Serializable

/**
 * 超星考试/测验实体
 */
data class ChaoXingExam(
    val id: String,
    val title: String,
    val status: String,
    val remaining: String = "",
    val score: String = "",
    val analysisUrl: String = "",
    val taskUrl: String = "",
    val courseId: String = "",
    val classId: String = "",
    val courseName: String = "",
    val examAnswerId: String = ""
) : Serializable

/**
 * 超星测验/考试详细试卷原生模型
 */
data class ChaoXingExamDetail(
    val examId: String,
    val title: String,
    val courseName: String = "",
    val statusText: String = "",
    val score: String = "",
    val timeRange: String = "",
    val limitTime: Int? = null,
    val scoreRule: String = "",
    val rawQuestions: List<ChaoXingQuestionItem> = emptyList(),
    val analysisUrl: String = "",
    val taskUrl: String = "",
    val isSubmitted: Boolean = false,
    val isGraded: Boolean = false
) : Serializable {

    fun toTronClassExamDetail(course: ChaoXingCourse? = null): TronClassExamDetail {
        val sc = score.orEmpty()
        val scDouble = sc.toDoubleOrNull()
        val stText = statusText.orEmpty()
        val isDone = isSubmitted || isGraded || stText.contains("已完成") || stText.contains("已批") || scDouble != null

        val safeRawQuestions = rawQuestions ?: emptyList()
        val subjectsList = safeRawQuestions.mapIndexed { idx, q ->
            val subType = when (q.typeName.orEmpty()) {
                "多选题" -> "multiple_selection"
                "判断题" -> "true_false"
                "填空题" -> "fill_in_blank"
                "简答题", "名词解释", "论述题", "计算题" -> "short_answer"
                else -> "single_selection"
            }

            val opts = (q.safeOptions()).mapIndexed { oIdx, opt ->
                TronClassSubjectOption(
                    id = (oIdx + 1).toLong(),
                    content = "${opt.letter.orEmpty()}. ${opt.content.orEmpty()}",
                    isAnswer = opt.isCorrect
                )
            }

            val pMatch = Regex("""(\d+(?:\.\d+)?)""").find(q.scoreText.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
            val stemHtmlSafe = q.stemHtml.orEmpty()
            val cleanStemSafe = q.cleanStem.orEmpty()
            val subjectTitle = if (stemHtmlSafe.contains("<img", ignoreCase = true) || cleanStemSafe.isBlank()) {
                stemHtmlSafe
            } else {
                cleanStemSafe
            }

            val myAnsSafe = if (q.myAnswerHtml.orEmpty().contains("<img", ignoreCase = true)) q.myAnswerHtml.orEmpty() else q.cleanMyAnswer.orEmpty()
            val corrAnsSafe = q.correctAnswer.orEmpty()
            val isMatch = myAnsSafe.isNotBlank() && corrAnsSafe.isNotBlank() && myAnsSafe.trim().equals(corrAnsSafe.trim(), ignoreCase = true)

            TronClassSubject(
                id = q.id.orEmpty().toLongOrNull() ?: (idx + 1).toLong(),
                title = subjectTitle,
                type = subType,
                point = pMatch,
                options = opts,
                myAnswer = myAnsSafe,
                correctAnswer = corrAnsSafe,
                score = if (isMatch) pMatch else null,
                explanation = q.explanation.orEmpty(),
                description = subjectTitle,
                teacherComment = q.teacherComment.orEmpty()
            )
        }

        val myAnsMap = mutableMapOf<String, Any>()
        val corrAnsMap = mutableMapOf<String, Any>()
        val isCorrMap = mutableMapOf<String, Boolean?>()

        safeRawQuestions.forEachIndexed { idx, q ->
            val sId = (q.id.orEmpty().toLongOrNull() ?: (idx + 1).toLong()).toString()
            val finalAns = if (q.myAnswerHtml.orEmpty().contains("<img", ignoreCase = true)) q.myAnswerHtml.orEmpty() else q.cleanMyAnswer.orEmpty()
            if (finalAns.isNotBlank()) {
                myAnsMap[sId] = finalAns
            }
            if (q.correctAnswer.orEmpty().isNotBlank()) {
                corrAnsMap[sId] = q.correctAnswer.orEmpty()
            }
            if (q.cleanMyAnswer.orEmpty().isNotBlank() && q.correctAnswer.orEmpty().isNotBlank()) {
                isCorrMap[sId] = q.cleanMyAnswer.orEmpty().trim().equals(q.correctAnswer.orEmpty().trim(), ignoreCase = true)
            }
        }

        val tr = timeRange.orEmpty()
        val st = when {
            tr.contains("至") -> tr.substringBefore("至").trim()
            tr.contains("~") -> tr.substringBefore("~").trim()
            else -> null
        }
        val et = when {
            tr.contains("至") -> tr.substringAfter("至").trim()
            tr.contains("~") -> tr.substringAfter("~").trim()
            else -> tr.ifBlank { null }
        }

        val tcExam = TronClassExam(
            id = examId.orEmpty().toLongOrNull() ?: (examId.orEmpty().hashCode().toLong() and 0x7FFFFFFF),
            title = title.orEmpty(),
            isStarted = true,
            isClosed = isDone || stText.contains("已截止") || stText.contains("已结束"),
            startTime = st,
            endTime = et,
            limitTime = limitTime,
            subjectsCount = safeRawQuestions.size,
            submittedTimes = if (isDone) 1 else 0,
            score = scDouble,
            scoreRule = scoreRule.ifBlank { "highest" }
        )

        val finalScoreText = score.ifBlank { if (isDone) "教师未公布得分" else "" }

        return TronClassExamDetail(
            exam = tcExam,
            submissions = if (isDone) listOf(
                TronClassExamSubmission(
                    id = 1L,
                    attemptIndex = 1,
                    score = scDouble,
                    scoreText = finalScoreText,
                    submittedAt = timeRange
                )
            ) else emptyList(),
            selectedSubmissionId = if (isDone) 1L else null,
            scoreRule = scoreRule,
            finalScore = scDouble,
            currentScore = scDouble,
            subjects = subjectsList,
            myAnswers = myAnsMap,
            correctAnswers = corrAnsMap,
            isCorrectMap = isCorrMap,
            submissionId = if (isDone) 1L else null
        )
    }
}

/**
 * 超星作业实体
 */
data class ChaoXingWork(
    val workId: String,
    val title: String,
    val courseName: String = "",
    val statusText: String = "",
    val deadline: String = "",
    val expired: Boolean = false,
    val taskUrl: String = "",
    val score: String = ""
) : Serializable {
    fun withScore(newScore: String): ChaoXingWork = copy(score = newScore)

    fun isSubmitted(): Boolean {
        val s = statusText.trim()
        return s.contains("已交") || s.contains("已批") || s.contains("已完成") ||
               s.contains("互评") || s.contains("打分") || score.isNotBlank()
    }
}

/**
 * 超星作业原生详情模型
 */
data class ChaoXingHomeworkDetail(
    val workId: String,
    val title: String,
    val courseName: String = "",
    val statusText: String = "",
    val deadline: String = "",
    val score: String = "",
    val questionsCountText: String = "",
    val contentHtml: String = "",
    val cleanContent: String = "",
    val teacherAttachments: List<ChaoXingCourseAttachment> = emptyList(),
    val myAnswerHtml: String = "",
    val cleanMyAnswer: String = "",
    val studentAttachments: List<ChaoXingCourseAttachment> = emptyList(),
    val teacherComment: String = "",
    val isSubmitted: Boolean = false,
    val isGraded: Boolean = false,
    val taskUrl: String = "",
    val rawQuestions: List<ChaoXingQuestionItem> = emptyList()
) : Serializable

/**
 * 超星作业/测验选项条目
 */
data class ChaoXingQuestionOption(
    val letter: String, // "A", "B", "C", "D" 或 "✓", "✕"
    val content: String, // 选项内容文本
    val isSelected: Boolean = false, // 是否为学生作答
    val isCorrect: Boolean = false // 是否为标准答案
) : Serializable

/**
 * 超星作业单题条目
 */
data class ChaoXingQuestionItem(
    val index: Int,
    val id: String = "",
    val typeCode: String = "4",
    val typeName: String = "",
    val scoreText: String = "",
    val stemHtml: String = "",
    val cleanStem: String = "",
    val options: List<ChaoXingQuestionOption> = emptyList(),
    val correctAnswer: String = "",
    val explanation: String = "",
    val myAnswerHtml: String = "",
    val cleanMyAnswer: String = "",
    val teacherComment: String = "",
    val studentAttachments: List<ChaoXingCourseAttachment> = emptyList(),
    val teacherAttachments: List<ChaoXingCourseAttachment> = emptyList()
) : Serializable {
    fun safeOptions(): List<ChaoXingQuestionOption> = options ?: emptyList()
}

/**
 * 超星作业提交/暂存结果包装
 */
data class ChaoXingSubmitResult(
    val isSuccess: Boolean,
    val message: String,
    val isSubmitted: Boolean = true
) : Serializable



