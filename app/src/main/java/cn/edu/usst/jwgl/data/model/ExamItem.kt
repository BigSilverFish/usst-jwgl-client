package cn.edu.usst.jwgl.data.model

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

data class ExamItem(
    val courseName: String = "",       // 课程名称 (kcmc)
    val courseCode: String = "",       // 课程代码 (kch)
    val examName: String = "",         // 考试类别 (ksmc: 期末考试/期中考试)
    val examTime: String = "",         // 考试时间 (kssj: 2027-01-18 09:00-11:00)
    val location: String = "",         // 考场地点 (cdmc / jsmc)
    val building: String = "",         // 教学楼 (jzwmc)
    val seatNumber: String = "",       // 座位号 (zwh / zw)
    val session: String = "",          // 考试场次 (ccmc / kccc)
    val examNature: String = "",       // 考试性质 (ksxz: 正常/补考/缓考)
    val examMethod: String = "",       // 考核方式 (khfs: 闭卷/机考)
    val credit: Double = 0.0,          // 学分 (xf)
    val yearName: String = "",         // 学年 (xnmmc)
    val semesterName: String = "",     // 学期 (xqmmc)
    val semesterTitle: String = "",    // 学年学期综合标题
    val remarks: String = ""           // 备注 (bz)
) : Serializable {

    /**
     * 从 examTime (如 "2027-01-18 09:00-11:00" 或 "2027-01-18(09:00-11:00)") 提取日期 yyyy-MM-dd
     */
    fun getDateString(): String {
        val regex = Regex("(\\d{4}-\\d{2}-\\d{2})")
        return regex.find(examTime)?.value ?: ""
    }

    /**
     * 智能识别当前考试所属场次 (1, 2, 3):
     * 场次 1: 09:00 - 11:00 (上午场)
     * 场次 2: 13:00 - 15:00 (下午第1场)
     * 场次 3: 15:30 - 17:30 (下午第2场)
     */
    fun getSessionIndex(): Int {
        val timeLower = examTime.replace(" ", "")
        return when {
            timeLower.contains("09:00") || timeLower.contains("08:30") || timeLower.contains("上午") || session.contains("1") || session.contains("一") -> 1
            timeLower.contains("13:00") || timeLower.contains("13:30") || timeLower.contains("14:00") || session.contains("2") || session.contains("二") -> 2
            timeLower.contains("15:30") || timeLower.contains("15:00") || timeLower.contains("16:00") || session.contains("3") || session.contains("三") -> 3
            else -> {
                // 根据起始小时数兜底
                val timeMatch = Regex("(\\d{1,2}):(\\d{2})").find(examTime)
                if (timeMatch != null) {
                    val hour = timeMatch.groupValues[1].toIntOrNull() ?: 9
                    when {
                        hour < 12 -> 1
                        hour in 12..14 -> 2
                        else -> 3
                    }
                } else {
                    1
                }
            }
        }
    }

    fun getTimeRangeString(): String {
        val timeMatch = Regex("(\\d{1,2}:\\d{2}\\s*[-~至]\\s*\\d{1,2}:\\d{2})").find(examTime)
        if (timeMatch != null) {
            return timeMatch.value.replace(" ", "")
        }
        return when (getSessionIndex()) {
            1 -> "09:00-11:00"
            2 -> "13:00-15:00"
            3 -> "15:30-17:30"
            else -> "09:00-11:00"
        }
    }

    /**
     * 计算该场考试开始的绝对毫秒级时间戳
     */
    fun getExamStartTimeMillis(): Long? {
        val date = getDateString()
        if (date.isEmpty()) return null
        val timeMatch = Regex("(\\d{1,2}):(\\d{2})").find(examTime)
        val hour = timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: when (getSessionIndex()) {
            1 -> 9
            2 -> 13
            3 -> 15
            else -> 9
        }
        val minute = timeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: when (getSessionIndex()) {
            3 -> 30
            else -> 0
        }
        return try {
            val cal = java.util.Calendar.getInstance(Locale.CHINA).apply {
                val parts = date.split("-")
                set(java.util.Calendar.YEAR, parts[0].toInt())
                set(java.util.Calendar.MONTH, parts[1].toInt() - 1)
                set(java.util.Calendar.DAY_OF_MONTH, parts[2].toInt())
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取距离开考的倒计时展示文本
     */
    fun getCountdownString(): String {
        val startMillis = getExamStartTimeMillis() ?: return ""
        val now = System.currentTimeMillis()
        val diff = startMillis - now
        if (diff < 0) {
            if (diff > -2 * 3600 * 1000L) {
                return "考试进行中"
            }
            return "已结束"
        }
        val days = (diff / (1000 * 60 * 60 * 24)).toInt()
        val hours = ((diff / (1000 * 60 * 60)) % 24).toInt()
        val minutes = ((diff / (1000 * 60)) % 60).toInt()
        return when {
            days > 0 -> "还有 ${days} 天"
            hours > 0 -> "还有 ${hours} 小时"
            minutes > 0 -> "还有 ${minutes} 分钟"
            else -> "即将开考"
        }
    }
}
