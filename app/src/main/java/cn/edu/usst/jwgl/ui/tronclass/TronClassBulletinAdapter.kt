package cn.edu.usst.jwgl.ui.tronclass

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import cn.edu.usst.jwgl.data.model.UnifiedBulletin
import cn.edu.usst.jwgl.databinding.ItemTronclassBulletinBinding
import com.google.android.material.card.MaterialCardView

class TronClassBulletinAdapter(
    private val onTronClassClick: ((TronClassBulletin) -> Unit)? = null
) : RecyclerView.Adapter<TronClassBulletinAdapter.BulletinViewHolder>() {

    private var items: List<UnifiedBulletin> = emptyList()
    var isMultiPlatformMode: Boolean = false
    var onChaoXingClickListener: ((UnifiedBulletin) -> Unit)? = null
    var onUnifiedClickListener: ((UnifiedBulletin) -> Unit)? = null

    fun updateData(newItems: List<TronClassBulletin>) {
        this.items = newItems.map { UnifiedBulletin.fromTronClass(it) }
        this.isMultiPlatformMode = false
        notifyDataSetChanged()
    }

    fun updateUnifiedData(newItems: List<UnifiedBulletin>, multiPlatform: Boolean = false) {
        this.items = newItems
        this.isMultiPlatformMode = multiPlatform
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BulletinViewHolder {
        val binding = ItemTronclassBulletinBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BulletinViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BulletinViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class BulletinViewHolder(private val binding: ItemTronclassBulletinBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(b: UnifiedBulletin) {
            val context = binding.root.context

            // 1. 课程名外标签 (有课程名时显示，无时隐藏)
            val cName = b.courseName.trim()
            if (cName.isNotBlank() && cName != "全校通知") {
                binding.layoutBulletinCourseTag.visibility = View.VISIBLE
                binding.tvBulletinCourseName.text = cName
            } else {
                binding.layoutBulletinCourseTag.visibility = View.GONE
            }

            // 2. 标题行与平台微徽标
            if (isMultiPlatformMode) {
                val badgeText = if (b.platform.isChaoXing) "【学习通】" else "【畅课】"
                val badgeColor = b.platform.getBrandColor()
                val ssb = SpannableStringBuilder(badgeText).apply {
                    setSpan(ForegroundColorSpan(badgeColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append(b.title.ifBlank { "课程通知" })
                binding.tvBulletinTitle.text = ssb
            } else {
                binding.tvBulletinTitle.text = b.title.ifBlank { "课程通知" }
            }

            binding.tvBulletinDate.text = b.publishTimeText ?: ""

            // 3. 正文摘要纯文本
            binding.tvBulletinContent.text = b.cleanContent.ifBlank { "（包含图片或表格内容，点击查看详情）" }

            // 4. 附图与缩略图预览
            val imageUrls = b.imageUrls
            if (imageUrls.isEmpty()) {
                binding.layoutImagesPreview.visibility = View.GONE
            } else if (imageUrls.size == 1) {
                binding.layoutImagesPreview.visibility = View.VISIBLE
                binding.cardSingleImage.visibility = View.VISIBLE
                binding.layoutMultiImages.visibility = View.GONE

                TronClassImageLoader.load(
                    binding.ivSingleImage,
                    imageUrls[0],
                    context,
                    targetWidth = 720,
                    targetHeight = 360
                )
            } else {
                binding.layoutImagesPreview.visibility = View.VISIBLE
                binding.cardSingleImage.visibility = View.GONE
                binding.layoutMultiImages.visibility = View.VISIBLE
                binding.tvImageCountBadge.text = "共 ${imageUrls.size} 张附图 · 点击查看全部"

                binding.llThumbnailsContainer.removeAllViews()
                val density = context.resources.displayMetrics.density
                val thumbSizePx = (84 * density).toInt()
                val marginEndPx = (8 * density).toInt()
                val cornerRadiusPx = 8 * density

                for (url in imageUrls.take(6)) {
                    val card = MaterialCardView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbSizePx, thumbSizePx).apply {
                            setMargins(0, 0, marginEndPx, 0)
                        }
                        radius = cornerRadiusPx
                        cardElevation = 0f
                        strokeWidth = (1 * density).toInt()
                        strokeColor = context.getColor(R.color.card_stroke)
                        setCardBackgroundColor(0xFF1E293B.toInt())
                    }

                    val iv = ImageView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    card.addView(iv)
                    binding.llThumbnailsContainer.addView(card)

                    TronClassImageLoader.load(
                        iv,
                        url,
                        context,
                        targetWidth = thumbSizePx * 2,
                        targetHeight = thumbSizePx * 2
                    )
                }
            }

            // 5. 双轨点击分发
            binding.root.setOnClickListener {
                if (onUnifiedClickListener != null) {
                    onUnifiedClickListener?.invoke(b)
                } else {
                    when (b.platform) {
                        LearningPlatform.TRONCLASS -> {
                            val raw = b.rawTronClassBulletin ?: TronClassBulletin(
                                id = b.originalBulletinId.toLongOrNull() ?: 0L,
                                courseId = b.originalCourseId.toLongOrNull() ?: 0L,
                                title = b.title,
                                content = b.content,
                                courseName = b.courseName,
                                createdAt = b.publishTimeText
                            )
                            onTronClassClick?.invoke(raw)
                        }
                        LearningPlatform.CHAOXING -> {
                            onChaoXingClickListener?.invoke(b)
                        }
                        LearningPlatform.DUAL -> {
                            if (b.rawTronClassBulletin != null) {
                                onTronClassClick?.invoke(b.rawTronClassBulletin)
                            } else {
                                onChaoXingClickListener?.invoke(b)
                            }
                        }
                    }
                }
            }
        }
    }
}
