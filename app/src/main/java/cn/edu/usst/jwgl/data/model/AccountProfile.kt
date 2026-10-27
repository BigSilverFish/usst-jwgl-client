package cn.edu.usst.jwgl.data.model

import java.io.Serializable

/**
 * 已保存账号的公开元数据模型（不含密码）
 * 用于多账号列表安全渲染与元数据展示
 */
data class AccountProfile(
    val studentId: String,                           // 学号（主键唯一标识）
    var studentName: String = "",                    // 姓名（如 "张三"）
    var college: String = "",                        // 学院
    var major: String = "",                          // 专业
    var className: String = "",                      // 班级
    var lastActiveTime: Long = System.currentTimeMillis(), // 最近一次使用时间戳
    var isAutoLogin: Boolean = true                  // 是否允许免密快速切换
) : Serializable
