package cn.edu.usst.jwgl.data.wakeup

data class LabReservation(
    val name: String,
    val day: Int,
    val startNode: Int,
    val step: Int,
    val week: Int,
    val room: String = ""
)
