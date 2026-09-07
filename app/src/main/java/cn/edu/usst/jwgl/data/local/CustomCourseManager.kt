package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import cn.edu.usst.jwgl.data.model.CourseItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CustomCourseManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("usst_custom_courses_prefs", Context.MODE_PRIVATE)

    fun getCustomCourses(semesterKey: String): List<CourseItem> {
        val jsonStr = prefs.getString("custom_courses_$semesterKey", null) ?: return emptyList()
        val list = mutableListOf<CourseItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val weeksList = mutableListOf<Int>()
                val weeksArr = obj.optJSONArray("weeks")
                if (weeksArr != null) {
                    for (w in 0 until weeksArr.length()) {
                        weeksList.add(weeksArr.getInt(w))
                    }
                }

                list.add(
                    CourseItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        teacher = obj.optString("teacher", ""),
                        classroom = obj.optString("classroom", ""),
                        dayOfWeek = obj.optInt("dayOfWeek", 1),
                        startSection = obj.optInt("startSection", 1),
                        step = obj.optInt("step", 2),
                        rawSections = obj.optString("rawSections", "1-2"),
                        weeks = weeksList,
                        rawWeeks = obj.optString("rawWeeks", "1-16周"),
                        credit = obj.optDouble("credit", 0.0),
                        courseType = obj.optString("courseType", "自定义课程"),
                        colorIndex = obj.optInt("colorIndex", (10..13).random()),
                        courseCode = obj.optString("courseCode", ""),
                        examType = obj.optString("examType", "考查")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addCustomCourse(semesterKey: String, course: CourseItem) {
        val current = getCustomCourses(semesterKey).toMutableList()
        current.add(course)
        saveCustomCourses(semesterKey, current)
    }

    fun addCustomCourses(semesterKey: String, newCourses: List<CourseItem>) {
        val current = getCustomCourses(semesterKey).toMutableList()
        current.addAll(newCourses)
        saveCustomCourses(semesterKey, current)
    }

    fun removeCustomCourse(semesterKey: String, courseId: String) {
        val current = getCustomCourses(semesterKey).toMutableList()
        current.removeAll { it.id == courseId }
        saveCustomCourses(semesterKey, current)
    }

    fun clearAllCustomCourses(semesterKey: String) {
        prefs.edit().remove("custom_courses_$semesterKey").apply()
    }

    private fun saveCustomCourses(semesterKey: String, courses: List<CourseItem>) {
        val jsonArray = JSONArray()
        for (c in courses) {
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("teacher", c.teacher)
                put("classroom", c.classroom)
                put("dayOfWeek", c.dayOfWeek)
                put("startSection", c.startSection)
                put("step", c.step)
                put("rawSections", c.rawSections)
                put("rawWeeks", c.rawWeeks)
                put("credit", c.credit)
                put("courseType", c.courseType)
                put("colorIndex", c.colorIndex)
                put("courseCode", c.courseCode)
                put("examType", c.examType)

                val weeksArr = JSONArray()
                c.weeks.forEach { weeksArr.put(it) }
                put("weeks", weeksArr)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("custom_courses_$semesterKey", jsonArray.toString()).apply()
    }

    fun getDeletedCourseIds(semesterKey: String): Set<String> {
        return prefs.getStringSet("deleted_courses_$semesterKey", emptySet()) ?: emptySet()
    }

    fun markCourseDeleted(semesterKey: String, courseId: String) {
        val set = getDeletedCourseIds(semesterKey).toMutableSet()
        set.add(courseId)
        prefs.edit().putStringSet("deleted_courses_$semesterKey", set).apply()
        // Also remove if it was a custom added course
        removeCustomCourse(semesterKey, courseId)
    }

    fun markMultipleCoursesDeleted(semesterKey: String, courseIds: Collection<String>) {
        val set = getDeletedCourseIds(semesterKey).toMutableSet()
        set.addAll(courseIds)
        prefs.edit().putStringSet("deleted_courses_$semesterKey", set).apply()
        val current = getCustomCourses(semesterKey).toMutableList()
        current.removeAll { courseIds.contains(it.id) }
        saveCustomCourses(semesterKey, current)
    }

    fun getExcludedWeeks(semesterKey: String, courseId: String): Set<Int> {
        val raw = prefs.getString("excluded_weeks_${semesterKey}_$courseId", "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun excludeWeekFromCourse(semesterKey: String, courseId: String, week: Int) {
        val current = getExcludedWeeks(semesterKey, courseId).toMutableSet()
        current.add(week)
        prefs.edit().putString("excluded_weeks_${semesterKey}_$courseId", current.joinToString(",")).apply()
    }

    fun getFontScale(): Float {
        return prefs.getFloat("course_font_scale", 1.0f)
    }

    fun setFontScale(scale: Float) {
        prefs.edit().putFloat("course_font_scale", scale).apply()
    }
}