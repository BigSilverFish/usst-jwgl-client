package cn.edu.usst.jwgl.data.wakeup

/**
 * 记录课表课程冲突时段与对应已有课程信息的实体
 */
data class CourseConflictInfo(
    val existingCourseName: String,
    val existingDay: Int,
    val existingStartNode: Int,
    val existingEndNode: Int,
    val existingWeeksDesc: String,
    val conflictStartNode: Int,
    val conflictEndNode: Int,
    val conflictWeeks: List<Int>
) {
    /**
     * 格式化冲突摘要，例如：• 《高等数学》: 周四 第3-4节 (第3-11周)
     */
    fun formatConflictSummary(): String {
        val dayStr = CourseUtils.getDayStr(existingDay)
        val weeksStr = if (conflictWeeks.isEmpty()) {
            existingWeeksDesc
        } else {
            formatWeeksRange(conflictWeeks)
        }
        val nodeStr = if (conflictStartNode == conflictEndNode) {
            "第 ${conflictStartNode} 节"
        } else {
            "第 ${conflictStartNode} - ${conflictEndNode} 节"
        }
        return "• 《$existingCourseName》: $dayStr $nodeStr ($weeksStr)"
    }

    companion object {
        fun formatWeeksRange(weeks: List<Int>): String {
            if (weeks.isEmpty()) return ""
            val sorted = weeks.distinct().sorted()
            val ranges = mutableListOf<String>()
            var start = sorted[0]
            var prev = sorted[0]
            for (i in 1 until sorted.size) {
                val cur = sorted[i]
                if (cur == prev + 1) {
                    prev = cur
                } else {
                    ranges.add(if (start == prev) "$start" else "$start-$prev")
                    start = cur
                    prev = cur
                }
            }
            ranges.add(if (start == prev) "$start" else "$start-$prev")
            return "第 ${ranges.joinToString(", ")} 周"
        }
    }
}
