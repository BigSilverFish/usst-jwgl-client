package cn.edu.usst.jwgl.data.wakeup

import cn.edu.usst.jwgl.data.model.CourseItem
import cn.edu.usst.jwgl.data.model.TimetableData

object WakeupScheduleImporter {

    fun importTimetableData(
        db: AppDatabase,
        data: TimetableData,
        startDate: String = "2026-09-07",
        setAsDefault: Boolean = true
    ): TableBean {
        val tableName = if (data.semesterTitle.isNotBlank()) {
            data.semesterTitle
        } else {
            "${data.academicYear}学年 第${data.semester}学期"
        }

        // Find or create table
        val existingTables = db.tableDao.getAllTables()
        var targetTable = existingTables.find { it.tableName == tableName }
        val isOldSemester = CourseUtils.isBefore2025_2026_2(tableName)
        val targetNodes = if (isOldSemester) 12 else 13
        val targetTimeTable = if (isOldSemester) 2 else 1

        val existingBases = if (targetTable != null) db.courseBaseDao.getCourseBasesOfTable(targetTable.id) else emptyList()
        val existingCourses = if (targetTable != null) db.courseBaseDao.getCourseOfTable(targetTable.id) else emptyList()

        val showSat = data.courses.any { it.dayOfWeek == 6 } || existingCourses.any { it.isLocked && it.day == 6 }
        val showSun = data.courses.any { it.dayOfWeek == 7 } || existingCourses.any { it.isLocked && it.day == 7 }

        if (targetTable == null) {
            val newTable = TableBean(
                tableName = tableName,
                startDate = startDate,
                maxWeek = 20,
                nodes = targetNodes,
                timeTable = targetTimeTable,
                showSat = showSat,
                showSun = showSun,
                type = if (setAsDefault) 1 else 0
            )
            val newId = db.tableDao.insertTable(newTable).toInt()
            targetTable = newTable.copy(id = newId)
        } else {
            targetTable.startDate = startDate
            targetTable.nodes = targetNodes
            targetTable.timeTable = targetTimeTable
            targetTable.maxWeek = 20
            targetTable.showSat = showSat
            targetTable.showSun = showSun
            db.tableDao.updateTable(targetTable)
        }

        // Set as default if requested
        if (setAsDefault) {
            db.tableDao.setDefaultTable(targetTable.id)
        }

        val plan = computeMergePlan(existingBases, data.courses)

        // Clear existing UNLOCKED courses in this table; preserve LOCKED courses
        for (idToDelete in plan.courseIdsToDelete) {
            db.courseBaseDao.deleteCourseBase(idToDelete, targetTable.id)
        }

        for ((currentCourseId, entry) in plan.newCourseAssignments) {
            val (courseName, itemList) = entry
            val color = CourseUtils.getColorHex(currentCourseId - 1)
            val baseBean = CourseBaseBean(
                id = currentCourseId,
                courseName = courseName,
                color = color,
                tableId = targetTable.id,
                isLocked = false
            )
            db.courseBaseDao.insertCourseBase(baseBean)

            val detailsToInsert = mutableListOf<CourseDetailBean>()

            for (item in itemList) {
                val weekBeans = if (item.weeks.isNotEmpty()) {
                    CourseUtils.intList2WeekBeanList(item.weeks)
                } else {
                    parseRawWeeks(item.rawWeeks)
                }

                val teacher = item.teacher.ifBlank { "" }
                val room = item.classroom.ifBlank { "" }

                for (wb in weekBeans) {
                    detailsToInsert.add(
                        CourseDetailBean(
                            id = currentCourseId,
                            day = item.dayOfWeek,
                            room = room,
                            teacher = teacher,
                            startNode = item.startSection,
                            step = if (item.step <= 0) 2 else item.step,
                            startWeek = wb.start,
                            endWeek = wb.end,
                            type = wb.type,
                            tableId = targetTable.id
                        )
                    )
                }
            }

            db.courseDetailDao.insertList(detailsToInsert)
        }

        return targetTable
    }

    data class MergePlan(
        val courseIdsToDelete: List<Int>,
        val preservedCourseNames: Set<String>,
        val newCourseAssignments: List<Pair<Int, Pair<String, List<CourseItem>>>>
    )

    fun computeMergePlan(
        existingBases: List<CourseBaseBean>,
        networkCourses: List<CourseItem>
    ): MergePlan {
        val lockedBases = existingBases.filter { it.isLocked }
        val lockedIds = lockedBases.map { it.id }.toSet()
        val lockedNames = lockedBases.map { it.courseName.trim() }.toSet()
        val idsToDelete = existingBases.filter { !it.isLocked }.map { it.id }

        var nextCourseId = 1
        fun allocateCourseId(): Int {
            while (lockedIds.contains(nextCourseId)) {
                nextCourseId++
            }
            return nextCourseId++
        }

        val grouped = networkCourses.groupBy { it.name.trim() }
        val assignments = mutableListOf<Pair<Int, Pair<String, List<CourseItem>>>>()
        for ((courseName, itemList) in grouped) {
            if (lockedNames.contains(courseName)) {
                continue
            }
            assignments.add(allocateCourseId() to (courseName to itemList))
        }

        return MergePlan(
            courseIdsToDelete = idsToDelete,
            preservedCourseNames = lockedNames,
            newCourseAssignments = assignments
        )
    }

    private fun parseRawWeeks(raw: String): List<WeekBean> {
        val clean = raw.replace("周", "").trim()
        val isSingle = clean.contains("单")
        val isDouble = clean.contains("双")
        val numberPart = clean.replace("(单周)", "")
            .replace("(双周)", "")
            .replace("（单周）", "")
            .replace("（双周）", "")
            .replace("(单)", "")
            .replace("(双)", "")
            .replace("（单）", "")
            .replace("（双）", "")
            .replace("单", "")
            .replace("双", "")
            .trim()

        val type = when {
            isSingle -> 1
            isDouble -> 2
            else -> 0
        }

        val result = mutableListOf<WeekBean>()
        val segments = numberPart.split(",")
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.contains("-")) {
                val parts = trimmed.split("-")
                val start = parts[0].toIntOrNull() ?: 1
                val end = parts[1].toIntOrNull() ?: start
                result.add(WeekBean(start = start, end = end, type = type))
            } else {
                val w = trimmed.toIntOrNull()
                if (w != null) {
                    result.add(WeekBean(start = w, end = w, type = type))
                }
            }
        }
        if (result.isEmpty()) {
            result.add(WeekBean(start = 1, end = 16, type = 0))
        }
        return result
    }
}
