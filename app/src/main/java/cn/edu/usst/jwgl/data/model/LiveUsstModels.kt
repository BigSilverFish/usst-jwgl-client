package cn.edu.usst.jwgl.data.model

import com.google.gson.annotations.SerializedName

/**
 * 智慧录播 (live.usst.edu.cn) 数据模型
 */
object LiveCameraConstant {
    const val VIEW_TEACHER_1 = 1    // 教师全景
    const val VIEW_TEACHER_2 = 2    // 教师特写
    const val VIEW_STUDENT_1 = 3    // 学生全景
    const val VIEW_STUDENT_2 = 4    // 学生特写
    const val VIEW_PPT = 5          // 大屏课件/PPT采集卡 (核心)
    const val VIEW_MOVIE = 6        // 电影模式 (导播合成)
    const val VIEW_COMPOSITE = 7    // 合成通道 (画中画/多画面)

    fun getViewName(viewNum: Int): String = when (viewNum) {
        VIEW_TEACHER_1 -> "教师全景"
        VIEW_TEACHER_2 -> "教师特写"
        VIEW_STUDENT_1 -> "学生全景"
        VIEW_STUDENT_2 -> "学生特写"
        VIEW_PPT -> "PPT大屏采集卡"
        VIEW_MOVIE -> "电影模式"
        VIEW_COMPOSITE -> "合成通道"
        else -> "机位 $viewNum"
    }
}

/**
 * 课程直播列表查询响应 (/mobile/union/vod_live_new)
 */
data class LiveUnionSearchResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("data") val data: LiveUnionSearchData?
)

data class LiveUnionSearchData(
    @SerializedName("records") val records: List<LiveCourseRecord>?,
    @SerializedName("rowCount") val rowCount: Int?,
    @SerializedName("pageCount") val pageCount: Int?,
    @SerializedName("todayBeginTime") val todayBeginTime: Long?
)

data class LiveCourseRecord(
    @SerializedName("id") val id: Long,
    @SerializedName("teclId") val teclId: String?,
    @SerializedName("subjName") val subjName: String?,
    @SerializedName("clroName") val clroName: String?,
    @SerializedName("teclTeacNames") val teclTeacNames: List<String>?,
    @SerializedName("courBeginTime") val courBeginTime: Long?,
    @SerializedName("courEndTime") val courEndTime: Long?,
    @SerializedName("courStatus") val courStatus: Int?
) {
    /**
     * 判断当前时间是否在直播区间内
     */
    fun isCurrentlyLive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        val start = courBeginTime ?: return false
        val end = courEndTime ?: return false
        // 允许提前 10 分钟以及延后 15 分钟的容错范围
        return currentTimeMillis in (start - 10 * 60 * 1000L)..(end + 15 * 60 * 1000L)
    }
}

/**
 * 直播间机位及播放流响应 (/v1/course_vod_videoinfos)
 */
data class LiveVideoInfoResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("code") val code: Int?,
    @SerializedName("data") val data: LiveVideoInfoData?,
    @SerializedName("message") val message: String?,
    @SerializedName("errorMsg") val errorMsg: String?
)

data class LiveVideoInfoData(
    @SerializedName("teclId") val teclId: String?,
    @SerializedName("lvcrLiveStatus") val lvcrLiveStatus: Int?,
    @SerializedName("courseDeviceViewDtoList") val courseDeviceViewDtoList: List<LiveDeviceViewDto>?,
    @SerializedName("userIp") val userIp: String?,
    @SerializedName("errorMsg") val errorMsg: String?
)

data class LiveDeviceViewDto(
    @SerializedName("deviViewNum") val deviViewNum: Int,
    @SerializedName("chanNameMainPlayUrl") val chanNameMainPlayUrl: String?,
    @SerializedName("mainTokenStr") val mainTokenStr: String?,
    @SerializedName("chanGbidMain") val chanGbidMain: String?,
    @SerializedName("deviceStatus") val deviceStatus: Int?
) {
    /**
     * 获取带鉴权 Token 的最终可直接拉流的推流 URL
     */
    fun getFullPlayUrl(): String? {
        val baseUrl = chanNameMainPlayUrl ?: return null
        val token = mainTokenStr ?: return baseUrl
        return if (baseUrl.contains("?")) {
            "$baseUrl&account_token=$token"
        } else {
            "$baseUrl?account_token=$token"
        }
    }

    /**
     * 获取机位名称
     */
    val viewName: String
        get() = LiveCameraConstant.getViewName(deviViewNum)
}
