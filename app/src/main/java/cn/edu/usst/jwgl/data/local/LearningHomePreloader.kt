package cn.edu.usst.jwgl.data.local

import android.content.Context
import cn.edu.usst.jwgl.data.model.*
import kotlinx.coroutines.*

/** One consumable, account-bound disk snapshot; warming never sends a network request. */
object LearningHomePreloader {
    data class CourseOptions(
        val tcSemesters: Map<String, String>,
        val cxSemesters: Map<String, String>,
        val bindings: Map<String, String>,
        val unbound: Set<String>
    )

    data class Snapshot(
        val account: String,
        val dual: Boolean,
        val tcTodos: List<TronClassTodo>,
        val tcCourses: List<TronClassCourse>,
        val tcBulletins: List<TronClassBulletin>,
        val cxTodos: List<UnifiedTodo>,
        val cxCourses: List<ChaoXingCourse>,
        val cxNotices: List<UnifiedBulletin>,
        val options: CourseOptions,
        val courses: List<UnifiedCourse> = emptyList(),
        val todos: List<UnifiedTodo> = emptyList(),
        val bulletins: List<UnifiedBulletin> = emptyList()
    )

    private var warming: Deferred<Snapshot>? = null

    @Synchronized
    fun warm(context: Context, scope: CoroutineScope) {
        if (warming != null) return
        val app = context.applicationContext
        warming = scope.async(Dispatchers.IO) {
            val account = AuthPreferences.currentStudentId(app)
            read(app, account, TronClassPreferences.getInstance(app).isDualPlatformModeEnabled())
        }
    }

    suspend fun take(context: Context, account: String, dual: Boolean, fresh: Boolean = false): Snapshot {
        val pending = synchronized(this) { warming.also { warming = null } }
        if (!fresh && pending != null) {
            val result = try { pending.await() } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (result?.account == account && result.dual == dual &&
                account == AuthPreferences.currentStudentId(context)) return result
        }
        return withContext(Dispatchers.IO) { read(context.applicationContext, account, dual) }
    }

    fun readOptions(context: Context, account: String, dual: Boolean): CourseOptions {
        val tc = TronClassPreferences.getInstance(context)
        return CourseOptions(tc.getAllCustomSemesters(account),
            if (dual) ChaoXingPreferences(context).getAllCustomSemesters(account) else emptyMap(),
            tc.getAllManualBindings(account), tc.getUnboundPairs(account).toSet())
    }

    fun mergeCourses(tc: List<TronClassCourse>, cx: List<ChaoXingCourse>, dual: Boolean,
                     options: CourseOptions): List<UnifiedCourse> =
        if (dual && cx.isNotEmpty()) UnifiedCourse.fuseCourses(tc, cx,
            fallbackSemesterCode = tc.mapNotNull { it.extractSemesterCode() }.maxOrNull(),
            customSemesterMap = options.cxSemesters, tcCustomSemesterMap = options.tcSemesters,
            manualBindings = options.bindings, unboundPairs = options.unbound)
        else tc.map { UnifiedCourse.fromTronClass(it, customSemesterCode = options.tcSemesters[it.id.toString()]) }

    private fun read(context: Context, account: String, dual: Boolean): Snapshot {
        val cache = TronClassDataCacheManager.getInstance(context)
        val tc = TronClassPreferences.getInstance(context)
        val cx = if (dual) ChaoXingPreferences(context) else null
        val result = Snapshot(account, dual, tc.getCachedTodos(account), tc.getCachedCourses(account),
            cache.getCachedHomeBulletins(), cx?.getCachedTodos(account).orEmpty(),
            cx?.getCachedCourses(account).orEmpty(), cx?.getCachedNotices(account).orEmpty(),
            readOptions(context, account, dual))
        val prepared = result.copy(
            courses = mergeCourses(result.tcCourses, result.cxCourses, dual, result.options),
            todos = (result.tcTodos.map { UnifiedTodo.fromTronClass(it) } + result.cxTodos)
                .sortedBy { it.deadlineMillis ?: Long.MAX_VALUE },
            bulletins = (result.tcBulletins.map { UnifiedBulletin.fromTronClass(it) } + result.cxNotices)
                .sortedByDescending { it.publishMillis })
        if (account != AuthPreferences.currentStudentId(context)) throw CancellationException("Account changed during cache warmup")
        return prepared
    }
}
