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

internal class TronClassDiscussionApi(
    private val executor: TronClassSessionExecutor
) {
    companion object {
        private const val TAG = "TronClassDiscussionApi"
    }

    private val context get() = executor.context
    private val prefs get() = executor.prefs
    private val gson get() = executor.gson
    private val okHttpClient get() = executor.okHttpClient

    private fun accountKey() = executor.accountKey()
    private suspend fun executeRequest(request: Request, expectedAccount: String? = null) = executor.executeRequest(request, expectedAccount)
    private suspend fun refreshSessionOnce(account: String, previousSession: String?) = executor.refreshSessionOnce(account, previousSession)

    suspend fun fetchCourseDiscussions(courseId: Long): Result<List<TronClassDiscussion>> = withContext(Dispatchers.IO) {
        try {
            val categoriesRes = fetchCourseTopicCategories(courseId)
            val categories = categoriesRes.getOrNull() ?: emptyList()

            if (categories.isNotEmpty()) {
                val discussions = mutableListOf<TronClassDiscussion>()
                val currentUserId = prefs.getUserId()
                val currentUserName = prefs.getUserName()

                for (cat in categories) {
                    // 1. 如果该分类绑定了活动，添加议题要求导读卡片
                    val act = cat.activity
                    if (act != null) {
                        val headerDisc = TronClassDiscussion.fromActivity(act).apply {
                            categoryId = cat.id
                        }
                        discussions.add(headerDisc)
                    }

                    // 2. 并发拉取分类下的所有讨论帖子 (最多前 50 条)
                    val topicRefs = cat.topics ?: emptyList()
                    if (topicRefs.isNotEmpty()) {
                        val topicDetails = coroutineScope {
                            topicRefs.take(50).map { ref ->
                                async {
                                    fetchTopicDetail(ref.id).getOrThrow()
                                }
                            }.awaitAll()
                        }

                        // 将用户自己的帖子排在前面，其余按时间倒序排列
                        val mapped = topicDetails.map { t ->
                            val isSelf = (currentUserId > 0L && t.createdBy?.id == currentUserId) ||
                                         (!currentUserName.isNullOrBlank() && t.createdBy?.name?.contains(currentUserName) == true)
                            TronClassDiscussion.fromTopic(t, isSelf = isSelf)
                        }.sortedWith(compareByDescending<TronClassDiscussion> { it.isSelf }.thenByDescending { it.createdAt ?: "" })

                        discussions.addAll(mapped)
                    }
                }

                if (discussions.isNotEmpty()) {
                    return@withContext Result.success(discussions)
                }
            }

            // 回退到老端点备用获取
            val url1 = "$BASE_URL/api/courses/$courseId/forum/topics"
            var req = Request.Builder().url(url1).build()
            var resp = executeRequest(req)
            var body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                val url2 = "$BASE_URL/api/courses/$courseId/discussions"
                req = Request.Builder().url(url2).build()
                resp = executeRequest(req)
                body = resp.body?.string() ?: ""
            }

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取课程讨论失败: HTTP ${resp.code}"))
            }

            val type = object : TypeToken<List<TronClassDiscussion>>() {}.type
            val items: List<TronClassDiscussion> =
                if (body.trim().startsWith("[")) {
                    gson.fromJson(body, type) ?: emptyList()
                } else {
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("topics") ?: json.optJSONArray("discussions") ?: json.optJSONArray("items")
                    if (arr != null) {
                        gson.fromJson(arr.toString(), type) ?: emptyList()
                    } else emptyList()
                }

            Result.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseDiscussions error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.3 获取课程讨论区分类
     * GET /api/courses/{courseId}/topic-categories
     */
    suspend fun fetchCourseTopicCategories(courseId: Long): Result<List<TronClassTopicCategory>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/topic-categories"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取讨论分类失败: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val arr = json.optJSONArray("topic_categories") ?: JSONArray()
            val type = object : TypeToken<List<TronClassTopicCategory>>() {}.type
            val list: List<TronClassTopicCategory> = gson.fromJson(arr.toString(), type) ?: emptyList()
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseTopicCategories error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.4 获取单篇帖子/讨论详情 (含正文与回帖列表)
     * GET /api/topics/{topicId}
     */
    suspend fun fetchTopicDetail(topicId: Long): Result<TronClassTopicDetail> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/topics/$topicId"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取讨论详情失败: HTTP ${resp.code}"))
            }

            val detail: TronClassTopicDetail = gson.fromJson(body, TronClassTopicDetail::class.java)
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTopicDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.5 点赞或取消点赞帖子
     * POST /api/topics/{topicId}/likes 或 DELETE /api/topics/{topicId}/likes
     */
    suspend fun likeTopic(topicId: Long, like: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/topics/$topicId/likes"
            val reqBuilder = Request.Builder().url(url)
            if (like) {
                reqBuilder.post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                reqBuilder.delete()
            }
            val resp = executeRequest(reqBuilder.build())
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                val errMsg = try {
                    JSONObject(body).optString("message", "操作失败 (${resp.code})")
                } catch (_: Exception) {
                    "操作失败 (${resp.code})"
                }
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "likeTopic error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.6 发表回帖参与讨论
     * POST /api/topics/{topicId}/replies
     * Body: { "content": "<p>...</p>", "uploads": [] }
     */
    suspend fun postTopicReply(topicId: Long, content: String): Result<TronClassTopicReply> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/topics/$topicId/replies"
            val htmlContent = if (content.trim().startsWith("<p>")) content.trim() else "<p>${content.trim()}</p>"
            val payload = JSONObject().apply {
                put("content", htmlContent)
                put("uploads", JSONArray())
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful) {
                val createdReply = try {
                    gson.fromJson(body, TronClassTopicReply::class.java)
                } catch (_: Exception) { null } ?: TronClassTopicReply(
                    id = System.currentTimeMillis(),
                    content = htmlContent,
                    createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date()),
                    createdBy = TronClassTopicDetail.TronClassTopicUser(
                        id = prefs.getUserId(),
                        name = prefs.getUserName() ?: "我"
                    )
                )
                Result.success(createdReply)
            } else {
                val errMsg = try {
                    JSONObject(body).optString("message", "发表回帖失败 (${resp.code})")
                } catch (_: Exception) {
                    "发表回帖失败 (${resp.code})"
                }
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "postTopicReply error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.7 在回帖下追加评论
     * POST /api/replies/{replyId}/comments
     * Body: { "content": "..." }
     */
    suspend fun postReplyComment(replyId: Long, content: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/replies/$replyId/comments"
            val payload = JSONObject().apply {
                put("content", content.trim())
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                val errMsg = try {
                    JSONObject(body).optString("message", "发表评论失败 (${resp.code})")
                } catch (_: Exception) {
                    "发表评论失败 (${resp.code})"
                }
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "postReplyComment error", e)
            Result.failure(e)
        }
    }

    /**
     * 13.8 发布新的讨论主题 (发帖参与讨论)
     * POST /api/topics
     * Body: { "title": "...", "content": "<p>...</p>", "category_id": categoryId, "activity_id": activityId, "uploads": [] }
     */
    suspend fun postNewTopic(
        categoryId: Long,
        title: String,
        content: String,
        activityId: Long? = null
    ): Result<TronClassTopicDetail> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/topics"
            val htmlContent = if (content.trim().startsWith("<p>")) content.trim() else "<p>${content.trim()}</p>"
            val payload = JSONObject().apply {
                put("title", title.trim())
                put("content", htmlContent)
                put("category_id", categoryId)
                if (activityId != null && activityId > 0) {
                    put("activity_id", activityId)
                }
                put("uploads", JSONArray())
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful) {
                val item: TronClassTopicDetail = gson.fromJson(body, TronClassTopicDetail::class.java)
                Result.success(item)
            } else {
                val errMsg = try {
                    JSONObject(body).optString("message", "发布讨论失败 (${resp.code})")
                } catch (_: Exception) {
                    "发布讨论失败 (${resp.code})"
                }
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "postNewTopic error", e)
            Result.failure(e)
        }
    }

    /**
     * 14. 获取活动详情 (包含作业说明富文本及评分规则)
     * GET /api/activities/{activityId}
     */
}
