package cn.edu.usst.jwgl.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentProfile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DocumentPdfGenerator {

    /**
     * Generates a high-quality PDF document using Android's standard framework PdfDocument.
     */
    fun generate(
        context: Context,
        docType: GradeDocumentType,
        profile: StudentProfile,
        grades: GradeReport?,
        destinationFile: File
    ): File {
        require(docType == GradeDocumentType.CHINESE_TRANSCRIPT) {
            "仅支持本地渲染生成中文本科生成绩总表，官方证明与英文成绩单须由教务处官方系统权威出具"
        }

        val pdfDocument = PdfDocument()
        val pageWidth = 595 // Standard A4 width in points (72 dpi)
        val pageHeight = 842 // Standard A4 height in points
        var pageNumber = 1

        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.parseColor("#800000") // USST Crimson
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.parseColor("#333333")
            textSize = 10f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val boldTextPaint = Paint().apply {
            color = Color.parseColor("#111111")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerPaint = Paint().apply {
            color = Color.parseColor("#EEEEEE")
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.parseColor("#BDBDBD")
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }

        var y = 50f

        // Draw Header
        val isEnglish = docType == GradeDocumentType.ENGLISH_TRANSCRIPT || docType == GradeDocumentType.ENGLISH_WEIGHTED_SCORE
        val headerTitle = if (isEnglish) "UNIVERSITY OF SHANGHAI FOR SCIENCE AND TECHNOLOGY" else "上 海 理 工 大 学"
        canvas.drawText(headerTitle, pageWidth / 2f, y, titlePaint)
        y += 24f

        val docTitle = when (docType) {
            GradeDocumentType.CHINESE_WEIGHTED_SCORE -> "加权平均分与平均学分绩点证明"
            GradeDocumentType.ENGLISH_WEIGHTED_SCORE -> "Certificate of Weighted Average Score & GPA"
            GradeDocumentType.RANKING_CERTIFICATE -> "学 生 成 绩 排 名 证 明"
            GradeDocumentType.CHINESE_TRANSCRIPT -> "本 科 生 成 绩 总 表"
            GradeDocumentType.ENGLISH_TRANSCRIPT -> "OFFICIAL ACADEMIC TRANSCRIPT"
        }
        canvas.drawText(docTitle, pageWidth / 2f, y, subtitlePaint)
        y += 26f

        // Student Basic Info Box
        val left = 36f
        val right = pageWidth - 36f
        val boxHeight = 54f
        canvas.drawRect(left, y, right, y + boxHeight, borderPaint)

        val col1 = left + 10f
        val col2 = left + 180f
        val col3 = left + 340f

        if (!isEnglish) {
            canvas.drawText("姓    名: ${profile.name.ifEmpty { "暂无" }}", col1, y + 16f, textPaint)
            canvas.drawText("学    号: ${profile.studentId.ifEmpty { "暂无" }}", col2, y + 16f, textPaint)
            canvas.drawText("学    院: ${profile.college.ifEmpty { "暂无" }}", col3, y + 16f, textPaint)

            canvas.drawText("专    业: ${profile.major.ifEmpty { "暂无" }}", col1, y + 32f, textPaint)
            canvas.drawText("班    级: ${profile.className.ifEmpty { "暂无" }}", col2, y + 32f, textPaint)
            canvas.drawText("年    级: ${profile.grade.ifEmpty { "暂无" }}${if (profile.grade.isNotEmpty()) "级" else ""} (${profile.durationYears.ifEmpty { "4" }}年制)", col3, y + 32f, textPaint)

            val gpaStr = String.format(Locale.CHINA, "%.2f", grades?.cumulativeGpa ?: 0.0)
            val scoreStr = String.format(Locale.CHINA, "%.2f", grades?.cumulativeWeightedScore ?: 0.0)
            val creditStr = String.format(Locale.CHINA, "%.1f", grades?.totalCredits ?: 0.0)
            canvas.drawText("总修读学分: $creditStr", col1, y + 48f, boldTextPaint)
            canvas.drawText("平均绩点(GPA): $gpaStr", col2, y + 48f, boldTextPaint)
            canvas.drawText("加权平均分: $scoreStr", col3, y + 48f, boldTextPaint)
        } else {
            canvas.drawText("Name: ${profile.name.ifEmpty { "N/A" }}", col1, y + 16f, textPaint)
            canvas.drawText("Student ID: ${profile.studentId.ifEmpty { "N/A" }}", col2, y + 16f, textPaint)
            canvas.drawText("School: ${profile.college.ifEmpty { "N/A" }}", col3, y + 16f, textPaint)

            canvas.drawText("Major: ${profile.major.ifEmpty { "N/A" }}", col1, y + 32f, textPaint)
            canvas.drawText("Degree: Bachelor of Engineering", col2, y + 32f, textPaint)
            canvas.drawText("Grade: ${profile.grade.ifEmpty { "N/A" }}", col3, y + 32f, textPaint)

            val gpaStr = String.format(Locale.US, "%.2f", grades?.cumulativeGpa ?: 0.0)
            val scoreStr = String.format(Locale.US, "%.2f", grades?.cumulativeWeightedScore ?: 0.0)
            val creditStr = String.format(Locale.US, "%.1f", grades?.totalCredits ?: 0.0)
            canvas.drawText("Total Credits: $creditStr", col1, y + 48f, boldTextPaint)
            canvas.drawText("Cumulative GPA: $gpaStr", col2, y + 48f, boldTextPaint)
            canvas.drawText("Weighted Score: $scoreStr", col3, y + 48f, boldTextPaint)
        }

        y += boxHeight + 14f

        // Course Table
        val rowHeight = 16f
        val c1Width = 40f
        val c2Width = 240f
        val c3Width = 45f
        val c4Width = 55f
        val c5Width = 50f
        val c6Width = right - left - (c1Width + c2Width + c3Width + c4Width + c5Width)

        // Table Header Background
        canvas.drawRect(left, y, right, y + rowHeight, headerPaint)
        canvas.drawRect(left, y, right, y + rowHeight, borderPaint)

        canvas.drawText(if (isEnglish) "No." else "序号", left + 8f, y + 12f, boldTextPaint)
        canvas.drawText(if (isEnglish) "Course Name" else "课程名称", left + c1Width + 6f, y + 12f, boldTextPaint)
        canvas.drawText(if (isEnglish) "Credit" else "学分", left + c1Width + c2Width + 6f, y + 12f, boldTextPaint)
        canvas.drawText(if (isEnglish) "Score" else "总评成绩", left + c1Width + c2Width + c3Width + 6f, y + 12f, boldTextPaint)
        canvas.drawText(if (isEnglish) "GPA" else "绩点", left + c1Width + c2Width + c3Width + c4Width + 6f, y + 12f, boldTextPaint)
        canvas.drawText(if (isEnglish) "Semester" else "修读学期", left + c1Width + c2Width + c3Width + c4Width + c5Width + 6f, y + 12f, boldTextPaint)
        y += rowHeight

        val allCourses = grades?.semesters?.flatMap { it.courses } ?: emptyList()
        var index = 1

        for (course in allCourses) {
            if (y + rowHeight > pageHeight - 60f) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = 40f

                // Repeat Header on new page
                canvas.drawRect(left, y, right, y + rowHeight, headerPaint)
                canvas.drawRect(left, y, right, y + rowHeight, borderPaint)
                canvas.drawText(if (isEnglish) "No." else "序号", left + 8f, y + 12f, boldTextPaint)
                canvas.drawText(if (isEnglish) "Course Name" else "课程名称", left + c1Width + 6f, y + 12f, boldTextPaint)
                canvas.drawText(if (isEnglish) "Credit" else "学分", left + c1Width + c2Width + 6f, y + 12f, boldTextPaint)
                canvas.drawText(if (isEnglish) "Score" else "总评成绩", left + c1Width + c2Width + c3Width + 6f, y + 12f, boldTextPaint)
                canvas.drawText(if (isEnglish) "GPA" else "绩点", left + c1Width + c2Width + c3Width + c4Width + 6f, y + 12f, boldTextPaint)
                canvas.drawText(if (isEnglish) "Semester" else "修读学期", left + c1Width + c2Width + c3Width + c4Width + c5Width + 6f, y + 12f, boldTextPaint)
                y += rowHeight
            }

            canvas.drawRect(left, y, right, y + rowHeight, borderPaint)
            canvas.drawText(index.toString(), left + 8f, y + 12f, textPaint)

            val cName = if (course.courseName.length > 18) course.courseName.take(17) + "…" else course.courseName
            canvas.drawText(cName, left + c1Width + 6f, y + 12f, textPaint)
            canvas.drawText(String.format(Locale.US, "%.1f", course.credit), left + c1Width + c2Width + 6f, y + 12f, textPaint)
            canvas.drawText(course.overallScore, left + c1Width + c2Width + c3Width + 6f, y + 12f, boldTextPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", course.gpa), left + c1Width + c2Width + c3Width + c4Width + 6f, y + 12f, textPaint)

            val semShort = course.semesterTitle.replace("学年", "").replace("第", "").replace("学期", "").trim()
            canvas.drawText(semShort, left + c1Width + c2Width + c3Width + c4Width + c5Width + 6f, y + 12f, textPaint)

            y += rowHeight
            index++
        }

        // Disclaimer and Date Footer
        y = pageHeight - 50f
        val sdf = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        val dateText = if (isEnglish) "Exported on: ${SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())}" else "导出日期：${sdf.format(Date())}"
        val disclaimerText = if (isEnglish) {
            "Note: Rendered from cached student data for reference only. Not an official transcript."
        } else {
            "注：本文件由客户端本地缓存数据渲染生成，仅供学生个人参考，不具备官方证明效力"
        }

        canvas.drawText(dateText, left, y, textPaint)
        val disclaimerPaint = Paint().apply {
            color = Color.parseColor("#757575")
            textSize = 9f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText(disclaimerText, right, y, disclaimerPaint)

        pdfDocument.finishPage(page)

        destinationFile.parentFile?.mkdirs()
        FileOutputStream(destinationFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return destinationFile
    }
}
