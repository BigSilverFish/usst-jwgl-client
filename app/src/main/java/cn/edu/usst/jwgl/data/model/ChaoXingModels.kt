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
    val activeType: Int = 0, // 2 表示签到

    @SerializedName("status")
    val status: Int = 0, // 1 表示进行中, 2 表示已结束

    @SerializedName("userStatus")
    val userStatus: Int = 0, // 0: 未参与/未签到, 1: 已签到/已出勤, 2: 迟到, 3: 请假/早退

    @SerializedName("nameOne")
    val nameOne: String? = null, // "二维码签到" / "签到"

    @SerializedName("nameTwo")
    val nameTwo: String? = null, // "[普通签到]" / "[手势签到]" / "[二维码签到]" / "[位置签到]"

    @SerializedName("nameFour")
    val nameFour: String? = null, // "11-04 15:11" / "进行中"

    @SerializedName("startTime")
    val startTime: Long = 0L,

    @SerializedName("endTime")
    val endTime: Long = 0L,

    @SerializedName("createtime")
    private val rawCreatetime: com.google.gson.JsonElement? = null,

    @SerializedName("otherId")
    private val rawOtherId: com.google.gson.JsonElement? = null
) : Serializable {

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
            if (!n1.isNullOrBlank() && n1.contains("签到") && n1 != "签到") return n1
            val n2 = nameTwo?.replace("[", "")?.replace("]", "")?.trim()
            if (!n2.isNullOrBlank()) return n2
            return when (otherId) {
                2 -> "二维码签到"
                3 -> "手势签到"
                4 -> "位置签到"
                5 -> "拍照签到"
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
    val isExpired: Boolean = false,
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
    val courseName: String = ""
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
        val scDouble = score.toDoubleOrNull()
        val isDone = isSubmitted || isGraded || statusText.contains("已完成") || statusText.contains("已批") || scDouble != null

        val safeRawQuestions = rawQuestions ?: emptyList()
        val subjectsList = safeRawQuestions.mapIndexed { idx, q ->
            val subType = when (q.typeName) {
                "多选题" -> "multiple_selection"
                "判断题" -> "true_false"
                "填空题" -> "fill_in_blank"
                "简答题", "名词解释", "论述题", "计算题" -> "short_answer"
                else -> "single_selection"
            }

            val opts = (q.options ?: emptyList()).mapIndexed { oIdx, opt ->
                TronClassSubjectOption(
                    id = (oIdx + 1).toLong(),
                    content = "${opt.letter}. ${opt.content}",
                    isAnswer = opt.isCorrect
                )
            }

            val pMatch = Regex("""(\d+(?:\.\d+)?)""").find(q.scoreText)?.groupValues?.get(1)?.toDoubleOrNull()

            TronClassSubject(
                id = q.id.toLongOrNull() ?: (idx + 1).toLong(),
                title = q.cleanStem.ifBlank { q.stemHtml },
                type = subType,
                point = pMatch,
                options = opts,
                myAnswer = q.cleanMyAnswer,
                correctAnswer = q.correctAnswer,
                score = if (q.cleanMyAnswer.isNotBlank() && q.correctAnswer.isNotBlank() && q.cleanMyAnswer.trim().equals(q.correctAnswer.trim(), ignoreCase = true)) pMatch else null,
                explanation = q.explanation,
                description = q.cleanStem.ifBlank { q.stemHtml },
                teacherComment = q.teacherComment
            )
        }

        val myAnsMap = mutableMapOf<String, Any>()
        val corrAnsMap = mutableMapOf<String, Any>()
        val isCorrMap = mutableMapOf<String, Boolean?>()

        safeRawQuestions.forEachIndexed { idx, q ->
            val sId = (q.id.toLongOrNull() ?: (idx + 1).toLong()).toString()
            if (q.cleanMyAnswer.isNotBlank()) {
                myAnsMap[sId] = q.cleanMyAnswer
            }
            if (q.correctAnswer.isNotBlank()) {
                corrAnsMap[sId] = q.correctAnswer
            }
            if (q.cleanMyAnswer.isNotBlank() && q.correctAnswer.isNotBlank()) {
                isCorrMap[sId] = q.cleanMyAnswer.trim().equals(q.correctAnswer.trim(), ignoreCase = true)
            }
        }

        val tcExam = TronClassExam(
            id = examId.toLongOrNull() ?: (examId.hashCode().toLong() and 0x7FFFFFFF),
            title = title,
            isStarted = true,
            isClosed = isDone || statusText.contains("已截止") || statusText.contains("已结束"),
            limitTime = limitTime,
            subjectsCount = safeRawQuestions.size,
            submittedTimes = if (isDone) 1 else 0,
            score = scDouble,
            scoreRule = scoreRule.ifBlank { "highest" }
        )

        return TronClassExamDetail(
            exam = tcExam,
            submissions = if (isDone) listOf(
                TronClassExamSubmission(
                    id = 1L,
                    attemptIndex = 1,
                    score = scDouble,
                    scoreText = score,
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



