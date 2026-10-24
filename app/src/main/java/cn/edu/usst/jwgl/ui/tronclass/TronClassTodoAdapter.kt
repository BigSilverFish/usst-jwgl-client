package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.databinding.ItemTronclassTodoBinding

class TronClassTodoAdapter(
    private var items: List<TronClassTodo> = emptyList(),
    private val onItemClick: (TronClassTodo) -> Unit
) : RecyclerView.Adapter<TronClassTodoAdapter.TodoViewHolder>() {

    fun updateData(newItems: List<TronClassTodo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TodoViewHolder {
        val binding = ItemTronclassTodoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TodoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TodoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TodoViewHolder(private val binding: ItemTronclassTodoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(todo: TronClassTodo) {
            val context = binding.root.context

            binding.tvCourseName.text = todo.getCleanCourseName()
            binding.tvTodoTitle.text = todo.getCleanTitle()
            binding.tvDeadline.text = "截止: ${todo.getFormattedDeadline()}"

            val remainingHours = todo.getRemainingHours()
            val remainingText = todo.getRemainingTimeText()
            binding.tvCountdownChip.text = remainingText

            // Style countdown chip based on urgency
            val chipBg = GradientDrawable().apply {
                cornerRadius = 24f
            }

            when {
                todo.isOverdue() -> {
                    chipBg.setColor(Color.parseColor("#FEE2E2"))
                    binding.tvCountdownChip.background = chipBg
                    binding.tvCountdownChip.setTextColor(Color.parseColor("#991B1B"))
                    binding.tvTypeTag.text = "已截止"
                    binding.tvTypeTag.setTextColor(Color.parseColor("#991B1B"))
                }
                todo.isUrgent() -> {
                    chipBg.setColor(Color.parseColor("#FEE2E2"))
                    binding.tvCountdownChip.background = chipBg
                    binding.tvCountdownChip.setTextColor(Color.parseColor("#DC2626"))
                    binding.tvTypeTag.text = "紧急待交"
                    binding.tvTypeTag.setTextColor(Color.parseColor("#DC2626"))
                }
                remainingHours <= 72 -> {
                    chipBg.setColor(Color.parseColor("#FEF3C7"))
                    binding.tvCountdownChip.background = chipBg
                    binding.tvCountdownChip.setTextColor(Color.parseColor("#92400E"))
                    binding.tvTypeTag.text = "待提交"
                    binding.tvTypeTag.setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
                else -> {
                    chipBg.setColor(Color.parseColor("#DBEAFE"))
                    binding.tvCountdownChip.background = chipBg
                    binding.tvCountdownChip.setTextColor(Color.parseColor("#1E40AF"))
                    binding.tvTypeTag.text = "待提交"
                    binding.tvTypeTag.setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
            }

            binding.root.setOnClickListener {
                onItemClick(todo)
            }
        }
    }
}
