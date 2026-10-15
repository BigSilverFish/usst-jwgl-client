package cn.edu.usst.jwgl.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.CourseItem
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.receiver.CourseReminderReceiver
import java.util.Calendar

object CourseReminderManager {

    private const val TAG = "CourseReminderManager"
    private const val PREF_NAME = "usst_reminder_pref"
    private const val KEY_REMINDER_ENABLED = "reminder_enabled"
    private const val KEY_COURSE_ADVANCE_MINS = "course_advance_mins"
    private const val KEY_EXAM_REMINDER_ENABLED = "exam_reminder_enabled"
    private const val KEY_EXAM_ADVANCE_MINS = "exam_advance_mins"
    private const val KEY_POWERFUL_REMINDER = "powerful_reminder_enabled"
    private const val KEY_ACTIVE_SEMESTER = "active_semester_key"

    // Official USST Section start times (Hour, Minute)
    val SECTION_START_TIMES = mapOf(
        1 to Pair(8, 0),
        2 to Pair(8, 45),
        3 to Pair(9, 45),
        4 to Pair(10, 30),
        5 to Pair(11, 15),
        6 to Pair(13, 0),
        7 to Pair(13, 45),
        8 to Pair(14, 45),
        9 to Pair(15, 30),
        10 to Pair(16, 15),
        11 to Pair(18, 0),
        12 to Pair(18, 45),
        13 to Pair(19, 30)
    )

