package cn.edu.usst.jwgl.data.model

import java.io.Serializable

data class CourseItem(
    val id: String,
    val name: String,
    val teacher: String,
    val classroom: String,
    val dayOfWeek: Int, // 1 = 周一 .. 7 = 周日
    val startSection: Int, // 1 .. 12
    val step: Int, // 节数跨度 (如 6-7节 为 2)
    val rawSections: String, // "6-7"
    val weeks: List<Int>, // 包含周次列表 [3, 4, 5, ... 18]
    val rawWeeks: String, // "3-18周"
    val credit: Double = 0.0,
    val courseType: String = "",
    var colorIndex: Int = 0,
    val courseCode: String = "", // 课程代码 (如 12004546)
    val examType: String = "" // 考察形式 (考试 / 考查)
) : Serializable {
    fun isInWeek(week: Int): Boolean = weeks.isEmpty() || weeks.contains(week)
}

data class TimetableData(
    val academicYear: String,
    val semester: String,
    val semesterTitle: String,
    val studentName: String,
    val className: String,
    val courses: List<CourseItem>,
    val currentWeek: Int = 3
) : Serializable