package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.AUTH_SERVER_URL
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.BASE_URL
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.OAUTH_CLIENT_ID
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.OAUTH_REDIRECT_URI
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.AppNetworkException
import cn.edu.usst.jwgl.util.CoordinateUtils
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class TronClassCourseApi(
    private val executor: TronClassSessionExecutor
) {
    companion object {
        private const val TAG = "TronClassCourseApi"
    }

    private val context get() = executor.context
    private val prefs get() = executor.prefs
    private val gson get() = executor.gson
    private val okHttpClient get() = executor.okHttpClient

    private fun accountKey() = executor.accountKey()
    private suspend fun executeRequest(request: Request, expectedAccount: String? = null) = executor.executeRequest(request, expectedAccount)
    private suspend fun refreshSessionOnce(account: String, previousSession: String?) = executor.refreshSessionOnce(account, previousSession)

    suspend fun fetchTodos(): Result<List<TronClassTodo>> = withContext(Dispatchers.IO) {
        val capturedAccount = accountKey()
        try {
            val url = "$BASE_URL/api/todos"
            val req = Request.Builder().url(url).get().build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (resp.code == 401 || resp.code == 403) {
                return@withContext Result.failure(AppAuthExpiredException("一网畅学登录已失效，请重新授权登录"))
            }

            if (!resp.isSuccessful) {
                return@withContext Result.failure(AppNetworkException(resp.code, "获取待办列表失败 (${resp.code})", url = url))
            }

            val json = JSONObject(body)
            val todoArray = json.optJSONArray("todo_list")
            val list = mutableListOf<TronClassTodo>()

            if (todoArray != null) {
                for (i in 0 until todoArray.length()) {
                    val item = todoArray.getJSONObject(i)
                    val todo = TronClassTodo(
                        id = item.optLong("id"),
                        title = item.optString("title", ""),
                        courseId = item.optLong("course_id"),
                        courseName = item.optString("course_name", ""),
                        courseCode = item.optString("course_code", null),
                        courseType = item.optInt("course_type", 1),
                        endTime = item.optString("end_time", null),
                        type = item.optString("type", "homework"),
                        isLocked = item.optBoolean("is_locked", false),
                        isStudent = item.optBoolean("is_student", true)
                    )
                    list.add(todo)
                }
            }

            // 按截止时间排序 (截止日期最近的排在最前)
            list.sortBy { it.getDeadlineDate()?.time ?: Long.MAX_VALUE }

            prefs.saveCachedTodos(list, capturedAccount)
            prefs.setLastSyncTime()
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTodos error", e)
            Result.failure(e)
        }
    }

    /**
     * 6. 获取当前学期在读课程 (/api/my-courses)
     */
    suspend fun fetchMyCourses(): Result<List<TronClassCourse>> = withContext(Dispatchers.IO) {
        val capturedAccount = accountKey()
        try {
            val url = "$BASE_URL/api/my-courses"
            val req = Request.Builder().url(url).get().build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (resp.code == 401 || resp.code == 403) {
                return@withContext Result.failure(AppAuthExpiredException("一网畅学登录已失效，请重新授权登录"))
            }

            if (!resp.isSuccessful) {
                return@withContext Result.failure(AppNetworkException(resp.code, "获取课程列表失败 (${resp.code})", url = url))
            }

            val json = JSONObject(body)
            val courseArray = json.optJSONArray("courses")
            val list = mutableListOf<TronClassCourse>()

            if (courseArray != null) {
                for (i in 0 until courseArray.length()) {
                    val item = courseArray.getJSONObject(i)
                    val deptName = item.optJSONObject("department")?.optString("name", null)
                    val yearName = item.optJSONObject("academic_year")?.optString("name", null)

                    val instructorsList = mutableListOf<String>()
                    val instArray = item.optJSONArray("instructors") ?: item.optJSONArray("teachers")
                    if (instArray != null) {
                        for (j in 0 until instArray.length()) {
                            val iObj = instArray.optJSONObject(j)
                            val name = iObj?.optString("name") ?: instArray.optString(j)
                            if (!name.isNullOrBlank()) instructorsList.add(name.trim())
                        }
                    } else if (item.has("instructor_name")) {
                        val name = item.optString("instructor_name")
                        if (name.isNotBlank()) instructorsList.add(name.trim())
                    }

                    val course = TronClassCourse(
                        id = item.optLong("id"),
                        name = item.optString("name", ""),
                        courseCode = item.optString("course_code", null),
                        cover = item.optString("cover", null),
                        department = if (deptName != null) TronClassCourse.DepartmentInfo(deptName) else null,
                        academicYear = if (yearName != null) TronClassCourse.AcademicYearInfo(yearName) else null,
                        instructors = instructorsList,
                        teacher = instructorsList.firstOrNull()
                    )
                    list.add(course)
                }
            }

            prefs.saveCachedCourses(list, capturedAccount)
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyCourses error", e)
            Result.failure(e)
        }
    }

    /**
     * 7. 轮询是否有正在进行的课堂签到 (/api/radar/rollcalls)
     */

suspend fun fetchCourseModules(courseId: Long): Result<List<TronClassModule>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/modules"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取课程章节失败: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val modulesArray = json.optJSONArray("modules") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassModule>>() {}.type
            val modules: List<TronClassModule> = gson.fromJson(modulesArray.toString(), type) ?: emptyList()
            Result.success(modules.sortedBy { it.sort })
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseModules error", e)
            Result.failure(e)
        }
    }

    /**
     * 12. 获取课程所有学习活动/课件/作业
     * GET /api/courses/{courseId}/activities
     */
    suspend fun fetchCourseActivities(courseId: Long): Result<List<TronClassActivityItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/activities"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取课程活动失败: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val actArray = json.optJSONArray("activities") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassActivityItem>>() {}.type
            val items: List<TronClassActivityItem> = gson.fromJson(actArray.toString(), type) ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseActivities error", e)
            Result.failure(e)
        }
    }

    /**
     * 13. 获取课程公告列表
     * GET /api/courses/{courseId}/bulletins
     */
    suspend fun fetchCourseBulletins(courseId: Long): Result<List<TronClassBulletin>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/bulletins"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取课程公告失败: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val bullArray = json.optJSONArray("bulletins") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            val items: List<TronClassBulletin> = gson.fromJson(bullArray.toString(), type) ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseBulletins error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.1 获取公告详情 (包含可能未在列表返回的附件 uploads)
     * GET /api/bulletins/{bulletinId} 或 GET /api/courses/{courseId}/bulletins/{bulletinId}
     */
    suspend fun fetchBulletinDetail(bulletinId: Long, courseId: Long = 0L): Result<TronClassBulletin> = withContext(Dispatchers.IO) {
        try {
            val url = if (courseId > 0) {
                "$BASE_URL/api/courses/$courseId/bulletins/$bulletinId"
            } else {
                "$BASE_URL/api/bulletins/$bulletinId"
            }
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                if (resp.code == 404 && courseId > 0) {
                    return@withContext fetchBulletinDetail(bulletinId, 0L)
                }
                return@withContext Result.failure(Exception("获取公告详情失败: HTTP ${resp.code}"))
            }

            val item: TronClassBulletin = gson.fromJson(body, TronClassBulletin::class.java)
            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "fetchBulletinDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.2 获取课程讨论板块与帖子列表 (全面支持 /api/courses/{courseId}/topic-categories 与 /api/topics/{id})
     */

    suspend fun fetchCourseMembers(courseId: Long): Result<List<TronClassMember>> = withContext(Dispatchers.IO) {
        try {
            val members = mutableListOf<TronClassMember>()
            val seenUserIds = mutableSetOf<Long>()

            // 1. 获取选课与教职名单 (/enrollments)
            val enrollUrl = "$BASE_URL/api/course/$courseId/enrollments"
            val enrollReq = Request.Builder().url(enrollUrl).build()
            val enrollResp = executeRequest(enrollReq)
            if (!enrollResp.isSuccessful) {
                enrollResp.close()
                return@withContext Result.failure(Exception("获取课程选课名单失败: HTTP ${enrollResp.code}"))
            }
            if (enrollResp.isSuccessful) {
                val enrollBody = enrollResp.body?.string() ?: ""
                val enrollJson = JSONObject(enrollBody)
                val enrollArray = enrollJson.optJSONArray("enrollments")
                if (enrollArray != null) {
                    for (i in 0 until enrollArray.length()) {
                        val item = enrollArray.getJSONObject(i)
                        val u = item.optJSONObject("user") ?: continue
                        val uid = u.optLong("id")
                        val name = u.optString("name", "未知")
                        val userNo = u.optString("user_no").takeIf { it.isNotBlank() }
                        val roles = item.optJSONArray("roles")
                        var role = "student"
                        var roleName = "学生"
                        if (roles != null) {
                            for (r in 0 until roles.length()) {
                                val ro = roles.optString(r)
                                if (ro == "instructor") {
                                    role = "instructor"
                                    roleName = "任课教师"
                                    break
                                } else if (ro == "ta") {
                                    role = "ta"
                                    roleName = "助教"
                                    break
                                }
                            }
                        }
                        seenUserIds.add(uid)
                        members.add(
                            TronClassMember(
                                id = uid,
                                name = name,
                                userNo = userNo,
                                role = role,
                                roleName = roleName
                            )
                        )
                    }
                }
            }

            // 2. 获取完整学生名单 (/students)，补充班级和学院信息
            val stuUrl = "$BASE_URL/api/course/$courseId/students"
            val stuReq = Request.Builder().url(stuUrl).build()
            val stuResp = executeRequest(stuReq)
            if (!stuResp.isSuccessful) {
                stuResp.close()
                return@withContext Result.failure(Exception("获取课程学生名单失败: HTTP ${stuResp.code}"))
            }
            if (stuResp.isSuccessful) {
                val stuBody = stuResp.body?.string() ?: ""
                val stuJson = JSONObject(stuBody)
                val stuArray = stuJson.optJSONArray("students")
                if (stuArray != null) {
                    for (i in 0 until stuArray.length()) {
                        val s = stuArray.getJSONObject(i)
                        val uid = s.optLong("id")
                        val name = s.optString("name", "未知")
                        val userNo = s.optString("user_no").takeIf { it.isNotBlank() }
                        val dept = s.optJSONObject("department")?.optString("name")
                        val klass = s.optJSONObject("klass")?.optString("name")
                        val avatar = s.optString("avatar_small_url").takeIf { it.isNotBlank() }

                        val existingIdx = members.indexOfFirst { it.id == uid }
                        if (existingIdx >= 0) {
                            val cur = members[existingIdx]
                            members[existingIdx] = cur.copy(
                                department = dept ?: cur.department,
                                klass = klass ?: cur.klass,
                                avatarUrl = avatar ?: cur.avatarUrl
                            )
                        } else {
                            seenUserIds.add(uid)
                            members.add(
                                TronClassMember(
                                    id = uid,
                                    name = name,
                                    userNo = userNo,
                                    role = "student",
                                    roleName = "学生",
                                    department = dept,
                                    klass = klass,
                                    avatarUrl = avatar
                                )
                            )
                        }
                    }
                }
            }

            // 排序：教师排最前，其次助教，最后学生（按学号正序）
            members.sortWith(compareBy({
                when (it.role) {
                    "instructor" -> 0
                    "ta" -> 1
                    else -> 2
                }
            }, { it.userNo ?: "" }))

            Result.success(members)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseMembers failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取用户在课程中的学习进度 (Activity Reads)
     */
    suspend fun fetchActivityReads(courseId: Long): Result<Map<Long, String>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/course/$courseId/activity-reads-for-user"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取学习进度失败: ${resp.code}"))
            }
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val readsArray = json.optJSONArray("activity_reads")
            val map = mutableMapOf<Long, String>()
            if (readsArray != null) {
                for (i in 0 until readsArray.length()) {
                    val item = readsArray.getJSONObject(i)
                    val actId = item.optLong("activity_id")
                    val comp = item.optString("completeness", "complete")
                    if (actId > 0) {
                        map[actId] = comp
                    }
                }
            }
            Result.success(map)
        } catch (e: Exception) {
            Log.e(TAG, "fetchActivityReads failed", e)
            Result.failure(e)
        }
    }

    /**
     * 标记活动已读 / 已完成 (同步上报学习进度至一网畅学服务器)
     */
    suspend fun logActivityRead(activityId: Long, uploadId: Long = 0L): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/course/activities-read/$activityId"
            val json = JSONObject().apply {
                if (uploadId > 0) {
                    put("upload_id", uploadId)
                }
            }
            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = executeRequest(req)
            val ok = resp.isSuccessful || resp.code == 201
            resp.close()
            Result.success(ok)
        } catch (e: Exception) {
            Log.e(TAG, "logActivityRead failed", e)
            Result.failure(e)
        }
    }

    /**
     * 针对视频活动分段并发/串行上报观看进度，确保达到 100% 观看判定 (completeness: "full")
     * TronClass 服务器单次上报时长限制为 120s
     */
    suspend fun reportVideoProgress(
        activityId: Long,
        durationSeconds: Double,
        uploadId: Long = 0L,
        onProgress: ((currentSec: Int, totalSec: Int) -> Unit)? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val totalSec = if (durationSeconds <= 0.0) 300 else durationSeconds.toInt()
            val chunkSize = 120
            var start = 0
            while (start < totalSec) {
                val end = (start + chunkSize).coerceAtMost(totalSec)
                val json = JSONObject().apply {
                    put("start", start)
                    put("end", end)
                    put("duration", totalSec)
                    if (uploadId > 0) {
                        put("upload_id", uploadId)
                    }
                }
                val url = "$BASE_URL/api/course/activities-read/$activityId"
                val req = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = executeRequest(req)
                resp.close()
                start = end
                onProgress?.invoke(start, totalSec)
                if (start < totalSec) {
                    kotlinx.coroutines.delay(80)
                }
            }
            // 最终 100% 确认上报
            val finalJson = JSONObject().apply {
                put("start", 0)
                put("end", totalSec)
                put("duration", totalSec)
                if (uploadId > 0) {
                    put("upload_id", uploadId)
                }
            }
            val finalReq = Request.Builder()
                .url("$BASE_URL/api/course/activities-read/$activityId")
                .post(finalJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val finalResp = executeRequest(finalReq)
            finalResp.close()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "reportVideoProgress failed for act $activityId", e)
            Result.failure(e)
        }
    }

    /**
     * 获取课程测验与考试列表
     */
}
