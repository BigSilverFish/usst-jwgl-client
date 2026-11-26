package cn.edu.usst.jwgl.ui.tronclass

import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * 列表行差分包装模型，将展示模式与节流时间桶纳入内容比对，
 * 避免切换平台模式或跨分钟刷新倒计时时遗漏重绑。
 */
internal data class HomeAdapterRow<T>(
    val item: T,
    val multiPlatform: Boolean,
    val presentationBucket: Long = 0L
)

/**
 * 基于 AsyncListDiffer 的后台差分提交器：
 * - 内容与展示配置未变化时直接跳过提交；
 * - 清空列表时传入 null 触发同步快速清空，避免切号残留。
 */
internal class HomeAdapterDiffer<T : Any>(
    adapter: RecyclerView.Adapter<*>,
    private val idSelector: (T) -> Any
) {
    private var lastSubmitted: List<HomeAdapterRow<T>> = emptyList()

    private val differ = AsyncListDiffer(
        adapter,
        object : DiffUtil.ItemCallback<HomeAdapterRow<T>>() {
            override fun areItemsTheSame(
                oldItem: HomeAdapterRow<T>,
                newItem: HomeAdapterRow<T>
            ): Boolean = idSelector(oldItem.item) == idSelector(newItem.item)

            override fun areContentsTheSame(
                oldItem: HomeAdapterRow<T>,
                newItem: HomeAdapterRow<T>
            ): Boolean = oldItem == newItem
        }
    )

    val itemCount: Int
        get() = differ.currentList.size

    fun getItem(position: Int): HomeAdapterRow<T> = differ.currentList[position]

    fun submit(items: List<T>, multiPlatform: Boolean, presentationBucket: Long = 0L) {
        val next = items.map { HomeAdapterRow(it, multiPlatform, presentationBucket) }
        if (next == lastSubmitted) return
        lastSubmitted = next
        differ.submitList(if (next.isEmpty()) null else next)
    }
}
