package cn.edu.usst.jwgl.data.wakeup

data class CourseDetailBean(
    var id: Int = 0,
    var day: Int = 1, // 1=Monday .. 7=Sunday
    var room: String? = "",
    var teacher: String? = "",
    var startNode: Int = 1,
    var step: Int = 2,
    var startWeek: Int = 1,
    var endWeek: Int = 16,
    var type: Int = 0, // 0=all weeks, 1=single/odd weeks, 2=double/even weeks
    var tableId: Int = 1
)
