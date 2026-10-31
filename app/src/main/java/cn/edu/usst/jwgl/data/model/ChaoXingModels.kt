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
    val status: Int = 0, // 1 表示进行中

    @SerializedName("nameOne")
    val nameOne: String? = null, // "签到"

    @SerializedName("nameTwo")
    val nameTwo: String? = null, // "[普通签到]" / "[手势签到]" / "[二维码签到]" / "[位置签到]"

    @SerializedName("nameFour")
    val nameFour: String? = null, // "进行中"

    @SerializedName("createtime")
    private val rawCreatetime: com.google.gson.JsonElement? = null,

    @SerializedName("otherId")
    val otherId: Int = 0
) : Serializable {

    val id: Long
        get() = try {
            rawId?.let { if (it.isJsonPrimitive) it.asString.toLongOrNull() else null } ?: 0L
        } catch (_: Exception) { 0L }

    val createtime: Long
        get() {
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
}

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
    val analysisUrl: String = ""
) : Serializable

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

