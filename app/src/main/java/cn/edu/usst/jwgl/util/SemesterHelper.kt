package cn.edu.usst.jwgl.util

import java.util.Calendar

data class SemesterInfo(
    val title: String,
    val xnm: String,
    val xqm: String,
    val isCurrent: Boolean = false,
    val isNext: Boolean = false
)

object SemesterHelper {

    var simulatedToday: Calendar? = null

    fun getToday(): Calendar = (simulatedToday?.clone() as? Calendar) ?: Calendar.getInstance()

    /**
     * 根据 2月1日 与 8月16日 分界点判定学期:
     * - 1月1日 ~ 1月31日: (Year-1)-(Year) 第1学期 (如 2026-01-10 -> 2025-2026-1)
     * - 2月1日 ~ 8月15日: (Year-1)-(Year) 第2学期 (如 2026-04-08 -> 2025-2026-2)
     * - 8月16日 ~ 12月31日: (Year)-(Year+1) 第1学期 (如 2026-09-06 -> 2026-2027-1)
     */
    fun getCurrentSemester(cal: Calendar = getToday()): Pair<String, String> {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // 1..12
        val day = cal.get(Calendar.DAY_OF_MONTH) // 1..31

        return when {
            month < 2 -> Pair((year - 1).toString(), "3")
            month < 8 || (month == 8 && day < 16) -> Pair((year - 1).toString(), "12")
            else -> Pair(year.toString(), "3")
        }
    }

    fun getNextSemester(xnm: String, xqm: String): Pair<String, String> {
        return if (xqm == "3") {
            Pair(xnm, "12")
        } else {
            Pair(((xnm.toIntOrNull() ?: 2025) + 1).toString(), "3")
        }
    }

    /**
     * 从课表名称（如 "2025-2026学年 第2学期" 或 "2024-2025-1"）中解析出 xnm 和 xqm
     * 返回 Pair(xnm, xqm)，如 ("2025", "12")
     */
    fun parseSemesterFromTableName(tableName: String?): Pair<String, String>? {
        if (tableName.isNullOrBlank()) return null
        val regex = Regex("""(\d{4})[-~](\d{4})[^0-9]*第?\s*([123一二三])""")
        val match = regex.find(tableName)
        if (match != null) {
            val xnm = match.groupValues[1]
            val semNumStr = match.groupValues[3]
            val xqm = when (semNumStr) {
                "1", "一" -> "3"
                "2", "二" -> "12"
                "3", "三" -> "16"
                else -> "3"
            }
            return Pair(xnm, xqm)
        }
        return null
    }

    /**
     * 根据学生的入学年份（如 2024）和当前学期，获取从入学至当前学期的所有学期清单（按时间先后升序排列）
     */
    fun getSemestersToSync(enrollmentYear: Int? = null, cal: Calendar = getToday()): List<SemesterInfo> {
        val (curXnm, curXqm) = getCurrentSemester(cal)
        val curYear = curXnm.toIntOrNull() ?: 2026
        // 如果入学年份有效（合理区间为当前年份前6年内），则从入学年开始；否则默认往前推 2 年
        val startYear = if (enrollmentYear != null && enrollmentYear in (curYear - 6)..curYear) {
            enrollmentYear
        } else {
            (curYear - 2).coerceAtLeast(2020)
        }

        val result = mutableListOf<SemesterInfo>()
        for (y in startYear..curYear) {
            val semMax = if (y == curYear) (if (curXqm == "3") 1 else 2) else 2
            for (s in 1..semMax) {
                val xqmVal = if (s == 1) "3" else "12"
                val isCurrent = (y == curYear && xqmVal == curXqm)
                result.add(
                    SemesterInfo(
                        title = "${y}-${y + 1}学年 第${s}学期",
                        xnm = y.toString(),
                        xqm = xqmVal,
                        isCurrent = isCurrent
                    )
                )
            }
        }
        return result
    }

