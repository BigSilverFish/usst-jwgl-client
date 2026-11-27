package cn.edu.usst.jwgl.data.network

import android.content.Context
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.*
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File

/**
 * 畅课（TronClass）统一门面客户端，内部按业务域委托至专职 API 服务
 */
class TronClassClient(context: Context) {

    private val context = context.applicationContext
    private val prefs = TronClassPreferences(this.context)
    private val gson = Gson()

    private val executor = TronClassSessionExecutor(this.context, prefs, gson)
    private val authApi = TronClassAuthApi(executor)
    private val rollcallApi = TronClassRollcallApi(executor, authApi)
    private val courseApi = TronClassCourseApi(executor)
    private val discussionApi = TronClassDiscussionApi(executor)
    private val assessmentApi = TronClassAssessmentApi(executor, authApi, courseApi)

    init {
        executor.sessionRefresher = { account -> authApi.refreshSessionInternal(account) }
    }

    companion object {
        private const val TAG = "TronClassClient"
        const val BASE_URL = "https://1906.usst.edu.cn"
        const val AUTH_SERVER_URL = "https://1906.usst.edu.cn:8600/auth"
        const val OAUTH_CLIENT_ID = "TronClassApp"
        const val OAUTH_REDIRECT_URI = "https://mobile-download.tronclass.com.cn/callback.html"

        fun getOAuthAuthUrl(): String {
            val encodedRedirect = java.net.URLEncoder.encode(OAUTH_REDIRECT_URI, "UTF-8")
            return "$AUTH_SERVER_URL/realms/usst/protocol/openid-connect/auth" +
                    "?client_id=$OAUTH_CLIENT_ID" +
                    "&response_type=code" +
                    "&scope=openid" +
                    "&redirect_uri=$encodedRedirect"
        }
    }

    fun accountKey(): String = executor.accountKey()

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()

    fun getSessionId(): String? = prefs.getSessionId()

    fun getOkHttpClient(): OkHttpClient = executor.okHttpClient

    fun getUserName(): String {
        val tcName = prefs.getUserName()
        if (!tcName.isNullOrBlank() && tcName != "同学") return tcName
        val globalProfile = DataCacheManager(context).getProfile()
        val gName = globalProfile?.name?.trim()
        if (!gName.isNullOrBlank()) {
            return gName
        }
        return "同学"
    }

    fun getUserNo(): String {
        val tcNo = prefs.getUserNo()
        if (!tcNo.isNullOrBlank()) return tcNo
        val globalProfile = DataCacheManager(context).getProfile()
        return globalProfile?.studentId ?: ""
    }

    fun getUserIdFromSession(): Long {
        val prefId = prefs.getUserId()
        if (prefId > 0L) return prefId
        return 0L
    }

    fun getVideoStreamUrl(uploadId: Long): String = "$BASE_URL/api/uploads/$uploadId/blob"

    fun parseRollcallQr(rawQr: String): ParsedQrRollcall? =
        TronClassQrParser.parseRollcallQr(rawQr)

    // --- Auth ---

    suspend fun loginWithAuthCode(authCode: String, studentId: String? = null): Result<Boolean> =
        authApi.loginWithAuthCode(authCode, studentId)

    suspend fun exchangeAccessTokenForSession(
        accessToken: String,
        refreshToken: String? = null,
        studentId: String? = null
    ): Result<Boolean> = authApi.exchangeAccessTokenForSession(accessToken, refreshToken, studentId)

    suspend fun refreshSession(studentId: String? = null): Result<Boolean> =
        authApi.refreshSession(studentId)

    suspend fun silentLoginTronClass(studentId: String, password: String): Result<Boolean> =
        authApi.silentLoginTronClass(studentId, password)

    suspend fun ensureSessionForAccount(studentId: String): Result<String> =
        authApi.ensureSessionForAccount(studentId)

