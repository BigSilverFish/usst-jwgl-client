package cn.edu.usst.jwgl.util

import org.junit.Assert.*
import org.junit.Test

class RadarLocationSelectionTest {
    private val now = 1_700_000_000_000L

    @Test fun `teacher reference requires one complete valid coordinate pair`() {
        assertNull(RollcallLocation.teacherReference(31.29, null))
        assertNull(RollcallLocation.teacherReference(null, 121.55))
        assertNull(RollcallLocation.teacherReference(Double.NaN, 121.55))
        assertNull(RollcallLocation.teacherReference(31.29, 181.0))
        assertNull(RollcallLocation.teacherReference(0.0, 0.0))
        val reference = RollcallLocation.teacherReference(31.29, 121.55)!!
        assertEquals(CoordinateSystem.GCJ02, reference.coordinateSystem)
        assertNull(reference.accuracyMeters)
        assertEquals(31.29, reference.latitude, 0.0)
        assertEquals(121.55, reference.longitude, 0.0)
    }

    private fun device(ageMillis: Long = 0, accuracy: Double? = 20.0) = RollcallLocation(
        31.29, 121.55, CoordinateSystem.WGS84, "test/device", now - ageMillis, accuracy
    )

    @Test
    fun `old callback cannot overwrite a building chosen after GPS starts`() {
        val selection = RadarLocationSelection()
        val request = selection.beginDeviceRequest()
        val building = CoordinateUtils.USST_BUILDINGS[1].asRollcallLocation()
        selection.select(RadarLocationSelection.Mode.BUILDING, building)

        assertFalse(selection.acceptDeviceLocation(request, device(), now))
        assertEquals(building, selection.snapshotForSubmission(now))
    }

    @Test
    fun `refresh and cancellation invalidate every previous request`() {
        val selection = RadarLocationSelection()
        val first = selection.beginDeviceRequest()
        val second = selection.beginDeviceRequest()
        assertFalse(selection.acceptDeviceLocation(first, device(), now))
        assertTrue(selection.acceptDeviceLocation(second, device(), now))

        val snapshot = selection.snapshotForSubmission(now)
        selection.cancelDeviceRequest()
        assertFalse(selection.acceptDeviceLocation(second, device(accuracy = 5.0), now))
        assertEquals(snapshot, selection.snapshotForSubmission(now))
    }

    @Test
    fun `stale future inaccurate and unknown accuracy samples are rejected`() {
        val selection = RadarLocationSelection()
        val request = selection.beginDeviceRequest()
        val invalidSamples = listOf(
            device(RadarLocationSelection.MAX_DEVICE_AGE_MILLIS + 1),
            device(-1), device(accuracy = 101.0), device(accuracy = null),
            device(accuracy = 0.0), device(accuracy = Double.NaN),
            device().copy(latitude = Double.NaN),
            device().copy(coordinateSystem = CoordinateSystem.UNKNOWN)
        )
        invalidSamples.forEach { assertFalse(selection.acceptDeviceLocation(request, it, now)) }
        assertNull(selection.snapshotForSubmission(now))
    }

    @Test
    fun `coarse first result does not prevent a newer more accurate result`() {
        val selection = RadarLocationSelection()
        val request = selection.beginDeviceRequest()
        assertFalse(selection.acceptDeviceLocation(request, device(accuracy = 500.0), now))
        assertTrue(selection.acceptDeviceLocation(request, device(1000, 80.0), now))
        assertTrue(selection.acceptDeviceLocation(request, device(0, 10.0), now))
        assertFalse(selection.acceptDeviceLocation(request, device(0, 90.0), now))
        assertFalse(selection.acceptDeviceLocation(request, device(1000, 5.0), now))
        assertEquals(10.0, selection.snapshotForSubmission(now)?.accuracyMeters ?: 0.0, 0.0)
    }

    @Test
    fun `expired device position cannot be submitted while preset has no invented expiry`() {
        val selection = RadarLocationSelection()
        val request = selection.beginDeviceRequest()
        selection.acceptDeviceLocation(request, device(), now)
        assertNull(selection.snapshotForSubmission(now + RadarLocationSelection.MAX_DEVICE_AGE_MILLIS + 1))

        val building = CoordinateUtils.USST_BUILDINGS.first().asRollcallLocation()
        selection.select(RadarLocationSelection.Mode.BUILDING, building)
        assertEquals(building, selection.snapshotForSubmission(now + 24 * 60 * 60 * 1000))
        assertNull(selection.selected?.accuracyMeters)
    }

    @Test
    fun `submission snapshot remains unchanged by later UI choices`() {
        val selection = RadarLocationSelection()
        val initial = CoordinateUtils.USST_BUILDINGS.first().asRollcallLocation()
        selection.select(RadarLocationSelection.Mode.BUILDING, initial)
        val submitted = selection.snapshotForSubmission(now)
        selection.select(RadarLocationSelection.Mode.BUILDING, CoordinateUtils.USST_BUILDINGS[1].asRollcallLocation())
        assertEquals(initial, submitted)
        assertNotEquals(submitted, selection.snapshotForSubmission(now))
    }

    @Test
    fun `GCJ02 preset is never converted again and WGS84 is converted exactly once`() {
        val preset = CoordinateUtils.USST_BUILDINGS.first().asRollcallLocation()
        assertSame(preset, preset.toGcj02())

        val raw = device()
        val converted = raw.toGcj02()!!
        val expected = CoordinateUtils.wgs84ToGcj02(raw.latitude, raw.longitude)
        assertEquals(expected.first, converted.latitude, 0.0)
        assertEquals(expected.second, converted.longitude, 0.0)
        assertSame(converted, converted.toGcj02())
    }

    @Test
    fun `teacher mode and empty selection cannot inherit a previous device result`() {
        val selection = RadarLocationSelection()
        val request = selection.beginDeviceRequest()
        selection.acceptDeviceLocation(request, device(), now)
        selection.select(RadarLocationSelection.Mode.TEACHER, null)
        assertFalse(selection.acceptDeviceLocation(request, device(), now))
        assertNull(selection.snapshotForSubmission(now))
    }
}
