package cn.edu.usst.jwgl.ui.tronclass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TronClassHomeOptimizationTest {

    @Test
    fun buildMemoryCacheKeySeparatesThumbnailsAndOriginalImages() {
        val url = "https://1906.usst.edu.cn/api/uploads/123"
        val thumbKey = TronClassImageLoader.buildMemoryCacheKey(url, 720, 360)
        val multiThumbKey = TronClassImageLoader.buildMemoryCacheKey(url, 168, 168)
        val origKey = TronClassImageLoader.buildMemoryCacheKey(url, 0, 0)
        val negativeKey = TronClassImageLoader.buildMemoryCacheKey(url, -1, 100)

        assertEquals("$url#720x360", thumbKey)
        assertEquals("$url#168x168", multiThumbKey)
        assertEquals("$url#orig", origKey)
        assertEquals("$url#orig", negativeKey)
        assertNotEquals(thumbKey, multiThumbKey)
        assertNotEquals(thumbKey, origKey)
    }

    @Test
    fun calculateInSampleSizeComputesExpectedPowerOfTwo() {
        assertEquals(16, TronClassImageLoader.calculateInSampleSize(2048, 2048, 128, 128))
        assertEquals(1, TronClassImageLoader.calculateInSampleSize(100, 100, 128, 128))
        assertEquals(1, TronClassImageLoader.calculateInSampleSize(2048, 2048, 0, 128))
    }

    @Test
    fun homeAdapterRowReflectsMultiPlatformAndPresentationBucketChanges() {
        val base = HomeAdapterRow(item = "todo-1", multiPlatform = false, presentationBucket = 100L)
        val same = HomeAdapterRow(item = "todo-1", multiPlatform = false, presentationBucket = 100L)
        val switchedPlatform = HomeAdapterRow(item = "todo-1", multiPlatform = true, presentationBucket = 100L)
        val nextMinute = HomeAdapterRow(item = "todo-1", multiPlatform = false, presentationBucket = 101L)

        assertEquals(base, same)
        assertNotEquals(base, switchedPlatform)
        assertNotEquals(base, nextMinute)
    }
}
