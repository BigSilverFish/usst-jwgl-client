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
            var start = if (classTimeBegin.length >= 5) classTimeBegin.substring(0, 5) else classTimeBegin
            var end = if (classTimeEnd.length >= 5) classTimeEnd.substring(0, 5) else classTimeEnd
            if (start.isBlank()) {
                start = getStandardStartTime(jc)
            }
            if (end.isBlank()) {
                val endSec = if (jccd > 1) jc + jccd - 1 else jc
                end = getStandardEndTime(endSec)
            }
            return if (start.isNotEmpty() && end.isNotEmpty()) "$start-$end" else ""
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

    companion object {
        private val standardSectionStartTimes = mapOf(
            1 to "08:00", 2 to "08:45", 3 to "09:45", 4 to "10:30", 5 to "11:15",
            6 to "13:00", 7 to "13:45", 8 to "14:45", 9 to "15:30", 10 to "16:15",
            11 to "18:00", 12 to "18:45", 13 to "19:30"
        )
        private val standardSectionEndTimes = mapOf(
            1 to "08:40", 2 to "09:25", 3 to "10:25", 4 to "11:10", 5 to "11:55",
            6 to "13:40", 7 to "14:25", 8 to "15:25", 9 to "16:10", 10 to "16:55",
            11 to "18:40", 12 to "19:25", 13 to "20:10"
        )

        fun getStandardStartTime(section: Int): String = standardSectionStartTimes[section] ?: ""
        fun getStandardEndTime(section: Int): String = standardSectionEndTimes[section] ?: ""
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

    /**
     * 区分本研课程：
     * 本科课程班级号格式：(xxxx-xxxx-x)-xxxxxxxx-xx，例如 (2026-2027-1)-22000210-10
     * 研究生课程班级号格式：xxxxxxxx-xxxxx-xxx班，例如 32000010-26271-军工路01班、12000449-26271-营口路1班
     */
    val isGraduate: Boolean
        get() {
            val text = if (bjmc.isNotBlank()) bjmc else bjdm
            // 研究生课程典型特征：形如 xxxxxxxx-xxxxx-xxx班，或包含"-26271-"且以"班"结尾
            val graduateRegex = Regex("""^\d{6,10}-\d{4,6}-.+班$""")
            return graduateRegex.matches(text.trim()) || text.contains("-26271-") || (text.endsWith("班") && !text.startsWith("("))
        }

    val isUndergraduate: Boolean
        get() {
            val text = if (bjmc.isNotBlank()) bjmc else bjdm
            // 本科课程典型特征：形如 (xxxx-xxxx-x)-xxxxxxxx-xx
            val undergraduateRegex = Regex("""^\(\d{4}-\d{4}-\d\)-\d{6,10}-\d{2}$""")
            return undergraduateRegex.matches(text.trim()) || text.startsWith("(") || (!isGraduate && text.isNotBlank())
        }

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

                val mergedSlots = mergeConsecutiveSlots(uniqueSlots)

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
                    slots = mergedSlots,
                    rawItems = groupItems,
                    category = initialCategory
                )
            }
        }

        /**
         * 合并连续节次：
         * 针对研究生课程（或部分排课系统按单节 1 节存储的课程），若在同一星期、同一教室、相同上课周次且节次连续，
         * 例如第3节、第4节、第5节，自动合并为“第3-5节”，起始与结束时间也相应扩展。
         */
        private fun mergeConsecutiveSlots(slots: List<CourseTimeSlot>): List<CourseTimeSlot> {
            if (slots.size <= 1) return slots

            // 按 (星期, 教室, 周次标识) 进行分组
            val groupedByContext = slots.groupBy { slot ->
                val weeksKey = if (slot.weekList.isNotEmpty()) {
                    slot.weekList.sorted().joinToString(",")
                } else if (slot.zc.isNotBlank()) {
                    slot.zc.trim()
                } else {
                    "${slot.qsz}_${slot.jsz}"
                }
                "${slot.zj}_${slot.jsdd.trim().lowercase()}_$weeksKey"
            }

            val resultSlots = mutableListOf<CourseTimeSlot>()

            for ((_, group) in groupedByContext) {
                val sortedGroup = group.sortedBy { it.jc }
                var current: CourseTimeSlot? = null

                for (next in sortedGroup) {
                    if (current == null) {
                        current = next
                    } else {
                        val currentEndSection = current.jc + current.jccd
                        if (next.jc == currentEndSection) {
                            // 节次恰好紧密相连 (例如 current 为第3节，next 为第4节；或 current 为第3-4节，next 为第5节)
                            val mergedJccd = current.jccd + next.jccd
                            val beginTime = current.classTimeBegin.ifBlank { next.classTimeBegin }
                            val endTime = next.classTimeEnd.ifBlank { current.classTimeEnd }
                            current = current.copy(
                                jccd = mergedJccd,
                                classTimeBegin = beginTime,
                                classTimeEnd = endTime
                            )
                        } else if (next.jc == current.jc) {
                            // 起始节次完全相同，保留跨度更大的记录
                            if (next.jccd > current.jccd) {
                                current = next
                            }
                        } else if (next.jc < currentEndSection) {
                            // 部分重叠节次
                            val maxEnd = maxOf(currentEndSection, next.jc + next.jccd)
                            val mergedJccd = maxEnd - current.jc
                            val endTime = if (next.classTimeEnd.isNotBlank()) next.classTimeEnd else current.classTimeEnd
                            current = current.copy(
                                jccd = mergedJccd,
                                classTimeEnd = endTime
                            )
                        } else {
                            // 节次不相连（例如上午 1-2 节与下午 6-7 节），结算前一时段并开启新时段
                            resultSlots.add(current)
                            current = next
                        }
                    }
                }
                if (current != null) {
                    resultSlots.add(current)
                }
            }

            return resultSlots.sortedWith(compareBy({ it.zj }, { it.jc }))
        }
    }
}
