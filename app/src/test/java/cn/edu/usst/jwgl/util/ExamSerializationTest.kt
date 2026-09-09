package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.ExamItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamSerializationTest {

    @Test
    fun testExamItemGson() {
        val gson = Gson()
        val list = listOf(
            ExamItem(
                courseName = "高等数学A(1)",
                examTime = "2027-01-13 09:00-11:00",
                location = "一教301",
                seatNumber = "25"
            ),
            ExamItem(
                courseName = "大学物理B",
                examTime = "2027-01-14 13:00-15:00",
                location = "二教205",
                seatNumber = "12"
            )
        )
        val json = gson.toJson(list)
        val type = object : TypeToken<List<ExamItem>>() {}.type
        val restored: List<ExamItem> = gson.fromJson(json, type)

        assertEquals(2, restored.size)
        assertEquals("高等数学A(1)", restored[0].courseName)
        assertEquals("2027-01-13", restored[0].getDateString())
        assertEquals(1, restored[0].getSessionIndex())
        assertEquals("09:00-11:00", restored[0].getTimeRangeString())

        assertEquals("大学物理B", restored[1].courseName)
        assertEquals("2027-01-14", restored[1].getDateString())
        assertEquals(2, restored[1].getSessionIndex())
        assertEquals("13:00-15:00", restored[1].getTimeRangeString())
    }
}
