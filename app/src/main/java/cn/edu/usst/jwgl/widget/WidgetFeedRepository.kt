package cn.edu.usst.jwgl.widget

import android.content.Context
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.UnifiedBulletin
import cn.edu.usst.jwgl.data.model.UnifiedTodo
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.network.TronClassClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * 桌面微件统一数据聚合与同步仓库：
 * 聚合一网畅学 (TronClass) 与超星学习通 (ChaoXing) 的待办及课程公告，
 * 并负责后台 / 启动时的双平台静默网络同步。
 */
object WidgetFeedRepository {

    /**
     * 加载所有未完成且未逾期的待办（用于课程角标与全量筛选）。
     */
    fun loadActiveTodos(context: Context): List<UnifiedTodo> {
        val list = mutableListOf<UnifiedTodo>()
        val nowMs = System.currentTimeMillis()
        try {
            val account = AuthPreferences.currentStudentId(context)
            // 1. 超星待办（仅双平台融合模式开启时纳入）
            if (TronClassPreferences.getInstance(context).isDualPlatformModeEnabled()) {
                val cxTodos = ChaoXingPreferences(context).getCachedTodos(account)
                    .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                list.addAll(cxTodos)
            }

            // 2. 畅课待办
            val tcTodos = TronClassDataCacheManager(context).getCachedTodos()
                .filterNot { it.isOverdue() || it.isLocked }
                .map { UnifiedTodo.fromTronClass(it) }
            list.addAll(tcTodos)
        } catch (_: Exception) {
        }

        return UnifiedTodo.deduplicateTodos(list)
            .filter { it.deadlineMillis == null || it.deadlineMillis > nowMs }
            .sortedBy { it.deadlineMillis ?: Long.MAX_VALUE }
    }

    /**
     * 加载未来 [withinDays] 天内的待办（默认 7 天）。
     * 包含“未定截止时间”的待办，排在有明确截止时间的待办之后。
     */
    fun loadTodosWithin(context: Context, withinDays: Int = 7): List<UnifiedTodo> {
        val nowMs = System.currentTimeMillis()
        val maxMs = nowMs + TimeUnit.DAYS.toMillis(withinDays.toLong())
        return loadActiveTodos(context).filter { todo ->
            val ddl = todo.deadlineMillis
            ddl == null || ddl in (nowMs + 1)..maxMs
        }
    }

    /**
     * 加载近 [withinDays] 天内的双平台课程通知/公告（默认 7 天），按发布时间降序混排。
     */
    fun loadNoticesWithin(context: Context, withinDays: Int = 7): List<UnifiedBulletin> {
        val nowMs = System.currentTimeMillis()
        val minMs = nowMs - TimeUnit.DAYS.toMillis(withinDays.toLong())
        val list = mutableListOf<UnifiedBulletin>()
        try {
            val account = AuthPreferences.currentStudentId(context)
            val cacheMgr = TronClassDataCacheManager(context)

            // 1. 一网畅学首页聚合公告
            val tcBulletins = cacheMgr.getCachedHomeBulletins()
                .map { UnifiedBulletin.fromTronClass(it) }
            list.addAll(tcBulletins)

            // 2. 超星学习通课程通知（仅双平台融合模式开启时纳入）
            if (TronClassPreferences.getInstance(context).isDualPlatformModeEnabled()) {
                val cxNotices = ChaoXingPreferences(context).getCachedNotices(account)
                list.addAll(cxNotices)
            }
        } catch (_: Exception) {
        }

        return list
            .distinctBy { it.unifiedId }
            .filter { it.publishMillis <= 0L || it.publishMillis >= minMs }
            .sortedByDescending { it.publishMillis }
    }

    /**
     * 后台/启动时从网络静默拉取双平台最新待办与近7天课程公告，写回本地缓存并刷新全部桌面微件。
     */
    suspend fun syncFeedFromNetwork(context: Context): Boolean = coroutineScope {
        val appContext = context.applicationContext
        var anyUpdated = false

        // 1. 一网畅学同步
        val tcJob = async {
            try {
                val tcClient = TronClassClient(appContext)
                if (!tcClient.isLoggedIn()) return@async false
                val account = tcClient.accountKey()
                val cacheMgr = TronClassDataCacheManager(appContext)

                val todosDef = async { tcClient.fetchTodos() }
                val coursesDef = async {
                    val cached = cacheMgr.getCachedCourses()
                    if (cached.isNotEmpty()) Result.success(cached) else tcClient.fetchMyCourses()
                }

                val todosRes = todosDef.await()
                if (account != tcClient.accountKey()) return@async false
                if (todosRes.isSuccess) {
                    cacheMgr.saveTodos(todosRes.getOrDefault(emptyList()))
                    anyUpdated = true
                }

                val courses = coursesDef.await().getOrDefault(emptyList())
                if (courses.isNotEmpty()) {
                    val latestSemester = courses.mapNotNull { it.extractSemesterCode() }
                        .distinct().sortedDescending().firstOrNull()
                    val targetCourses = if (latestSemester != null) {
                        courses.filter { it.extractSemesterCode() == latestSemester }
                    } else {
                        courses.take(12)
                    }
                    if (targetCourses.isNotEmpty()) {
                        val bulletinLists = targetCourses.map { c ->
                            async {
                                val res = tcClient.fetchCourseBulletins(c.id)
                                val list = res.getOrDefault(emptyList())
                                list.forEach { item ->
                                    item.courseId = c.id
                                    item.courseName = c.getCleanName()
                                }
                                list
                            }
                        }.awaitAll()
                        val aggregated = bulletinLists.flatten()
                            .distinctBy { it.id }
                            .sortedByDescending { it.createdAt ?: "" }
                        if (aggregated.isNotEmpty() && account == tcClient.accountKey()) {
                            cacheMgr.saveHomeBulletins(aggregated)
                            anyUpdated = true
                        }
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        // 2. 超星学习通同步（仅双平台模式开启、已登录且未在冷却期且超过后台节流间隔时顺序同步）
        val cxJob = async {
            try {
                if (!TronClassPreferences.getInstance(appContext).isDualPlatformModeEnabled()) {
                    return@async false
                }
                if (ChaoXingRequestGuard.isCircuitOpen(appContext)) {
                    return@async false
                }
                val cxClient = ChaoXingClient(appContext)
                if (!cxClient.isLoggedIn()) return@async false
                val cxAccount = AuthPreferences.currentStudentId(appContext)
                if (ChaoXingRequestGuard.isBackgroundSyncFresh(cxAccount)) {
                    return@async false
                }
                val todosRes = cxClient.fetchTodos(resolveMissingDeadlines = false)
                val noticesRes = if (!ChaoXingRequestGuard.isCircuitOpen(appContext)) {
                    cxClient.fetchNotices()
                } else {
                    Result.failure(Exception("风控保护冷却中"))
                }
                if (todosRes.isSuccess || noticesRes.isSuccess) {
                    ChaoXingRequestGuard.markBackgroundSyncNow(cxAccount)
                    anyUpdated = true
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        tcJob.await()
        cxJob.await()

        WidgetUpdateHelper.updateAllWidgets(appContext)
        anyUpdated
    }
}