    internal suspend fun ensureSessionForAccountWithDevice(
        studentId: String,
        fastPathIfVerified: Boolean = false
    ): Result<Pair<String, String>> {
        val devId = prefs.getDeviceId(studentId)
        if (fastPathIfVerified) {
            val cached = prefs.getSessionId(studentId)?.takeIf {
                it.isNotBlank() && TronClassAuthApi.isSessionVerified(studentId, it)
            }
            if (cached != null) {
                return Result.success(cached to devId)
            }
        }
        return authApi.ensureSessionForAccount(studentId).map { sid ->
            sid to prefs.getDeviceId(studentId)
        }
    }

    internal suspend fun executeRawAuthenticatedRequest(
        method: String,
        url: String,
        jsonBody: String? = null,
        customSessionId: String? = null,
        expectedAccount: String? = null
    ): Result<Pair<Int, String>> =
        rollcallApi.executeRawAuthenticatedRequest(method, url, jsonBody, customSessionId, expectedAccount)

    // --- Rollcall ---

    suspend fun fetchOngoingRollcalls(expectedAccount: String? = null): Result<List<TronClassRollcall>> =
        rollcallApi.fetchOngoingRollcalls(expectedAccount)

    suspend fun answerQRRollcall(
        rollcallId: Long,
        qrTokenData: String,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null,
        allowRejectedRetry: Boolean = true
    ): Result<RollcallSubmitResult> =
        rollcallApi.answerQRRollcall(rollcallId, qrTokenData, customSessionId, customDeviceId, customAccountKey, allowRejectedRetry)

