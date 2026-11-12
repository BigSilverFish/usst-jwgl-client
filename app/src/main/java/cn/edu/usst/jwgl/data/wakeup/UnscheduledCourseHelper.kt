package cn.edu.usst.jwgl.data.wakeup

import cn.edu.usst.jwgl.data.model.TimetableData
import cn.edu.usst.jwgl.data.model.TronClassCourse

data class UnscheduledCourseCandidate(
    val name: String,
    val teacher: String = "",
    val classCode: String = "",
    val courseCode: String = "",
    val examType: String = "",
    val credit: Double = 0.0,
    val sourceLabel: String = "教务未排课",
    val alreadyInTable: Boolean = false
)

object UnscheduledCourseHelper {

    fun collectCandidates(
        timetableData: TimetableData?,
        tronClassCourses: List<TronClassCourse>,
        existingCourses: List<CourseBaseBean>,
        expectedSemesterCode: String?
    ): List<UnscheduledCourseCandidate> {
        val existingNames = existingCourses.map { it.courseName.trim().lowercase() }.toSet()
        val scheduledNames = timetableData?.courses.orEmpty()
            .map { it.name.trim().lowercase() }
            .toSet()

        val collected = mutableListOf<UnscheduledCourseCandidate>()
        val seenKeys = mutableSetOf<String>()

        for (item in timetableData?.unscheduledCourses.orEmpty()) {
            val cleanName = item.name.trim()
            if (cleanName.isEmpty()) continue
            val dedupKey = "${cleanName.lowercase()}|${item.classCode.ifBlank { item.courseCode }.trim()}"
            if (!seenKeys.add(dedupKey)) continue

            val inTable = existingNames.contains(cleanName.lowercase())
            collected.add(
                UnscheduledCourseCandidate(
                    name = cleanName,
                    teacher = item.teacher.trim(),
                    classCode = item.classCode.ifBlank { item.courseCode }.trim(),
                    courseCode = item.courseCode.trim(),
                    examType = item.examType.trim(),
                    credit = item.credit,
                    sourceLabel = "教务未排课",
                    alreadyInTable = inTable
                )
            )
        }

        val collectedLowerNames = collected.map { it.name.lowercase() }.toSet()
        for (tc in tronClassCourses) {
            val classCode = tc.courseCode?.trim().orEmpty()
            if (classCode.startsWith("_uuid_", ignoreCase = true)) {
                continue
            }
            val semCode = tc.extractSemesterCode()
            if (!expectedSemesterCode.isNullOrBlank() && semCode != expectedSemesterCode) {
                continue
            }
            val cleanName = tc.getCleanName().trim()
            if (cleanName.isEmpty() || cleanName == "未命名课程") continue
            val lower = cleanName.lowercase()
            if (lower in scheduledNames || lower in collectedLowerNames || lower in existingNames) {
                continue
            }
            val dedupKey = "$lower|$classCode"
            if (!seenKeys.add(dedupKey)) continue

            val teacher = tc.teacher?.trim().orEmpty().ifBlank {
                tc.instructors.firstOrNull()?.trim().orEmpty()
            }
            val guessedExamType = if (cleanName.contains("实验") || cleanName.contains("设计") || cleanName.contains("实践")) "考查" else ""
            collected.add(
                UnscheduledCourseCandidate(
                    name = cleanName,
                    teacher = teacher,
                    classCode = classCode,
                    courseCode = "",
                    examType = guessedExamType,
                    credit = 0.0,
                    sourceLabel = "一网畅学",
                    alreadyInTable = false
                )
            )
        }

        return collected.sortedBy { it.alreadyInTable }
    }
}
