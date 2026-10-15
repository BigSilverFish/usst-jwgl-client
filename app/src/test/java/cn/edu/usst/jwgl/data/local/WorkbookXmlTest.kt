package cn.edu.usst.jwgl.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WorkbookXmlTest {
    @Test
    fun readsUtf8AndUtf16WithNamespaces() {
        val xml = "<worksheet xmlns=\"urn:test\"><cell>预约</cell></worksheet>"
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val document = WorkbookXml.parse(xml.toByteArray(encoding), "sheet.xml")
            assertEquals("预约", document.getElementsByTagNameNS("urn:test", "cell").item(0).textContent)
        }
    }

    @Test
    fun rejectsExternalAndInternalEntitiesInEverySupportedEncoding() {
        val declarations = listOf(
            "<!DOCTYPE worksheet SYSTEM \"https://example.invalid/external.dtd\">",
            "<!DOCTYPE worksheet [<!ENTITY value SYSTEM \"file:///not-readable\">]>",
            "<!DOCTYPE worksheet [<!ENTITY value \"expanded\">]>"
        )
        for (declaration in declarations) {
            for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_16LE)) {
                assertThrows(IOException::class.java) {
                    WorkbookXml.parse((declaration + "<worksheet/>").toByteArray(encoding), "sheet.xml")
                }
            }
        }
    }

    @Test
    fun rejectsMalformedInputInsteadOfReplacingBytes() {
        assertThrows(IOException::class.java) {
            WorkbookXml.parse("<worksheet>".toByteArray() + byteArrayOf(0xFF.toByte()) + "</worksheet>".toByteArray(), "sheet.xml")
        }
    }
}
