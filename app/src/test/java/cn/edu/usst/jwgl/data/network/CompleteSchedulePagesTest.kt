package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.LappCourseItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompleteSchedulePagesTest {
    @Test fun eightThousandOfNineThousandIsIncomplete() = runTest {
        val result = runCatching { completeSchedulePages<Int>({ it }) { offset, rows ->
            9000 to if (offset < 8000) (offset until offset + rows).toList() else emptyList()
        } }
        assertTrue(result.isFailure)
    }

    @Test fun shortPagesUseActualOffsetsAndOnlyReturnWhenComplete() = runTest {
        val offsets = mutableListOf<Int>()
        val items = completeSchedulePages<Int>({ it }) { offset, _ ->
            offsets.add(offset)
            5 to (offset until minOf(offset + 2, 5)).toList()
        }
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals((0..4).toList(), items)
    }

    @Test fun duplicatePagesCannotSatisfyTotal() = runTest {
        assertTrue(runCatching { completeSchedulePages<Int>({ it }, 2) { _, _ -> 4 to listOf(1, 2) } }.isFailure)
    }

    @Test fun changingTotalsAndPageErrorsDoNotProduceSuccessfulResults() = runTest {
        assertTrue(runCatching { completeSchedulePages<Int>({ it }, 2) { offset, _ ->
            (if (offset == 0) 4 else 3) to listOf(offset, offset + 1)
        } }.isFailure)
        assertTrue(runCatching { completeSchedulePages<Int>({ it }) { _, _ -> error("offline") } }.isFailure)
    }

    @Test fun anExplicitZeroTotalIsACompleteEmptyResult() = runTest {
        assertTrue(completeSchedulePages<Int>({ it }) { _, _ -> 0 to emptyList() }.isEmpty())
    }

    @Test fun coursesOverlappingTheSelectedPeriodRemainVisible() {
        val course = LappCourseItem(jc = 2, jccd = 2, zc = "1,3,5", qsz = 1, jsz = 18)
        assertTrue(LappClient.matchesSection(course, "3-5"))
        assertFalse(LappClient.matchesSection(course, "6-7"))
        assertTrue(LappClient.isCourseActiveInWeek(course, 3))
        assertFalse(LappClient.isCourseActiveInWeek(course, 2))
    }
}
