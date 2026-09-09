package cn.edu.usst.jwgl.data.model

import com.google.gson.annotations.SerializedName

data class AppConfig(
    @SerializedName("app_version")
    val appVersion: AppVersionInfo = AppVersionInfo(),
    @SerializedName("semester_config")
    val semesterConfig: SemesterRemoteConfig = SemesterRemoteConfig(),
    @SerializedName("semesters")
    val semesters: List<SemesterConfigItem> = emptyList(),
    @SerializedName("adjustments")
    val adjustments: List<ScheduleAdjustment> = emptyList(),
    @SerializedName("announcement")
    val announcement: AnnouncementInfo? = null
)

data class SemesterConfigItem(
    @SerializedName("semester_id")
    val semesterId: String = "",
    @SerializedName("semester_title")
    val semesterTitle: String = "",
    @SerializedName("start_date")
    val startDate: String = "",
    @SerializedName("max_week")
    val maxWeek: Int = 20
)

data class ScheduleAdjustment(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("semester_id")
    val semesterId: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("type")
    val type: String = "HOLIDAY_OFF", // "HOLIDAY_OFF" or "SWAP_WEEKDAY"
    @SerializedName("dates")
    val dates: List<String> = emptyList(),
    @SerializedName("date_range")
    val dateRange: DateRange? = null,
    @SerializedName("date")
    val date: String? = null,
    @SerializedName("target_weekday")
    val targetWeekday: Int? = null,
    @SerializedName("target_week")
    val targetWeek: Int? = null,
    @SerializedName("remark")
    val remark: String = ""
)

data class DateRange(
    @SerializedName("start")
    val start: String = "",
    @SerializedName("end")
    val end: String = ""
)

data class AppVersionInfo(
    @SerializedName("version_code")
    val versionCode: Int = 1,
    @SerializedName("version_name")
    val versionName: String = "1.0.0",
    @SerializedName("release_date")
    val releaseDate: String = "2026-09-06",
    @SerializedName("release_notes")
    val releaseNotes: String = "",
    @SerializedName("download_url")
    val downloadUrl: String = "",
    @SerializedName("force_update")
    val forceUpdate: Boolean = false
)

data class SemesterRemoteConfig(
    @SerializedName("current_semester")
    val currentSemester: String = "2026-2027-1",
    @SerializedName("week1_monday")
    val week1Monday: String = "2026-09-07",
    @SerializedName("total_weeks")
    val totalWeeks: Int = 20
)

data class AnnouncementInfo(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("content")
    val content: String = "",
    @SerializedName("show_dialog")
    val showDialog: Boolean = false
)
