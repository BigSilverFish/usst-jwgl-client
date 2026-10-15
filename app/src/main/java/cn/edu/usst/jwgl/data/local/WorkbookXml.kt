package cn.edu.usst.jwgl.data.local

import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

internal object WorkbookXml {
    fun parse(bytes: ByteArray, partName: String): Document = try {
        val xml = decode(bytes)
        // Check the same decoded input we parse: Android lacks some JAXP security features.
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw IOException("工作簿 XML 不允许 DTD 或实体声明")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setOptionalFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setOptionalFeature("http://xml.org/sax/features/external-general-entities", false)
            setOptionalFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setOptionalFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("工作簿 XML 不允许外部实体") }
        }.parse(InputSource(StringReader(xml)))
    } catch (error: Exception) {
        throw IOException("工作簿 XML 无效：$partName", error)
    }

    private fun DocumentBuilderFactory.setOptionalFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // The decoded-input check and resolver remain mandatory on every platform.
        }
    }

    private fun decode(bytes: ByteArray): String {
        fun prefix(vararg values: Int): Boolean = bytes.size >= values.size &&
            values.indices.all { bytes[it].toInt() and 0xFF == values[it] }

        val encoding = when {
            prefix(0, 0, 0xFE, 0xFF) || prefix(0xFF, 0xFE, 0, 0) ||
                prefix(0, 0, 0, 0x3C) || prefix(0x3C, 0, 0, 0) ->
                throw IOException("工作簿 XML 需要 UTF-8 或 UTF-16 编码")
            prefix(0xFE, 0xFF) || prefix(0, 0x3C) -> Charsets.UTF_16BE
            prefix(0xFF, 0xFE) || prefix(0x3C, 0) -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return strictDecode(bytes, encoding).removePrefix("\uFEFF")
    }

    private fun strictDecode(bytes: ByteArray, encoding: Charset): String = encoding.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}
