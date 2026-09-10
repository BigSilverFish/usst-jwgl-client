package cn.edu.usst.jwgl.util

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.WakeupScheduleImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InitialSyncHelper {
    private const val TAG = "InitialSyncHelper"

    /**
     * 执行初次登录全量学期课表、校历开学日与调休配置同步
     * @param context 上下文
     * @param studentId 学号，用于智能推断入学年份
     * @param onProgress 进度回调 (例如显示在登录加载文字或进度条中)
     * @return 成功同步入库的课表学期数量
     */
    suspend fun performInitialSync(
        context: Context,
        studentId: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        val authPrefs = AuthPreferences(context)
        val cacheManager = DataCacheManager(context)
        val db = AppDatabase.getDatabase(context)

        try {
            // 1. 同步远程校历与调休配置
            withContext(Dispatchers.Main) {
                onProgress?.invoke("正在同步校历配置与放假调休...")
            }
            try {
                RemoteConfigManager.fetchConfig(context)
                val config = RemoteConfigManager.getConfig()
                RemoteConfigManager.calibrateTablesAndSync(context, config)
            } catch (e: Exception) {
                Log.w(TAG, "Remote config sync failed, using built-in config", e)
            }

            // 2. 智能确定需要同步的所有学期清单
            val actualStudentId = studentId?.takeUnless { it.isBlank() } ?: authPrefs.getStudentId()
            val cachedProfile = cacheManager.getProfile()
            val enrollmentYear = actualStudentId.take(2).toIntOrNull()?.let { 2000 + it }
                ?: cachedProfile?.grade?.take(4)?.toIntOrNull()

            val semestersToSync = SemesterHelper.getSemestersToSync(enrollmentYear)
            Log.d(TAG, "Semesters to sync (count=${semestersToSync.size}): ${semestersToSync.map { it.title }}")

            var successfulSyncCount = 0
            val remoteSemesters = RemoteConfigManager.getSemesters()

            // 3. 循环拉取过往各学期与当前学期课表
            for ((index, sem) in semestersToSync.withIndex()) {
                withContext(Dispatchers.Main) {
                    onProgress?.invoke("正在同步课表 (${index + 1}/${semestersToSync.size}): ${sem.title}...")
                }

                try {
                    val result = JwglClient.fetchTimetable(sem.xnm, sem.xqm, context)
                    result.onSuccess { timetableData ->
                        // 寻找该学期对应的官方开学基准日期
                        val semNum = if (sem.xqm == "3") "1" else if (sem.xqm == "12") "2" else "3"
                        val matchedConfig = remoteSemesters.find {
                            it.semesterId == "${sem.xnm}-${(sem.xnm.toIntOrNull() ?: 2025) + 1}-$semNum" ||
                            it.semesterTitle.contains(sem.title) ||
                            (it.semesterId.contains(sem.xnm) && it.semesterId.endsWith(semNum))
                        }

                        val startDate = matchedConfig?.startDate?.takeIf { it.isNotBlank() }
                            ?: if (semNum == "1") "${sem.xnm}-09-07" else "${(sem.xnm.toIntOrNull() ?: 2025) + 1}-03-01"

                        // 仅当当前学期时设置为默认课表，过往学期保留在列表中供切换
                        val importedTable = WakeupScheduleImporter.importTimetableData(
                            db = db,
                            data = timetableData,
                            startDate = startDate,
                            setAsDefault = sem.isCurrent
                        )

                        // 缓存各学期数据
                        cacheManager.saveTimetable(sem.xnm, sem.xqm, timetableData)
                        successfulSyncCount++
                        Log.d(TAG, "Successfully synced ${sem.title}, courses=${timetableData.courses.size}, tableId=${importedTable.id}")
                    }.onFailure { err ->
                        Log.w(TAG, "Failed to sync ${sem.title}: ${err.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception syncing ${sem.title}", e)
                }
            }

            // 4. 考试日程与提醒预热（针对当前学期）
            val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
            try {
                val examRes = JwglClient.fetchExams(curXnm, curXqm, context)
                examRes.onSuccess { exams ->
                    cacheManager.saveExams(curXnm, curXqm, exams)
                    CourseReminderManager.scheduleExamReminders(context, exams)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync initial exams", e)
            }

            // 5. 标记全量初始化同步已完成
            authPrefs.setInitialSyncCompleted(true)

            withContext(Dispatchers.Main) {
                onProgress?.invoke("全部学期同步完成！")
            }

            Result.success(successfulSyncCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error in performInitialSync", e)
            Result.failure(e)
        }
    }
}
