package cn.edu.usst.jwgl.data.model

import java.io.Serializable

/**
 * 学生专业/年级学业成绩排名数据模型
 */
data class StudentRankingInfo(
    val major: String = "",
    val grade: String = "",
    val rank: Int = 0,
    val total: Int = 0,
    val percentile: Int = 0
) : Serializable {

    val majorAndGrade: String
        get() {
            val cleanMajor = major.removeSuffix("专业").trim()
            val cleanGrade = if (grade.isNotEmpty()) {
                if (grade.endsWith("级")) grade else "${grade}级"
            } else ""

            return when {
                cleanMajor.isNotEmpty() && cleanGrade.isNotEmpty() -> "$cleanMajor $cleanGrade"
                cleanMajor.isNotEmpty() -> cleanMajor
                cleanGrade.isNotEmpty() -> cleanGrade
                else -> "本科在读学生"
            }
        }
}
