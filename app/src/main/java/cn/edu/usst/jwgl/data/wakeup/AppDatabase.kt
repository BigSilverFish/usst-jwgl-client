package cn.edu.usst.jwgl.data.wakeup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse

class AppDatabase private constructor(context: Context, val dbName: String = DATABASE_NAME) {

    private val dbHelper = DatabaseHelper(context.applicationContext, dbName)

    companion object {
        const val LAB_RESERVATION_PREFIX = "[实验预约] "
        private const val DATABASE_NAME = "wakeup.db"
        private const val DATABASE_VERSION = 4

        private val instances = java.util.concurrent.ConcurrentHashMap<String, AppDatabase>()

        fun getDatabaseName(studentId: String? = null): String {
            val targetStudentId = studentId?.trim()?.takeIf { it.isNotEmpty() } ?: ""
            return if (targetStudentId.isNotEmpty()) {
                val safeSid = targetStudentId.replace(Regex("""[^a-zA-Z0-9_-]"""), "")
                "wakeup_${safeSid}.db"
            } else {
                DATABASE_NAME
            }
        }

        fun getDatabase(context: Context, studentId: String? = null): AppDatabase {
            val appCtx = context.applicationContext
            val targetStudentId = studentId?.trim()?.takeIf { it.isNotEmpty() }
                ?: try {
                    cn.edu.usst.jwgl.data.local.AuthPreferences.currentStudentId(appCtx)
                } catch (_: Exception) {
                    ""
                }

            val dbName = getDatabaseName(targetStudentId)

            return instances.computeIfAbsent(dbName) {
                // 仅当老版本单账号数据库迁移，且目标学号确实为老数据主人时才可单次平滑迁移，严禁无差别复制给其他新账号
                if (dbName != DATABASE_NAME && targetStudentId.isNotEmpty()) {
                    try {
                        val newDbFile = appCtx.getDatabasePath(dbName)
                        val legacyDbFile = appCtx.getDatabasePath(DATABASE_NAME)
                        val legacyOwner = try {
                            val authPrefs = appCtx.getSharedPreferences("usst_jwgl_auth", Context.MODE_PRIVATE)
                            authPrefs.getString("auth_student_id", null) ?: authPrefs.getString("student_id", null)
                        } catch (_: Exception) { null }
                        val migrationPrefs = appCtx.getSharedPreferences("wakeup_migration", Context.MODE_PRIVATE)
                        val hasMigrated = migrationPrefs.getBoolean("has_migrated_legacy_db", false)

                        if (!hasMigrated && !newDbFile.exists() && legacyDbFile.exists() && legacyDbFile.length() > 0 && legacyOwner == targetStudentId) {
                            legacyDbFile.copyTo(newDbFile, overwrite = false)
                            migrationPrefs.edit().putBoolean("has_migrated_legacy_db", true).apply()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AppDatabase", "Failed to migrate legacy wakeup.db to $dbName", e)
                    }
                }
                AppDatabase(appCtx, dbName)
            }
        }

        fun closeAll() {
            instances.values.forEach {
                try { it.dbHelper.close() } catch (_: Exception) {}
            }
            instances.clear()
        }
    }

    private class DatabaseHelper(context: Context, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE TimeTableBean (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE TimeDetailBean (
                    node INTEGER NOT NULL,
                    startTime TEXT NOT NULL,
                    endTime TEXT NOT NULL,
                    timeTable INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (node, timeTable),
                    FOREIGN KEY (timeTable) REFERENCES TimeTableBean (id) ON DELETE CASCADE ON UPDATE CASCADE
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE TableBean (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    tableName TEXT NOT NULL,
                    nodes INTEGER NOT NULL DEFAULT 13,
                    background TEXT NOT NULL DEFAULT '',
                    timeTable INTEGER NOT NULL DEFAULT 1,
                    startDate TEXT NOT NULL DEFAULT '2026-09-07',
                    maxWeek INTEGER NOT NULL DEFAULT 20,
                    itemHeight INTEGER NOT NULL DEFAULT 56,
                    itemAlpha INTEGER NOT NULL DEFAULT 75,
                    itemTextSize INTEGER NOT NULL DEFAULT 11,
                    strokeColor INTEGER NOT NULL DEFAULT 0x40ffffff,
                    textColor INTEGER NOT NULL DEFAULT 0xff1f2937,
                    courseTextColor INTEGER NOT NULL DEFAULT 0xffffffff,
                    showSat INTEGER NOT NULL DEFAULT 0,
                    showSun INTEGER NOT NULL DEFAULT 0,
                    sundayFirst INTEGER NOT NULL DEFAULT 0,
                    showOtherWeekCourse INTEGER NOT NULL DEFAULT 1,
                    showTime INTEGER NOT NULL DEFAULT 1,
                    type INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (timeTable) REFERENCES TimeTableBean (id) ON DELETE SET DEFAULT ON UPDATE CASCADE
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE CourseBaseBean (
                    id INTEGER NOT NULL,
                    courseName TEXT NOT NULL,
                    color TEXT NOT NULL,
                    tableId INTEGER NOT NULL,
                    isLocked INTEGER NOT NULL DEFAULT 0,
                    classCode TEXT NOT NULL DEFAULT '',
                    examType TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (id, tableId),
                    FOREIGN KEY (tableId) REFERENCES TableBean (id) ON DELETE CASCADE ON UPDATE CASCADE
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE CourseDetailBean (
                    id INTEGER NOT NULL,
                    day INTEGER NOT NULL,
                    room TEXT,
                    teacher TEXT,
                    startNode INTEGER NOT NULL,
                    step INTEGER NOT NULL,
                    startWeek INTEGER NOT NULL,
                    endWeek INTEGER NOT NULL,
                    type INTEGER NOT NULL,
                    tableId INTEGER NOT NULL,
                    PRIMARY KEY (day, startNode, startWeek, type, tableId, id),
                    FOREIGN KEY (id, tableId) REFERENCES CourseBaseBean (id, tableId) ON DELETE CASCADE ON UPDATE CASCADE
                )
                """.trimIndent()
            )

            // Seed initial data (TimeTableBean 1: 13-node new timetable, TimeTableBean 2: 12-node old timetable)
            db.execSQL("INSERT INTO TimeTableBean (id, name) VALUES (1, '新课时表(13节)');")
            db.execSQL("INSERT INTO TimeTableBean (id, name) VALUES (2, '老课时表(12节)');")

            val usstTimes = arrayOf(
                Triple(1, "08:00", "08:40"),
                Triple(2, "08:45", "09:25"),
                Triple(3, "09:45", "10:25"),
                Triple(4, "10:30", "11:10"),
                Triple(5, "11:15", "11:55"),
                Triple(6, "13:00", "13:40"),
                Triple(7, "13:45", "14:25"),
                Triple(8, "14:45", "15:25"),
                Triple(9, "15:30", "16:10"),
                Triple(10, "16:15", "16:55"),
                Triple(11, "18:00", "18:40"),
                Triple(12, "18:45", "19:25"),
                Triple(13, "19:30", "20:10")
            )
            for ((node, start, end) in usstTimes) {
                val cv = ContentValues().apply {
                    put("node", node)
                    put("startTime", start)
                    put("endTime", end)
                    put("timeTable", 1)
                }
                db.insert("TimeDetailBean", null, cv)
            }

            val usstOldTimes = arrayOf(
                Triple(1, "08:00", "08:45"),
                Triple(2, "08:50", "09:35"),
                Triple(3, "09:55", "10:40"),
                Triple(4, "10:45", "11:30"),
                Triple(5, "11:35", "12:20"),
                Triple(6, "13:15", "14:00"),
                Triple(7, "14:05", "14:50"),
                Triple(8, "15:05", "15:50"),
                Triple(9, "15:55", "16:40"),
                Triple(10, "18:00", "18:45"),
                Triple(11, "18:50", "19:35"),
                Triple(12, "19:40", "20:25")
            )
            for ((node, start, end) in usstOldTimes) {
                val cv = ContentValues().apply {
                    put("node", node)
                    put("startTime", start)
                    put("endTime", end)
                    put("timeTable", 2)
                }
                db.insert("TimeDetailBean", null, cv)
            }

            db.execSQL(
                """
                INSERT INTO TableBean (id, tableName, nodes, startDate, maxWeek, showSat, showSun, type)
                VALUES (1, '2026-2027学年 第1学期', 13, '2026-09-07', 20, 0, 0, 1);
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DELETE FROM TimeDetailBean WHERE timeTable = 1;")
                val usstTimes = arrayOf(
                    Triple(1, "08:00", "08:40"),
                    Triple(2, "08:45", "09:25"),
                    Triple(3, "09:45", "10:25"),
                    Triple(4, "10:30", "11:10"),
                    Triple(5, "11:15", "11:55"),
                    Triple(6, "13:00", "13:40"),
                    Triple(7, "13:45", "14:25"),
                    Triple(8, "14:45", "15:25"),
                    Triple(9, "15:30", "16:10"),
                    Triple(10, "16:15", "16:55"),
                    Triple(11, "18:00", "18:40"),
                    Triple(12, "18:45", "19:25"),
                    Triple(13, "19:30", "20:10")
                )
                for ((node, start, end) in usstTimes) {
                    val cv = ContentValues().apply {
                        put("node", node)
                        put("startTime", start)
                        put("endTime", end)
                        put("timeTable", 1)
                    }
                    db.insert("TimeDetailBean", null, cv)
                }
                db.execSQL("UPDATE TableBean SET maxWeek = 20 WHERE maxWeek = 25;")
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE CourseBaseBean ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0;")
                } catch (_: Exception) {
                    // Column might already exist
                }
            }
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE CourseBaseBean ADD COLUMN classCode TEXT NOT NULL DEFAULT '';")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE CourseBaseBean ADD COLUMN examType TEXT NOT NULL DEFAULT '';")
                } catch (_: Exception) {}
            }
        }
    }

    fun clearAllScheduleData() {
        synchronized(this) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("CourseDetailBean", null, null)
                db.delete("CourseBaseBean", null, null)
                db.delete("TableBean", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    val tableDao = TableDaoImpl(dbHelper)
    val courseBaseDao = CourseBaseDaoImpl(dbHelper)
    val courseDetailDao = CourseDetailDaoImpl(dbHelper)
    val timeDetailDao = TimeDetailDaoImpl(dbHelper)

    private val mergeService = CourseScheduleMergeService(
        dbHelper = dbHelper,
        tableDao = tableDao,
        courseBaseDao = courseBaseDao,
        courseDetailDao = courseDetailDao
    )

    fun ensureLatestTimeTableAndDefaults() =
        timeDetailDao.ensureLatestTimeTableAndDefaults(tableDao, courseBaseDao)

    fun replaceAllLabReservations(tableId: Int, reservations: List<LabReservation>) =
        mergeService.replaceAllLabReservations(tableId, reservations)

    fun addMergedSchoolCourse(tableId: Int, mergedCourse: MergedSchoolCourse): Int =
        mergeService.addMergedSchoolCourse(tableId, mergedCourse)

    fun findConflictsForMergedCourse(tableId: Int, mergedCourse: MergedSchoolCourse): List<CourseConflictInfo> =
        mergeService.findConflictsForMergedCourse(tableId, mergedCourse)

    fun findConflictsForDetails(
        tableId: Int,
        excludeCourseId: Int,
        newDetails: List<CourseDetailBean>
    ): List<CourseConflictInfo> =
        mergeService.findConflictsForDetails(tableId, excludeCourseId, newDetails)
}
