package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.devaddon.DevAddonLoader
import cn.edu.usst.jwgl.devapi.DevApiConstants
import cn.edu.usst.jwgl.devapi.NoOpDevAddonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DevAddonLoaderTest {

    @Test
    fun `computeCertificateSha256 matches standard lowercase hex digest`() {
        val sample = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(sample)
            .joinToString("") { "%02x".format(it) }

        val actual = DevAddonLoader.computeCertificateSha256(sample)
        assertEquals(64, actual.length)
        assertEquals(expected, actual)
    }

    @Test
    fun `isCompatibleApiVersion enforces exact contract match`() {
        assertTrue(DevAddonLoader.isCompatibleApiVersion(DevApiConstants.DEV_API_VERSION, DevApiConstants.DEV_API_VERSION))
        assertFalse(DevAddonLoader.isCompatibleApiVersion(DevApiConstants.DEV_API_VERSION + 1, DevApiConstants.DEV_API_VERSION))
        assertFalse(DevAddonLoader.isCompatibleApiVersion(0, DevApiConstants.DEV_API_VERSION))
        assertFalse(DevAddonLoader.isCompatibleApiVersion(-1, DevApiConstants.DEV_API_VERSION))
    }

    @Test
    fun `NoOpDevAddonEntry exposes safe disabled defaults`() {
        assertEquals(DevApiConstants.DEV_API_VERSION, NoOpDevAddonEntry.apiVersion)
        assertEquals("", NoOpDevAddonEntry.addonVersionName)
        assertFalse(NoOpDevAddonEntry.autoSignExtension.isAutoSignAvailable())
        assertFalse(NoOpDevAddonEntry.numberRollcallExtension.shouldPrefillChaoXingCode())
        assertNull(NoOpDevAddonEntry.radarRollcallExtension.resolveDefaultBuildingLabel("一教", "101"))
        assertFalse(NoOpDevAddonEntry.autoBrushExtension.isAutoBrushAvailable())
        assertFalse(NoOpDevAddonEntry.liveScreenExtension.isLiveScreenAvailable())
    }
}
