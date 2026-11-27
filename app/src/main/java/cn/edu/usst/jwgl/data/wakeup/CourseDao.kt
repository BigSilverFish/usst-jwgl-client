package cn.edu.usst.jwgl.data.wakeup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CourseBaseDaoImpl(private val dbHelper: SQLiteOpenHelper) {

    fun insertCourseBase(course: CourseBaseBean) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("id", course.id)
            put("courseName", course.courseName)
            put("color", course.color)
            put("tableId", course.tableId)
            put("isLocked", if (course.isLocked) 1 else 0)
            put("classCode", course.classCode)
            put("examType", course.examType)
        }
        db.insertWithOnConflict(
            "CourseBaseBean",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun updateCourseBase(course: CourseBaseBean) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("courseName", course.courseName)
            put("color", course.color)
            put("isLocked", if (course.isLocked) 1 else 0)
            put("classCode", course.classCode)
            put("examType", course.examType)
        }
        db.update(
            "CourseBaseBean",
            cv,
            "id = ? AND tableId = ?",
            arrayOf(course.id.toString(), course.tableId.toString())
        )
    }

    fun setCourseLocked(id: Int, tableId: Int, isLocked: Boolean) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("isLocked", if (isLocked) 1 else 0)
        }
        db.update(
            "CourseBaseBean",
            cv,
            "id = ? AND tableId = ?",
            arrayOf(id.toString(), tableId.toString())
        )
    }

    fun deleteCourseBase(id: Int, tableId: Int) {
        val db = dbHelper.writableDatabase
        db.delete(
            "CourseBaseBean",
            "id = ? AND tableId = ?",
            arrayOf(id.toString(), tableId.toString())
        )
        db.delete(
            "CourseDetailBean",
            "id = ? AND tableId = ?",
            arrayOf(id.toString(), tableId.toString())
        )
    }

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
                cursorToCourseBaseBean(it)
            } else null
        }
    }

    private fun cursorToCourseBaseBean(c: Cursor): CourseBaseBean {
        val lockedIdx = c.getColumnIndex("isLocked")
        val classIdx = c.getColumnIndex("classCode")
        val examIdx = c.getColumnIndex("examType")
        return CourseBaseBean(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            courseName = c.getString(c.getColumnIndexOrThrow("courseName")),
            color = c.getString(c.getColumnIndexOrThrow("color")),
            tableId = c.getInt(c.getColumnIndexOrThrow("tableId")),
            isLocked = if (lockedIdx >= 0) c.getInt(lockedIdx) == 1 else false,
            classCode = if (classIdx >= 0) c.getString(classIdx).orEmpty() else "",
            examType = if (examIdx >= 0) c.getString(examIdx).orEmpty() else ""
        )
    }

    fun getCourseBasesOfTable(tableId: Int): List<CourseBaseBean> {
        val list = mutableListOf<CourseBaseBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "CourseBaseBean",
            null,
            "tableId = ?",
            arrayOf(tableId.toString()),
            null,
            null,
            "id ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToCourseBaseBean(it))
            }
        }
        return list
    }

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

    fun getCourseOfTable(tableId: Int): List<CourseBean> {
        val list = mutableListOf<CourseBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                   d.startWeek, d.endWeek, d.type, b.color, b.tableId, b.isLocked,
                   b.classCode, b.examType
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

    fun getCourseByDayOfTable(day: Int, tableId: Int): List<CourseBean> {
        val list = mutableListOf<CourseBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                   d.startWeek, d.endWeek, d.type, b.color, b.tableId, b.isLocked,
                   b.classCode, b.examType
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

    fun getCourseByDayAndWeekOfTable(day: Int, week: Int, type: Int, tableId: Int): List<CourseBean> {
        val list = mutableListOf<CourseBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT b.id, b.courseName, d.day, d.room, d.teacher, d.startNode, d.step,
                   d.startWeek, d.endWeek, d.type, b.color, b.tableId, b.isLocked,
                   b.classCode, b.examType
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
        val isLocked = if (c.columnCount > 12) (c.getInt(12) == 1) else false
        val classCode = if (c.columnCount > 13) c.getString(13).orEmpty() else ""
        val examType = if (c.columnCount > 14) c.getString(14).orEmpty() else ""
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
            tableId = c.getInt(11),
            isLocked = isLocked,
            classCode = classCode,
            examType = examType
        )
    }
}

class CourseDetailDaoImpl(private val dbHelper: SQLiteOpenHelper) {

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

    fun insertList(list: List<CourseDetailBean>) {
        val db = dbHelper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            for (item in list) {
                insertDetail(item)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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

    fun deleteByIdOfTable(id: Int, tableId: Int) {
        val db = dbHelper.writableDatabase
        db.delete(
            "CourseDetailBean",
            "id = ? AND tableId = ?",
            arrayOf(id.toString(), tableId.toString())
        )
    }

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
