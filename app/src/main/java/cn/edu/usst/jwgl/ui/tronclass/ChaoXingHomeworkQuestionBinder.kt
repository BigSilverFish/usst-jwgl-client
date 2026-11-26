package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.ChaoXingQuestionItem
import cn.edu.usst.jwgl.databinding.ActivityTronclassHomeworkBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassOptionCardBinding

/**
 * 超星作业/测验题目列表动态渲染绑定器
 */
internal object ChaoXingHomeworkQuestionBinder {
    fun renderChaoXingQuestions(fragment: Fragment, b: ActivityTronclassHomeworkBinding, questions: List<ChaoXingQuestionItem>, onCreateAttachmentView: (ChaoXingCourseAttachment) -> View) {
        val context = fragment.context ?: return
        val density = fragment.resources.displayMetrics.density

        b.llChaoXingQuestions.removeAllViews()
        b.llChaoXingQuestions.visibility = View.VISIBLE
        b.tvRequirementDesc.visibility = View.GONE

        val primaryColor = ContextCompat.getColor(context, R.color.primary)
        val textPrimaryColor = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
        val textTertiaryColor = ContextCompat.getColor(context, R.color.text_tertiary)
        val surfaceColor = ContextCompat.getColor(context, R.color.surface)
        val borderColor = ContextCompat.getColor(context, R.color.border)
        val surfaceContainerHigh = ContextCompat.getColor(context, R.color.surface_container_high)

        for ((idx, q) in questions.withIndex()) {
            val qRoot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = (16 * density).toInt()
                }
            }

            // 1. 顶部 Header (题号、题型 Badge、分值)
            val headerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 题型 Badge
            val typeBadge = TextView(context).apply {
                text = q.typeName
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
                setBackgroundResource(R.drawable.badge_bg)
                when (q.typeName) {
                    "单选题", "判断题" -> {
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#158C1D27"))
                        setTextColor(Color.parseColor("#8C1D27"))
                    }
                    "多选题" -> {
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#150284C7"))
                        setTextColor(Color.parseColor("#0284C7"))
                    }
                    else -> {
                        backgroundTintList = ColorStateList.valueOf(surfaceContainerHigh)
                        setTextColor(textSecondaryColor)
                    }
                }
            }
            headerLayout.addView(typeBadge)

