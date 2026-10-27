package cn.edu.usst.jwgl.data.model

import java.io.Serializable

/**
 * 多账号批量签到结果模型
 */
data class MultiAccountRollcallResult(
    val studentId: String,
    val studentName: String,
    val success: Boolean,
    val message: String,
    val statusName: String = ""
) : Serializable
