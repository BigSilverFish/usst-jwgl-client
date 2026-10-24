package cn.edu.usst.jwgl.ui.tronclass

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.databinding.ItemTronclassDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TronClassDownloadAdapter(
    private var items: List<TronClassDownloadedItem> = emptyList(),
    private val onItemClick: (TronClassDownloadedItem) -> Unit,
    private val onShareClick: (TronClassDownloadedItem) -> Unit,
    private val onDeleteClick: (TronClassDownloadedItem) -> Unit
) : RecyclerView.Adapter<TronClassDownloadAdapter.ViewHolder>() {

    fun updateData(newItems: List<TronClassDownloadedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTronclassDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTronclassDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TronClassDownloadedItem) {
            binding.tvFileName.text = item.fileName
            binding.tvCourseName.text = if (item.courseName.isNotBlank()) item.courseName else "未分类课程"

            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.downloadTime))
            binding.tvFileInfo.text = "${item.getFormattedSize()} · $dateStr"

            // 根据扩展名设置图标染色
            val ext = item.getExtension()
            when (ext) {
                "pdf" -> binding.ivFileIcon.setColorFilter(binding.root.context.getColor(R.color.grade_red_text))
                "doc", "docx" -> binding.ivFileIcon.setColorFilter(binding.root.context.getColor(R.color.grade_blue_text))
                "ppt", "pptx" -> binding.ivFileIcon.setColorFilter(binding.root.context.getColor(R.color.grade_orange_text))
                "xls", "xlsx" -> binding.ivFileIcon.setColorFilter(binding.root.context.getColor(R.color.grade_green_text))
                else -> binding.ivFileIcon.setColorFilter(binding.root.context.getColor(R.color.primary))
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnShare.setOnClickListener { onShareClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
