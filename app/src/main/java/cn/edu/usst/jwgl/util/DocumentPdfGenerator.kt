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
            GradeDocumentType.CHINESE_TRANSCRIPT,
            GradeDocumentType.CHINESE_TRANSCRIPT_HD -> "本 科 生 成 绩 总 表"
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
            canvas.drawText("姓    名: ${profile.name.ifEmpty { "周智轩" }}", col1, y + 16f, textPaint)
            canvas.drawText("学    号: ${profile.studentId.ifEmpty { "2435055230" }}", col2, y + 16f, textPaint)
            canvas.drawText("学    院: ${profile.college.ifEmpty { "光电信息与计算机工程学院" }}", col3, y + 16f, textPaint)

            canvas.drawText("专    业: ${profile.major.ifEmpty { "计算机科学与技术" }}", col1, y + 32f, textPaint)
            canvas.drawText("班    级: ${profile.className.ifEmpty { "计算机24120801班" }}", col2, y + 32f, textPaint)
            canvas.drawText("年    级: ${profile.grade.ifEmpty { "2024" }}级 (${profile.durationYears.ifEmpty { "4" }}年制)", col3, y + 32f, textPaint)

            val gpaStr = String.format(Locale.CHINA, "%.2f", grades?.cumulativeGpa ?: 0.0)
            val scoreStr = String.format(Locale.CHINA, "%.2f", grades?.cumulativeWeightedScore ?: 0.0)
            val creditStr = String.format(Locale.CHINA, "%.1f", grades?.totalCredits ?: 0.0)
            canvas.drawText("总修读学分: $creditStr", col1, y + 48f, boldTextPaint)
            canvas.drawText("平均绩点(GPA): $gpaStr", col2, y + 48f, boldTextPaint)
            canvas.drawText("加权平均分: $scoreStr", col3, y + 48f, boldTextPaint)
        } else {
            canvas.drawText("Name: ${profile.name.ifEmpty { "Zhou Zhixuan" }}", col1, y + 16f, textPaint)
            canvas.drawText("Student ID: ${profile.studentId.ifEmpty { "2435055230" }}", col2, y + 16f, textPaint)
            canvas.drawText("School: ${profile.college.ifEmpty { "School of Optical-Electrical & Computer Eng." }}", col3, y + 16f, textPaint)

            canvas.drawText("Major: ${profile.major.ifEmpty { "Computer Science & Technology" }}", col1, y + 32f, textPaint)
            canvas.drawText("Degree: Bachelor of Engineering", col2, y + 32f, textPaint)
            canvas.drawText("Grade: ${profile.grade.ifEmpty { "2024" }}", col3, y + 32f, textPaint)

            val gpaStr = String.format(Locale.US, "%.2f", grades?.cumulativeGpa ?: 0.0)
            val scoreStr = String.format(Locale.US, "%.2f", grades?.cumulativeWeightedScore ?: 0.0)
            val creditStr = String.format(Locale.US, "%.1f", grades?.totalCredits ?: 0.0)
            canvas.drawText("Total Credits: $creditStr", col1, y + 48f, boldTextPaint)
            canvas.drawText("Cumulative GPA: $gpaStr", col2, y + 48f, boldTextPaint)
            canvas.drawText("Weighted Score: $scoreStr", col3, y + 48f, boldTextPaint)
        }

        y += boxHeight + 14f

        // If Ranking Certificate
        if (docType == GradeDocumentType.RANKING_CERTIFICATE) {
            val certText = """
                兹证明学生 ${profile.name.ifEmpty { "周智轩" }}（学号：${profile.studentId.ifEmpty { "2435055230" }}），系我校 ${profile.college.ifEmpty { "光电信息与计算机工程学院" }} ${profile.major.ifEmpty { "计算机科学与技术" }} 专业 ${profile.grade.ifEmpty { "2024" }} 级全日制在读本科生。

                截至目前，该生已修读总学分 ${String.format(Locale.CHINA, "%.1f", grades?.totalCredits ?: 0.0)} 学分，加权平均分为 ${String.format(Locale.CHINA, "%.2f", grades?.cumulativeWeightedScore ?: 0.0)} 分，平均学分绩点（GPA）为 ${String.format(Locale.CHINA, "%.2f", grades?.cumulativeGpa ?: 0.0)}。

                特此证明。
            """.trimIndent()

            val textPaintWrap = android.text.TextPaint().apply {
                color = Color.parseColor("#222222")
                textSize = 12f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val contentWidth = (right - left - 20f).toInt()

            canvas.save()
            canvas.translate(left + 10f, y + 10f)
            val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(certText, 0, certText.length, textPaintWrap, contentWidth)
                    .setLineSpacing(8f, 1.2f)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.text.StaticLayout(certText, textPaintWrap, contentWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.2f, 8f, false)
            }
            staticLayout.draw(canvas)
            canvas.restore()
        } else {
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
        }

        // Stamp and Date Footer
        y = pageHeight - 50f
        val sdf = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        val dateText = if (isEnglish) "Issued on: ${SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())}" else "打印日期：${sdf.format(Date())}"
        val stampText = if (isEnglish) "Academic Affairs Office, USST" else "上海理工大学教务处 (公章有效)"

        canvas.drawText(dateText, left, y, textPaint)
        val stampPaint = Paint().apply {
            color = Color.parseColor("#800000")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText(stampText, right, y, stampPaint)

        pdfDocument.finishPage(page)

        destinationFile.parentFile?.mkdirs()
        FileOutputStream(destinationFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return destinationFile
    }
}
