package cn.edu.usst.jwgl.data.wakeup

data class TimeDetailBean(
    val node: Int,
    var startTime: String,
    var endTime: String,
    var timeTable: Int = 1
)

data class TimeTableBean(
    val id: Int,
    var name: String
)

data class TableSelectBean(
    val id: Int,
    val tableName: String,
    val background: String,
    val maxWeek: Int,
    val nodes: Int,
    val type: Int
)
