package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cn.edu.usst.jwgl.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

/**
 * 一网畅学 (TronClass) 综合数据层与内容缓存管理器
 * 遵循 Stale-While-Revalidate 离线优先策略，支持课程、待办、大纲、测验及做题记录的本地秒开持久化
 */
class TronClassDataCacheManager(private val context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "TronClassDataCache"
        private const val PREF_NAME = "usst_tronclass_data_cache"

        private const val KEY_COURSES = "tc_cache_courses"
        private const val KEY_COURSES_TIME = "tc_cache_courses_time"

        private const val KEY_TODOS = "tc_cache_todos"
        private const val KEY_TODOS_TIME = "tc_cache_todos_time"

        private const val PREFIX_SYLLABUS = "tc_cache_syllabus_"
        private const val PREFIX_SYLLABUS_TIME = "tc_cache_syllabus_time_"

        private const val PREFIX_EXAMS = "tc_cache_exams_"
        private const val PREFIX_EXAMS_TIME = "tc_cache_exams_time_"

        private const val PREFIX_MEMBERS = "tc_cache_members_"
        private const val PREFIX_MEMBERS_TIME = "tc_cache_members_time_"

        private const val PREFIX_BULLETINS = "tc_cache_bulletins_"
        private const val PREFIX_BULLETINS_TIME = "tc_cache_bulletins_time_"

        private const val PREFIX_EXAM_DETAIL = "tc_cache_exam_detail_"
        private const val PREFIX_EXAM_DETAIL_TIME = "tc_cache_exam_detail_time_"

        @Volatile
        private var instance: TronClassDataCacheManager? = null

        fun getInstance(context: Context): TronClassDataCacheManager {
            return instance ?: synchronized(this) {
                instance ?: TronClassDataCacheManager(context.applicationContext).also { instance = it }
            }
        }

        // --- 便捷静态方法 ---
        fun saveCourseExams(context: Context, courseId: Long, exams: List<TronClassExam>) {
            getInstance(context).saveCourseExams(courseId, exams)
        }

        fun getCourseExams(context: Context, courseId: Long): List<TronClassExam>? {
            return getInstance(context).getCachedCourseExams(courseId).takeIf { it.isNotEmpty() }
        }

        fun saveExamDetail(context: Context, examId: Long, detail: TronClassExamDetail) {
            getInstance(context).saveExamDetail(examId, detail)
        }

        fun getExamDetail(context: Context, examId: Long): TronClassExamDetail? {
            return getInstance(context).getCachedExamDetail(examId)
        }

        fun getDataCacheSize(context: Context): Long {
            return getInstance(context).getDataCacheSizeBytes()
        }

        fun getDownloadFilesSize(context: Context): Long {
            return try {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "tronclass_downloads")
                calculateFolderSize(dir)
            } catch (e: Exception) {
                0L
            }
        }

        private fun calculateFolderSize(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            var size = 0L
            file.listFiles()?.forEach {
                size += calculateFolderSize(it)
            }
            return size
        }

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 KB"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.CHINA, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.CHINA, "%.2f MB", mb)
                else -> String.format(Locale.CHINA, "%.1f KB", kb)
            }
        }

        fun clearDataCache(context: Context) {
            getInstance(context).clearDataCache()
        }

        fun clearDownloadFiles(context: Context) {
            try {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "tronclass_downloads")
                if (dir.exists()) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "clearDownloadFiles error", e)
            }
        }

        fun clearAllCache(context: Context) {
            clearDataCache(context)
            clearDownloadFiles(context)
        }
    }

    // --- 1. 我的课程缓存 ---
    fun saveCourses(courses: List<TronClassCourse>) {
        if (courses.isEmpty()) return
        prefs.edit()
            .putString(KEY_COURSES, gson.toJson(courses))
            .putLong(KEY_COURSES_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourses(): List<TronClassCourse> {
        val json = prefs.getString(KEY_COURSES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassCourse>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCoursesCacheTime(): Long = prefs.getLong(KEY_COURSES_TIME, 0L)

    // --- 2. 待办事项缓存 ---
    fun saveTodos(todos: List<TronClassTodo>) {
        prefs.edit()
            .putString(KEY_TODOS, gson.toJson(todos))
            .putLong(KEY_TODOS_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedTodos(): List<TronClassTodo> {
        val json = prefs.getString(KEY_TODOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassTodo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTodosCacheTime(): Long = prefs.getLong(KEY_TODOS_TIME, 0L)

    // --- 3. 课程章节大纲/课件目录缓存 ---
    fun saveCourseSyllabus(courseId: Long, activities: List<TronClassActivityItem>) {
        if (activities.isEmpty()) return
        prefs.edit()
            .putString(PREFIX_SYLLABUS + courseId, gson.toJson(activities))
            .putLong(PREFIX_SYLLABUS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseSyllabus(courseId: Long): List<TronClassActivityItem> {
        val json = prefs.getString(PREFIX_SYLLABUS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassActivityItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- 4. 课程测验考试列表缓存 ---
    fun saveCourseExams(courseId: Long, exams: List<TronClassExam>) {
        if (exams.isEmpty()) return
        prefs.edit()
            .putString(PREFIX_EXAMS + courseId, gson.toJson(exams))
            .putLong(PREFIX_EXAMS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseExams(courseId: Long): List<TronClassExam> {
        val json = prefs.getString(PREFIX_EXAMS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassExam>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- 5. 课程班级成员缓存 ---
    fun saveCourseMembers(courseId: Long, members: List<TronClassMember>) {
        if (members.isEmpty()) return
        prefs.edit()
            .putString(PREFIX_MEMBERS + courseId, gson.toJson(members))
            .putLong(PREFIX_MEMBERS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseMembers(courseId: Long): List<TronClassMember> {
        val json = prefs.getString(PREFIX_MEMBERS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassMember>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- 6. 课程公告缓存 ---
    fun saveCourseBulletins(courseId: Long, bulletins: List<TronClassBulletin>) {
        if (bulletins.isEmpty()) return
        prefs.edit()
            .putString(PREFIX_BULLETINS + courseId, gson.toJson(bulletins))
            .putLong(PREFIX_BULLETINS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseBulletins(courseId: Long): List<TronClassBulletin> {
        val json = prefs.getString(PREFIX_BULLETINS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- 7. 测验做题与查卷详情缓存 ---
    fun saveExamDetail(examId: Long, detail: TronClassExamDetail) {
        prefs.edit()
            .putString(PREFIX_EXAM_DETAIL + examId, gson.toJson(detail))
            .putLong(PREFIX_EXAM_DETAIL_TIME + examId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedExamDetail(examId: Long): TronClassExamDetail? {
        val json = prefs.getString(PREFIX_EXAM_DETAIL + examId, null) ?: return null
        return try {
            gson.fromJson(json, TronClassExamDetail::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // --- 8. 统计与清理缓存 ---
    fun getDataCacheCount(): Int {
        return prefs.all.count { it.key.startsWith("tc_cache_") && !it.key.endsWith("_time") }
    }

    fun getDataCacheSizeBytes(): Long {
        return prefs.all.entries
            .filter { it.key.startsWith("tc_cache_") }
            .sumOf { (it.value as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 8L }
    }

    fun getFormattedDataCacheSize(): String {
        val bytes = getDataCacheSizeBytes()
        return formatSize(bytes)
    }

    fun clearDataCache(): Long {
        val size = getDataCacheSizeBytes()
        prefs.edit().clear().apply()
        return size
    }

    fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0L) return "从未同步"
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            diff < 60 * 1000 -> "刚刚更新"
            minutes < 60 -> "${minutes}分钟前更新"
            hours < 24 -> "${hours}小时前更新"
            days < 30 -> "${days}天前更新"
            else -> "已久未更新"
        }
    }
}
