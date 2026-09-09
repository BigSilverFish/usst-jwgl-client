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
}
