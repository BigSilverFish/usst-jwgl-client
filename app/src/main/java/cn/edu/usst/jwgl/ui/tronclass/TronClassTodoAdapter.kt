package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.data.model.UnifiedTodo
import cn.edu.usst.jwgl.databinding.ItemTronclassTodoBinding

class TronClassTodoAdapter(
    private val onTronClassClick: ((TronClassTodo) -> Unit)? = null
) : RecyclerView.Adapter<TronClassTodoAdapter.TodoViewHolder>() {

    private var items: List<UnifiedTodo> = emptyList()
    var isMultiPlatformMode: Boolean = false
    var onChaoXingClickListener: ((UnifiedTodo) -> Unit)? = null
    var onUnifiedClickListener: ((UnifiedTodo) -> Unit)? = null

    fun updateData(newItems: List<TronClassTodo>) {
        this.items = newItems.map { UnifiedTodo.fromTronClass(it) }
        this.isMultiPlatformMode = false
        notifyDataSetChanged()
    }

    fun updateUnifiedData(newItems: List<UnifiedTodo>, multiPlatform: Boolean = false) {
        this.items = newItems
        this.isMultiPlatformMode = multiPlatform
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

        fun bind(todo: UnifiedTodo) {
            val context = binding.root.context

            // 1. 课程名称与平台徽章 (支持双轨模式动态徽标)
            if (isMultiPlatformMode) {
                val badgeText = if (todo.platform.isChaoXing) "【学习通】" else "【畅课】"
                val badgeColor = todo.platform.getBrandColor()
                val ssb = SpannableStringBuilder(badgeText).apply {
                    setSpan(ForegroundColorSpan(badgeColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append(todo.cleanCourseName)
                binding.tvCourseName.text = ssb
            } else {
                binding.tvCourseName.text = todo.cleanCourseName
            }

            binding.tvTodoTitle.text = todo.cleanTitle
            binding.tvDeadline.text = "截止: ${todo.getFormattedDeadline()}"

            // 2. 倒计时 Chip 与紧急程度色彩系统
            val remainingHours = todo.getRemainingHours()
            val remainingText = todo.getRemainingTimeText()
            binding.tvCountdownChip.text = remainingText

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
                    binding.tvTypeTag.text = todo.typeDisplayName.ifBlank { "待提交" }
                    binding.tvTypeTag.setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
                else -> {
                    chipBg.setColor(Color.parseColor("#DBEAFE"))
                    binding.tvCountdownChip.background = chipBg
                    binding.tvCountdownChip.setTextColor(Color.parseColor("#1E40AF"))
                    binding.tvTypeTag.text = todo.typeDisplayName.ifBlank { "待提交" }
                    binding.tvTypeTag.setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
            }

            // 3. 双轨点击分发
            binding.root.setOnClickListener {
                if (onUnifiedClickListener != null) {
                    onUnifiedClickListener?.invoke(todo)
                } else {
                    when (todo.platform) {
                        LearningPlatform.TRONCLASS -> {
                            val raw = todo.rawTronClassTodo ?: TronClassTodo(
                                id = todo.originalTodoId.toLongOrNull() ?: 0L,
                                courseId = todo.originalCourseId.toLongOrNull() ?: 0L,
                                courseName = todo.courseName,
                                title = todo.title,
                                type = todo.type,
                                isLocked = todo.isLocked
                            )
                            onTronClassClick?.invoke(raw)
                        }
                        LearningPlatform.CHAOXING -> {
                            onChaoXingClickListener?.invoke(todo)
                        }
                        LearningPlatform.DUAL -> {
                            if (todo.rawTronClassTodo != null) {
                                onTronClassClick?.invoke(todo.rawTronClassTodo)
                            } else {
                                onChaoXingClickListener?.invoke(todo)
                            }
                        }
                    }
                }
            }
        }
    }
}
