package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.CourseGrade
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.SemesterGradeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeDocumentTest {

    @Test
    fun testGradeDocumentTypesDefined() {
        val types = GradeDocumentType.values()
        assertEquals(5, types.size)

        val zhAvg = GradeDocumentType.CHINESE_WEIGHTED_SCORE
        val enAvg = GradeDocumentType.ENGLISH_WEIGHTED_SCORE
        val rank = GradeDocumentType.RANKING_CERTIFICATE
        val zhTrans = GradeDocumentType.CHINESE_TRANSCRIPT
        val enTrans = GradeDocumentType.ENGLISH_TRANSCRIPT

        assertTrue(zhAvg.defaultFileName.endsWith(".pdf"))
        assertTrue(enAvg.defaultFileName.endsWith(".pdf"))
        assertTrue(rank.defaultFileName.endsWith(".pdf"))
        assertTrue(zhTrans.defaultFileName.endsWith(".pdf"))
        assertTrue(enTrans.defaultFileName.endsWith(".pdf"))

        assertTrue(zhAvg.displayName.contains("中文加权平均分证明"))
        assertTrue(enAvg.displayName.contains("英文加权平均分证明"))
        assertTrue(rank.displayName.contains("专业排名证明"))
        assertTrue(zhTrans.displayName.contains("学生成绩总表 (中文成绩单)"))
        assertTrue(enTrans.displayName.contains("英文·官方盖章版"))
    }

    @Test
    fun testSemesterAndCumulativeGradeRecalculation() {
        val sem1Courses = listOf(
            CourseGrade(courseName = "高等数学A(1)", credit = 5.0, overallScore = "90", gpa = 4.0),
            CourseGrade(courseName = "大学英语(1)", credit = 3.0, overallScore = "80", gpa = 3.0)
        )
        val sem1 = SemesterGradeSummary(
            semesterTitle = "2024-2025学年 第1学期",
            totalCredits = 8.0,
            weightedAverageScore = (90 * 5.0 + 80 * 3.0) / 8.0, // 86.25
            weightedGpa = (4.0 * 5.0 + 3.0 * 3.0) / 8.0, // 3.625
            courses = sem1Courses
        )

        val sem2Courses = listOf(
            CourseGrade(courseName = "线性代数", credit = 3.0, overallScore = "95", gpa = 4.5),
            CourseGrade(courseName = "大学物理", credit = 4.0, overallScore = "85", gpa = 3.5)
        )
        val sem2 = SemesterGradeSummary(
            semesterTitle = "2024-2025学年 第2学期",
            totalCredits = 7.0,
            weightedAverageScore = (95 * 3.0 + 85 * 4.0) / 7.0, // 89.285
            weightedGpa = (4.5 * 3.0 + 3.5 * 4.0) / 7.0, // 3.928
            courses = sem2Courses
        )

        val report = GradeReport(
            totalCredits = 15.0,
            cumulativeWeightedScore = (86.25 * 8.0 + 89.2857 * 7.0) / 15.0,
            cumulativeGpa = (3.625 * 8.0 + 3.92857 * 7.0) / 15.0,
            semesters = listOf(sem2, sem1)
        )

        // Verify total summary
        assertEquals(15.0, report.totalCredits, 0.01)
        assertEquals(2, report.semesters.size)

        // Verify single semester filtering
        val selectedSem = report.semesters.find { it.semesterTitle == "2024-2025学年 第1学期" }
        assertNotNull(selectedSem)
        assertEquals(8.0, selectedSem!!.totalCredits, 0.01)
        assertEquals(86.25, selectedSem.weightedAverageScore, 0.01)
        assertEquals(3.625, selectedSem.weightedGpa, 0.01)
        assertEquals(2, selectedSem.courses.size)
    }

    @Test
    fun testDeduplicationForRetakeAndResitCourses() {
        // 模拟用户提出的一门课首修挂科、补考挂科、重修通过，生成 3 条记录的场景
        val physics1 = CourseGrade(
            courseId = "PHYS1001",
            courseName = "大学物理B",
            credit = 4.0,
            overallScore = "42",
            gpa = 0.0,
            ksxz = "正常考试"
        )
        val physics2 = CourseGrade(
            courseId = "PHYS1001",
            courseName = "大学物理B",
            credit = 4.0,
            overallScore = "53",
            gpa = 0.0,
            ksxz = "补考"
        )
        val physics3 = CourseGrade(
            courseId = "PHYS1001",
            courseName = "大学物理B",
            credit = 4.0,
            overallScore = "84",
            gpa = 3.4,
            ksxz = "重修"
        )
        val math = CourseGrade(
            courseId = "MATH1001",
            courseName = "高等数学A(1)",
            credit = 5.0,
            overallScore = "90",
            gpa = 4.0,
            ksxz = "正常考试"
        )

        val allCourses = listOf(physics1, physics2, physics3, math)
        val (totalCredits, avgScore, gpa) = cn.edu.usst.jwgl.data.network.JwglClient.calculateCumulativeGrades(allCourses)

        // 验证学分只计入一次（4.0 + 5.0 = 9.0），而非 4+4+4+5=17
        assertEquals(9.0, totalCredits, 0.001)

        // 验证只取重修通过的 84 分与 3.4 绩点参与加权计算
        // 期望加权均分: (84 * 4.0 + 90 * 5.0) / 9.0 = 786 / 9.0 ≈ 87.333
        assertEquals((84 * 4.0 + 90 * 5.0) / 9.0, avgScore, 0.001)

        // 期望平均 GPA: (3.4 * 4.0 + 4.0 * 5.0) / 9.0 = 33.6 / 9.0 ≈ 3.733
        assertEquals((3.4 * 4.0 + 4.0 * 5.0) / 9.0, gpa, 0.001)
    }
}