    fun getSemesterList(cal: Calendar = Calendar.getInstance()): List<SemesterInfo> {
        val (curXnm, curXqm) = getCurrentSemester(cal)
        val (nextXnm, nextXqm) = getNextSemester(curXnm, curXqm)

        val list = mutableListOf<SemesterInfo>()

        // 1. Next semester (for course selection preview)
        val nextYearEnd = (nextXnm.toIntOrNull() ?: 2026) + 1
        val nextSemNum = if (nextXqm == "3") "1" else "2"
        list.add(
            SemesterInfo(
                title = "${nextXnm}-${nextYearEnd}学年 第${nextSemNum}学期 (下学期 选课)",
                xnm = nextXnm,
                xqm = nextXqm,
                isNext = true
            )
        )

        // 2. Current semester
        val curYearEnd = (curXnm.toIntOrNull() ?: 2025) + 1
        val curSemNum = if (curXqm == "3") "1" else "2"
        list.add(
            SemesterInfo(
                title = "${curXnm}-${curYearEnd}学年 第${curSemNum}学期 (当前学期)",
                xnm = curXnm,
                xqm = curXqm,
                isCurrent = true
            )
        )

        // 3. Past semesters
        var checkYear = curXnm.toIntOrNull() ?: 2025
        var checkSem = if (curXqm == "3") "12" else "3"
        if (curXqm == "3") {
            checkYear -= 1
        }

        for (i in 0 until 4) {
            val semNum = if (checkSem == "3") "1" else "2"
            list.add(
                SemesterInfo(
                    title = "${checkYear}-${checkYear + 1}学年 第${semNum}学期",
                    xnm = checkYear.toString(),
                    xqm = checkSem
                )
            )
            if (checkSem == "12") {
                checkSem = "3"
            } else {
                checkSem = "12"
                checkYear -= 1
            }
        }

        return list
    }

    /**
     * 根据开学第一周周一的日期字符串 (yyyy-MM-dd) 计算当前是第几周:
     * - 如果当前日期在第一周周一之前（例如开学前夕/报到），返回第 1 周
     * - 开学后，按每 7 天递增 1 周计算，限制在 1..totalWeeks 范围内
     */
    fun calculateCurrentWeek(
        week1MondayStr: String?,
        totalWeeks: Int = 20,
        now: Calendar = Calendar.getInstance()
    ): Int {
        if (!week1MondayStr.isNullOrBlank()) {
            try {
                val parts = week1MondayStr.trim().split("-")
                if (parts.size == 3) {
                    val startCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, parts[0].toInt())
                        set(Calendar.MONTH, parts[1].toInt() - 1)
                        set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val nowCal = Calendar.getInstance().apply {
                        timeInMillis = now.timeInMillis
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    val diffMillis = nowCal.timeInMillis - startCal.timeInMillis
                    val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

                    return when {
                        diffDays < 0 -> 1 // 开学前夕默认为第 1 周
                        else -> {
                            val week = (diffDays / 7) + 1
                            week.coerceIn(1, totalWeeks)
                        }
                    }
                }
            } catch (e: Exception) {
                // Parse error fallback
            }
        }
        return 1
    }

    /**
     * 获取指定周次周一至周日的 7 个具体公历日期对象
     */
    fun getDatesForWeek(week1MondayStr: String?, week: Int): List<Calendar> {
        val baseMonday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var parsed = false
        if (!week1MondayStr.isNullOrBlank()) {
            try {
                val parts = week1MondayStr.trim().split("-")
                if (parts.size == 3) {
                    baseMonday.set(Calendar.YEAR, parts[0].toInt())
                    baseMonday.set(Calendar.MONTH, parts[1].toInt() - 1)
                    baseMonday.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    parsed = true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (!parsed) {
            val dayOfWeek = baseMonday.get(Calendar.DAY_OF_WEEK)
            val diff = if (dayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - dayOfWeek
            baseMonday.add(Calendar.DAY_OF_MONTH, diff)
        }

        val weekMonday = (baseMonday.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, (week - 1) * 7)
        }

        val result = mutableListOf<Calendar>()
        for (i in 0..6) {
            val dayCal = (weekMonday.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, i)
            }
            result.add(dayCal)
        }
        return result
    }

    /**
     * 判断两个 Calendar 是否为同一天
     */
    fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }
}