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
}
