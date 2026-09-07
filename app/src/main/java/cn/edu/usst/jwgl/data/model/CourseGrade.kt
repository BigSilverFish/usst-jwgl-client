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
