package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.*
import org.junit.Assert.*
import org.junit.Test

class RollcallNumberCodeLookupTest {
    private val target = TronClassRollcall(id = 99001, courseId = 42, type = "number")

    private fun failure(body: String) = (RollcallNumberCodeLookup.parse(body, target) as NumberCodeLookupResult.Unavailable).reason

    @Test fun readsOnlyNamedCodeAndPreservesLeadingZeros() {
        val found = RollcallNumberCodeLookup.parse("""{"rollcall_id":99001,"course_id":42,"number_code":"0042"}""", target)
        assertEquals(NumberCodeLookupResult.Found("0042", 99001, 42), found)
        assertEquals(NumberCodeLookupResult.Found("1234", 99001, 42),
            RollcallNumberCodeLookup.parse("""{"number_code":1234}""", target))
    }

    @Test fun readsNamedRollcallObjectButDoesNotTraverseStudentsOrUnrelatedNumbers() {
        assertEquals(NumberCodeLookupResult.Found("0001", 99001, 42),
            RollcallNumberCodeLookup.parse("""{"rollcall":{"id":99001,"course_id":42,"number_code":"0001"},"students":[{"number_code":"8888"}]}""", target))
        assertEquals(NumberCodeLookupFailure.CodeNotReturned, failure("""{"number":"1234","students":[{"number_code":"8888"}]}"""))
    }

    @Test fun conflictingActivityCourseOrCodeAreRejected() {
        assertEquals(NumberCodeLookupFailure.Conflict, failure("""{"rollcall_id":99002,"number_code":"1234"}"""))
        assertEquals(NumberCodeLookupFailure.Conflict, failure("""{"course_id":43,"number_code":"1234"}"""))
        assertEquals(NumberCodeLookupFailure.Conflict, failure("""{"number_code":"1234","rollcall":{"number_code":"4321"}}"""))
    }

    @Test fun numericOrMalformedCodeIsNeverPaddedOrTruncated() {
        for (body in listOf("""{"number_code":42}""", """{"number_code":1234.0}""", """{"number_code":1.234e3}""", """{"number_code":"12345"}""", """{"number_code":" 1234"}""", """{"number_code":true}""")) {
            assertEquals(NumberCodeLookupFailure.SchemaMismatch, failure(body))
        }
    }

    @Test fun missingAndUnknownSchemasHaveExplicitResults() {
        assertEquals(NumberCodeLookupFailure.CodeNotReturned, failure("""{"number_code":null}"""))
        assertEquals(NumberCodeLookupFailure.SchemaMismatch, failure("<html>login</html>"))
        assertEquals(NumberCodeLookupFailure.SchemaMismatch, failure("[]"))
        assertEquals(NumberCodeLookupFailure.SchemaMismatch, failure("""{"rollcall":[]}"""))
    }

    @Test fun unknownActivityTypeIsNotInterpretedAsNumberOrQr() {
        assertEquals("unknown", TronClassRollcall(id = 1, type = "future_type", source = "manual").getNormalizedType())
        assertEquals("unknown", TronClassRollcall(id = 1).getNormalizedType())
        assertEquals("unknown", TronClassRollcall(id = 1, type = "qr_code", isNumber = true).getNormalizedType())
        assertEquals("unknown", TronClassRollcall(id = 1, isRadar = true, isNumber = true).getNormalizedType())
    }

    @Test fun expiredDetailsAndMissingTargetContextCannotReturnACode() {
        assertEquals(NumberCodeLookupFailure.NoActiveRollcall, failure("""{"number_code":"1234","is_expired":true}"""))
        assertEquals(NumberCodeLookupFailure.SchemaMismatch, failure("""{"number_code":"1234","is_expired":"true"}"""))
        assertEquals(NumberCodeLookupFailure.NoActiveRollcall,
            (RollcallNumberCodeLookup.parse("""{"number_code":"1234"}""", target.copy(isExpired = true)) as NumberCodeLookupResult.Unavailable).reason)
        assertEquals(NumberCodeLookupFailure.ContextMissing,
            (RollcallNumberCodeLookup.parse("""{"number_code":"1234"}""", target.copy(courseId = 0)) as NumberCodeLookupResult.Unavailable).reason)
    }

    @Test fun studentHistoryUsesActivityIdInsteadOfTheStudentRecordId() {
        assertEquals(99001L, TronClassStudentRollcall(id = 17, rollcallId = 99001).getEffectiveId())
        assertEquals(17L, TronClassStudentRollcall(id = 17).getEffectiveId())
        assertFalse(TronClassStudentRollcall(id = 17, studentStatus = "not_late").isLateOrEarly())
    }
}
