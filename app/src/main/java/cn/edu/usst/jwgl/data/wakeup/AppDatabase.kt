package cn.edu.usst.jwgl.data.wakeup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase private constructor(context: Context) {

    private val dbHelper = DatabaseHelper(context.applicationContext)

    companion object {
        private const val DATABASE_NAME = "wakeup.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context).also { INSTANCE = it }
            }
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

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
                    maxWeek INTEGER NOT NULL DEFAULT 25,
                    itemHeight INTEGER NOT NULL DEFAULT 56,
                    itemAlpha INTEGER NOT NULL DEFAULT 75,
                    itemTextSize INTEGER NOT NULL DEFAULT 11,
                    strokeColor INTEGER NOT NULL DEFAULT 0x40ffffff,
                    textColor INTEGER NOT NULL DEFAULT 0xff1f2937,
                    courseTextColor INTEGER NOT NULL DEFAULT 0xffffffff,
                    showSat INTEGER NOT NULL DEFAULT 1,
                    showSun INTEGER NOT NULL DEFAULT 1,
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

            // Seed initial data
            db.execSQL("INSERT INTO TimeTableBean (id, name) VALUES (1, '默认作息');")

            val usstTimes = arrayOf(
                Triple(1, "08:00", "08:45"),
                Triple(2, "08:50", "09:35"),
                Triple(3, "09:55", "10:40"),
                Triple(4, "10:45", "11:30"),
                Triple(5, "11:35", "12:20"),
                Triple(6, "13:30", "14:15"),
                Triple(7, "14:20", "15:05"),
                Triple(8, "15:25", "16:10"),
                Triple(9, "16:15", "17:00"),
                Triple(10, "18:00", "18:45"),
                Triple(11, "18:50", "19:35"),
                Triple(12, "19:40", "20:25"),
                Triple(13, "20:30", "21:15")
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

            db.execSQL(
                """
                INSERT INTO TableBean (id, tableName, nodes, startDate, maxWeek, type)
                VALUES (1, '2026-2027学年 第1学期', 13, '2026-09-07', 25, 1);
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future schema migrations
        }
    }

    val tableDao = TableDaoImpl()
    val courseBaseDao = CourseBaseDaoImpl()
    val courseDetailDao = CourseDetailDaoImpl()
    val timeDetailDao = TimeDetailDaoImpl()

    // ----------------------------------------------------
    // TableDao
    // ----------------------------------------------------
    inner class TableDaoImpl {

        @Synchronized
        fun insertTable(table: TableBean): Long {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("tableName", table.tableName)
                put("nodes", table.nodes)
                put("background", table.background)
                put("timeTable", table.timeTable)
                put("startDate", table.startDate)
                put("maxWeek", table.maxWeek)
                put("itemHeight", table.itemHeight)
                put("itemAlpha", table.itemAlpha)
                put("itemTextSize", table.itemTextSize)
                put("strokeColor", table.strokeColor)
                put("textColor", table.textColor)
                put("courseTextColor", table.courseTextColor)
                put("showSat", if (table.showSat) 1 else 0)
                put("showSun", if (table.showSun) 1 else 0)
                put("sundayFirst", if (table.sundayFirst) 1 else 0)
                put("showOtherWeekCourse", if (table.showOtherWeekCourse) 1 else 0)
                put("showTime", if (table.showTime) 1 else 0)
                put("type", table.type)
            }
            return db.insert("TableBean", null, cv)
        }

        @Synchronized
        fun updateTable(table: TableBean) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("tableName", table.tableName)
                put("nodes", table.nodes)
                put("background", table.background)
                put("timeTable", table.timeTable)
                put("startDate", table.startDate)
                put("maxWeek", table.maxWeek)
                put("itemHeight", table.itemHeight)
                put("itemAlpha", table.itemAlpha)
                put("itemTextSize", table.itemTextSize)
                put("strokeColor", table.strokeColor)
                put("textColor", table.textColor)
                put("courseTextColor", table.courseTextColor)
                put("showSat", if (table.showSat) 1 else 0)
                put("showSun", if (table.showSun) 1 else 0)
                put("sundayFirst", if (table.sundayFirst) 1 else 0)
                put("showOtherWeekCourse", if (table.showOtherWeekCourse) 1 else 0)
                put("showTime", if (table.showTime) 1 else 0)
                put("type", table.type)
            }
            db.update("TableBean", cv, "id = ?", arrayOf(table.id.toString()))
        }

        @Synchronized
        fun deleteTable(id: Int) {
            val db = dbHelper.writableDatabase
            db.delete("TableBean", "id = ?", arrayOf(id.toString()))
        }

        @Synchronized
        fun getTableById(id: Int): TableBean? {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "TableBean",
                null,
                "id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null
            )
            return cursor.use {
                if (it.moveToFirst()) cursorToTableBean(it) else null
            }
        }

        @Synchronized
        fun getDefaultTable(): TableBean {
            val db = dbHelper.readableDatabase
            var cursor = db.query("TableBean", null, "type = 1", null, null, null, "id DESC", "1")
            cursor.use {
                if (it.moveToFirst()) {
                    return cursorToTableBean(it)
                }
            }
            // If no table is marked type=1, fallback to first table
            cursor = db.query("TableBean", null, null, null, null, null, "id ASC", "1")
            cursor.use {
                if (it.moveToFirst()) {
                    val table = cursorToTableBean(it)
                    setDefaultTable(table.id)
                    return table
                }
            }
            // Fallback default
            val defaultTable = TableBean(id = 1)
            insertTable(defaultTable)
            return defaultTable
        }

        @Synchronized
        fun setDefaultTable(newId: Int) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("UPDATE TableBean SET type = 0")
                db.execSQL("UPDATE TableBean SET type = 1 WHERE id = ?", arrayOf(newId.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        @Synchronized
        fun getAllTables(): List<TableBean> {
            val list = mutableListOf<TableBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.query("TableBean", null, null, null, null, null, "id ASC")
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToTableBean(it))
                }
            }
            return list
        }

        @Synchronized
        fun getTableSelectList(): List<TableSelectBean> {
            val list = mutableListOf<TableSelectBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, tableName, background, maxWeek, nodes, type FROM TableBean ORDER BY id ASC",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        TableSelectBean(
                            id = it.getInt(0),
                            tableName = it.getString(1),
                            background = it.getString(2) ?: "",
                            maxWeek = it.getInt(3),
                            nodes = it.getInt(4),
                            type = it.getInt(5)
                        )
                    )
                }
            }
            return list
        }

        private fun cursorToTableBean(c: Cursor): TableBean {
            return TableBean(
                id = c.getInt(c.getColumnIndexOrThrow("id")),
                tableName = c.getString(c.getColumnIndexOrThrow("tableName")),
                nodes = c.getInt(c.getColumnIndexOrThrow("nodes")),
                background = c.getString(c.getColumnIndexOrThrow("background")) ?: "",
                timeTable = c.getInt(c.getColumnIndexOrThrow("timeTable")),
                startDate = c.getString(c.getColumnIndexOrThrow("startDate")),
                maxWeek = c.getInt(c.getColumnIndexOrThrow("maxWeek")),
                itemHeight = c.getInt(c.getColumnIndexOrThrow("itemHeight")),
                itemAlpha = c.getInt(c.getColumnIndexOrThrow("itemAlpha")),
                itemTextSize = c.getInt(c.getColumnIndexOrThrow("itemTextSize")),
                strokeColor = c.getInt(c.getColumnIndexOrThrow("strokeColor")),
                textColor = c.getInt(c.getColumnIndexOrThrow("textColor")),
                courseTextColor = c.getInt(c.getColumnIndexOrThrow("courseTextColor")),
                showSat = c.getInt(c.getColumnIndexOrThrow("showSat")) == 1,
                showSun = c.getInt(c.getColumnIndexOrThrow("showSun")) == 1,
                sundayFirst = c.getInt(c.getColumnIndexOrThrow("sundayFirst")) == 1,
                showOtherWeekCourse = c.getInt(c.getColumnIndexOrThrow("showOtherWeekCourse")) == 1,
                showTime = c.getInt(c.getColumnIndexOrThrow("showTime")) == 1,
                type = c.getInt(c.getColumnIndexOrThrow("type"))
            )
        }
    }

    // ----------------------------------------------------
    // CourseBaseDao
    // ----------------------------------------------------
    inner class CourseBaseDaoImpl {

        @Synchronized
        fun insertCourseBase(course: CourseBaseBean) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("id", course.id)
                put("courseName", course.courseName)
                put("color", course.color)
                put("tableId", course.tableId)
            }
            db.insertWithOnConflict(
                "CourseBaseBean",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        @Synchronized
        fun updateCourseBase(course: CourseBaseBean) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("courseName", course.courseName)
                put("color", course.color)
            }
            db.update(
                "CourseBaseBean",
                cv,
                "id = ? AND tableId = ?",
                arrayOf(course.id.toString(), course.tableId.toString())
            )
        }

        @Synchronized
        fun deleteCourseBase(id: Int, tableId: Int) {
            val db = dbHelper.writableDatabase
            db.delete(
                "CourseBaseBean",
                "id = ? AND tableId = ?",
                arrayOf(id.toString(), tableId.toString())
            )
        }

        @Synchronized
        fun getCourseBaseById(id: Int, tableId: Int): CourseBaseBean? {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "CourseBaseBean",
                null,
                "id = ? AND tableId = ?",
                arrayOf(id.toString(), tableId.toString()),
                null,
                null,
                null
            )
            return cursor.use {
                if (it.moveToFirst()) {
                    CourseBaseBean(
                        id = it.getInt(it.getColumnIndexOrThrow("id")),
                        courseName = it.getString(it.getColumnIndexOrThrow("courseName")),
                        color = it.getString(it.getColumnIndexOrThrow("color")),
                        tableId = it.getInt(it.getColumnIndexOrThrow("tableId"))
                    )
                } else null
            }
        }

        @Synchronized
        fun getLastIdOfTable(tableId: Int): Int {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COALESCE(MAX(id), 0) FROM CourseBaseBean WHERE tableId = ?",
                arrayOf(tableId.toString())
            )
            return cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }

        @Synchronized
        fun getCourseOfTable(tableId: Int): List<CourseBean> {
            val list = mutableListOf<CourseBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                       d.startWeek, d.endWeek, d.type, b.color, b.tableId
                FROM CourseBaseBean b
                INNER JOIN CourseDetailBean d ON b.id = d.id AND b.tableId = d.tableId
                WHERE b.tableId = ?
                ORDER BY d.day ASC, d.startNode ASC
                """.trimIndent(),
                arrayOf(tableId.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToCourseBean(it))
                }
            }
            return list
        }

        @Synchronized
        fun getCourseByDayOfTable(day: Int, tableId: Int): List<CourseBean> {
            val list = mutableListOf<CourseBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                       d.startWeek, d.endWeek, d.type, b.color, b.tableId
                FROM CourseBaseBean b
                INNER JOIN CourseDetailBean d ON b.id = d.id AND b.tableId = d.tableId
                WHERE d.day = ? AND b.tableId = ?
                ORDER BY d.startNode ASC
                """.trimIndent(),
                arrayOf(day.toString(), tableId.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToCourseBean(it))
                }
            }
            return list
        }

        @Synchronized
        fun getCourseByDayAndWeekOfTable(day: Int, week: Int, type: Int, tableId: Int): List<CourseBean> {
            val list = mutableListOf<CourseBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                       d.startWeek, d.endWeek, d.type, b.color, b.tableId
                FROM CourseBaseBean b
                INNER JOIN CourseDetailBean d ON b.id = d.id AND b.tableId = d.tableId
                WHERE d.day = ? AND b.tableId = ? AND d.startWeek <= ? AND d.endWeek >= ?
                  AND (d.type = 0 OR d.type = ?)
                ORDER BY d.startNode ASC
                """.trimIndent(),
                arrayOf(day.toString(), tableId.toString(), week.toString(), week.toString(), type.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToCourseBean(it))
                }
            }
            return list
        }

        private fun cursorToCourseBean(c: Cursor): CourseBean {
            return CourseBean(
                id = c.getInt(0),
                courseName = c.getString(1),
                day = c.getInt(2),
                room = c.getString(3) ?: "",
                teacher = c.getString(4) ?: "",
                startNode = c.getInt(5),
                step = c.getInt(6),
                startWeek = c.getInt(7),
                endWeek = c.getInt(8),
                type = c.getInt(9),
                color = c.getString(10),
                tableId = c.getInt(11)
            )
        }
    }

    // ----------------------------------------------------
    // CourseDetailDao
    // ----------------------------------------------------
    inner class CourseDetailDaoImpl {

        @Synchronized
        fun insertDetail(detail: CourseDetailBean) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("id", detail.id)
                put("day", detail.day)
                put("room", detail.room)
                put("teacher", detail.teacher)
                put("startNode", detail.startNode)
                put("step", detail.step)
                put("startWeek", detail.startWeek)
                put("endWeek", detail.endWeek)
                put("type", detail.type)
                put("tableId", detail.tableId)
            }
            db.insertWithOnConflict(
                "CourseDetailBean",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        @Synchronized
        fun insertList(list: List<CourseDetailBean>) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (item in list) {
                    insertDetail(item)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        @Synchronized
        fun deleteCourseDetail(detail: CourseDetailBean) {
            val db = dbHelper.writableDatabase
            db.delete(
                "CourseDetailBean",
                "id = ? AND day = ? AND startNode = ? AND startWeek = ? AND type = ? AND tableId = ?",
                arrayOf(
                    detail.id.toString(),
                    detail.day.toString(),
                    detail.startNode.toString(),
                    detail.startWeek.toString(),
                    detail.type.toString(),
                    detail.tableId.toString()
                )
            )
        }

        @Synchronized
        fun deleteByIdOfTable(id: Int, tableId: Int) {
            val db = dbHelper.writableDatabase
            db.delete(
                "CourseDetailBean",
                "id = ? AND tableId = ?",
                arrayOf(id.toString(), tableId.toString())
            )
        }

        @Synchronized
        fun getDetailByIdOfTable(id: Int, tableId: Int): List<CourseDetailBean> {
            val list = mutableListOf<CourseDetailBean>()
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "CourseDetailBean",
                null,
                "id = ? AND tableId = ?",
                arrayOf(id.toString(), tableId.toString()),
                null,
                null,
                "day ASC, startNode ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToCourseDetailBean(it))
                }
            }
            return list
        }

        private fun cursorToCourseDetailBean(c: Cursor): CourseDetailBean {
            return CourseDetailBean(
                id = c.getInt(c.getColumnIndexOrThrow("id")),
                day = c.getInt(c.getColumnIndexOrThrow("day")),
                room = c.getString(c.getColumnIndexOrThrow("room")),
                teacher = c.getString(c.getColumnIndexOrThrow("teacher")),
                startNode = c.getInt(c.getColumnIndexOrThrow("startNode")),
                step = c.getInt(c.getColumnIndexOrThrow("step")),
                startWeek = c.getInt(c.getColumnIndexOrThrow("startWeek")),
                endWeek = c.getInt(c.getColumnIndexOrThrow("endWeek")),
                type = c.getInt(c.getColumnIndexOrThrow("type")),
                tableId = c.getInt(c.getColumnIndexOrThrow("tableId"))
            )
        }
    }

    // ----------------------------------------------------
    // TimeDetailDao
    // ----------------------------------------------------
    inner class TimeDetailDaoImpl {

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
    }
}