            // 题号
            val indexTv = TextView(context).apply {
                text = "第 ${q.index} 题"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondaryColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * density).toInt()
                }
            }
            headerLayout.addView(indexTv)

            // 弹性空白
            headerLayout.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            // 分值
            val scoreStr = q.scoreText.orEmpty().trim()
            if (scoreStr.isNotBlank()) {
                val pointTv = TextView(context).apply {
                    text = scoreStr
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(primaryColor)
                }
                headerLayout.addView(pointTv)
            }
            qRoot.addView(headerLayout)

            // 2. 题干 Stem (富文本解析，完整支持图片与公式渲染)
            val stemTv = TextView(context).apply {
                val stemText = when {
                    !q.stemHtml.isNullOrBlank() -> q.stemHtml.orEmpty()
                    !q.cleanStem.isNullOrBlank() -> q.cleanStem.orEmpty()
                    else -> ""
                }
                val styledStemText = TronClassHtmlHelper.cleanHtmlStylesPreservingImages(stemText)
                val spanned = try {
                    val imgGetter = TronClassHtmlImageGetter(this, styledStemText)
                    Html.fromHtml(styledStemText, Html.FROM_HTML_MODE_LEGACY, imgGetter, null).trimEnd()
                } catch (_: Exception) {
                    styledStemText
                }
                text = if (spanned.isNotBlank()) spanned else q.cleanStem.orEmpty()
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimaryColor)
                setTextIsSelectable(true)
                setLineSpacing(3f * density, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            }
            qRoot.addView(stemTv)

            // 3. 选项卡片 (若为客观选择题)
            val qOptions = q.safeOptions()
            if (qOptions.isNotEmpty()) {
                val optionsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                }

                val isMulti = q.typeCode == "1" || q.typeName == "多选题"
                val myAnsClean = q.cleanMyAnswer.orEmpty().trim()
                val rightAnsClean = q.correctAnswer.orEmpty().trim()

                for (opt in qOptions) {
                    val optBinding = ItemTronclassOptionCardBinding.inflate(
                        LayoutInflater.from(context),
                        optionsContainer,
                        false
                    )

                    optBinding.tvOptionBadge.text = opt.letter
                    val optHtml = opt.content ?: ""
                    val optSpanned = try {
                        val imgGetter = TronClassHtmlImageGetter(optBinding.tvOptionContent, optHtml)
                        Html.fromHtml(optHtml, Html.FROM_HTML_MODE_LEGACY, imgGetter, null).trimEnd()
                    } catch (_: Exception) {
                        optHtml
                    }
                    optBinding.tvOptionContent.text = optSpanned

                    if (isMulti) {
                        optBinding.rbOption.visibility = View.GONE
                        optBinding.cbOption.visibility = View.VISIBLE
                    } else {
                        optBinding.rbOption.visibility = View.VISIBLE
                        optBinding.cbOption.visibility = View.GONE
                    }

                    // 选项判定与复习模式样式
                    val isMy = opt.isSelected || (myAnsClean.contains(opt.letter) && opt.letter.isNotBlank())
                    val isCor = opt.isCorrect || (rightAnsClean.contains(opt.letter) && opt.letter.isNotBlank())

                    when {
                        isCor && isMy -> {
                            // 答对！学生选择且是标准答案
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#2E7D32")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✓ 您的选择 (正确)"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C8E6C9"))
                        }
                        isCor -> {
                            // 标准答案 (学生未选)
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#4CAF50")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✓ 标准答案"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#2E7D32"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C8E6C9"))
                        }
                        isMy -> {
                            // 答错！学生选择但非标准答案
                            optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
                            optBinding.cardOptionRoot.strokeColor = Color.parseColor("#C62828")
                            optBinding.cardOptionRoot.strokeWidth = (1.5f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = true
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            } else {
                                optBinding.rbOption.isChecked = true
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                            optBinding.tvOptionContent.setTextColor(Color.parseColor("#C62828"))
                            optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)

                            optBinding.tvOptionReviewTag.visibility = View.VISIBLE
                            optBinding.tvOptionReviewTag.text = "✕ 您的选择"
                            optBinding.tvOptionReviewTag.setTextColor(Color.parseColor("#C62828"))
                            optBinding.tvOptionReviewTag.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFCDD2"))
                        }
                        else -> {
                            // 普通未选项
                            optBinding.cardOptionRoot.setCardBackgroundColor(surfaceColor)
                            optBinding.cardOptionRoot.strokeColor = borderColor
                            optBinding.cardOptionRoot.strokeWidth = (1.0f * density).toInt()
                            if (isMulti) {
                                optBinding.cbOption.isChecked = false
                                optBinding.cbOption.buttonTintList = ColorStateList.valueOf(textTertiaryColor)
                            } else {
                                optBinding.rbOption.isChecked = false
                                optBinding.rbOption.buttonTintList = ColorStateList.valueOf(textTertiaryColor)
                            }
                            optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(surfaceContainerHigh)
                            optBinding.tvOptionBadge.setTextColor(textSecondaryColor)
                            optBinding.tvOptionContent.setTextColor(textPrimaryColor)
                            optBinding.tvOptionContent.setTypeface(null, Typeface.NORMAL)
                            optBinding.tvOptionReviewTag.visibility = View.GONE
                        }
                    }

                    optionsContainer.addView(optBinding.root)
                }
                qRoot.addView(optionsContainer)
            }

            // 4. 底部作答与解析结果卡片 (若有作答或标准答案)
            val hasAnswerInfo = q.cleanMyAnswer.orEmpty().isNotBlank() || q.correctAnswer.orEmpty().isNotBlank() || q.explanation.orEmpty().isNotBlank()
            if (hasAnswerInfo) {
                val resultBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (10 * density).toInt()
                    }
                }

                val rowAnswers = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val rawMyAns = if (q.myAnswerHtml.contains("<img", ignoreCase = true)) q.myAnswerHtml else q.cleanMyAnswer.orEmpty()
                if (rawMyAns.isNotBlank()) {
                    val myAnsTv = TextView(context).apply {
                        val getter = TronClassHtmlImageGetter(this, rawMyAns)
                        val spanned = try {
                            Html.fromHtml("<b>我的作答:</b> $rawMyAns", Html.FROM_HTML_MODE_LEGACY, getter, null).trimEnd()
                        } catch (_: Exception) {
                            "我的作答: $rawMyAns"
                        }
                        text = spanned
                        textSize = 13f
                        setTextColor(textPrimaryColor)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(
                            if (rawMyAns.contains("<img") || rawMyAns.length > 30) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            if (rawMyAns.contains("<img") || rawMyAns.length > 30) 0f else 1f
                        )
                    }
                    if (rawMyAns.contains("<img") || rawMyAns.length > 30) {
                        resultBox.addView(myAnsTv)
                    } else {
                        rowAnswers.addView(myAnsTv)
                    }
                }

                val rawRightAns = q.correctAnswer.orEmpty()
                if (rawRightAns.isNotBlank()) {
                    val rightAnsTv = TextView(context).apply {
                        val getter = TronClassHtmlImageGetter(this, rawRightAns)
                        val spanned = try {
                            Html.fromHtml("<b>标准答案:</b> $rawRightAns", Html.FROM_HTML_MODE_LEGACY, getter, null).trimEnd()
                        } catch (_: Exception) {
                            "标准答案: $rawRightAns"
                        }
                        text = spanned
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#2E7D32"))
                        setTextIsSelectable(true)
                        if (rawRightAns.contains("<img") || rawRightAns.length > 30) {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = (4 * density).toInt()
                            }
                        }
                    }
                    if (rawRightAns.contains("<img") || rawRightAns.length > 30) {
                        resultBox.addView(rightAnsTv)
                    } else {
                        rowAnswers.addView(rightAnsTv)
                    }
                }

                if (rowAnswers.childCount > 0) {
                    resultBox.addView(rowAnswers)
                }

                if (q.explanation.isNotBlank()) {
                    val expTv = TextView(context).apply {
                        val getter = TronClassHtmlImageGetter(this, q.explanation)
                        val spanned = try {
                            Html.fromHtml("<b>解析:</b> ${q.explanation}", Html.FROM_HTML_MODE_LEGACY, getter, null).trimEnd()
                        } catch (_: Exception) {
                            "解析: ${q.explanation}"
                        }
                        text = spanned
                        textSize = 12f
                        setTextColor(textSecondaryColor)
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (6 * density).toInt()
                        }
                    }
                    resultBox.addView(expTv)
                }

                qRoot.addView(resultBox)
            }

            // 5. 题目分割线 (除最后一题外)
            if (idx < questions.size - 1) {
                val divider = View(context).apply {
                    setBackgroundColor(borderColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt().coerceAtLeast(1)
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                }
                qRoot.addView(divider)
            }

            b.llChaoXingQuestions.addView(qRoot)
        }
    }

}
