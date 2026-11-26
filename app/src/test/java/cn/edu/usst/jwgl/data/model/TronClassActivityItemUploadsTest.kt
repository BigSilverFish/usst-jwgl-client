package cn.edu.usst.jwgl.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TronClassActivityItemUploadsTest {

    @Test
    fun `empty or null uploads returns empty list and single flag false`() {
        val item = TronClassActivityItem(id = 1L, title = "空课件", uploads = null)
        assertTrue(item.getAllUploads().isEmpty())
        assertNull(item.getFirstUpload())
        assertFalse(item.hasMultipleUploads())
        assertEquals(0L, item.getTotalUploadSize())
    }

    @Test
    fun `single upload reports single and exact size`() {
        val upload = TronClassUpload(id = 101L, name = "第一章.pdf", size = 2048L)
        val item = TronClassActivityItem(id = 2L, title = "第一章", uploads = listOf(upload))
        assertEquals(1, item.getAllUploads().size)
        assertEquals(upload, item.getFirstUpload())
        assertFalse(item.hasMultipleUploads())
        assertEquals(2048L, item.getTotalUploadSize())
        assertEquals("2.0 KB", item.getFormattedTotalUploadSize())
    }

    @Test
    fun `multiple uploads deduplicate by id and sum total size`() {
        val u1 = TronClassUpload(id = 201L, name = "讲义.pdf", size = 1024L * 1024L)
        val u2 = TronClassUpload(id = 202L, name = "实验指导.docx", size = 512L * 1024L)
        val u1Dup = TronClassUpload(id = 201L, name = "讲义.pdf", size = 1024L * 1024L)
        val item = TronClassActivityItem(id = 3L, title = "实验资料", uploads = listOf(u1, u2, u1Dup))

        val all = item.getAllUploads()
        assertEquals(2, all.size)
        assertEquals(listOf(201L, 202L), all.map { it.id })
        assertTrue(item.hasMultipleUploads())
        assertEquals(1536L * 1024L, item.getTotalUploadSize())
        assertEquals("1.50 MB", item.getFormattedTotalUploadSize())
    }
}
