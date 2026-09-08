package cn.edu.usst.jwgl.data.wakeup

data class CourseBean(
    var id: Int = 0,
    var courseName: String = "",
    var day: Int = 1,
    var room: String? = "",
    var teacher: String? = "",
    var startNode: Int = 1,
    var step: Int = 2,
    var startWeek: Int = 1,
    var endWeek: Int = 16,
    var type: Int = 0, // 0=all, 1=odd, 2=even
    var color: String = "#4A90E2",
    var tableId: Int = 1
)
