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

        // Step 2: Strictly load courses for the active table in AppDatabase
        val db = cn.edu.usst.jwgl.data.wakeup.AppDatabase.getDatabase(context)
        val activeTable = db.tableDao.getDefaultTable()
        val curWeek = cn.edu.usst.jwgl.data.wakeup.CourseUtils.countWeek(activeTable.startDate)
        if (curWeek !in 1..activeTable.maxWeek) {
            Log.d(TAG, "Current week ($curWeek) is outside active table maxWeek (${activeTable.maxWeek}).")
            return
        }

        val times = db.timeDetailDao.getTimeDetails(activeTable.timeTable)
        val now = System.currentTimeMillis()

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        val weekCal = Calendar.getInstance()
        weekCal.firstDayOfWeek = if (activeTable.sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        while (weekCal.get(Calendar.DAY_OF_WEEK) != weekCal.firstDayOfWeek) {
            weekCal.add(Calendar.DAY_OF_MONTH, -1)
        }

        for (dayIdx in 0..6) {
            val dayNumber = if (activeTable.sundayFirst) (if (dayIdx == 0) 7 else dayIdx) else (dayIdx + 1)
            val dayCal = (weekCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, dayIdx)
            }

            val weekType = if (curWeek % 2 != 0) 1 else 2
            val dayCourses = db.courseBaseDao.getCourseByDayAndWeekOfTable(dayNumber, curWeek, weekType, activeTable.id)

            for (course in dayCourses) {
                val node = course.startNode
                val timeItem = times.find { it.node == node }
                val (hour, minute) = if (timeItem != null) {
                    val parts = timeItem.startTime.split(":")
                    Pair(parts.getOrNull(0)?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                } else {
                    Pair(8, 0)
                }

                val reminderCal = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, -15) // Exactly 15 minutes before class
                }

                val reminderTimeMillis = reminderCal.timeInMillis
                if (reminderTimeMillis > now) {
                    val requestCode = dayNumber * 100 + node
                    val intent = Intent(context, CourseReminderReceiver::class.java).apply {
                        putExtra(CourseReminderReceiver.EXTRA_COURSE_NAME, course.courseName)
                        putExtra(CourseReminderReceiver.EXTRA_CLASSROOM, course.room ?: "")
                        putExtra(CourseReminderReceiver.EXTRA_TEACHER, course.teacher ?: "")
                        putExtra(CourseReminderReceiver.EXTRA_START_TIME, String.format("%02d:%02d", hour, minute))
                        putExtra(CourseReminderReceiver.EXTRA_SECTION, "第${node}节")
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
                        Log.d(TAG, "Scheduled reminder for ${course.courseName} at ${reminderCal.time}")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to schedule alarm for ${course.courseName}: ${e.message}")
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
