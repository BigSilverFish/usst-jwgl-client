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
        // Reschedule reminders for the new active semester
        if (isReminderEnabled(context)) {
            scheduleUpcomingReminders(context)
        }
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

    /**
     * Schedules reminders STRICTLY for the single active semester.
     * Always cancels all existing alarms first to avoid orphan alarms across semesters.
     */
    fun scheduleUpcomingReminders(context: Context, ignoredCourses: List<CourseItem>? = null) {
        if (!isReminderEnabled(context)) {
            cancelAllReminders(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // Step 1: ALWAYS flush all prior reminders first so no old/orphan alarms remain!
        cancelAllReminders(context)

        // Step 2: Strictly load courses for the active semester
        val activeKey = getActiveSemesterKey(context)
        val parts = activeKey.split("_")
        val activeXnm = parts.getOrNull(0) ?: "2026"
        val activeXqm = parts.getOrNull(1) ?: "3"

        val cacheManager = DataCacheManager(context)
        val customCourseManager = cn.edu.usst.jwgl.data.local.CustomCourseManager(context)

        val cachedData = cacheManager.getTimetable(activeXnm, activeXqm)
        val deletedIds = customCourseManager.getDeletedCourseIds(activeKey)
        val customList = customCourseManager.getCustomCourses(activeKey)

        val courseList = mutableListOf<CourseItem>()
        if (cachedData != null) {
            for (c in cachedData.courses) {
                if (deletedIds.contains(c.id)) continue
                val excluded = customCourseManager.getExcludedWeeks(activeKey, c.id)
                if (excluded.isNotEmpty()) {
                    val remainingWeeks = c.weeks.filter { !excluded.contains(it) }
                    courseList.add(c.copy(weeks = remainingWeeks))
                } else {
                    courseList.add(c)
                }
            }
        }
        courseList.addAll(customList.filter { !deletedIds.contains(it.id) })

        if (courseList.isEmpty()) {
            Log.d(TAG, "No courses in active semester ($activeKey) to schedule reminders.")
            return
        }

        val semConfig = RemoteConfigManager.getSemesterConfig()
        val week1Monday = customCourseManager.getCustomWeek1Monday(activeKey) ?: semConfig.week1Monday
        val totalWeeks = customCourseManager.getCustomTotalWeeks(activeKey, semConfig.totalWeeks)
        val currentWeek = SemesterHelper.calculateCurrentWeek(week1Monday, totalWeeks)
        if (currentWeek !in 1..totalWeeks) {
            Log.d(TAG, "Current week ($currentWeek) is out of semester teaching weeks (1..$totalWeeks).")
            return
        }

        val weekDates = SemesterHelper.getDatesForWeek(week1Monday, currentWeek)
        val now = System.currentTimeMillis()

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        // Iterate through each day of the current week (dayOfWeek 1 to 7)
        for (dayIndex in 0..6) {
            val dayOfWeek = dayIndex + 1
            val dayCal = weekDates.getOrNull(dayIndex) ?: continue

            val dayCourses = courseList.filter { it.dayOfWeek == dayOfWeek && it.isInWeek(currentWeek) }

            for (course in dayCourses) {
                val startSection = course.startSection.coerceIn(1, 13)
                val timePair = SECTION_START_TIMES[startSection] ?: Pair(8, 0)

                val reminderCal = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, timePair.first)
                    set(Calendar.MINUTE, timePair.second)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, -15) // Exactly 15 minutes before class
                }

                val reminderTimeMillis = reminderCal.timeInMillis

                // Only schedule future reminders
                if (reminderTimeMillis > now) {
                    val requestCode = dayOfWeek * 100 + startSection
                    val intent = Intent(context, CourseReminderReceiver::class.java).apply {
                        putExtra(CourseReminderReceiver.EXTRA_COURSE_NAME, course.name)
                        putExtra(CourseReminderReceiver.EXTRA_CLASSROOM, course.classroom)
                        putExtra(CourseReminderReceiver.EXTRA_TEACHER, course.teacher)
                        putExtra(CourseReminderReceiver.EXTRA_START_TIME, String.format("%02d:%02d", timePair.first, timePair.second))
                        putExtra(CourseReminderReceiver.EXTRA_SECTION, "第${startSection}节")
                        putExtra(CourseReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    try {
                        if (canScheduleExact) {
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
                        Log.d(TAG, "Scheduled reminder for ${course.name} ($activeKey) at ${reminderCal.time}")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to schedule alarm for ${course.name}: ${e.message}")
                    }
                }
            }
        }
    }

    fun cancelAllReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (day in 1..7) {
            for (sec in 1..13) {
                val requestCode = day * 100 + sec
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
                    } catch (e: Throwable) {
                        // Safe cancel
                    }
                }
            }
        }
        Log.d(TAG, "Cancelled all course reminders.")
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

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to schedule test reminder: ${e.message}")
        }
    }
}
