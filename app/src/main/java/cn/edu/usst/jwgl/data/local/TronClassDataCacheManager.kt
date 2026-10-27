package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cn.edu.usst.jwgl.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 一网畅学 (TronClass) 综合数据层与内容缓存管理器
 * 遵循 Stale-While-Revalidate 离线优先策略，支持内存 L1 极速直出与本地持久化秒开
 */
class TronClassDataCacheManager(private val context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- 内存一级快速缓存 (In-Memory L1 Cache，微秒级极速直出，杜绝主线程重复反序列化) ---
    private val memCourses = ConcurrentHashMap<String, List<TronClassCourse>>()
    private val memTodos = ConcurrentHashMap<String, List<TronClassTodo>>()
    private val memModules = ConcurrentHashMap<Long, List<TronClassModule>>()
    private val memSyllabus = ConcurrentHashMap<Long, List<TronClassActivityItem>>()
    private val memReads = ConcurrentHashMap<Long, Map<Long, String>>()
    private val memExams = ConcurrentHashMap<Long, List<TronClassExam>>()
    private val memMembers = ConcurrentHashMap<Long, List<TronClassMember>>()
    private val memBulletins = ConcurrentHashMap<Long, List<TronClassBulletin>>()

    companion object {
        private const val TAG = "TronClassDataCache"
        private const val PREF_NAME = "usst_tronclass_data_cache"

        private const val KEY_COURSES = "tc_cache_courses"
        private const val KEY_COURSES_TIME = "tc_cache_courses_time"

        private const val KEY_TODOS = "tc_cache_todos"
        private const val KEY_TODOS_TIME = "tc_cache_todos_time"

        private const val PREFIX_MODULES = "tc_cache_modules_"
        private const val PREFIX_MODULES_TIME = "tc_cache_modules_time_"

        private const val PREFIX_READS = "tc_cache_reads_"
        private const val PREFIX_READS_TIME = "tc_cache_reads_time_"

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
                Log.w(TAG, "Failed to calculate download files size", e)
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

        /**
         * 智能课表与一网畅学课程匹配器
         */
        fun findMatchingCourse(courseName: String, courses: List<TronClassCourse>): TronClassCourse? {
            if (courses.isEmpty() || courseName.isBlank()) return null
            val targetClean = courseName.trim()
                .replace(Regex("""\(.*?\)|（.*?）"""), "")
                .trim()
            
            // 1. 精确 cleanName 匹配
            val exact = courses.firstOrNull {
                it.getCleanName().equals(targetClean, ignoreCase = true) ||
                it.getCleanName().equals(courseName.trim(), ignoreCase = true)
            }
            if (exact != null) return exact

            // 2. 包含匹配 (如 "数字图像处理A" vs "数字图像处理")
            val contains = courses.firstOrNull {
                val cn = it.getCleanName()
                cn.contains(targetClean, ignoreCase = true) || targetClean.contains(cn, ignoreCase = true)
            }
            return contains
        }
    }

    // --- 1. 我的课程缓存 ---
    fun saveCourses(courses: List<TronClassCourse>) {
        if (courses.isEmpty()) return
        memCourses["all"] = courses
        TronClassPreferences(context).saveCachedCourses(courses)
        prefs.edit()
            .putString(KEY_COURSES, gson.toJson(courses))
            .putLong(KEY_COURSES_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourses(): List<TronClassCourse> {
        memCourses["all"]?.let { return it }
        val prefCourses = TronClassPreferences(context).getCachedCourses()
        if (prefCourses.isNotEmpty()) {
            memCourses["all"] = prefCourses
            return prefCourses
        }
        val json = prefs.getString(KEY_COURSES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassCourse>>() {}.type
            val res: List<TronClassCourse> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memCourses["all"] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached courses from prefs", e)
            emptyList()
        }
    }

    fun getCoursesCacheTime(): Long = prefs.getLong(KEY_COURSES_TIME, 0L)

    // --- 2. 待办事项缓存 ---
    fun saveTodos(todos: List<TronClassTodo>) {
        memTodos["all"] = todos
        TronClassPreferences(context).saveCachedTodos(todos)
        prefs.edit()
            .putString(KEY_TODOS, gson.toJson(todos))
            .putLong(KEY_TODOS_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedTodos(): List<TronClassTodo> {
        memTodos["all"]?.let { return it }
        val prefTodos = TronClassPreferences(context).getCachedTodos()
        if (prefTodos.isNotEmpty()) {
            memTodos["all"] = prefTodos
            return prefTodos
        }
        val json = prefs.getString(KEY_TODOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassTodo>>() {}.type
            val res: List<TronClassTodo> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memTodos["all"] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached todos from prefs", e)
            emptyList()
        }
    }

    fun getTodosCacheTime(): Long = prefs.getLong(KEY_TODOS_TIME, 0L)

    // --- 3. 课程章节目录 (Modules) 缓存 ---
    fun saveCourseModules(courseId: Long, modules: List<TronClassModule>) {
        if (modules.isEmpty()) return
        memModules[courseId] = modules
        prefs.edit()
            .putString(PREFIX_MODULES + courseId, gson.toJson(modules))
            .putLong(PREFIX_MODULES_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseModules(courseId: Long): List<TronClassModule> {
        memModules[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_MODULES + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassModule>>() {}.type
            val res: List<TronClassModule> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memModules[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached modules for course $courseId", e)
            emptyList()
        }
    }

    // --- 3.1 学习进度 / 完成度映射缓存 ---
    fun saveCourseReads(courseId: Long, reads: Map<Long, String>) {
        if (reads.isEmpty()) return
        memReads[courseId] = reads
        prefs.edit()
            .putString(PREFIX_READS + courseId, gson.toJson(reads))
            .putLong(PREFIX_READS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseReads(courseId: Long): Map<Long, String> {
        memReads[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_READS + courseId, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<Long, String>>() {}.type
            val res: Map<Long, String> = gson.fromJson(json, type) ?: emptyMap()
            if (res.isNotEmpty()) {
                memReads[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached reads for course $courseId", e)
            emptyMap()
        }
    }

    // --- 3.2 课程章节大纲/课件目录缓存 ---
    fun saveCourseSyllabus(courseId: Long, activities: List<TronClassActivityItem>) {
        if (activities.isEmpty()) return
        memSyllabus[courseId] = activities
        prefs.edit()
            .putString(PREFIX_SYLLABUS + courseId, gson.toJson(activities))
            .putLong(PREFIX_SYLLABUS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseSyllabus(courseId: Long): List<TronClassActivityItem> {
        memSyllabus[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_SYLLABUS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassActivityItem>>() {}.type
            val res: List<TronClassActivityItem> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memSyllabus[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached syllabus for course $courseId", e)
            emptyList()
        }
    }

    /**
     * 计算并获取课程当前缓存的总体学习进度百分比（0 ~ 100，若未缓存或无课件则返回 -1）
     */
    fun getCourseProgressPercent(courseId: Long): Int {
        val activities = getCachedCourseSyllabus(courseId)
        if (activities.isEmpty()) return -1
        val reads = getCachedCourseReads(courseId)
        var completedCount = 0
        for (act in activities) {
            val comp = reads[act.id]
            if (comp == "full" || comp == "complete") {
                completedCount++
            }
        }
        val total = activities.size
        return if (total > 0) (completedCount * 100) / total else -1
    }

    // --- 4. 课程测验考试列表缓存 ---
    fun saveCourseExams(courseId: Long, exams: List<TronClassExam>) {
        if (exams.isEmpty()) return
        memExams[courseId] = exams
        prefs.edit()
            .putString(PREFIX_EXAMS + courseId, gson.toJson(exams))
            .putLong(PREFIX_EXAMS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseExams(courseId: Long): List<TronClassExam> {
        memExams[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_EXAMS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassExam>>() {}.type
            val res: List<TronClassExam> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memExams[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached exams for course $courseId", e)
            emptyList()
        }
    }

    // --- 5. 课程班级成员缓存 ---
    fun saveCourseMembers(courseId: Long, members: List<TronClassMember>) {
        if (members.isEmpty()) return
        memMembers[courseId] = members
        prefs.edit()
            .putString(PREFIX_MEMBERS + courseId, gson.toJson(members))
            .putLong(PREFIX_MEMBERS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseMembers(courseId: Long): List<TronClassMember> {
        memMembers[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_MEMBERS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassMember>>() {}.type
            val res: List<TronClassMember> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memMembers[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached members for course $courseId", e)
            emptyList()
        }
    }

    // --- 6. 课程公告缓存 ---
    fun saveCourseBulletins(courseId: Long, bulletins: List<TronClassBulletin>) {
        if (bulletins.isEmpty()) return
        memBulletins[courseId] = bulletins
        prefs.edit()
            .putString(PREFIX_BULLETINS + courseId, gson.toJson(bulletins))
            .putLong(PREFIX_BULLETINS_TIME + courseId, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseBulletins(courseId: Long): List<TronClassBulletin> {
        memBulletins[courseId]?.let { return it }
        val json = prefs.getString(PREFIX_BULLETINS + courseId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            val res: List<TronClassBulletin> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memBulletins[courseId] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached bulletins for course $courseId", e)
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
            Log.w(TAG, "Failed to deserialize cached exam detail for exam $examId", e)
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
        memCourses.clear()
        memTodos.clear()
        memModules.clear()
        memSyllabus.clear()
        memReads.clear()
        memExams.clear()
        memMembers.clear()
        memBulletins.clear()
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
