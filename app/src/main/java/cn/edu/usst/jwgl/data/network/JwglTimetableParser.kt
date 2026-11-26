package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.CourseItem
import cn.edu.usst.jwgl.data.model.TimetableData
import cn.edu.usst.jwgl.data.model.UnscheduledCourseItem
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务系统课表与周次纯解析器
 */
internal object JwglTimetableParser {

    private fun JsonObject.optStr(key: String): String {
        val el = this.get(key) ?: return ""
        return if (el.isJsonNull) "" else el.asString.trim()
    }

    fun parseFastTimetableJson(
        body: String,
        xnm: String,
        xqm: String,
        studentName: String = "",
        className: String = "",
        currentWeek: Int = 1
    ): TimetableData {
        val root = JsonParser.parseString(body).asJsonObject
        val items = if (root.has("items") && root.get("items").isJsonArray) {
            root.getAsJsonArray("items")
        } else {
            JsonArray()
        }

        val yearName = "$xnm-${(xnm.toIntOrNull() ?: 2025) + 1}"
        val semName = if (xqm == "3") "1" else if (xqm == "12") "2" else xqm
        val semTitle = "${yearName}学年 第${semName}学期"

        val courses = mutableListOf<CourseItem>()
        val unscheduledCourses = mutableListOf<UnscheduledCourseItem>()
        val seenUnscheduledKeys = mutableSetOf<String>()
        val courseColorMap = mutableMapOf<String, Int>()
        var nextColor = 0

        val sectionRegex = Regex("""(?:第)?([0-9]+)(?:-([0-9]+))?节""")
        val weekRegex = Regex("""\{([^}]+)\}""")

        for (el in items) {
            if (!el.isJsonObject) continue
            val item = el.asJsonObject
            val courseName = item.optStr("kcmc")
            if (courseName.isEmpty()) continue

            val sksj = item.optStr("sksj")
            val jxdd = item.optStr("jxdd")
            val jsxx = item.optStr("jsxx")
            val teacher = if (jsxx.contains("/")) {
                val p = jsxx.split("/")
                if (p.size >= 2) p[1].trim() else jsxx
            } else jsxx

            val credit = item.optStr("xf").toDoubleOrNull() ?: 0.0
            val courseType = item.optStr("kcxz")
            val courseCode = item.optStr("kch")
            val classCode = item.optStr("jxbmc")
                .ifEmpty { item.optStr("jxb") }
                .ifEmpty { item.optStr("jxb_id") }

            var examType = item.optStr("khfsmc").ifEmpty { item.optStr("khfs") }
            if (examType.isEmpty()) {
                examType = if (courseType.contains("考查") || courseType.contains("设计") || courseType.contains("实践") || courseName.contains("设计") || courseName.contains("实验")) "考查" else "考试"
            }

            fun recordUnscheduled() {
                val key = "${courseName}_${classCode.ifBlank { courseCode }}_$teacher"
                if (seenUnscheduledKeys.add(key)) {
                    unscheduledCourses.add(
                        UnscheduledCourseItem(
                            name = courseName,
                            teacher = teacher,
                            classCode = classCode,
                            courseCode = courseCode,
                            examType = examType,
                            credit = credit,
                            courseType = courseType
                        )
                    )
                }
            }

            if (sksj.isEmpty() || sksj == "null") {
                recordUnscheduled()
                continue
            }

            val sksjParts = sksj.split(";", "；", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (sksjParts.isEmpty()) {
                recordUnscheduled()
                continue
            }
            val jxddParts = if (jxdd.isNotEmpty() && jxdd != "null") {
                jxdd.split(";", "；", "\n").map { it.trim() }
            } else emptyList()

            for ((idx, sp) in sksjParts.withIndex()) {
                val dayOfWeek = when {
                    sp.contains("星期一") || sp.contains("周一") -> 1
                    sp.contains("星期二") || sp.contains("周二") -> 2
                    sp.contains("星期三") || sp.contains("周三") -> 3
                    sp.contains("星期四") || sp.contains("周四") -> 4
                    sp.contains("星期五") || sp.contains("周五") -> 5
                    sp.contains("星期六") || sp.contains("周六") -> 6
                    sp.contains("星期日") || sp.contains("星期天") || sp.contains("周日") -> 7
                    else -> 1
                }

                val secMatch = sectionRegex.find(sp)
                val startSection = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val endSection = secMatch?.groupValues?.get(2)?.toIntOrNull() ?: startSection
                val step = (endSection - startSection + 1).coerceIn(1, 6)
                val rawSections = "$startSection-$endSection"

                val weekMatch = weekRegex.find(sp)
                val rawWeeks = weekMatch?.groupValues?.get(1)?.trim() ?: ""
                val weeks = parseWeeks(rawWeeks)

                val classroom = if (jxddParts.isNotEmpty()) {
                    if (idx < jxddParts.size) jxddParts[idx] else jxddParts[0]
                } else ""

                val slotId = if (courseCode.isNotEmpty()) {
                    "${courseCode}_${dayOfWeek}_${startSection}_${step}"
                } else {
                    "slot_${courseName.hashCode().let { kotlin.math.abs(it) }}_${dayOfWeek}_${startSection}_${step}"
                }

                val colorIdx = courseColorMap.getOrPut(courseName) {
                    val c = nextColor
                    nextColor++
                    c
                }

                courses.add(
                    CourseItem(
                        id = slotId,
                        name = courseName,
                        teacher = teacher,
                        classroom = classroom,
                        dayOfWeek = dayOfWeek,
                        startSection = startSection,
                        step = step,
                        rawSections = rawSections,
                        weeks = weeks,
                        rawWeeks = rawWeeks,
                        credit = credit,
                        courseType = courseType,
                        colorIndex = colorIdx,
                        courseCode = courseCode,
                        examType = examType,
                        classCode = classCode
                    )
                )
            }
        }

        val scheduledNames = courses.map { it.name.trim() }.toSet()
        val filteredUnscheduled = unscheduledCourses.filter { it.name.trim() !in scheduledNames }

        return TimetableData(
            academicYear = xnm,
            semester = xqm,
            semesterTitle = semTitle,
            studentName = studentName,
            className = className,
            courses = courses.distinct(),
            currentWeek = currentWeek,
            unscheduledCourses = filteredUnscheduled
        )
    }

    fun parseStandardTimetableJson(
        body: String,
        xnm: String,
        xqm: String,
        currentWeek: Int
    ): TimetableData {
        val json = JSONObject(body)
        val kbList = json.optJSONArray("kbList") ?: JSONArray()
        val sjkList = json.optJSONArray("sjkList") ?: JSONArray()
        val xsxx = json.optJSONObject("xsxx")

        val studentName = xsxx?.optString("XM", "") ?: ""
        val className = xsxx?.optString("BJMC", "") ?: ""
        val yearName = xsxx?.optString("XNMC", "$xnm-${(xnm.toIntOrNull() ?: 2025) + 1}")
        val semName = xsxx?.optString("XQMMC", if (xqm == "3") "1" else if (xqm == "12") "2" else xqm)
        val semTitle = "${yearName}学年 第${semName}学期"

        val courses = mutableListOf<CourseItem>()
        val unscheduledCourses = mutableListOf<UnscheduledCourseItem>()
        val seenUnscheduledKeys = mutableSetOf<String>()
        val courseColorMap = mutableMapOf<String, Int>()
        var nextColor = 0

        for (i in 0 until kbList.length()) {
            val item = kbList.optJSONObject(i) ?: continue
            val courseName = item.optString("kcmc", "").trim()
            if (courseName.isEmpty()) continue

            val teacher = item.optString("xm", "").trim()
            val classroom = item.optString("cdmc", "").trim()
            val dayRaw = item.optString("xqj", "").trim()
            val dayOfWeek = dayRaw.toIntOrNull() ?: 1
            val jcs = item.optString("jcs", "").trim()
            val zcd = item.optString("zcd", "").trim()
            val credit = item.optString("xf", "0").toDoubleOrNull() ?: 0.0
            val courseType = item.optString("kcxz", "").trim()
            val courseCode = item.optString("kch", item.optString("kch_id", "")).trim()
            val classCode = item.optString("jxbmc", item.optString("jxb", "")).trim()

            var examType = item.optString("khfsmc", item.optString("khfs", "")).trim()
            if (examType.isEmpty()) {
                examType = if (courseType.contains("考查") || courseType.contains("设计") || courseType.contains("实践") || courseName.contains("设计") || courseName.contains("实验")) "考查" else "考试"
            }

            if (jcs.isEmpty() || dayRaw.isEmpty()) {
                val key = "${courseName}_${classCode.ifBlank { courseCode }}_$teacher"
                if (seenUnscheduledKeys.add(key)) {
                    unscheduledCourses.add(
                        UnscheduledCourseItem(
                            name = courseName,
                            teacher = teacher,
                            classCode = classCode,
                            courseCode = courseCode,
                            examType = examType,
                            credit = credit,
                            courseType = courseType
                        )
                    )
                }
                continue
            }

            val jcParts = jcs.split("-")
            val startSection = jcParts.firstOrNull()?.toIntOrNull() ?: 1
            val endSection = jcParts.lastOrNull()?.toIntOrNull() ?: startSection
            val step = (endSection - startSection + 1).coerceIn(1, 6)

            val slotId = if (courseCode.isNotEmpty()) {
                "${courseCode}_${dayOfWeek}_${startSection}_${step}"
            } else {
                "slot_${courseName.hashCode().let { kotlin.math.abs(it) }}_${dayOfWeek}_${startSection}_${step}"
            }

            val weeks = parseWeeks(zcd)

            val colorIdx = courseColorMap.getOrPut(courseName) {
                val c = nextColor
                nextColor++
                c
            }

            courses.add(
                CourseItem(
                    id = slotId,
                    name = courseName,
                    teacher = teacher,
                    classroom = classroom,
                    dayOfWeek = dayOfWeek,
                    startSection = startSection,
                    step = step,
                    rawSections = jcs,
                    weeks = weeks,
                    rawWeeks = zcd,
                    credit = credit,
                    courseType = courseType,
                    colorIndex = colorIdx,
                    courseCode = courseCode,
                    examType = examType,
                    classCode = classCode
                )
            )
        }

        for (i in 0 until sjkList.length()) {
            val item = sjkList.optJSONObject(i) ?: continue
            val courseName = item.optString("kcmc", "").trim()
            if (courseName.isEmpty()) continue

            val teacher = item.optString("xm", item.optString("jsmc", "")).trim()
            val credit = item.optString("xf", "0").toDoubleOrNull() ?: 0.0
            val courseType = item.optString("kcxz", "").trim()
            val courseCode = item.optString("kch", item.optString("kch_id", "")).trim()
            val classCode = item.optString("jxbmc", item.optString("jxb", "")).trim()

            var examType = item.optString("khfsmc", item.optString("khfs", "")).trim()
            if (examType.isEmpty()) {
                examType = if (courseType.contains("考查") || courseType.contains("设计") || courseType.contains("实践") || courseName.contains("设计") || courseName.contains("实验")) "考查" else "考试"
            }

            val key = "${courseName}_${classCode.ifBlank { courseCode }}_$teacher"
            if (seenUnscheduledKeys.add(key)) {
                unscheduledCourses.add(
                    UnscheduledCourseItem(
                        name = courseName,
                        teacher = teacher,
                        classCode = classCode,
                        courseCode = courseCode,
                        examType = examType,
                        credit = credit,
                        courseType = courseType
                    )
                )
            }
        }

        val scheduledNames = courses.map { it.name.trim() }.toSet()
        val filteredUnscheduled = unscheduledCourses.filter { it.name.trim() !in scheduledNames }

        return TimetableData(
            academicYear = xnm,
            semester = xqm,
            semesterTitle = semTitle,
            studentName = studentName,
            className = className,
            courses = courses,
            currentWeek = currentWeek,
            unscheduledCourses = filteredUnscheduled
        )
    }

    fun parseWeeks(zcd: String): List<Int> {
        val result = mutableSetOf<Int>()
        if (zcd.isEmpty()) return emptyList()
        val segments = zcd.split(",", "，")
        for (seg in segments) {
            val isSingle = seg.contains("单")
            val isDouble = seg.contains("双")
            val clean = seg.replace("[^0-9-]".toRegex(), "")
            if (clean.contains("-")) {
                val parts = clean.split("-")
                val start = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val end = parts.getOrNull(1)?.toIntOrNull() ?: start
                for (w in start..end) {
                    if (isSingle && w % 2 == 0) continue
                    if (isDouble && w % 2 != 0) continue
                    result.add(w)
                }
            } else {
                clean.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result.sorted()
    }
}
