package cn.edu.usst.jwgl.data.model

import java.io.Serializable

enum class GradeDocumentType(
    val displayName: String,
    val description: String,
    val defaultFileName: String
) : Serializable {
    CHINESE_WEIGHTED_SCORE(
        "中文加权平均分证明 (官方盖章)",
        "加权平均分与平均学分绩点官方证明 (教务处红章·PDF)",
        "上海理工大学加权平均分证明(中文).pdf"
    ),
    ENGLISH_WEIGHTED_SCORE(
        "英文加权平均分证明 (官方盖章)",
        "Weighted Average Score & GPA Certificate (Official Seal·PDF)",
        "USST_Weighted_Average_Score_Certificate(EN).pdf"
    ),
    RANKING_CERTIFICATE(
        "专业排名证明 (官方盖章)",
        "专业/班级综合成绩与排名认证 (教务处红章·PDF)",
        "上海理工大学学生排名证明.pdf"
    ),
    CHINESE_TRANSCRIPT(
        "学生成绩总表 (中文成绩单)",
        "内嵌完整中文矢量字体，移动端阅读清晰全览 (PDF)",
        "上海理工大学学生成绩总表(中文).pdf"
    ),
    ENGLISH_TRANSCRIPT(
        "学生成绩总表 (英文·官方盖章版)",
        "Official Academic Transcript in English (Official Seal·PDF)",
        "USST_Academic_Transcript(EN).pdf"
    )
}
