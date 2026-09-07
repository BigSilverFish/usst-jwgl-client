package cn.edu.usst.jwgl.data.model

import java.io.Serializable

data class StudentProfile(
    val studentId: String = "",
    val name: String = "",
    val pinyin: String = "",
    val gender: String = "",
    val college: String = "",
    val major: String = "",
    val className: String = "",
    val grade: String = "",
    val status: String = "在读",
    val durationYears: String = "4",
    val educationLevel: String = "本科",
    val studentCategory: String = "",
    val phone: String = ""
) : Serializable
