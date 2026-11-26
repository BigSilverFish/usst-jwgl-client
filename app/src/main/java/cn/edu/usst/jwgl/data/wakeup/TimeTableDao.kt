package cn.edu.usst.jwgl.data.wakeup

import android.content.ContentValues
import android.database.sqlite.SQLiteOpenHelper

class TimeDetailDaoImpl(private val dbHelper: SQLiteOpenHelper) {

    @Synchronized
    fun getTimeDetails(timeTable: Int = 1): List<TimeDetailBean> {
        val list = mutableListOf<TimeDetailBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "TimeDetailBean",
            null,
            "timeTable = ?",
            arrayOf(timeTable.toString()),
            null,
            null,
            "node ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TimeDetailBean(
                        node = it.getInt(it.getColumnIndexOrThrow("node")),
                        startTime = it.getString(it.getColumnIndexOrThrow("startTime")),
                        endTime = it.getString(it.getColumnIndexOrThrow("endTime")),
                        timeTable = it.getInt(it.getColumnIndexOrThrow("timeTable"))
                    )
                )
            }
        }
        return list
    }

    fun ensureLatestTimeTableAndDefaults(
        tableDao: TableDaoImpl,
        courseBaseDao: CourseBaseDaoImpl
    ) {
        val db = dbHelper.writableDatabase

        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO TimeTableBean (id, name) VALUES (1, '新课时表(13节)');")
            db.execSQL("INSERT OR IGNORE INTO TimeTableBean (id, name) VALUES (2, '老课时表(12节)');")

            val times = getTimeDetails(1)
            val needsTimeUpdate = times.isEmpty() || times.any { it.node == 2 && it.startTime == "08:50" }
            if (needsTimeUpdate) {
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
            }

            val oldTimes = getTimeDetails(2)
            if (oldTimes.isEmpty() || oldTimes.size != 12) {
                db.execSQL("DELETE FROM TimeDetailBean WHERE timeTable = 2;")
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
            }

            val allTables = tableDao.getAllTables()
            for (t in allTables) {
                val isOld = CourseUtils.isBefore2025_2026_2(t.tableName)
                val expectedNodes = if (isOld) 12 else 13
                val expectedTimeTable = if (isOld) 2 else 1
                var tableChanged = false
                if (t.nodes != expectedNodes || t.timeTable != expectedTimeTable) {
                    t.nodes = expectedNodes
                    t.timeTable = expectedTimeTable
                    tableChanged = true
                }
                if (t.maxWeek == 25) {
                    t.maxWeek = 20
                    tableChanged = true
                }
                if (tableChanged) {
                    tableDao.updateTable(t)
                }
            }

            val defaultTable = tableDao.getDefaultTable()
            var updated = false
            val allCourses = courseBaseDao.getCourseOfTable(defaultTable.id)
            val hasWeekend = allCourses.any { it.day == 6 || it.day == 7 }
            if (!hasWeekend && (defaultTable.showSat || defaultTable.showSun)) {
                defaultTable.showSat = false
                defaultTable.showSun = false
                updated = true
            }
            if (updated) {
                tableDao.updateTable(defaultTable)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
