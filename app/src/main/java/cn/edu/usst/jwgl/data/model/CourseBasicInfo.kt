package cn.edu.usst.jwgl.data.model

import java.io.Serializable

/**
 * 正方教务系统课程库课程基本信息数据实体
 */
data class CourseBasicInfo(
    val courseCode: String,      // 课程号 kch (例如 22000210)
    val courseName: String,      // 课程名称 kcmc (例如 高等数学A(1))
    val englishName: String = "",// 英文名称 kcywmc
    val credit: Float = 0f,      // 学分 xf
    val totalHours: Int = 0,     // 总学时 zxs
    val lectureHours: Int = 0,   // 理论学时 llxs
    val labHours: Int = 0,       // 实验学时 syxs
    val department: String = "", // 开课部门/学院 kkbm
    val courseNature: String = "",// 课程性质 kcxzmc (通识必修/专业选修等)
    val courseCategory: String = "", // 课程类别 kclbmc (理论/实践/实验等)
    val courseCategoryCode: String = "", // 课程类别代码 kclbdm
    val courseAffiliation: String = "", // 课程归属 kcgsmc (创新思维与创业实践/全球视野与文明对话等)
    val syllabusId: String = "",  // 课程大纲标识 kcdg_id
    val hasSyllabus: Boolean = false, // 是否已有教学大纲
    val syllabusDownloadUrl: String = "", // 真实的 PDF/DOCX 下载链接 (例如 https://jwgl.usst.edu.cn/jwglxt/ftp/...)
    val syllabusFileName: String = "" // 大纲文件名 (例如 上海理工大学高等数学A(1)本科课程教学大纲-修订版.pdf)
) : Serializable
