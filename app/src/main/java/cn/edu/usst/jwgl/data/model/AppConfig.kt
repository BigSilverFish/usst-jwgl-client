package cn.edu.usst.jwgl.data.model

import com.google.gson.annotations.SerializedName

data class AppConfig(
    @SerializedName("app_version")
    val appVersion: AppVersionInfo = AppVersionInfo(),
    @SerializedName("semester_config")
    val semesterConfig: SemesterRemoteConfig = SemesterRemoteConfig(),
    @SerializedName("announcement")
    val announcement: AnnouncementInfo? = null
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
