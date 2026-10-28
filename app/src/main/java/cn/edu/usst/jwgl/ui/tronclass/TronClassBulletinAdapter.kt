package cn.edu.usst.jwgl.ui.tronclass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import cn.edu.usst.jwgl.databinding.ItemTronclassBulletinBinding
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TronClassBulletinAdapter(
    private var items: List<TronClassBulletin> = emptyList(),
    private val onItemClick: ((TronClassBulletin) -> Unit)? = null
) : RecyclerView.Adapter<TronClassBulletinAdapter.BulletinViewHolder>() {

    fun updateData(newItems: List<TronClassBulletin>) {
        items = newItems
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

        fun bind(b: TronClassBulletin) {
            val cName = b.courseName?.trim()
            if (!cName.isNullOrBlank()) {
                binding.layoutBulletinCourseTag.visibility = View.VISIBLE
                binding.tvBulletinCourseName.text = cName
            } else {
                binding.layoutBulletinCourseTag.visibility = View.GONE
            }

            binding.tvBulletinTitle.text = if (!b.title.isNullOrBlank()) b.title else "课程通知"

            val dateStr = formatIsoDate(b.createdAt)
            binding.tvBulletinDate.text = dateStr

            val rawContent = b.content ?: ""
            val cleanText = TronClassHtmlHelper.getCleanText(rawContent)
            binding.tvBulletinContent.text = cleanText.ifBlank { "（包含图片或表格内容，点击查看详情）" }

            val imageUrls = TronClassHtmlHelper.extractImageUrls(rawContent)
            val context = binding.root.context

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

            binding.root.setOnClickListener {
                onItemClick?.invoke(b)
            }
        }

        private fun formatIsoDate(iso: String?): String {
            if (iso.isNullOrBlank()) return ""
            return try {
                val inFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = inFormat.parse(iso)
                val outFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
                    timeZone = TimeZone.getDefault()
                }
                if (date != null) outFormat.format(date) else iso
            } catch (e: Exception) {
                iso.take(10)
            }
        }
    }
}
