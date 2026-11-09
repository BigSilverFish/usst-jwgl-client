package cn.edu.usst.jwgl.util

enum class CoordinateSystem { WGS84, GCJ02, UNKNOWN }

/** An immutable position; a missing accuracy is unknown, never a fabricated measurement. */
data class RollcallLocation(
    val latitude: Double,
    val longitude: Double,
    val coordinateSystem: CoordinateSystem,
    val source: String,
    val observedAtMillis: Long? = null,
    val accuracyMeters: Double? = null,
    val pointVersion: String? = null
) {
    companion object {
        fun teacherReference(latitude: Double?, longitude: Double?): RollcallLocation? {
            if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) return null
            return RollcallLocation(latitude, longitude, CoordinateSystem.GCJ02,
                "平台教师参考点（沿用现有接口坐标约定）").toGcj02()
        }
    }

    fun toGcj02(): RollcallLocation? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return when (coordinateSystem) {
            CoordinateSystem.GCJ02 -> this
            CoordinateSystem.WGS84 -> {
                val (lat, lng) = CoordinateUtils.wgs84ToGcj02(latitude, longitude)
                copy(latitude = lat, longitude = lng, coordinateSystem = CoordinateSystem.GCJ02)
            }
            CoordinateSystem.UNKNOWN -> null
        }
    }
}

/** Request generations prevent a cancelled GPS callback from replacing a manual choice. */
class RadarLocationSelection {
    enum class Mode { BUILDING, DEVICE, TEACHER }

    var mode = Mode.BUILDING
        private set
    var selected: RollcallLocation? = null
        private set
    private var generation = 0L

    fun select(mode: Mode, location: RollcallLocation?) {
        generation++
        this.mode = mode
        selected = location?.toGcj02()
    }

    fun beginDeviceRequest(): Long {
        select(Mode.DEVICE, null)
        return generation
    }

    fun cancelDeviceRequest() {
        generation++
    }

    fun isCurrentRequest(request: Long): Boolean = mode == Mode.DEVICE && request == generation

    fun acceptDeviceLocation(request: Long, location: RollcallLocation, nowMillis: Long): Boolean {
        if (!isCurrentRequest(request) || !isUsableDeviceLocation(location, nowMillis)) return false
        val normalized = location.toGcj02() ?: return false
        val previous = selected
        if (previous != null && isUsableDeviceLocation(previous, nowMillis) &&
            (normalized.observedAtMillis!! < previous.observedAtMillis!! ||
                normalized.accuracyMeters!! > previous.accuracyMeters!!)) return false
        selected = normalized
        return true
    }

    fun snapshotForSubmission(nowMillis: Long): RollcallLocation? {
        val snapshot = selected ?: return null
        if (mode == Mode.DEVICE && !isUsableDeviceLocation(snapshot, nowMillis)) return null
        return snapshot
    }

    companion object {
        const val MAX_DEVICE_AGE_MILLIS = 2 * 60 * 1000L
        const val MAX_DEVICE_ACCURACY_METERS = 100.0
        const val TARGET_DEVICE_ACCURACY_METERS = 25.0

        private fun isUsableDeviceLocation(location: RollcallLocation, nowMillis: Long): Boolean {
            val observed = location.observedAtMillis ?: return false
            val accuracy = location.accuracyMeters ?: return false
            return nowMillis - observed in 0..MAX_DEVICE_AGE_MILLIS &&
                accuracy.isFinite() && accuracy > 0 && accuracy <= MAX_DEVICE_ACCURACY_METERS
        }
    }
}
