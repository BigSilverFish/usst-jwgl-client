package cn.edu.usst.jwgl.devapi

data class HostRollcallInfo(
    val id: Long,
    val courseId: Long,
    val courseName: String?,
    val normalizedType: String,
    val typeDisplayName: String,
    val classroom: String?,
    val latitude: Double?,
    val longitude: Double?,
    val scope: Double?,
    val numberCode: String?,
    val isExpired: Boolean
)

data class HostRollcallSubmitResult(
    val success: Boolean,
    val message: String,
    val statusName: String = "",
    val distance: Double? = null
)

data class HostAccountInfo(
    val studentId: String,
    val studentName: String
)

data class HostAccountSession(
    val studentId: String,
    val sessionId: String,
    val deviceId: String
)

data class HostAccountSignResult(
    val studentId: String,
    val studentName: String,
    val success: Boolean,
    val message: String,
    val statusName: String = ""
)

data class HostAttendanceStats(
    val presentCount: Int,
    val totalCount: Int,
    val badgeText: String
)

data class HostBuilding(
    val id: String,
    val name: String,
    val campus: String,
    val latitude: Double,
    val longitude: Double,
    val aliases: List<String> = emptyList()
)

data class HostLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val source: String
)

data class HostHttpResponse(
    val code: Int,
    val body: String
)

enum class HostVerificationState {
    CONFIRMED,
    NOT_CONFIRMED,
    UNAVAILABLE
}

data class HostActivityBrief(
    val id: Long,
    val type: String,
    val completeness: String?,
    val videoDuration: Double,
    val uploadIds: List<Long>
)

data class HostLiveCourse(
    val id: Long,
    val subjName: String?,
    val clroName: String?,
    val isCurrentlyLive: Boolean
)

data class HostLiveDeviceView(
    val deviViewNum: Int,
    val viewName: String,
    val fullPlayUrl: String?
)

data class HostQrRollcallParsed(
    val rollcallId: Long,
    val qrData: String
)

data class HostChaoXingQrParsed(
    val aid: Long,
    val enc: String,
    val code: String? = null
)

sealed class NumberCodeResult {
    data class Found(
        val code: String,
        val rollcallId: Long,
        val courseId: Long
    ) : NumberCodeResult()

    data class Unavailable(
        val isTerminal: Boolean,
        val message: String
    ) : NumberCodeResult()
}

data class AutoSignRequest(
    val rollcall: HostRollcallInfo,
    val sessionCourseName: String,
    val sessionRoom: String?,
    val attempt: Int,
    val maxAttempts: Int
)

interface AutoSignCallbacks {
    fun shouldShowPopup(): Boolean
    fun onForegroundStatus(statusText: String)
}

data class AutoSignOutcome(
    val handledByAutoSign: Boolean,
    val isTerminal: Boolean
)

enum class HostColor {
    PRIMARY,
    SURFACE,
    SURFACE_VARIANT,
    TEXT_PRIMARY,
    TEXT_SECONDARY,
    ACCENT_BLUE,
    DANGER
}
