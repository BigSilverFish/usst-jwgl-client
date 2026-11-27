package cn.edu.usst.jwgl.data.wakeup

import android.database.sqlite.SQLiteOpenHelper
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse

/**
 * 课程导入合并、实验预约替换与排课冲突检测服务
 */
class CourseScheduleMergeService(
    private val dbHelper: SQLiteOpenHelper,
    private val tableDao: TableDaoImpl,
    private val courseBaseDao: CourseBaseDaoImpl,
    private val courseDetailDao: CourseDetailDaoImpl
) {

    fun replaceAllLabReservations(tableId: Int, reservations: List<LabReservation>) {
        require(reservations.isNotEmpty()) { "没有可导入的实验预约" }
        val table = tableDao.getTableById(tableId) ?: throw IllegalArgumentException("找不到目标课表")
        require(reservations.all {
            it.week in 1..table.maxWeek && it.day in 1..7 &&
                it.startNode in 1..table.nodes && it.step > 0 && it.startNode + it.step - 1 <= table.nodes && it.name.isNotBlank()
        }) {
            "预约时间超出当前课表节次范围"
        }

        val db = dbHelper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val imported = courseBaseDao.getCourseOfTable(tableId)
                .filter { it.courseName.startsWith(AppDatabase.LAB_RESERVATION_PREFIX) }
                .distinctBy { it.id }

            for (course in imported) {
                val details = courseDetailDao.getDetailByIdOfTable(course.id, tableId)
                details.filter { it.type == 0 }
                    .forEach(courseDetailDao::deleteCourseDetail)
                if (courseDetailDao.getDetailByIdOfTable(course.id, tableId).isEmpty()) {
                    courseBaseDao.deleteCourseBase(course.id, tableId)
                }
            }

            var nextId = courseBaseDao.getLastIdOfTable(tableId)
            for ((name, items) in reservations.groupBy { it.name.trim() }) {
                if (name.isBlank()) continue
                nextId++
                courseBaseDao.insertCourseBase(
                    CourseBaseBean(
                        id = nextId,
                        courseName = AppDatabase.LAB_RESERVATION_PREFIX + name,
                        color = CourseUtils.getColorHex(nextId - 1),
                        tableId = tableId
                    )
                )
                courseDetailDao.insertList(items.map { item ->
                    CourseDetailBean(
                        id = nextId,
                        day = item.day,
                        room = item.room,
                        teacher = "",
                        startNode = item.startNode,
                        step = item.step,
                        startWeek = item.week,
                        endWeek = item.week,
                        type = 0,
                        tableId = tableId
                    )
                })
            }

            if (reservations.any { it.day == 6 }) table.showSat = true
            if (reservations.any { it.day == 7 }) table.showSun = true
            tableDao.updateTable(table)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addMergedSchoolCourse(tableId: Int, mergedCourse: MergedSchoolCourse): Int {
        val table = tableDao.getTableById(tableId) ?: tableDao.getDefaultTable()
        val nextId = courseBaseDao.getLastIdOfTable(table.id) + 1
        val color = CourseUtils.getColorHex(nextId - 1)
        val classCode = mergedCourse.bjmc.ifBlank { mergedCourse.bjdm }.ifBlank { mergedCourse.kcdm }
        val baseBean = CourseBaseBean(
            id = nextId,
            courseName = mergedCourse.kcmc.ifBlank { "未命名课程" },
            color = color,
            tableId = table.id,
            classCode = classCode
        )
        courseBaseDao.insertCourseBase(baseBean)

        val detailsToInsert = mutableListOf<CourseDetailBean>()
        for (slot in mergedCourse.slots) {
            val weekBeans = if (slot.weekList.isNotEmpty()) {
                CourseUtils.intList2WeekBeanList(slot.weekList)
            } else if (slot.qsz in 1..30 && slot.jsz >= slot.qsz) {
                listOf(WeekBean(start = slot.qsz, end = slot.jsz, type = 0))
            } else {
                listOf(WeekBean(start = 1, end = table.maxWeek, type = 0))
            }
            val teacher = if (mergedCourse.lszc.isNotEmpty() && mergedCourse.lszc != "无") {
                "${mergedCourse.lsmc} (${mergedCourse.lszc})"
            } else {
                mergedCourse.lsmc
            }
            val room = slot.jsdd.ifBlank { "待定地点" }
            val startNode = if (slot.jc in 1..table.nodes) slot.jc else 1
            val step = if (slot.jccd in 1..table.nodes) slot.jccd else 2
            val day = if (slot.zj in 1..7) slot.zj else 1

            for (wb in weekBeans) {
                detailsToInsert.add(
                    CourseDetailBean(
                        id = nextId,
                        day = day,
                        room = room,
                        teacher = teacher,
                        startNode = startNode,
                        step = step,
                        startWeek = wb.start,
                        endWeek = wb.end,
                        type = wb.type,
                        tableId = table.id
                    )
                )
            }
        }

        if (detailsToInsert.isNotEmpty()) {
            courseDetailDao.insertList(detailsToInsert)
        }

        var tableUpdated = false
        if (detailsToInsert.any { it.day == 6 } && !table.showSat) {
            table.showSat = true
            tableUpdated = true
        }
        if (detailsToInsert.any { it.day == 7 } && !table.showSun) {
            table.showSun = true
            tableUpdated = true
        }
        if (tableUpdated) {
            tableDao.updateTable(table)
        }

        return nextId
    }

    fun findConflictsForMergedCourse(tableId: Int, mergedCourse: MergedSchoolCourse): List<CourseConflictInfo> {
        val table = tableDao.getTableById(tableId) ?: tableDao.getDefaultTable()
        val tempDetails = mutableListOf<CourseDetailBean>()
        for (slot in mergedCourse.slots) {
            val weekBeans = if (slot.weekList.isNotEmpty()) {
                CourseUtils.intList2WeekBeanList(slot.weekList)
            } else if (slot.qsz in 1..30 && slot.jsz >= slot.qsz) {
                listOf(WeekBean(start = slot.qsz, end = slot.jsz, type = 0))
            } else {
                listOf(WeekBean(start = 1, end = table.maxWeek, type = 0))
            }
            val startNode = if (slot.jc in 1..table.nodes) slot.jc else 1
            val step = if (slot.jccd in 1..table.nodes) slot.jccd else 2
            val day = if (slot.zj in 1..7) slot.zj else 1
            for (wb in weekBeans) {
                tempDetails.add(
                    CourseDetailBean(
                        id = -1,
                        day = day,
                        room = slot.jsdd.ifBlank { "待定地点" },
                        teacher = mergedCourse.lsmc,
                        startNode = startNode,
                        step = step,
                        startWeek = wb.start,
                        endWeek = wb.end,
                        type = wb.type,
                        tableId = table.id
                    )
                )
            }
        }
        return findConflictsForDetails(table.id, excludeCourseId = -1, tempDetails)
    }

    fun findConflictsForDetails(
        tableId: Int,
        excludeCourseId: Int,
        newDetails: List<CourseDetailBean>
    ): List<CourseConflictInfo> {
        val existingCourses = courseBaseDao.getCourseOfTable(tableId).filter { it.id != excludeCourseId }
        if (existingCourses.isEmpty() || newDetails.isEmpty()) return emptyList()

        val conflicts = mutableListOf<CourseConflictInfo>()

        for (newDetail in newDetails) {
            val newWeeks = (newDetail.startWeek..newDetail.endWeek).filter { w ->
                when (newDetail.type) {
                    1 -> w % 2 != 0
                    2 -> w % 2 == 0
                    else -> true
                }
            }.toSet()
            if (newWeeks.isEmpty()) continue

            val newStartNode = newDetail.startNode
            val newEndNode = newDetail.startNode + newDetail.step - 1

            for (exist in existingCourses) {
                if (exist.day != newDetail.day) continue

                val existStartNode = exist.startNode
                val existEndNode = exist.startNode + exist.step - 1

                val overlapStart = maxOf(newStartNode, existStartNode)
                val overlapEnd = minOf(newEndNode, existEndNode)
                if (overlapStart > overlapEnd) continue

                val existWeeks = (exist.startWeek..exist.endWeek).filter { w ->
                    when (exist.type) {
                        1 -> w % 2 != 0
                        2 -> w % 2 == 0
                        else -> true
                    }
                }.toSet()

                val commonWeeks = (newWeeks intersect existWeeks).sorted()
                if (commonWeeks.isNotEmpty()) {
                    val existWeekTypeStr = when (exist.type) {
                        1 -> " (单周)"
                        2 -> " (双周)"
                        else -> ""
                    }
                    conflicts.add(
                        CourseConflictInfo(
                            existingCourseName = exist.courseName,
                            existingDay = exist.day,
                            existingStartNode = existStartNode,
                            existingEndNode = existEndNode,
                            existingWeeksDesc = "第 ${exist.startWeek} - ${exist.endWeek} 周$existWeekTypeStr",
                            conflictStartNode = overlapStart,
                            conflictEndNode = overlapEnd,
                            conflictWeeks = commonWeeks
                        )
                    )
                }
            }
        }

        return conflicts.distinctBy {
            "${it.existingCourseName}_${it.existingDay}_${it.conflictStartNode}_${it.conflictEndNode}_${it.conflictWeeks.joinToString()}"
        }
    }
}
