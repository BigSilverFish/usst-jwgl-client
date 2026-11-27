package cn.edu.usst.jwgl.data.wakeup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper

class TableDaoImpl(private val dbHelper: SQLiteOpenHelper) {

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

    fun deleteTable(id: Int) {
        val db = dbHelper.writableDatabase
        db.delete("TableBean", "id = ?", arrayOf(id.toString()))
    }

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

    fun getDefaultTable(): TableBean {
        val db = dbHelper.readableDatabase
        var cursor = db.query("TableBean", null, "type = 1", null, null, null, "id DESC", "1")
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTableBean(it)
            }
        }
        cursor = db.query("TableBean", null, null, null, null, null, "id ASC", "1")
        cursor.use {
            if (it.moveToFirst()) {
                val table = cursorToTableBean(it)
                setDefaultTable(table.id)
                return table
            }
        }
        val defaultTable = TableBean(id = 1)
        insertTable(defaultTable)
        return defaultTable
    }

    fun setDefaultTable(newId: Int) {
        val db = dbHelper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.execSQL("UPDATE TableBean SET type = 0")
            db.execSQL("UPDATE TableBean SET type = 1 WHERE id = ?", arrayOf(newId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllTables(): List<TableBean> {
        val list = mutableListOf<TableBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.query("TableBean", null, null, null, null, null, "startDate DESC, id DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTableBean(it))
            }
        }
        return list
    }

    fun getTableSelectList(): List<TableSelectBean> {
        val list = mutableListOf<TableSelectBean>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, tableName, background, maxWeek, nodes, type FROM TableBean ORDER BY startDate DESC, id DESC",
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
