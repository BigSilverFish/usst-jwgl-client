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
