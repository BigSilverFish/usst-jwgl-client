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

    private val differ = HomeAdapterDiffer<UnifiedBulletin>(this) { it.unifiedId }
    private var multiPlatformMode = false
    var isMultiPlatformMode: Boolean
        get() = multiPlatformMode
        set(value) { updateUnifiedData(differ.submittedItems, value) }
    var onChaoXingClickListener: ((UnifiedBulletin) -> Unit)? = null
    var onUnifiedClickListener: ((UnifiedBulletin) -> Unit)? = null

    fun updateData(newItems: List<TronClassBulletin>) {
        updateUnifiedData(newItems.map { UnifiedBulletin.fromTronClass(it) })
    }

    fun updateUnifiedData(newItems: List<UnifiedBulletin>, multiPlatform: Boolean = false) {
        multiPlatformMode = multiPlatform
        differ.submit(newItems, multiPlatform)
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
        val row = differ[position]
        holder.bind(row.item, row.multiPlatform)
    }

    override fun getItemCount(): Int = differ.size

    override fun onViewRecycled(holder: BulletinViewHolder) {
        holder.clearImages()
        super.onViewRecycled(holder)
    }

    private data class ThumbnailSlot(val card: MaterialCardView, val image: ImageView, var url: String? = null)

    inner class BulletinViewHolder(private val binding: ItemTronclassBulletinBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val thumbnails = mutableListOf<ThumbnailSlot>()
        private var singleUrl: String? = null
        private var renderedUrls: List<String>? = null
        private var boundBulletin: UnifiedBulletin? = null

        init { bindImageClicks(binding.cardSingleImage) { singleUrl } }

        fun clearImages() {
            TronClassImageLoader.clear(binding.ivSingleImage)
            singleUrl = null
            thumbnails.forEach {
                TronClassImageLoader.clear(it.image)
                it.url = null
                it.card.visibility = View.GONE
            }
            renderedUrls = null
        }

        fun bind(b: UnifiedBulletin, multiPlatform: Boolean) {
            boundBulletin = b

            // 1. 课程名外标签 (有课程名时显示，无时隐藏)
            val cName = b.courseName.trim()
            if (cName.isNotBlank() && cName != "全校通知") {
                binding.layoutBulletinCourseTag.visibility = View.VISIBLE
                binding.tvBulletinCourseName.text = cName
            } else {
                binding.layoutBulletinCourseTag.visibility = View.GONE
            }

            // 2. 标题行与平台微徽标
            if (multiPlatform) {
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

            bindImages(b.imageUrls)

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

        private fun bindImages(urls: List<String>) {
            if (renderedUrls == urls) return
            val context = binding.root.context
            if (urls.isEmpty()) {
                clearImages()
                binding.layoutImagesPreview.visibility = View.GONE
            } else if (urls.size == 1) {
                binding.layoutImagesPreview.visibility = View.VISIBLE
                binding.cardSingleImage.visibility = View.VISIBLE
                binding.layoutMultiImages.visibility = View.GONE
                thumbnails.forEach {
                    if (it.url != null) TronClassImageLoader.clear(it.image)
                    it.url = null
                    it.card.visibility = View.GONE
                }
                if (singleUrl != urls[0]) {
                    singleUrl = urls[0]
                    TronClassImageLoader.load(binding.ivSingleImage, urls[0], context, 720, 360)
                }
            } else {
                binding.layoutImagesPreview.visibility = View.VISIBLE
                binding.cardSingleImage.visibility = View.GONE
                binding.layoutMultiImages.visibility = View.VISIBLE
                binding.tvImageCountBadge.text = "共 ${urls.size} 张附图 · 点击查看全部"
                if (singleUrl != null) TronClassImageLoader.clear(binding.ivSingleImage)
                singleUrl = null
                val thumbSize = (84 * context.resources.displayMetrics.density).toInt()
                urls.take(6).forEachIndexed { index, url ->
                    val slot = thumbnails.getOrNull(index) ?: createThumbnail(thumbSize).also { thumbnails.add(it) }
                    slot.card.visibility = View.VISIBLE
                    if (slot.url != url) {
                        slot.url = url
                        TronClassImageLoader.load(slot.image, url, context, thumbSize * 2, thumbSize * 2)
                    }
                }
                thumbnails.drop(urls.size.coerceAtMost(6)).forEach {
                    if (it.url != null) TronClassImageLoader.clear(it.image)
                    it.url = null
                    it.card.visibility = View.GONE
                }
            }
            renderedUrls = urls.toList()
        }

        private fun createThumbnail(size: Int): ThumbnailSlot {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * density).toInt() }
                radius = 8 * density
                cardElevation = 0f
                strokeWidth = (1 * density).toInt()
                strokeColor = context.getColor(R.color.card_stroke)
                setCardBackgroundColor(0xFF1E293B.toInt())
            }
            val image = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(image)
            binding.llThumbnailsContainer.addView(card)
            return ThumbnailSlot(card, image).also { slot -> bindImageClicks(card) { slot.url } }
        }

        private fun bindImageClicks(view: View, url: () -> String?) {
            view.setOnClickListener {
                val imageUrl = url() ?: return@setOnClickListener
                val bulletin = boundBulletin ?: return@setOnClickListener
                TronClassImageHelper.showFullScreenPreview(view.context, imageUrl, title = bulletin.title, courseName = bulletin.courseName)
            }
            view.setOnLongClickListener {
                val imageUrl = url() ?: return@setOnLongClickListener false
                val bulletin = boundBulletin ?: return@setOnLongClickListener false
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                TronClassImageHelper.showImageOptionsDialog(view.context, imageUrl, title = bulletin.title, courseName = bulletin.courseName) {
                    TronClassImageHelper.showFullScreenPreview(view.context, imageUrl, title = bulletin.title, courseName = bulletin.courseName)
                }
                true
            }
        }
    }
}
