package cn.edu.usst.jwgl.data.network

/** A partial or changing result must never replace the last complete timetable. */
internal suspend fun <T> completeSchedulePages(
    key: (T) -> Any,
    pageSize: Int = 1000,
    maxPages: Int = 200,
    fetch: suspend (offset: Int, rows: Int) -> Pair<Int, List<T>>
): List<T> {
    val items = linkedMapOf<Any, T>()
    var expected: Int? = null
    var offset = 0
    repeat(maxPages) {
        val (total, page) = fetch(offset, pageSize)
        check(total >= 0 && (expected == null || expected == total)) { "课表总数发生变化，请重新同步" }
        expected = total
        check(page.size <= pageSize && offset + page.size <= total) { "课表分页数量不一致" }
        for (item in page) {
            check(items.put(key(item), item) == null) { "课表分页包含重复记录，未保存不完整数据" }
        }
        offset += page.size
        if (items.size == total) return items.values.toList()
        check(page.isNotEmpty()) { "课表同步提前结束（${items.size}/$total），已保留原缓存" }
    }
    error("课表分页超过上限，未覆盖原缓存")
}
