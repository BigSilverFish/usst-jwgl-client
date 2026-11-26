package cn.edu.usst.jwgl.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseReminderAccountIsolationTest {

    @Test
    fun shouldDeliverReminderForAccount_enforcesStrictStudentMatch() {
        assertTrue(CourseReminderReceiver.shouldDeliverReminderForAccount("2435055230", "2435055230"))
        assertTrue(CourseReminderReceiver.shouldDeliverReminderForAccount(" 2435055230 ", "2435055230"))
        assertFalse(CourseReminderReceiver.shouldDeliverReminderForAccount("2435055231", "2435055230"))
        assertFalse(CourseReminderReceiver.shouldDeliverReminderForAccount("2435055230", ""))
        assertFalse(CourseReminderReceiver.shouldDeliverReminderForAccount(null, ""))
        assertTrue(CourseReminderReceiver.shouldDeliverReminderForAccount(null, "2435055230"))
    }
}
