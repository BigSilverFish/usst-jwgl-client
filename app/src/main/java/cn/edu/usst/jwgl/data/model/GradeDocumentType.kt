package cn.edu.usst.jwgl.data.model

import java.io.Serializable

enum class GradeDocumentType(
    val displayName: String,
    val description: String,
    val defaultFileName: String
) : Serializable {
    CHINESE_WEIGHTED_SCORE(
        "中文加权平均分证明",
        "加权平均分与平均学分绩点官方证明 (PDF)",
        "上海理工大学加权平均分证明(中文).pdf"
    ),
    ENGLISH_WEIGHTED_SCORE(
        "英文加权平均分证明",
        "Weighted Average Score & GPA Certificate (PDF)",
        "USST_Weighted_Average_Score_Certificate(EN).pdf"
    ),
    RANKING_CERTIFICATE(
        "专业排名证明",
        "专业/班级综合成绩与排名认证 (PDF)",
        "上海理工大学学生排名证明.pdf"
    ),
    CHINESE_TRANSCRIPT(
        "学生成绩总表 (中文成绩单)",
        "官方学期修读成绩总表与盖章格式 (PDF)",
        "上海理工大学学生成绩总表(中文).pdf"
    ),
    ENGLISH_TRANSCRIPT(
        "学生成绩总表 (英文成绩单)",
        "Official Academic Transcript in English (PDF)",
        "USST_Academic_Transcript(EN).pdf"
    )
}
