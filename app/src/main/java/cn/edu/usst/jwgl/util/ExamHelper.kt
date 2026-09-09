package cn.edu.usst.jwgl.util

data class ExamSession(
    val index: Int,
    val name: String,
    val timeRange: String,
    val startTime: String,
    val endTime: String
)

object ExamHelper {

    val SESSIONS = listOf(
        ExamSession(1, "第一场", "09:00-11:00", "09:00", "11:00"),
        ExamSession(2, "第二场", "13:00-15:00", "13:00", "15:00"),
        ExamSession(3, "第三场", "15:30-17:30", "15:30", "17:30")
    )

    /**
     * 判断课表所属学期类型:
     * @return 1 表示第一学期, 2 表示第二学期, 0 表示未知
     */
    fun getSemesterType(tableName: String): Int {
        val lower = tableName.trim()
        val regex1 = Regex("第\\s*1\\s*学期|1学期|第一学期|-1\\b")
        val regex2 = Regex("第\\s*2\\s*学期|2学期|第二学期|-2\\b")
        return when {
            regex1.containsMatchIn(lower) -> 1
            regex2.containsMatchIn(lower) -> 2
            else -> 0
        }
    }

    /**
     * 判断指定周次是否为考试周:
     * - 第一学期: 第 19、20 周为考试周
     * - 第二学期: 第 17、18 周为考试周
     */
    fun isExamWeek(tableName: String, week: Int): Boolean {
        return when (getSemesterType(tableName)) {
            1 -> week in 19..20
            2 -> week in 17..18
            else -> week in 17..20
        }
    }

    /**
     * 判断当前是否处于考试周自动同步时间窗口:
     * “在考试周开始 4 周前至考试周结束时，刷新同步课表自动同步考试周”
     * - 第一学期 (19-20周): 19 - 4 = 15，即第 15 周至第 20 周
     * - 第二学期 (17-18周): 17 - 4 = 13，即第 13 周至第 18 周
     */
    fun isExamSyncWindow(tableName: String, currentWeek: Int): Boolean {
        return when (getSemesterType(tableName)) {
            1 -> currentWeek in 15..20
            2 -> currentWeek in 13..18
            else -> currentWeek in 13..20
        }
    }
}
