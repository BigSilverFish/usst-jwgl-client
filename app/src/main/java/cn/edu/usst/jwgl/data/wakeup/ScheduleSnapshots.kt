package cn.edu.usst.jwgl.data.wakeup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal data class ScheduleSnapshot(val table: TableBean, val times: List<TimeDetailBean>,
                                     val courses: List<CourseBean>, val revision: Long)

internal object ScheduleSnapshots {
    private val revision = AtomicLong()
    private var cached: ScheduleSnapshot? = null
    private var requestedTable = Int.MIN_VALUE

    fun invalidate() { revision.incrementAndGet() }

    suspend fun load(context: Context, tableId: Int): ScheduleSnapshot = withContext(Dispatchers.IO) {
        synchronized(this@ScheduleSnapshots) {
            val db = AppDatabase.getDatabase(context.applicationContext)
            var value = cached
            while (value == null || requestedTable != tableId || value.revision != revision.get()) {
                val before = revision.get()
                val table = if (tableId > 0) db.tableDao.getTableById(tableId) ?: db.tableDao.getDefaultTable()
                    else db.tableDao.getDefaultTable()
                value = ScheduleSnapshot(table, db.timeDetailDao.getTimeDetails(table.timeTable),
                    db.courseBaseDao.getCourseOfTable(table.id), before)
                requestedTable = tableId
                cached = value
            }
            // Mutable edit-dialog models must not modify the shared cache in place.
            value.copy(table = value.table.copy(), times = value.times.map { it.copy() }, courses = value.courses.map { it.copy() })
        }
    }
}
