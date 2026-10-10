package cn.edu.usst.jwgl.data.model

import cn.edu.usst.jwgl.data.network.CourseCatalogService
import java.io.Serializable

data class CourseTimeSlot(
    val zj: Int,                // 星期 1..7
    val jc: Int,                // 开始节次
    val jccd: Int,              // 连续节数
    val classTimeBegin: String, // "08:00"
    val classTimeEnd: String,   // "09:25"
    val jsdd: String,           // 教室地点 (如 一教301)
    val jsbh: String,           // 教室编号
    val qsz: Int,               // 起始周
    val jsz: Int,               // 结束周
    val zc: String,             // 周次 (如 "1,2,3,4,5,6,7,8")
    val weekList: List<Int>,
    val rawItem: LappCourseItem
) : Serializable {
    val formattedDayOfWeek: String
        get() = when (zj) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7, 0 -> "周日"
            else -> "周$zj"
        }

    val formattedSections: String
        get() = if (jccd > 1) "第${jc}-${jc + jccd - 1}节" else "第${jc}节"

    val formattedTime: String
        get() {
            val start = if (classTimeBegin.length >= 5) classTimeBegin.substring(0, 5) else classTimeBegin
            val end = if (classTimeEnd.length >= 5) classTimeEnd.substring(0, 5) else classTimeEnd
            return if (start.isNotEmpty() && end.isNotEmpty()) "$start-$end" else ""
        }

    val formattedWeeks: String
        get() {
            return if (weekList.isNotEmpty()) {
                val minW = weekList.first()
                val maxW = weekList.last()
                if (weekList.size == (maxW - minW + 1)) {
                    "${minW}-${maxW}周"
                } else {
                    "${minW}-${maxW}周(共${weekList.size}周)"
                }
            } else if (qsz > 0 && jsz >= qsz) {
                "${qsz}-${jsz}周"
            } else {
                "全学期"
            }
        }
}

data class MergedSchoolCourse(
    val classKey: String,
    val kcmc: String,
    val kcdm: String,
    val kclx: String,
    val xf: String,
    val xs: String,
    val xy: String,
    val lsmc: String,
    val lsgh: String,
    val lszc: String,
    val bjmc: String,
    val bjdm: String,
    val xn: String,
    val xq: String,
    val slots: List<CourseTimeSlot>,
    val rawItems: List<LappCourseItem>,
    var category: String = "待定"
) : Serializable {

    fun toCourseItems(): List<CourseItem> {
        val baseId = if (bjdm.isNotEmpty()) bjdm else (if (kcdm.isNotEmpty()) kcdm else kcmc)
        return slots.mapIndexed { index, slot ->
            CourseItem(
                id = "lapp_${baseId}_slot$index",
                name = kcmc.ifEmpty { "未命名课程" },
                teacher = if (lszc.isNotEmpty() && lszc != "无") "$lsmc ($lszc)" else lsmc,
                classroom = slot.jsdd.ifEmpty { "待定" },
                dayOfWeek = if (slot.zj in 1..7) slot.zj else 1,
                startSection = if (slot.jc in 1..13) slot.jc else 1,
                step = if (slot.jccd in 1..8) slot.jccd else 2,
                rawSections = if (slot.jccd > 1) "${slot.jc}-${slot.jc + slot.jccd - 1}" else "${slot.jc}",
                weeks = slot.weekList,
                rawWeeks = slot.formattedWeeks,
                credit = xf.toDoubleOrNull() ?: 0.0,
                courseType = kclx,
                courseCode = kcdm,
                examType = ""
            )
        }
    }

    companion object {
        fun fromLappItems(items: List<LappCourseItem>): List<MergedSchoolCourse> {
            // Group by teaching class: bjdm if present, otherwise kcdm + lsmc + bjmc
            val grouped = items.groupBy { item ->
                if (item.bjdm.isNotBlank()) {
                    item.bjdm
                } else {
                    "${item.kcdm}_${item.kcmc}_${item.lsmc}_${item.bjmc}"
                }
            }

            return grouped.map { (_, groupItems) ->
                val first = groupItems.first()
                val uniqueSlots = mutableListOf<CourseTimeSlot>()
                val seenSlots = mutableSetOf<String>()

                for (item in groupItems) {
                    val slotKey = "${item.zj}_${item.jc}_${item.jccd}_${item.jsdd}_${item.zc}"
                    if (!seenSlots.contains(slotKey)) {
                        seenSlots.add(slotKey)
                        uniqueSlots.add(
                            CourseTimeSlot(
                                zj = item.zj,
                                jc = item.jc,
                                jccd = item.jccd,
                                classTimeBegin = item.classTimeBegin,
                                classTimeEnd = item.classTimeEnd,
                                jsdd = item.jsdd,
                                jsbh = item.jsbh,
                                qsz = item.qsz,
                                jsz = item.jsz,
                                zc = item.zc,
                                weekList = item.weekList,
                                rawItem = item
                            )
                        )
                    }
                }

                val sortedSlots = uniqueSlots.sortedWith(compareBy({ it.zj }, { it.jc }))

                val initialCategory = CourseCatalogService.getCategoryFromCacheOrHeuristic(first.kcdm, first.kcmc)

                MergedSchoolCourse(
                    classKey = if (first.bjdm.isNotBlank()) first.bjdm else "${first.kcdm}_${first.kcmc}_${first.lsmc}",
                    kcmc = first.kcmc,
                    kcdm = first.kcdm,
                    kclx = first.kclx,
                    xf = first.xf,
                    xs = first.xs,
                    xy = first.xy,
                    lsmc = first.lsmc,
                    lsgh = first.lsgh,
                    lszc = first.lszc,
                    bjmc = first.bjmc,
                    bjdm = first.bjdm,
                    xn = first.xn,
                    xq = first.xq,
                    slots = sortedSlots,
                    rawItems = groupItems,
                    category = initialCategory
                )
            }
        }
    }
}
