package cn.edu.usst.jwgl.util

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.content.FileProvider
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CalendarExportHelper {

    private const val TAG_COURSE = "[USST-JWGL-COURSE]"
    private const val TAG_EXAM = "[USST-JWGL-EXAM]"

    /**
     * Shows a dialog allowing user to choose between importing timetable to system calendar or exporting to .ics.
     */
    fun showScheduleCalendarDialog(
        activity: Activity,
        tableId: Int? = null,
        onRequestDirectImport: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        MaterialAlertDialogBuilder(activity)
            .setTitle("课表导入与导出日历")
            .setMessage("请选择课表同步方式：\n\n1. 直接导入系统自带日历：一键写入本地系统日历应用，支持提前 15 分钟课前提醒。\n\n2. 导出为标准 .ics 日历文件：可直接分享至微信、外部日历软件或作为备份。")
            .setPositiveButton("一键导入系统日历") { _, _ ->
                onRequestDirectImport()
            }
            .setNeutralButton("导出 .ics 文件") { _, _ ->
                exportScheduleToIcs(activity, tableId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Shows a dialog allowing user to choose between importing exams to system calendar or exporting to .ics.
     */
    fun showExamCalendarDialog(
        activity: Activity,
        exams: List<ExamItem>? = null,
        onRequestDirectImport: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        MaterialAlertDialogBuilder(activity)
            .setTitle("考试排期导入与导出日历")
            .setMessage("请选择考试日程同步方式：\n\n1. 直接导入系统自带日历：将期末/期中考试（含课程名称、考场地点、座位号）写入系统日历，设置提前 30 分钟考前提醒。\n\n2. 导出为标准 .ics 日历文件：可分享至系统日历、微信或外部日历软件。")
            .setPositiveButton("一键导入系统日历") { _, _ ->
                onRequestDirectImport()
            }
            .setNeutralButton("导出 .ics 文件") { _, _ ->
                exportExamsToIcs(activity, exams)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Export timetable courses to a standard RFC 5545 .ics file and trigger system share.
     */
    fun exportScheduleToIcs(activity: Activity, tableId: Int? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            val (icsContent, fileName) = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(activity)
                val table = if (tableId != null && tableId > 0) {
                    db.tableDao.getTableById(tableId) ?: db.tableDao.getDefaultTable()
                } else {
                    db.tableDao.getDefaultTable()
                }

                val courses = db.courseBaseDao.getCourseOfTable(table.id)
                if (courses.isEmpty()) {
                    return@withContext Pair("", "")
                }

                val times = db.timeDetailDao.getTimeDetails(table.timeTable)
                val ics = buildScheduleIcs(table, courses, times)
                val safeTitle = table.tableName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
                Pair(ics, "usst_schedule_${safeTitle}.ics")
            }

            if (icsContent.isBlank() || !icsContent.contains("BEGIN:VEVENT")) {
                Toast.makeText(activity, "当前课表暂无排定时间的有效课程数据", Toast.LENGTH_SHORT).show()
                return@launch
            }

            saveAndShareIcsFile(activity, fileName, icsContent, "上海理工大学课表 (.ics)")
        }
    }

    /**
     * Direct import timetable courses into Android system calendar.
     */
    suspend fun importScheduleToSystemCalendar(
        activity: Activity,
        tableId: Int? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(activity)
            val table = if (tableId != null && tableId > 0) {
                db.tableDao.getTableById(tableId) ?: db.tableDao.getDefaultTable()
            } else {
                db.tableDao.getDefaultTable()
            }

            val courses = db.courseBaseDao.getCourseOfTable(table.id)
            if (courses.isEmpty()) {
                return@withContext Result.failure(Exception("当前课表暂无课程数据"))
            }

            val times = db.timeDetailDao.getTimeDetails(table.timeTable)
            val calendarId = getOrCreateCalendarId(activity)
            if (calendarId <= 0) {
                return@withContext Result.failure(Exception("无法获取或创建系统日历账户"))
            }

            // Clean up previous imports for courses
            deleteEventsByTag(activity, calendarId, TAG_COURSE)

            val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val startCal = Calendar.getInstance(Locale.CHINA).apply {
                time = sdfDate.parse(table.startDate) ?: Date()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            var totalEvents = 0
            val batch = ArrayList<ContentProviderOperation>()

            for (course in courses) {
                for (w in course.startWeek..course.endWeek) {
                    if (course.type == 1 && w % 2 == 0) continue
                    if (course.type == 2 && w % 2 != 0) continue

                    val dayOffset = (w - 1) * 7 + (course.day - 1)
                    val lectureCal = (startCal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                    }

                    val startNode = course.startNode
                    val endNode = course.startNode + course.step - 1
                    val startTimeStr = times.find { it.node == startNode }?.startTime ?: "08:00"
                    val endTimeStr = times.find { it.node == endNode }?.endTime ?: "09:35"

                    val startParts = startTimeStr.split(":")
                    lectureCal.set(Calendar.HOUR_OF_DAY, startParts.getOrNull(0)?.toIntOrNull() ?: 8)
                    lectureCal.set(Calendar.MINUTE, startParts.getOrNull(1)?.toIntOrNull() ?: 0)
                    lectureCal.set(Calendar.SECOND, 0)
                    val startMillis = lectureCal.timeInMillis

                    val endParts = endTimeStr.split(":")
                    val endCal = (lectureCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, endParts.getOrNull(0)?.toIntOrNull() ?: 9)
                        set(Calendar.MINUTE, endParts.getOrNull(1)?.toIntOrNull() ?: 35)
                        set(Calendar.SECOND, 0)
                    }
                    val endMillis = endCal.timeInMillis

                    val eventOpIndex = batch.size
                    batch.add(
                        ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                            .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                            .withValue(CalendarContract.Events.TITLE, course.courseName)
                            .withValue(CalendarContract.Events.EVENT_LOCATION, course.room.orEmpty())
                            .withValue(
                                CalendarContract.Events.DESCRIPTION,
                                "$TAG_COURSE\n教师: ${course.teacher.orEmpty()}\n节次: 第${startNode}-${endNode}节\n周次: 第${w}周\n学期: ${table.tableName}"
                            )
                            .withValue(CalendarContract.Events.DTSTART, startMillis)
                            .withValue(CalendarContract.Events.DTEND, endMillis)
                            .withValue(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
                            .withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                            .withValue(CalendarContract.Events.HAS_ALARM, 1)
                            .build()
                    )

                    // 15-minute advance reminder
                    batch.add(
                        ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                            .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventOpIndex)
                            .withValue(CalendarContract.Reminders.MINUTES, 15)
                            .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                            .build()
                    )

                    totalEvents++

                    if (batch.size >= 300) {
                        activity.contentResolver.applyBatch(CalendarContract.AUTHORITY, batch)
                        batch.clear()
                    }
                }
            }

            if (batch.isNotEmpty()) {
                activity.contentResolver.applyBatch(CalendarContract.AUTHORITY, batch)
                batch.clear()
            }

            if (totalEvents == 0) {
                Result.failure(Exception("当前课表暂无排定具体节次与周次的有效课程"))
            } else {
                Result.success(totalEvents)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Export exam schedule to a standard RFC 5545 .ics file and trigger system share.
     */
    fun exportExamsToIcs(activity: Activity, exams: List<ExamItem>? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            val (icsContent, fileName) = withContext(Dispatchers.IO) {
                val list = exams?.takeIf { it.isNotEmpty() } ?: DataCacheManager(activity).getAllCachedExams()
                if (list.isEmpty()) {
                    return@withContext Pair("", "")
                }

                val ics = buildExamsIcs(list)
                Pair(ics, "usst_exams_${System.currentTimeMillis()}.ics")
            }

            if (icsContent.isBlank() || !icsContent.contains("BEGIN:VEVENT")) {
                Toast.makeText(activity, "暂无排定具体日期的考试数据（教务系统尚未放开具体考场与时间）", Toast.LENGTH_LONG).show()
                return@launch
            }

            saveAndShareIcsFile(activity, fileName, icsContent, "上海理工大学考试日程 (.ics)")
        }
    }

    /**
     * Direct import exams into Android system calendar.
     */
    suspend fun importExamsToSystemCalendar(
        activity: Activity,
        exams: List<ExamItem>? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val list = exams?.takeIf { it.isNotEmpty() } ?: DataCacheManager(activity).getAllCachedExams()
            if (list.isEmpty()) {
                return@withContext Result.failure(Exception("暂无排定的考试数据，请先同步考试日程"))
            }

            val calendarId = getOrCreateCalendarId(activity)
            if (calendarId <= 0) {
                return@withContext Result.failure(Exception("无法获取或创建系统日历账户"))
            }

            // Clean up previous imports for exams
            deleteEventsByTag(activity, calendarId, TAG_EXAM)

            var totalEvents = 0
            val batch = ArrayList<ContentProviderOperation>()

            for (exam in list) {
                val startMillis = exam.getExamStartTimeMillis() ?: continue
                val endMillis = parseExamEndTimeMillis(exam, startMillis)

                val locationText = if (exam.seatNumber.isNotBlank()) {
                    "${exam.location} (座位号: ${exam.seatNumber})"
                } else {
                    exam.location
                }

                val descText = buildString {
                    appendLine(TAG_EXAM)
                    appendLine("科目: ${exam.courseName}")
                    appendLine("性质: ${exam.examNature} (${exam.examName})")
                    if (exam.session.isNotBlank()) appendLine("场次: ${exam.session}")
                    if (exam.location.isNotBlank()) appendLine("考场: ${exam.location}")
                    if (exam.seatNumber.isNotBlank()) appendLine("座位号: ${exam.seatNumber}")
                    if (exam.examTime.isNotBlank()) appendLine("时间: ${exam.examTime}")
                    if (exam.examMethod.isNotBlank()) appendLine("考核方式: ${exam.examMethod}")
                    if (exam.remarks.isNotBlank()) appendLine("备注: ${exam.remarks}")
                }.trim()

                val eventOpIndex = batch.size
                batch.add(
                    ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                        .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                        .withValue(CalendarContract.Events.TITLE, "【考试】${exam.courseName}")
                        .withValue(CalendarContract.Events.EVENT_LOCATION, locationText)
                        .withValue(CalendarContract.Events.DESCRIPTION, descText)
                        .withValue(CalendarContract.Events.DTSTART, startMillis)
                        .withValue(CalendarContract.Events.DTEND, endMillis)
                        .withValue(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
                        .withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                        .withValue(CalendarContract.Events.HAS_ALARM, 1)
                        .build()
                )

                // 30-minute advance reminder
                batch.add(
                    ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventOpIndex)
                        .withValue(CalendarContract.Reminders.MINUTES, 30)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )

                totalEvents++

                if (batch.size >= 300) {
                    activity.contentResolver.applyBatch(CalendarContract.AUTHORITY, batch)
                    batch.clear()
                }
            }

            if (batch.isNotEmpty()) {
                activity.contentResolver.applyBatch(CalendarContract.AUTHORITY, batch)
                batch.clear()
            }

            if (totalEvents == 0) {
                Result.failure(Exception("暂无排定具体日期的考试数据（教务系统尚未放开具体考场与时间）"))
            } else {
                Result.success(totalEvents)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    internal fun buildScheduleIcs(
        table: TableBean,
        courses: List<CourseBean>,
        times: List<TimeDetailBean>
    ): String {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val sdfIcs = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.CHINA)
        val nowUtcStr = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val startCal = Calendar.getInstance(Locale.CHINA).apply {
            time = sdfDate.parse(table.startDate) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//USST JWGL Assistant//CN")
            appendLine("CALSCALE:GREGORIAN")
            appendLine("METHOD:PUBLISH")
            appendLine("X-WR-CALNAME:${escapeIcs(table.tableName)}")
            appendLine("X-WR-TIMEZONE:Asia/Shanghai")
            appendLine("BEGIN:VTIMEZONE")
            appendLine("TZID:Asia/Shanghai")
            appendLine("BEGIN:STANDARD")
            appendLine("DTSTART:19700101T000000")
            appendLine("TZOFFSETFROM:+0800")
            appendLine("TZOFFSETTO:+0800")
            appendLine("TZNAME:CST")
            appendLine("END:STANDARD")
            appendLine("END:VTIMEZONE")

            for (course in courses) {
                for (w in course.startWeek..course.endWeek) {
                    if (course.type == 1 && w % 2 == 0) continue
                    if (course.type == 2 && w % 2 != 0) continue

                    val dayOffset = (w - 1) * 7 + (course.day - 1)
                    val lectureCal = (startCal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                    }

                    val startNode = course.startNode
                    val endNode = course.startNode + course.step - 1
                    val startTimeStr = times.find { it.node == startNode }?.startTime ?: "08:00"
                    val endTimeStr = times.find { it.node == endNode }?.endTime ?: "09:35"

                    val startParts = startTimeStr.split(":")
                    lectureCal.set(Calendar.HOUR_OF_DAY, startParts.getOrNull(0)?.toIntOrNull() ?: 8)
                    lectureCal.set(Calendar.MINUTE, startParts.getOrNull(1)?.toIntOrNull() ?: 0)
                    lectureCal.set(Calendar.SECOND, 0)
                    val startDt = sdfIcs.format(lectureCal.time)

                    val endParts = endTimeStr.split(":")
                    val endCal = (lectureCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, endParts.getOrNull(0)?.toIntOrNull() ?: 9)
                        set(Calendar.MINUTE, endParts.getOrNull(1)?.toIntOrNull() ?: 35)
                        set(Calendar.SECOND, 0)
                    }
                    val endDt = sdfIcs.format(endCal.time)

                    val uid = "course_${course.id}_w${w}_d${course.day}_n${course.startNode}@usst.jwgl"
                    val desc = "教师: ${course.teacher.orEmpty()}\n节次: 第${startNode}-${endNode}节\n周次: 第${w}周\n学期: ${table.tableName}"

                    appendLine("BEGIN:VEVENT")
                    appendLine("UID:$uid")
                    appendLine("DTSTAMP:$nowUtcStr")
                    appendLine("DTSTART;TZID=Asia/Shanghai:$startDt")
                    appendLine("DTEND;TZID=Asia/Shanghai:$endDt")
                    appendLine("SUMMARY:${escapeIcs(course.courseName)}")
                    if (!course.room.isNullOrBlank()) {
                        appendLine("LOCATION:${escapeIcs(course.room.orEmpty())}")
                    }
                    appendLine("DESCRIPTION:${escapeIcs(desc)}")
                    appendLine("STATUS:CONFIRMED")
                    appendLine("BEGIN:VALARM")
                    appendLine("TRIGGER:-PT15M")
                    appendLine("ACTION:DISPLAY")
                    appendLine("DESCRIPTION:${escapeIcs("上课提醒: " + course.courseName)}")
                    appendLine("END:VALARM")
                    appendLine("END:VEVENT")
                }
            }

            appendLine("END:VCALENDAR")
        }
    }

    internal fun buildExamsIcs(exams: List<ExamItem>): String {
        val sdfIcs = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.CHINA)
        val nowUtcStr = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//USST JWGL Assistant//CN")
            appendLine("CALSCALE:GREGORIAN")
            appendLine("METHOD:PUBLISH")
            appendLine("X-WR-CALNAME:上海理工大学考试日程")
            appendLine("X-WR-TIMEZONE:Asia/Shanghai")
            appendLine("BEGIN:VTIMEZONE")
            appendLine("TZID:Asia/Shanghai")
            appendLine("BEGIN:STANDARD")
            appendLine("DTSTART:19700101T000000")
            appendLine("TZOFFSETFROM:+0800")
            appendLine("TZOFFSETTO:+0800")
            appendLine("TZNAME:CST")
            appendLine("END:STANDARD")
            appendLine("END:VTIMEZONE")

            for (exam in exams) {
                val startMillis = exam.getExamStartTimeMillis() ?: continue
                val endMillis = parseExamEndTimeMillis(exam, startMillis)

                val startDt = sdfIcs.format(Date(startMillis))
                val endDt = sdfIcs.format(Date(endMillis))
                val uid = "exam_${exam.courseCode}_${exam.getDateString()}_${exam.getSessionIndex()}@usst.jwgl"

                val locationText = if (exam.seatNumber.isNotBlank()) {
                    "${exam.location} (座位号: ${exam.seatNumber})"
                } else {
                    exam.location
                }

                val descText = buildString {
                    appendLine("科目: ${exam.courseName}")
                    appendLine("性质: ${exam.examNature} (${exam.examName})")
                    if (exam.session.isNotBlank()) appendLine("场次: ${exam.session}")
                    if (exam.location.isNotBlank()) appendLine("考场: ${exam.location}")
                    if (exam.seatNumber.isNotBlank()) appendLine("座位号: ${exam.seatNumber}")
                    if (exam.examTime.isNotBlank()) appendLine("时间: ${exam.examTime}")
                    if (exam.examMethod.isNotBlank()) appendLine("考核方式: ${exam.examMethod}")
                    if (exam.remarks.isNotBlank()) appendLine("备注: ${exam.remarks}")
                }.trim()

                appendLine("BEGIN:VEVENT")
                appendLine("UID:$uid")
                appendLine("DTSTAMP:$nowUtcStr")
                appendLine("DTSTART;TZID=Asia/Shanghai:$startDt")
                appendLine("DTEND;TZID=Asia/Shanghai:$endDt")
                appendLine("SUMMARY:${escapeIcs("【考试】" + exam.courseName)}")
                if (locationText.isNotBlank()) {
                    appendLine("LOCATION:${escapeIcs(locationText)}")
                }
                appendLine("DESCRIPTION:${escapeIcs(descText)}")
                appendLine("STATUS:CONFIRMED")
                appendLine("BEGIN:VALARM")
                appendLine("TRIGGER:-PT30M")
                appendLine("ACTION:DISPLAY")
                appendLine("DESCRIPTION:${escapeIcs("考试提醒: " + exam.courseName)}")
                appendLine("END:VALARM")
                appendLine("END:VEVENT")
            }

            appendLine("END:VCALENDAR")
        }
    }

    private fun parseExamEndTimeMillis(exam: ExamItem, startMillis: Long): Long {
        val rangeStr = exam.getTimeRangeString()
        val parts = rangeStr.split("-", "~", "至")
        if (parts.size >= 2) {
            val endStr = parts[1].trim()
            val timeParts = endStr.split(":")
            if (timeParts.size >= 2) {
                val hour = timeParts[0].toIntOrNull()
                val min = timeParts[1].toIntOrNull()
                if (hour != null && min != null) {
                    val cal = Calendar.getInstance(Locale.CHINA).apply {
                        timeInMillis = startMillis
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0)
                    }
                    if (cal.timeInMillis > startMillis) {
                        return cal.timeInMillis
                    }
                }
            }
        }
        // Fallback: 2 hours standard duration
        return startMillis + (2 * 3600 * 1000L)
    }

    private fun escapeIcs(text: String): String {
        return text.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
    }

    private fun getOrCreateCalendarId(context: Context): Long {
        try {
            val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(CalendarContract.Calendars._ID)
                    if (idx >= 0) return it.getLong(idx)
                }
            }

            val fallbackCursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            fallbackCursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(CalendarContract.Calendars._ID)
                    if (idx >= 0) return it.getLong(idx)
                }
            }

            // Create local calendar account
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, "usst_jwgl")
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "上海理工大学")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "上理课表与日程")
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF006633.toInt())
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, "usst.edu.cn")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Asia/Shanghai")
            }

            val insertUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "usst_jwgl")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()

            val resultUri = context.contentResolver.insert(insertUri, values)
            return resultUri?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            e.printStackTrace()
            return -1L
        }
    }

    private fun deleteEventsByTag(context: Context, calendarId: Long, tag: String) {
        try {
            val where = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?"
            val args = arrayOf(calendarId.toString(), "%$tag%")
            context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, where, args)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveAndShareIcsFile(
        activity: Activity,
        fileName: String,
        content: String,
        title: String
    ) {
        try {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(intent, "分享或导入日历 (.ics)"))
        } catch (e: Exception) {
            Toast.makeText(activity, "导出 .ics 失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
