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
    val rawJson: String = ""
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
    val content: ChaoXingCourseContent? = null
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
    val id: Long = 0L,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("createrName")
    val createrName: String? = null,

    @SerializedName("completeTime")
    val completeTime: Long = 0L,

    @SerializedName("isread")
    val isread: Int = 0,

    @SerializedName("sourceName")
    val sourceName: String? = null,

    @SerializedName("sourceId")
    val sourceId: Long = 0L
) : Serializable

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
    val id: Long = 0L,

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
    val createtime: Long = 0L,

    @SerializedName("otherId")
    val otherId: Int = 0
) : Serializable {

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
