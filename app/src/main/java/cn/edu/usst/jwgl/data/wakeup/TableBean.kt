package cn.edu.usst.jwgl.data.wakeup

data class TableBean(
    var id: Int = 0,
    var tableName: String = "默认课表",
    var nodes: Int = 13,
    var background: String = "",
    var timeTable: Int = 1,
    var startDate: String = "2026-09-07",
    var maxWeek: Int = 20,
    var itemHeight: Int = 56, // in dp
    var itemAlpha: Int = 75,  // 0-100
    var itemTextSize: Int = 11, // in sp
    var strokeColor: Int = 0x40FFFFFF,
    var textColor: Int = 0xFF1F2937.toInt(),
    var courseTextColor: Int = 0xFFFFFFFF.toInt(),
    var showSat: Boolean = false,
    var showSun: Boolean = false,
    var sundayFirst: Boolean = false,
    var showOtherWeekCourse: Boolean = true,
    var showTime: Boolean = true,
    var type: Int = 1 // 1=active/default table, 0=inactive
)
