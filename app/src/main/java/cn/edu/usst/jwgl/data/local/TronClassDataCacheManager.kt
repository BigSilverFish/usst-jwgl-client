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
 * 遵循 Stale-While-Revalidate 离线优先策略，支持按学号严格分桶的内存 L1 与本地持久化缓存
 */
class TronClassDataCacheManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    init {
        // These four legacy keys duplicated the account-scoped Preferences cache.
        prefs.edit().remove(KEY_COURSES).remove(KEY_COURSES_TIME)
            .remove(KEY_TODOS).remove(KEY_TODOS_TIME).apply()
    }

    // --- 内存一级快速缓存（全部以 "${studentId}_${id}" 分桶隔离） ---
    private val memModules = ConcurrentHashMap<String, List<TronClassModule>>()
    private val memSyllabus = ConcurrentHashMap<String, List<TronClassActivityItem>>()
    private val memReads = ConcurrentHashMap<String, Map<Long, String>>()
    private val memExams = ConcurrentHashMap<String, List<TronClassExam>>()
    private val memMembers = ConcurrentHashMap<String, List<TronClassMember>>()
    private val memBulletins = ConcurrentHashMap<String, List<TronClassBulletin>>()
    private val memDiscussions = ConcurrentHashMap<String, List<TronClassDiscussion>>()
    private val memHomeBulletins = ConcurrentHashMap<String, List<TronClassBulletin>>()
    private val memCxHwDetails = ConcurrentHashMap<String, ChaoXingHomeworkDetail>()
    private val memCxExamDetails = ConcurrentHashMap<String, ChaoXingExamDetail>()

    private fun accountPrefix(studentId: String? = null): String {
        val sid = studentId?.trim()?.takeIf { it.isNotEmpty() } ?: try {
            AuthPreferences.currentStudentId(appContext).trim()
        } catch (_: Exception) {
            ""
        }
        return if (sid.isNotEmpty()) "${sid}_" else ""
    }

    private fun bucketKey(baseKey: String, studentId: String? = null): String {
        return accountPrefix(studentId) + baseKey
    }

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

        private const val PREFIX_DISCUSSIONS = "tc_cache_discussions_"
        private const val PREFIX_DISCUSSIONS_TIME = "tc_cache_discussions_time_"

        private const val KEY_HOME_BULLETINS = "tc_cache_home_bulletins"
        private const val KEY_HOME_BULLETINS_TIME = "tc_cache_home_bulletins_time"

        private const val PREFIX_EXAM_DETAIL = "tc_cache_exam_detail_"
        private const val PREFIX_EXAM_DETAIL_TIME = "tc_cache_exam_detail_time_"

        private const val PREFIX_CX_HW_DETAIL = "cx_cache_hw_detail_"
        private const val PREFIX_CX_HW_DETAIL_TIME = "cx_cache_hw_detail_time_"

        @Volatile
        private var instance: TronClassDataCacheManager? = null

        fun getInstance(context: Context): TronClassDataCacheManager {
            return instance ?: synchronized(this) {
                instance ?: TronClassDataCacheManager(context.applicationContext).also { instance = it }
            }
        }

        // --- 便捷静态方法 ---
        fun saveCourseExams(context: Context, courseId: Long, exams: List<TronClassExam>, studentId: String? = null) {
            getInstance(context).saveCourseExams(courseId, exams, studentId)
        }

        fun getCourseExams(context: Context, courseId: Long, studentId: String? = null): List<TronClassExam>? {
            return getInstance(context).getCachedCourseExams(courseId, studentId).takeIf { it.isNotEmpty() }
        }

        fun saveExamDetail(context: Context, examId: Long, detail: TronClassExamDetail, studentId: String? = null) {
            getInstance(context).saveExamDetail(examId, detail, studentId)
        }

        fun getExamDetail(context: Context, examId: Long, studentId: String? = null): TronClassExamDetail? {
            return getInstance(context).getCachedExamDetail(examId, studentId)
        }

        fun saveCourseDiscussions(context: Context, courseId: Long, discussions: List<TronClassDiscussion>, studentId: String? = null) {
            getInstance(context).saveCourseDiscussions(courseId, discussions, studentId)
        }

        fun getCourseDiscussions(context: Context, courseId: Long, studentId: String? = null): List<TronClassDiscussion>? {
            return getInstance(context).getCachedCourseDiscussions(courseId, studentId).takeIf { it.isNotEmpty() }
        }

        fun saveChaoXingHomeworkDetail(context: Context, workId: String, detail: ChaoXingHomeworkDetail, studentId: String? = null) {
            getInstance(context).saveChaoXingHomeworkDetail(workId, detail, studentId)
        }

        fun getChaoXingHomeworkDetail(context: Context, workId: String, studentId: String? = null): ChaoXingHomeworkDetail? {
            return getInstance(context).getCachedChaoXingHomeworkDetail(workId, studentId)
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
    fun saveCourses(courses: List<TronClassCourse>, studentId: String? = null) {
        TronClassPreferences(appContext).saveCachedCourses(courses, studentId)
    }

    fun getCachedCourses(studentId: String? = null): List<TronClassCourse> {
        return TronClassPreferences(appContext).getCachedCourses(studentId)
    }

    fun getCoursesCacheTime(): Long = TronClassPreferences(appContext).getCoursesCacheTime()

    // --- 2. 待办事项缓存 ---
    fun saveTodos(todos: List<TronClassTodo>, studentId: String? = null) {
        TronClassPreferences(appContext).saveCachedTodos(todos, studentId)
    }

    fun getCachedTodos(studentId: String? = null): List<TronClassTodo> {
        return TronClassPreferences(appContext).getCachedTodos(studentId)
    }

    fun getTodosCacheTime(): Long = TronClassPreferences(appContext).getTodosCacheTime()

    // --- 3. 课程章节目录 (Modules) 缓存 ---
    fun saveCourseModules(courseId: Long, modules: List<TronClassModule>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_MODULES$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_MODULES_TIME$courseId", studentId)
        memModules[key] = modules
        prefs.edit()
            .putString(key, gson.toJson(modules))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseModules(courseId: Long, studentId: String? = null): List<TronClassModule> {
        val key = bucketKey("$PREFIX_MODULES$courseId", studentId)
        memModules[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassModule>>() {}.type
            val res: List<TronClassModule> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memModules[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached modules for course $courseId", e)
            emptyList()
        }
    }

    // --- 3.1 学习进度 / 完成度映射缓存 ---
    fun saveCourseReads(courseId: Long, reads: Map<Long, String>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_READS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_READS_TIME$courseId", studentId)
        memReads[key] = reads
        prefs.edit()
            .putString(key, gson.toJson(reads))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseReads(courseId: Long, studentId: String? = null): Map<Long, String> {
        val key = bucketKey("$PREFIX_READS$courseId", studentId)
        memReads[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<Long, String>>() {}.type
            val res: Map<Long, String> = gson.fromJson(json, type) ?: emptyMap()
            if (res.isNotEmpty()) {
                memReads[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached reads for course $courseId", e)
            emptyMap()
        }
    }

    // --- 3.2 课程章节大纲/课件目录缓存 ---
    fun saveCourseSyllabus(courseId: Long, activities: List<TronClassActivityItem>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_SYLLABUS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_SYLLABUS_TIME$courseId", studentId)
        memSyllabus[key] = activities
        prefs.edit()
            .putString(key, gson.toJson(activities))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseSyllabus(courseId: Long, studentId: String? = null): List<TronClassActivityItem> {
        val key = bucketKey("$PREFIX_SYLLABUS$courseId", studentId)
        memSyllabus[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassActivityItem>>() {}.type
            val res: List<TronClassActivityItem> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memSyllabus[key] = res
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
    fun getCourseProgressPercent(courseId: Long, studentId: String? = null): Int {
        val activities = getCachedCourseSyllabus(courseId, studentId)
        if (activities.isEmpty()) return -1
        val reads = getCachedCourseReads(courseId, studentId)
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
    fun saveCourseExams(courseId: Long, exams: List<TronClassExam>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_EXAMS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_EXAMS_TIME$courseId", studentId)
        memExams[key] = exams
        prefs.edit()
            .putString(key, gson.toJson(exams))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseExams(courseId: Long, studentId: String? = null): List<TronClassExam> {
        val key = bucketKey("$PREFIX_EXAMS$courseId", studentId)
        memExams[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassExam>>() {}.type
            val res: List<TronClassExam> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memExams[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached exams for course $courseId", e)
            emptyList()
        }
    }

    // --- 5. 课程班级成员缓存 ---
    fun saveCourseMembers(courseId: Long, members: List<TronClassMember>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_MEMBERS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_MEMBERS_TIME$courseId", studentId)
        memMembers[key] = members
        prefs.edit()
            .putString(key, gson.toJson(members))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseMembers(courseId: Long, studentId: String? = null): List<TronClassMember> {
        val key = bucketKey("$PREFIX_MEMBERS$courseId", studentId)
        memMembers[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassMember>>() {}.type
            val res: List<TronClassMember> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memMembers[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached members for course $courseId", e)
            emptyList()
        }
    }

    // --- 6. 课程公告缓存 ---
    fun saveCourseBulletins(courseId: Long, bulletins: List<TronClassBulletin>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_BULLETINS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_BULLETINS_TIME$courseId", studentId)
        memBulletins[key] = bulletins
        prefs.edit()
            .putString(key, gson.toJson(bulletins))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseBulletins(courseId: Long, studentId: String? = null): List<TronClassBulletin> {
        val key = bucketKey("$PREFIX_BULLETINS$courseId", studentId)
        memBulletins[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            val res: List<TronClassBulletin> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memBulletins[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached bulletins for course $courseId", e)
            emptyList()
        }
    }

    // --- 6.1 主页多课程聚合公告缓存 ---
    fun saveHomeBulletins(bulletins: List<TronClassBulletin>, studentId: String? = null) {
        val key = bucketKey(KEY_HOME_BULLETINS, studentId)
        val timeKey = bucketKey(KEY_HOME_BULLETINS_TIME, studentId)
        memHomeBulletins[key] = bulletins
        prefs.edit()
            .putString(key, gson.toJson(bulletins))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedHomeBulletins(studentId: String? = null): List<TronClassBulletin> {
        val key = bucketKey(KEY_HOME_BULLETINS, studentId)
        memHomeBulletins[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            val res: List<TronClassBulletin> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memHomeBulletins[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached home bulletins from prefs", e)
            emptyList()
        }
    }

    fun getHomeBulletinsCacheTime(studentId: String? = null): Long =
        prefs.getLong(bucketKey(KEY_HOME_BULLETINS_TIME, studentId), 0L)

    // --- 6.2 课程课堂讨论缓存 ---
    fun saveCourseDiscussions(courseId: Long, discussions: List<TronClassDiscussion>, studentId: String? = null) {
        val key = bucketKey("$PREFIX_DISCUSSIONS$courseId", studentId)
        val timeKey = bucketKey("$PREFIX_DISCUSSIONS_TIME$courseId", studentId)
        memDiscussions[key] = discussions
        prefs.edit()
            .putString(key, gson.toJson(discussions))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedCourseDiscussions(courseId: Long, studentId: String? = null): List<TronClassDiscussion> {
        val key = bucketKey("$PREFIX_DISCUSSIONS$courseId", studentId)
        memDiscussions[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassDiscussion>>() {}.type
            val res: List<TronClassDiscussion> = gson.fromJson(json, type) ?: emptyList()
            if (res.isNotEmpty()) {
                memDiscussions[key] = res
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached discussions for course $courseId", e)
            emptyList()
        }
    }

    // --- 7. 测验做题与查卷详情缓存 ---
    fun saveExamDetail(examId: Long, detail: TronClassExamDetail, studentId: String? = null) {
        val key = bucketKey("$PREFIX_EXAM_DETAIL$examId", studentId)
        val timeKey = bucketKey("$PREFIX_EXAM_DETAIL_TIME$examId", studentId)
        prefs.edit()
            .putString(key, gson.toJson(detail))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedExamDetail(examId: Long, studentId: String? = null): TronClassExamDetail? {
        val key = bucketKey("$PREFIX_EXAM_DETAIL$examId", studentId)
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, TronClassExamDetail::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached exam detail for exam $examId", e)
            null
        }
    }

    // --- 9. 超星课程详情缓存 (章节、作业、考试) ---
    fun saveChaoXingChapters(courseId: String, units: List<ChaoXingChapterUnit>, studentId: String? = null) {
        val json = gson.toJson(units)
        prefs.edit()
            .putString(bucketKey("cx_cache_chapters_$courseId", studentId), json)
            .putLong(bucketKey("cx_cache_chapters_time_$courseId", studentId), System.currentTimeMillis())
            .apply()
    }

    fun getCachedChaoXingChapters(courseId: String, studentId: String? = null): List<ChaoXingChapterUnit> {
        val json = prefs.getString(bucketKey("cx_cache_chapters_$courseId", studentId), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChaoXingChapterUnit>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChaoXingHomeworks(courseId: String, works: List<ChaoXingWork>, studentId: String? = null) {
        val json = gson.toJson(works)
        prefs.edit()
            .putString(bucketKey("cx_cache_works_$courseId", studentId), json)
            .putLong(bucketKey("cx_cache_works_time_$courseId", studentId), System.currentTimeMillis())
            .apply()
    }

    fun getCachedChaoXingHomeworks(courseId: String, studentId: String? = null): List<ChaoXingWork> {
        val json = prefs.getString(bucketKey("cx_cache_works_$courseId", studentId), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChaoXingWork>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChaoXingExams(courseId: String, exams: List<ChaoXingExam>, studentId: String? = null) {
        val json = gson.toJson(exams)
        prefs.edit()
            .putString(bucketKey("cx_cache_exams_$courseId", studentId), json)
            .putLong(bucketKey("cx_cache_exams_time_$courseId", studentId), System.currentTimeMillis())
            .apply()
    }

    fun getCachedChaoXingExams(courseId: String, studentId: String? = null): List<ChaoXingExam> {
        val json = prefs.getString(bucketKey("cx_cache_exams_$courseId", studentId), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChaoXingExam>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChaoXingHomeworkDetail(workId: String, detail: ChaoXingHomeworkDetail, studentId: String? = null) {
        val key = bucketKey("$PREFIX_CX_HW_DETAIL$workId", studentId)
        val timeKey = bucketKey("$PREFIX_CX_HW_DETAIL_TIME$workId", studentId)
        memCxHwDetails[key] = detail
        prefs.edit()
            .putString(key, gson.toJson(detail))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedChaoXingHomeworkDetail(workId: String, studentId: String? = null): ChaoXingHomeworkDetail? {
        val key = bucketKey("$PREFIX_CX_HW_DETAIL$workId", studentId)
        memCxHwDetails[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return null
        return try {
            val detail = gson.fromJson(json, ChaoXingHomeworkDetail::class.java)
            if (detail != null) {
                memCxHwDetails[key] = detail
            }
            detail
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached ChaoXingHomeworkDetail for $workId", e)
            null
        }
    }

    fun saveChaoXingExamDetail(examId: String, detail: ChaoXingExamDetail, studentId: String? = null) {
        val key = bucketKey("cx_cache_exam_detail_$examId", studentId)
        val timeKey = bucketKey("cx_cache_exam_detail_time_$examId", studentId)
        memCxExamDetails[key] = detail
        prefs.edit()
            .putString(key, gson.toJson(detail))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun getCachedChaoXingExamDetail(examId: String, studentId: String? = null): ChaoXingExamDetail? {
        val key = bucketKey("cx_cache_exam_detail_$examId", studentId)
        memCxExamDetails[key]?.let { return it }
        val json = prefs.getString(key, null) ?: return null
        return try {
            val detail = gson.fromJson(json, ChaoXingExamDetail::class.java)
            if (detail != null) {
                memCxExamDetails[key] = detail
            }
            detail
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached ChaoXingExamDetail for $examId", e)
            null
        }
    }

    // --- 8. 统计与清理缓存 ---
    fun getDataCacheCount(): Int {
        return prefs.all.count {
            (it.key.contains("tc_cache_") || it.key.contains("cx_cache_")) && !it.key.endsWith("_time")
        } + TronClassPreferences(appContext).getLearningCacheCount()
    }

    fun getDataCacheSizeBytes(): Long {
        return TronClassPreferences(appContext).getLearningCacheSize() + prefs.all.entries
            .filter { it.key.contains("tc_cache_") || it.key.contains("cx_cache_") }
            .sumOf { (it.value as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 8L }
    }

    fun getFormattedDataCacheSize(): String {
        val bytes = getDataCacheSizeBytes()
        return formatSize(bytes)
    }

    fun clearMemoryCache() {
        memModules.clear()
        memSyllabus.clear()
        memReads.clear()
        memExams.clear()
        memMembers.clear()
        memBulletins.clear()
        memDiscussions.clear()
        memHomeBulletins.clear()
        memCxHwDetails.clear()
        memCxExamDetails.clear()
    }

    fun clearAccountDataCache(studentId: String) {
        if (studentId.isBlank()) return
        val prefix = "${studentId.trim()}_"
        listOf(
            memModules, memSyllabus, memReads, memExams, memMembers,
            memBulletins, memDiscussions, memHomeBulletins, memCxHwDetails, memCxExamDetails
        ).forEach { map ->
            map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) }
        }
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    fun clearDataCache(): Long {
        val size = getDataCacheSizeBytes()
        TronClassPreferences(appContext).clearLearningCache()
        clearMemoryCache()
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
