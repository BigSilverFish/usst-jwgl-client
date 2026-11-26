package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.AcademicCourse
import cn.edu.usst.jwgl.data.model.AcademicProgress
import cn.edu.usst.jwgl.data.model.CourseGrade
import cn.edu.usst.jwgl.data.model.CreditCategory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 教务系统学业进度与成绩累计计算纯解析器
 */
internal object JwglAcademicParser {
    private val gson = Gson()

    /**
     * 严格遵循上海理工大学学分绩点与加权平均分算法：
     * 1. 按课程去重（同一门课程如有重修/补考，合并为同一课程实体）。
     * 2. 优先选取重修通过/最高成绩的有效记录作为计算依据。
     */
    fun calculateCumulativeGrades(
        allCourses: List<CourseGrade>
    ): Triple<Double, Double, Double> {
        val coursesByKey = linkedMapOf<String, MutableList<CourseGrade>>()
        for (c in allCourses) {
            val key = c.courseId.ifBlank { c.courseName }
            if (key.isNotBlank()) {
                coursesByKey.getOrPut(key) { mutableListOf() }.add(c)
            }
        }

        var totalCredit = 0.0
        var totalScoreWeighted = 0.0
        var totalGpaWeighted = 0.0
        var validScoreCredits = 0.0

        for ((_, records) in coursesByKey) {
            val passedRecords = records.filter { it.gpa > 0.0 || (it.overallScore.toDoubleOrNull() ?: 0.0) >= 60.0 }
            val effective = if (passedRecords.isNotEmpty()) {
                passedRecords.maxWithOrNull(
                    compareBy<CourseGrade> { it.ksxz.contains("重修") }
                        .thenBy { it.gpa }
                        .thenBy { it.overallScore.toDoubleOrNull() ?: 0.0 }
                ) ?: passedRecords.last()
            } else {
                records.maxByOrNull { it.overallScore.toDoubleOrNull() ?: 0.0 } ?: records.last()
            }

            val scoreVal = effective.overallScore.toDoubleOrNull() ?: 0.0
            val isEffectivePassed = effective.gpa > 0.0 || scoreVal >= 60.0

            if (isEffectivePassed) {
                totalCredit += effective.credit
            }

            if (effective.credit > 0.0) {
                totalScoreWeighted += scoreVal * effective.credit
                totalGpaWeighted += effective.gpa * effective.credit
                validScoreCredits += effective.credit
            }
        }

        val cumulativeWeightedScore = if (validScoreCredits > 0) totalScoreWeighted / validScoreCredits else 0.0
        val cumulativeGpa = if (validScoreCredits > 0) totalGpaWeighted / validScoreCredits else 0.0

        return Triple(totalCredit, cumulativeWeightedScore, cumulativeGpa)
    }

    /**
     * 解析培养方案树形节点
     */
    fun parseAcademicProgress(html: String): AcademicProgress {
        val pattern = java.util.regex.Pattern.compile(
            "<p class='title1' id='p([0-9A-Za-z_-]+)' yxxf='([0-9.]*)' yqzdxf='([0-9.]*)' sftg='(\\d*)'>\"\\s*\\+\\s*\"([^\"&]+)&nbsp;"
        )
        val matcher = pattern.matcher(html)
        val nodes = linkedMapOf<String, CreditCategory>()

        while (matcher.find()) {
            val id = matcher.group(1) ?: continue
            val name = (matcher.group(5) ?: "").trim()
            if (name.isEmpty() || nodes.containsKey(id)) continue

            val earned = matcher.group(2)?.toDoubleOrNull() ?: 0.0
            val required = matcher.group(3)?.toDoubleOrNull() ?: 0.0
            val passed = matcher.group(4) == "1"
            val parentId = findParentCategoryId(html, matcher.start())

            nodes[id] = CreditCategory(
                id = id,
                parentId = parentId,
                name = name,
                earned = earned,
                required = required,
                passed = passed
            )
        }

        if (nodes.isEmpty()) {
            return AcademicProgress()
        }

        val ordered = nodes.values.toList()
        val root = ordered.maxByOrNull { it.required } ?: ordered.first()

        val childMap = mutableMapOf<String, MutableList<CreditCategory>>()
        for (node in ordered) {
            if (node.id == root.id) continue
            val parentId = if (nodes.containsKey(node.parentId)) node.parentId else root.id
            childMap.getOrPut(parentId) { mutableListOf() }.add(node)
        }

        fun attach(node: CreditCategory): CreditCategory {
            val children = (childMap[node.id] ?: emptyList()).map { attach(it) }
            return node.copy(children = children)
        }

        val rootNode = attach(root)
        return AcademicProgress(
            majorName = root.name,
            earned = root.earned,
            required = root.required,
            categories = rootNode.children,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun findParentCategoryId(html: String, nodeStart: Int): String {
        val start = if (nodeStart > 3000) nodeStart - 3000 else 0
        val window = html.substring(start, nodeStart)
        val p = java.util.regex.Pattern.compile("fxfyqjd_id='([0-9A-Za-z_-]*)'")
        val m = p.matcher(window)
        var lastParent = ""
        while (m.find()) {
            lastParent = m.group(1) ?: ""
        }
        return lastParent
    }

    fun parseAcademicCourses(raw: String): List<AcademicCourse> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val rawList: List<Map<String, Any?>> = try {
            gson.fromJson(trimmed, listType) ?: emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        fun Map<String, Any?>.getVal(vararg keys: String): String {
            for (key in keys) {
                val v = this[key] ?: this[key.lowercase()] ?: this[key.uppercase()]
                if (v != null) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty()) return s
                }
            }
            return ""
        }

        val out = mutableListOf<AcademicCourse>()
        for (item in rawList) {
            val name = item.getVal("KCMC", "kcmc")
            if (name.isEmpty()) continue
            val code = item.getVal("KCH", "kch", "KCH_ID", "kch_id")
            val creditsStr = item.getVal("XF", "xf", "JHXF", "jhxf")
            val credits = creditsStr.toDoubleOrNull() ?: 0.0
            val xnm = item.getVal("XNMC", "xnmmc", "JYXDXNMC", "jyxdxnmmc", "CJXNMC", "cjxnmc")
            val xqm = item.getVal("XQMMC", "xqmmc", "JYXDXQMC", "jyxdxqmc", "CJXQMC", "cjxqmc")
            val term = "$xnm $xqm".trim()
            val score = item.getVal("CJ", "cj", "MAXCJ", "maxcj", "BFZCJ", "bfzcj")
            val xdzt = item.getVal("XDZT", "xdzt")
            out.add(
                AcademicCourse(
                    name = name,
                    code = code,
                    credits = credits,
                    term = term,
                    score = score,
                    status = AcademicCourse.statusOf(xdzt)
                )
            )
        }
        return out
    }
}