    suspend fun answerNumberRollcall(
        rollcallId: Long,
        numberCode: String,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> =
        rollcallApi.answerNumberRollcall(rollcallId, numberCode, customSessionId, customDeviceId, customAccountKey)

    suspend fun answerSelfRegistrationRollcall(
        rollcallId: Long,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> =
        rollcallApi.answerSelfRegistrationRollcall(rollcallId, customSessionId, customDeviceId, customAccountKey)

    suspend fun queryRollcallAttendance(
        rollcall: TronClassRollcall,
        expectedAccount: String? = null
    ): Result<TronClassStudentRollcall?> =
        rollcallApi.queryRollcallAttendance(rollcall, expectedAccount)

    suspend fun getRollcallInfoLite(rollcallId: Long): Result<JSONObject> =
        rollcallApi.getRollcallInfoLite(rollcallId)

    suspend fun answerRadarRollcall(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double? = null,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> =
        rollcallApi.answerRadarRollcall(rollcallId, latitude, longitude, accuracy, customSessionId, customDeviceId, customAccountKey)

    internal suspend fun verifyRollcallAttendance(
        rollcallId: Long,
        studentId: String,
        customSessionId: String? = null,
        expectedAccount: String? = null,
        courseIdHint: Long = 0L
    ): RollcallVerificationState =
        rollcallApi.verifyRollcallAttendance(rollcallId, studentId, customSessionId, expectedAccount, courseIdHint)

    suspend fun fetchRollcallAttendanceStats(
        rollcallId: Long,
        expectedAccount: String? = null
    ): Result<RollcallAttendanceStats> =
        rollcallApi.fetchRollcallAttendanceStats(rollcallId, expectedAccount)

    suspend fun fetchCourseStudentRollcalls(
        courseId: Long,
        expectedAccount: String? = null
    ): Result<List<TronClassStudentRollcall>> =
        rollcallApi.fetchCourseStudentRollcalls(courseId, expectedAccount)

    // --- Course ---

    suspend fun fetchTodos(): Result<List<TronClassTodo>> =
        courseApi.fetchTodos()

    suspend fun fetchMyCourses(): Result<List<TronClassCourse>> =
        courseApi.fetchMyCourses()

    suspend fun fetchCourseModules(courseId: Long): Result<List<TronClassModule>> =
        courseApi.fetchCourseModules(courseId)

    suspend fun fetchCourseActivities(courseId: Long): Result<List<TronClassActivityItem>> =
        courseApi.fetchCourseActivities(courseId)

    suspend fun fetchCourseBulletins(courseId: Long): Result<List<TronClassBulletin>> =
        courseApi.fetchCourseBulletins(courseId)

    suspend fun fetchBulletinDetail(bulletinId: Long, courseId: Long = 0L): Result<TronClassBulletin> =
        courseApi.fetchBulletinDetail(bulletinId, courseId)

    suspend fun fetchCourseMembers(courseId: Long): Result<List<TronClassMember>> =
        courseApi.fetchCourseMembers(courseId)

    suspend fun fetchActivityReads(courseId: Long): Result<Map<Long, String>> =
        courseApi.fetchActivityReads(courseId)

    suspend fun logActivityRead(activityId: Long, uploadId: Long = 0L): Result<Boolean> =
        courseApi.logActivityRead(activityId, uploadId)

    suspend fun reportVideoProgress(
        activityId: Long,
        durationSeconds: Double,
        uploadId: Long = 0L,
        onProgress: ((currentSec: Int, totalSec: Int) -> Unit)? = null
    ): Result<Boolean> =
        courseApi.reportVideoProgress(activityId, durationSeconds, uploadId, onProgress)

    // --- Discussion ---

    suspend fun fetchCourseDiscussions(courseId: Long): Result<List<TronClassDiscussion>> =
        discussionApi.fetchCourseDiscussions(courseId)

    suspend fun fetchCourseTopicCategories(courseId: Long): Result<List<TronClassTopicCategory>> =
        discussionApi.fetchCourseTopicCategories(courseId)

    suspend fun fetchTopicDetail(topicId: Long): Result<TronClassTopicDetail> =
        discussionApi.fetchTopicDetail(topicId)

    suspend fun likeTopic(topicId: Long, like: Boolean): Result<Boolean> =
        discussionApi.likeTopic(topicId, like)

    suspend fun postTopicReply(topicId: Long, content: String): Result<TronClassTopicReply> =
        discussionApi.postTopicReply(topicId, content)

    suspend fun postReplyComment(replyId: Long, content: String): Result<Boolean> =
        discussionApi.postReplyComment(replyId, content)

    suspend fun postNewTopic(
        categoryId: Long,
        title: String,
        content: String,
        activityId: Long? = null
    ): Result<TronClassTopicDetail> =
        discussionApi.postNewTopic(categoryId, title, content, activityId)

    // --- Assessment ---

    suspend fun fetchActivityDetail(activityId: Long): Result<TronClassActivityItem> =
        assessmentApi.fetchActivityDetail(activityId)

    suspend fun downloadAttachment(
        uploadId: Long,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> =
        assessmentApi.downloadAttachment(uploadId, destFile, onProgress)

    suspend fun fetchSubmissionDetail(
        activityId: Long,
        userId: Long = getUserIdFromSession()
    ): Result<TronClassSubmission> =
        assessmentApi.fetchSubmissionDetail(activityId, userId)

    suspend fun uploadFile(
        file: File,
        fileName: String = file.name,
        parentType: String = "activity",
        allowRetry: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): Result<TronClassUpload> =
        assessmentApi.uploadFile(file, fileName, parentType, allowRetry, onProgress)

    suspend fun submitHomework(
        activityId: Long,
        comment: String,
        uploads: List<TronClassUpload>,
        isResubmit: Boolean = false,
        submissionId: Long? = null,
        allowRetry: Boolean = true
    ): Result<Boolean> =
        assessmentApi.submitHomework(activityId, comment, uploads, isResubmit, submissionId, allowRetry)

    suspend fun fetchCourseExams(courseId: Long): Result<List<TronClassExam>> =
        assessmentApi.fetchCourseExams(courseId)

    suspend fun fetchExamDetail(
        examId: Long,
        targetSubmissionId: Long? = null
    ): Result<TronClassExamDetail> =
        assessmentApi.fetchExamDetail(examId, targetSubmissionId)

    suspend fun submitExamAnswers(
        examId: Long,
        answers: Map<String, Any>,
        allowRetry: Boolean = true
    ): Result<Boolean> =
        assessmentApi.submitExamAnswers(examId, answers, allowRetry)

    data class ParsedQrRollcall(
        val rollcallId: Long,
        val qrData: String,
        val courseId: Long? = null
    )
}
