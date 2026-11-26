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
) : Serializable {
    companion object {
        fun orderAccountsPrimaryFirst(
            accounts: List<AccountProfile>,
            currentStudentId: String
        ): List<AccountProfile> {
            val targetId = currentStudentId.trim()
            if (targetId.isBlank() || accounts.size <= 1) return accounts
            val primary = accounts.filter { it.studentId == targetId }
            if (primary.isEmpty()) return accounts
            val others = accounts.filter { it.studentId != targetId }
            return primary + others
        }

        fun findPrimaryResult(
            results: List<MultiAccountRollcallResult>,
            currentStudentId: String
        ): MultiAccountRollcallResult? {
            if (results.isEmpty()) return null
            val targetId = currentStudentId.trim()
            return if (targetId.isNotBlank()) {
                results.find { it.studentId == targetId }
            } else {
                results.firstOrNull()
            }
        }

        fun isPrimaryAccountSuccess(
            results: List<MultiAccountRollcallResult>,
            currentStudentId: String
        ): Boolean {
            if (results.isEmpty()) return false
            val targetId = currentStudentId.trim()
            if (targetId.isNotBlank()) {
                return results.find { it.studentId == targetId }?.success == true
            }
            return results.first().success
        }
    }
}
