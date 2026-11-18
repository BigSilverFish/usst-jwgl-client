package cn.edu.usst.jwgl.data.network

import org.junit.Assert.*
import org.junit.Test

class ChaoXingQrPayloadTest {
    private val base = "https://mobilelearn.chaoxing.com/newsign/preSign?activePrimaryId=42"

    @Test fun returnsDecodedCredentialForSelectedActivity() {
        assertEquals("a+b/0", ChaoXingQrPayload.readEnc("$base&enc=a%2Bb%2F0", 42).getOrThrow())
    }

    @Test fun otherActivityAndMissingActivityCannotSubmit() {
        assertTrue(ChaoXingQrPayload.readEnc("$base&enc=x", 43).isFailure)
        assertTrue(ChaoXingQrPayload.readEnc("https://mobilelearn.chaoxing.com/?enc=x", 42).isFailure)
        assertTrue(ChaoXingQrPayload.readEnc("$base&activeId=99&enc=x", 42).isFailure)
    }

    @Test fun unrelatedPlatformOrBlankCredentialCannotSubmit() {
        assertTrue(ChaoXingQrPayload.readEnc("https://1906.usst.edu.cn/?activeId=42&enc=x", 42).isFailure)
        assertTrue(ChaoXingQrPayload.readEnc("$base&enc=", 42).isFailure)
        assertTrue(ChaoXingQrPayload.readEnc(base, 42).isFailure)
    }

    @Test fun conflictingCredentialsAndMalformedEscapeCannotSubmit() {
        assertTrue(ChaoXingQrPayload.readEnc("$base&enc=x&enc=y", 42).isFailure)
        assertTrue(ChaoXingQrPayload.readEnc("$base&enc=%ZZ", 42).isFailure)
    }

    @Test fun parsesRealWidgetSignUrlWithDynamicCodeAndEnc() {
        val url = "https://mobilelearn.chaoxing.com/widget/sign/e?id=7000180993584&c=285939&enc=C82E32AC981EF43969C54BAE863969C3&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=id"
        assertTrue(ChaoXingQrPayload.isChaoXingQrUrl(url))
        val parsed = ChaoXingQrPayload.parse(url).getOrThrow()
        assertEquals(7000180993584L, parsed.activeId)
        assertEquals("C82E32AC981EF43969C54BAE863969C3", parsed.enc)
        assertEquals("285939", parsed.code)

        val matched = ChaoXingQrPayload.parse(url, expectedActivity = 7000180993584L).getOrThrow()
        assertEquals(parsed, matched)
        assertTrue(ChaoXingQrPayload.parse(url, expectedActivity = 12345L).isFailure)
    }
}
