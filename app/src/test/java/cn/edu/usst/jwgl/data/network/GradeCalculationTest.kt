package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.CourseGrade
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成绩与绩点汇总指标计算专项测试（PRD 4.3 GRD-02）
 * 固化累计学分、加权平均分与平均学分绩点（GPA）的核心口径
 */
class GradeCalculationTest {

    @Test
    fun testStandardGradesCalculation() {
        val courses = listOf(
            CourseGrade(courseId = "CS101", courseName = "计算机程序设计", credit = 4.0, overallScore = "90", gpa = 4.0),
            CourseGrade(courseId = "MATH101", courseName = "高等数学", credit = 5.0, overallScore = "80", gpa = 3.0)
        )

        val (totalCredit, avgScore, gpa) = JwglClient.calculateCumulativeGrades(courses)

        // 学分 = 4 + 5 = 9.0
        assertEquals(9.0, totalCredit, 0.001)
        // 加权均分 = (90 * 4 + 80 * 5) / (4 + 5) = 760 / 9 = 84.4444...
        assertEquals(84.44, avgScore, 0.01)
        // 加权 GPA = (4.0 * 4 + 3.0 * 5) / (4 + 5) = 31 / 9 = 3.4444...
        assertEquals(3.44, gpa, 0.01)
    }

    @Test
    fun testZeroCreditCoursesExcludedFromAverages() {
        // PRD AC-GRD-02-2: 0 学分课程不计入加权平均分与 GPA 分母
        val courses = listOf(
            CourseGrade(courseId = "CS101", courseName = "计算机程序设计", credit = 4.0, overallScore = "90", gpa = 4.0),
            CourseGrade(courseId = "LECTURE01", courseName = "形势与政策讲座", credit = 0.0, overallScore = "100", gpa = 5.0)
        )

        val (totalCredit, avgScore, gpa) = JwglClient.calculateCumulativeGrades(courses)

        // 0 学分课程不计入有效加权分母，均分与 GPA 应完全由 4.0 学分的 CS101 决定
        assertEquals(4.0, totalCredit, 0.001)
        assertEquals(90.0, avgScore, 0.001)
        assertEquals(4.0, gpa, 0.001)
    }

    @Test
    fun testLetterGradeGradesUseProvidedGpa() {
        // PRD AC-GRD-02-3: 等级制成绩（如"优秀"、"良好"）按教务给定绩点计算，不做本地换算
        val courses = listOf(
            CourseGrade(courseId = "PE101", courseName = "大学体育", credit = 1.0, overallScore = "优秀", gpa = 4.5),
            CourseGrade(courseId = "ENG101", courseName = "大学英语", credit = 3.0, overallScore = "85", gpa = 3.5)
        )

        val (totalCredit, avgScore, gpa) = JwglClient.calculateCumulativeGrades(courses)

        assertEquals(4.0, totalCredit, 0.001)
        // GPA = (4.5 * 1.0 + 3.5 * 3.0) / 4.0 = 15.0 / 4.0 = 3.75
        assertEquals(3.75, gpa, 0.001)
    }

    @Test
    fun testDeduplicationForRetakeAndResitCourses() {
        // 首修挂科 (42分/0绩点) -> 重修通过 (88分/3.8绩点)
        val courses = listOf(
            CourseGrade(courseId = "PHYS101", courseName = "大学物理", credit = 4.0, overallScore = "42", gpa = 0.0, ksxz = "正常考试"),
            CourseGrade(courseId = "PHYS101", courseName = "大学物理", credit = 4.0, overallScore = "88", gpa = 3.8, ksxz = "重修")
        )

        val (totalCredit, avgScore, gpa) = JwglClient.calculateCumulativeGrades(courses)

        // 仅计入 1 次 4.0 学分
        assertEquals(4.0, totalCredit, 0.001)
        // 优先采纳重修通过有效成绩 88 分 / 3.8 绩点
        assertEquals(88.0, avgScore, 0.001)
        assertEquals(3.8, gpa, 0.001)
    }

    @Test
    fun testEmptyCoursesReturnsZeros() {
        val (totalCredit, avgScore, gpa) = JwglClient.calculateCumulativeGrades(emptyList())
        assertEquals(0.0, totalCredit, 0.001)
        assertEquals(0.0, avgScore, 0.001)
        assertEquals(0.0, gpa, 0.001)
    }
}