    fun isReminderEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_REMINDER_ENABLED, false)
    }

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply()

        if (enabled) {
            scheduleUpcomingReminders(context)
        } else {
            cancelAllReminders(context)
        }
    }

    fun isPowerfulReminderEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_POWERFUL_REMINDER, true) // Default enabled for 100% reliable alarms
    }

    fun setPowerfulReminderEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_POWERFUL_REMINDER, enabled).apply()
        if (isReminderEnabled(context)) {
            scheduleUpcomingReminders(context)
        }
    }

    fun getCourseReminderAdvanceMinutes(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_COURSE_ADVANCE_MINS, 15) // Default 15 minutes
    }

    fun setCourseReminderAdvanceMinutes(context: Context, minutes: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_COURSE_ADVANCE_MINS, minutes).apply()
        if (isReminderEnabled(context)) {
            scheduleUpcomingReminders(context)
        }
    }

    fun isExamReminderEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_EXAM_REMINDER_ENABLED, true) // Default enabled
    }

    fun setExamReminderEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_EXAM_REMINDER_ENABLED, enabled).apply()
        if (enabled) {
            val exams = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getAllCachedExams()
            scheduleExamReminders(context, exams)
        } else {
            cancelAllExamReminders(context)
        }
    }

    fun getExamReminderAdvanceMinutes(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_EXAM_ADVANCE_MINS, 30) // Default 30 minutes
    }

    fun setExamReminderAdvanceMinutes(context: Context, minutes: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_EXAM_ADVANCE_MINS, minutes).apply()
        if (isExamReminderEnabled(context)) {
            val exams = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getAllCachedExams()
            scheduleExamReminders(context, exams)
        }
    }

    fun formatAdvanceMinutes(minutes: Int): String {
        return when {
            minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440} 天"
            minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} 小时"
            minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
            else -> "${minutes} 分钟"
        }
    }

    fun getActiveSemesterKey(context: Context): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_ACTIVE_SEMESTER, null)
        if (!saved.isNullOrBlank()) {
            return saved
        }
        val (calcXnm, calcXqm) = SemesterHelper.getCurrentSemester()
        return "${calcXnm}_${calcXqm}"
    }

    fun setActiveSemesterKey(context: Context, semKey: String) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_ACTIVE_SEMESTER, semKey).apply()
        if (isReminderEnabled(context)) {
            scheduleUpcomingReminders(context)
        }
    }

    /**
     * Schedules reminders for a rolling 14-day window (covering current week + next week).
     * This permanently resolves the "cross-week missed reminder" issue.
     */
    fun scheduleUpcomingReminders(context: Context, ignoredCourses: List<CourseItem>? = null) {
        if (!isReminderEnabled(context)) {
            cancelAllReminders(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // Step 1: Cancel previous reminders in window
        cancelAllReminders(context)

        // Step 2: Load active timetable & time details
        val db = cn.edu.usst.jwgl.data.wakeup.AppDatabase.getDatabase(context)
        val activeTable = db.tableDao.getDefaultTable()
        val times = db.timeDetailDao.getTimeDetails(activeTable.timeTable)
        val advanceMins = getCourseReminderAdvanceMinutes(context)
        val powerfulMode = isPowerfulReminderEnabled(context)
        val now = System.currentTimeMillis()

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        val allCourses = db.courseBaseDao.getCourseOfTable(activeTable.id)
        val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        var scheduledCount = 0

        // Schedule for the next 14 consecutive days (dayOffset = 0..13)
        for (dayOffset in 0..13) {
            val dayCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dateStr = sdfDate.format(dayCal.time)
            val targetWeek = cn.edu.usst.jwgl.data.wakeup.CourseUtils.countWeek(activeTable.startDate, dayCal)

            if (targetWeek !in 1..activeTable.maxWeek) {
                continue
            }

            val dayOfWeek = dayCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)

            val resolved = cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver.resolve(
                db,
                activeTable.id,
                dateStr,
                targetWeek,
                dayNumber,
                allCourses
            )

            if (resolved.isHolidayOff) {
                continue
            }

            val dayCourses = if (resolved.isSwapped) {
                resolved.courses
            } else {
                val weekType = if (targetWeek % 2 != 0) 1 else 2
                db.courseBaseDao.getCourseByDayAndWeekOfTable(dayNumber, targetWeek, weekType, activeTable.id)
            }

            for (course in dayCourses) {
                val node = course.startNode
                val timeItem = times.find { it.node == node }
                val (hour, minute) = if (timeItem != null) {
                    val parts = timeItem.startTime.split(":")
                    Pair(parts.getOrNull(0)?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                } else {
                    SECTION_START_TIMES[node] ?: Pair(8, 0)
                }

                val reminderCal = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, -advanceMins)
                }

                val reminderTimeMillis = reminderCal.timeInMillis
                if (reminderTimeMillis > now) {
                    val requestCode = 10000 + (dayOffset * 100) + node
                    val intent = Intent(context, CourseReminderReceiver::class.java).apply {
                        putExtra(CourseReminderReceiver.EXTRA_COURSE_NAME, course.courseName)
                        putExtra(CourseReminderReceiver.EXTRA_CLASSROOM, course.room ?: "")
                        putExtra(CourseReminderReceiver.EXTRA_TEACHER, course.teacher ?: "")
                        putExtra(CourseReminderReceiver.EXTRA_START_TIME, String.format("%02d:%02d", hour, minute))
                        putExtra(CourseReminderReceiver.EXTRA_SECTION, "第${node}节")
                        putExtra(CourseReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
                        putExtra(CourseReminderReceiver.EXTRA_ADVANCE_MINUTES, advanceMins)
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val showIntent = Intent(context, cn.edu.usst.jwgl.ui.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("extra_tab", 1)
                    }
                    val showPendingIntent = PendingIntent.getActivity(
                        context,
                        requestCode + 50000,
                        showIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    try {
                        if (powerfulMode || (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
                            val alarmClockInfo = AlarmManager.AlarmClockInfo(reminderTimeMillis, showPendingIntent)
                            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                        } else if (canScheduleExact) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    reminderTimeMillis,
                                    pendingIntent
                                )
                            } else {
                                alarmManager.setExact(
                                    AlarmManager.RTC_WAKEUP,
                                    reminderTimeMillis,
                                    pendingIntent
                                )
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    reminderTimeMillis,
                                    pendingIntent
                                )
                            } else {
                                alarmManager.set(
                                    AlarmManager.RTC_WAKEUP,
                                    reminderTimeMillis,
                                    pendingIntent
                                )
                            }
                        }
                        scheduledCount++
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to schedule alarm for ${course.courseName}: ${e.message}")
                    }
                }
            }
        }
        Log.d(TAG, "Successfully scheduled $scheduledCount upcoming course reminders across rolling 14 days.")
    }

    fun cancelAllReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Cancel rolling 14-day requests (10000..11313)
        for (dayOffset in 0..13) {
            for (sec in 1..13) {
                val requestCode = 10000 + (dayOffset * 100) + sec
                cancelPendingIntent(context, alarmManager, requestCode)
            }
        }
        // Cancel legacy requests (100..713)
        for (day in 1..7) {
            for (sec in 1..13) {
                val requestCode = day * 100 + sec
                cancelPendingIntent(context, alarmManager, requestCode)
            }
        }
        // Cancel test reminder (9999)
        cancelPendingIntent(context, alarmManager, 9999)
        Log.d(TAG, "Cancelled all course reminders.")
    }

    private fun cancelPendingIntent(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, CourseReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Helper for instant test verification: triggers a reminder notification in 2 seconds
     */
    fun scheduleTestReminder(context: Context, courseName: String = "高等数学(A)Ⅰ", classroom: String = "一教 204") {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerTime = System.currentTimeMillis() + 2000L

        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            putExtra(CourseReminderReceiver.EXTRA_COURSE_NAME, courseName)
            putExtra(CourseReminderReceiver.EXTRA_CLASSROOM, classroom)
            putExtra(CourseReminderReceiver.EXTRA_TEACHER, "张教授")
            putExtra(CourseReminderReceiver.EXTRA_START_TIME, "08:00")
            putExtra(CourseReminderReceiver.EXTRA_SECTION, "第1节")
            putExtra(CourseReminderReceiver.EXTRA_NOTIFICATION_ID, 9999)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, cn.edu.usst.jwgl.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_tab", 1)
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            9999 + 50000,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    /**
     * 为考试日程安排考前提醒（采用系统闹钟级别 setAlarmClock 保证零漏报）
     */
    fun scheduleExamReminders(context: Context, exams: List<cn.edu.usst.jwgl.data.model.ExamItem>) {
        if (!isExamReminderEnabled(context)) {
            cancelAllExamReminders(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelAllExamReminders(context)

        val advanceMins = getExamReminderAdvanceMinutes(context)
        val now = System.currentTimeMillis()
        var scheduledExamsCount = 0

        for ((idx, exam) in exams.withIndex()) {
            val startMillis = exam.getExamStartTimeMillis() ?: continue
            val reminderMillis = startMillis - advanceMins * 60 * 1000L
            if (reminderMillis > now) {
                val requestCode = 30000 + (exam.courseCode.hashCode().let { if (it < 0) -it else it } % 8000) + (idx % 100)
                val intent = Intent(context, CourseReminderReceiver::class.java).apply {
                    putExtra(CourseReminderReceiver.EXTRA_IS_EXAM, true)
                    putExtra(CourseReminderReceiver.EXTRA_COURSE_NAME, exam.courseName)
                    putExtra(CourseReminderReceiver.EXTRA_EXAM_NATURE, exam.examNature.ifEmpty { exam.examName })
                    putExtra(CourseReminderReceiver.EXTRA_CLASSROOM, "${exam.building} ${exam.location}".trim())
                    putExtra(CourseReminderReceiver.EXTRA_SEAT_NUMBER, exam.seatNumber.ifEmpty { "未指定" })
                    putExtra(CourseReminderReceiver.EXTRA_START_TIME, exam.getTimeRangeString())
                    putExtra(CourseReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
                    putExtra(CourseReminderReceiver.EXTRA_ADVANCE_MINUTES, advanceMins)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val showIntent = Intent(context, cn.edu.usst.jwgl.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("extra_tab", 1)
                    putExtra("extra_open_exam", true)
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode + 50000,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    // Exams are critical events; always schedule with setAlarmClock for highest priority
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(reminderMillis, showPendingIntent)
                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                    scheduledExamsCount++
                    Log.d(TAG, "Scheduled exam AlarmClock for ${exam.courseName} ($advanceMins mins before) at $reminderMillis")
                } catch (e: Exception) {
                    Log.w(TAG, "setAlarmClock failed for ${exam.courseName}, falling back", e)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderMillis, pendingIntent)
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, reminderMillis, pendingIntent)
                        }
                        scheduledExamsCount++
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed fallback exam reminder for ${exam.courseName}", e2)
                    }
                }
            }
        }
        Log.d(TAG, "Scheduled $scheduledExamsCount exam reminders.")
    }

    fun cancelAllExamReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val cachedExams = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getAllCachedExams()
        for ((idx, exam) in cachedExams.withIndex()) {
            val req1 = 20000 + (exam.courseCode.hashCode().let { if (it < 0) -it else it } % 8000) + (idx % 100)
            val req2 = 30000 + (exam.courseCode.hashCode().let { if (it < 0) -it else it } % 8000) + (idx % 100)
            cancelPendingIntent(context, alarmManager, req1)
            cancelPendingIntent(context, alarmManager, req2)
        }
        Log.d(TAG, "Cancelled all exam reminders.")
    }
}
