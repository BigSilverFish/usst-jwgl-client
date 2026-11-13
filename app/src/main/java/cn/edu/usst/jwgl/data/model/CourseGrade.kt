package cn.edu.usst.jwgl.data.model

import java.io.Serializable

data class CourseGrade(
    val courseId: String = "",
    val courseName: String = "",
    val credit: Double = 0.0,
    val regularScore: String = "",
    val regularRatio: String = "",
    val finalScore: String = "",
    val finalRatio: String = "",
    val overallScore: String = "",
    val gpa: Double = 0.0,
    val yearName: String = "",
    val semesterName: String = "",
    val semesterTitle: String = "",
    val examType: String = "" // 考核方式: 考试 / 考查
) : Serializable

data class SemesterGradeSummary(
    val semesterTitle: String = "",
    val totalCredits: Double = 0.0,
    val weightedAverageScore: Double = 0.0,
    val weightedGpa: Double = 0.0,
    val courses: List<CourseGrade> = emptyList()
) : Serializable

data class GradeReport(
    val totalCredits: Double = 0.0,
    val cumulativeWeightedScore: Double = 0.0,
    val cumulativeGpa: Double = 0.0,
    val semesters: List<SemesterGradeSummary> = emptyList()
) : Serializable

/**
 * 培养方案树上的学分节点（大类 / 子类，如“通识教育课程”、“专业核心课程”等）。
 */
data class CreditCategory(
    val id: String = "",
    val parentId: String = "",
    val name: String = "",
    val earned: Double = 0.0,
    val required: Double = 0.0,
    val passed: Boolean = false,
    val children: List<CreditCategory> = emptyList()
) : Serializable {
    val isLeaf: Boolean get() = children.isEmpty()
    val missing: Double get() = (required - earned).coerceAtLeast(0.0)
    val progress: Double get() = if (required <= 0) 1.0 else (earned / required).coerceIn(0.0, 1.0)
}

/**
 * 培养方案节点下的一门具体课程（来自 Kcxx / FKcxx 接口）。
 */
data class AcademicCourse(
    val name: String = "",
    val code: String = "",
    val credits: Double = 0.0,
    val term: String = "",
    val score: String = "",
    val status: String = "other" // passed, inProgress, failed, notStarted, other
) : Serializable {
    val isPassed: Boolean get() = status == "passed"
    val isInProgress: Boolean get() = status == "inProgress"
    val isFailed: Boolean get() = status == "failed"
    val isNotStarted: Boolean get() = status == "notStarted"

    val statusLabel: String
        get() = when (status) {
            "passed" -> "已修"
            "inProgress" -> "在修"
            "failed" -> "未过"
            "notStarted" -> "未修"
            else -> "其他"
        }

    companion object {
        /**
         * 正方教务系统 XDZT 状态码转换：1=在修，2=未过，3=未修，4/21=已修
         */
        fun statusOf(xdzt: String): String = when (xdzt.trim()) {
            "1" -> "inProgress"
            "2" -> "failed"
            "3" -> "notStarted"
            "4", "21" -> "passed"
            else -> "other"
        }
    }
}

/**
 * 学业情况 / 培养方案学分进展总览。
 */
data class AcademicProgress(
    val majorName: String = "",
    val earned: Double = 0.0,
    val required: Double = 0.0,
    val categories: List<CreditCategory> = emptyList(),
    val coursesByCategory: Map<String, List<AcademicCourse>> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
) : Serializable {
    val missing: Double get() = (required - earned).coerceAtLeast(0.0)
    val progress: Double get() = if (required <= 0) 0.0 else (earned / required).coerceIn(0.0, 1.0)
}
