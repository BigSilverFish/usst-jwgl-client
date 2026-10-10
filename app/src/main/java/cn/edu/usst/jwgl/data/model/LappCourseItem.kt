package cn.edu.usst.jwgl.data.model

import java.io.Serializable

data class LappCourseItem(
    val id: String = "",
    val bjdm: String = "",
    val bjmc: String = "",
    val kcdm: String = "",
    val kcmc: String = "",
    val kclx: String = "",
    val xf: String = "",
    val xs: String = "",
    val xy: String = "",
    val lsmc: String = "",
    val lsgh: String = "",
    val lszc: String = "",
    val zj: Int = 1,
    val jc: Int = 1,
    val jccd: Int = 2,
    val classTimeBegin: String = "",
    val classTimeEnd: String = "",
    val jsdd: String = "",
    val jsbh: String = "",
    val qsz: Int = 1,
    val jsz: Int = 18,
    val zc: String = "",
    val xn: String = "",
    val xq: String = ""
) : Serializable {

    val weekList: List<Int> by lazy {
        if (zc.isNotEmpty()) {
            zc.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..30 }
        } else if (qsz in 1..30 && jsz >= qsz) {
            (qsz..jsz).toList()
        } else {
            emptyList()
        }
    }

    val formattedDayOfWeek: String
        get() = when (zj) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7, 0 -> "周日"
            else -> "周" + zj
        }

    val formattedSections: String
        get() = if (jccd > 1) {
            "第" + jc + "-" + (jc + jccd - 1) + "节"
        } else {
            "第" + jc + "节"
        }

    val formattedTime: String
        get() {
            val start = if (classTimeBegin.length >= 5) classTimeBegin.substring(0, 5) else classTimeBegin
            val end = if (classTimeEnd.length >= 5) classTimeEnd.substring(0, 5) else classTimeEnd
            return if (start.isNotEmpty() && end.isNotEmpty()) start + "-" + end else ""
        }

    val formattedWeeks: String
        get() {
            return if (weekList.isNotEmpty()) {
                val sorted = weekList.sorted()
                val minW = sorted.first()
                val maxW = sorted.last()
                val isConsecutive = sorted.size == (maxW - minW + 1)
                val isStep2 = sorted.size > 1 && sorted.zipWithNext().all { (a, b) -> b - a == 2 }
                val isAllOdd = isStep2 && sorted.all { it % 2 != 0 }
                val isAllEven = isStep2 && sorted.all { it % 2 == 0 }

                if (isConsecutive) {
                    if (minW == maxW) "${minW}周" else "${minW}-${maxW}周"
                } else if (isAllOdd) {
                    "${minW}-${maxW}周(单周)"
                } else if (isAllEven) {
                    "${minW}-${maxW}周(双周)"
                } else {
                    "${minW}-${maxW}周(共${sorted.size}周)"
                }
            } else if (qsz > 0 && jsz >= qsz) {
                if (qsz == jsz) "${qsz}周" else "${qsz}-${jsz}周"
            } else {
                "全学期"
            }
        }

    fun toCourseItem(): CourseItem {
        return CourseItem(
            id = "lapp_" + (if (bjdm.isNotEmpty()) bjdm else if (id.isNotEmpty()) id else System.currentTimeMillis().toString()),
            name = if (kcmc.isNotEmpty()) kcmc else "未命名课程",
            teacher = if (lszc.isNotEmpty() && lszc != "无") lsmc + " (" + lszc + ")" else lsmc,
            classroom = if (jsdd.isNotEmpty()) jsdd else "待定",
            dayOfWeek = if (zj in 1..7) zj else 1,
            startSection = if (jc in 1..13) jc else 1,
            step = if (jccd in 1..8) jccd else 2,
            rawSections = if (jccd > 1) "" + jc + "-" + (jc + jccd - 1) else "" + jc,
            weeks = weekList,
            rawWeeks = formattedWeeks,
            credit = xf.toDoubleOrNull() ?: 0.0,
            courseType = kclx,
            courseCode = kcdm,
            examType = ""
        )
    }
}