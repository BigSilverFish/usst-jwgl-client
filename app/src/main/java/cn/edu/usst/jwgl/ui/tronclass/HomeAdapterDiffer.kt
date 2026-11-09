package cn.edu.usst.jwgl.ui.tronclass

import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

internal data class HomeAdapterRow<T>(val item: T, val multiPlatform: Boolean)

/** Include presentation mode in the diff so existing holders receive badge changes too. */
internal class HomeAdapterDiffer<T>(adapter: RecyclerView.Adapter<*>, id: (T) -> String) {
    private val differ = AsyncListDiffer(adapter, object : DiffUtil.ItemCallback<HomeAdapterRow<T>>() {
        override fun areItemsTheSame(old: HomeAdapterRow<T>, new: HomeAdapterRow<T>) = id(old.item) == id(new.item)
        override fun areContentsTheSame(old: HomeAdapterRow<T>, new: HomeAdapterRow<T>) = old == new
    })
    var submittedItems: List<T> = emptyList()
        private set
    private var submittedMode = false

    fun submit(items: List<T>, multiPlatform: Boolean) {
        if (submittedItems == items && submittedMode == multiPlatform) return
        submittedItems = items.toList()
        submittedMode = multiPlatform
        // Account/session clears must remove the previous rows before returning, not after a background diff.
        differ.submitList(if (submittedItems.isEmpty()) null else submittedItems.map { HomeAdapterRow(it, multiPlatform) })
    }

    val size get() = differ.currentList.size
    operator fun get(position: Int) = differ.currentList[position]
}
