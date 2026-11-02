package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassSubject
import cn.edu.usst.jwgl.data.model.TronClassSubjectOption
import cn.edu.usst.jwgl.databinding.ItemTronclassOptionCardBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassSubjectBinding
import java.util.Locale

class TronClassSubjectAdapter(
    private var subjects: List<TronClassSubject> = emptyList(),
    private var myAnswers: Map<String, Any> = emptyMap(),
    private var correctAnswers: Map<String, Any> = emptyMap(),
    private var isReviewMode: Boolean = true,
    private val onAnswerSelected: ((subjectId: Long, answer: String) -> Unit)? = null
) : RecyclerView.Adapter<TronClassSubjectAdapter.SubjectViewHolder>() {

    private val currentAnswers = mutableMapOf<String, Any>()

    fun updateData(
        newSubjects: List<TronClassSubject>,
        newMyAnswers: Map<String, Any>,
        newCorrectAnswers: Map<String, Any>,
        reviewMode: Boolean
    ) {
        subjects = newSubjects
        myAnswers = newMyAnswers
        correctAnswers = newCorrectAnswers
        isReviewMode = reviewMode
        currentAnswers.clear()
        currentAnswers.putAll(newMyAnswers)
        notifyDataSetChanged()
    }

    fun getCurrentAnswers(): Map<String, Any> = currentAnswers

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val binding = ItemTronclassSubjectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SubjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        holder.bind(subjects[position], position)
    }

    override fun getItemCount(): Int = subjects.size

    inner class SubjectViewHolder(private val binding: ItemTronclassSubjectBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(subject: TronClassSubject, position: Int) {
            val context = binding.root.context

            // 1. 顶部基础信息与类型标签
            binding.tvSubjectIndex.text = "第 ${position + 1} 题"
            binding.tvSubjectTypeTag.text = subject.getTypeName()
            
            val ptStr = subject.point?.let { 
                if (it % 1.0 == 0.0) "${it.toInt()} 分" else "$it 分" 
            } ?: ""
            binding.tvSubjectPoint.text = ptStr

            // 2. 评阅结果与得分标签
            if (isReviewMode) {
                binding.tvSubjectScoreStatus.visibility = View.VISIBLE
                val uScore = subject.userScore
                val fullPoint = subject.point
                val isCor = subject.isCorrect

                val scoreText = if (uScore != null) {
                    val scFormatted = if (uScore % 1.0 == 0.0) "${uScore.toInt()}" else "$uScore"
                    "得分: $scFormatted 分"
                } else if (isCor == true) {
                    "✓ 正确"
                } else if (isCor == false) {
                    "✕ 错误"
                } else {
                    "已批阅"
                }

                binding.tvSubjectScoreStatus.text = scoreText

                when {
                    isCor == true || (uScore != null && fullPoint != null && uScore >= fullPoint) -> {
                        binding.tvSubjectScoreStatus.setBackgroundColor(Color.parseColor("#E8F5E9"))
                        binding.tvSubjectScoreStatus.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    isCor == false || (uScore != null && uScore == 0.0) -> {
                        binding.tvSubjectScoreStatus.setBackgroundColor(Color.parseColor("#FFEBEE"))
                        binding.tvSubjectScoreStatus.setTextColor(Color.parseColor("#C62828"))
                    }
                    else -> {
                        binding.tvSubjectScoreStatus.setBackgroundColor(Color.parseColor("#FFF3E0"))
                        binding.tvSubjectScoreStatus.setTextColor(Color.parseColor("#EF6C00"))
                    }
                }
            } else {
                binding.tvSubjectScoreStatus.visibility = View.GONE
            }

            // 3. 题干富文本渲染 (HTML，支持异步图片解析与自适应大屏渲染，避免未知占位符方块)
            val rawTitle = subject.getCleanTitle()
            binding.tvSubjectTitle.tag = rawTitle
            val imageGetter = TronClassHtmlImageGetter(binding.tvSubjectTitle)
            val cleanTitleSpanned: CharSequence = try {
                Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_LEGACY, imageGetter, null).trimEnd()
            } catch (_: Exception) {
                rawTitle
            }
            binding.tvSubjectTitle.text = if (cleanTitleSpanned.isNotBlank()) cleanTitleSpanned else "第 ${position + 1} 题"

            // 4. 重置各区域容器
            binding.llOptionsContainer.removeAllViews()
            binding.llCustomAnswerContainer.removeAllViews()
            binding.llOptionsContainer.visibility = View.GONE
            binding.llCustomAnswerContainer.visibility = View.GONE
            binding.layoutAnswerResult.visibility = View.GONE
            binding.layoutSimpleAnswers.visibility = View.GONE
            binding.tvDetailedAnswer.visibility = View.GONE
            binding.tvTeacherComment.visibility = View.GONE
            binding.tvExplanation.visibility = View.GONE

            val sidStr = subject.id.toString()
            val userRaw = currentAnswers[sidStr] ?: subject.myAnswer ?: myAnswers[sidStr]
            val correctRaw = subject.correctAnswer ?: correctAnswers[sidStr]

            // 5. 按照不同题目类型分别渲染
            when (subject.type) {
                "single_selection", "multiple_selection", "true_false" -> {
                    renderChoiceSubject(subject, userRaw, correctRaw)
                }
                "fill_in_blank" -> {
                    renderFillInBlankSubject(subject, userRaw, correctRaw)
                }
                "short_answer", "essay" -> {
                    renderShortAnswerSubject(subject, userRaw, correctRaw)
                }
                else -> {
                    if (subject.options.isNotEmpty()) {
                        renderChoiceSubject(subject, userRaw, correctRaw)
                    } else {
                        renderShortAnswerSubject(subject, userRaw, correctRaw)
                    }
                }
            }
        }

        /**
         * 渲染单选题 / 多选题 / 判断题 原生组件选项卡片
         */
        private fun renderChoiceSubject(subject: TronClassSubject, userRaw: Any?, correctRaw: Any?) {
            val context = binding.root.context
            binding.llOptionsContainer.visibility = View.VISIBLE
            binding.llOptionsContainer.removeAllViews()

            val optionLetters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
            val userChosenOptIds = extractOptionIds(userRaw).toMutableSet()
            val correctOptIds = extractOptionIds(correctRaw).toMutableSet()

            // 如果后端标准答案集合为空，降级尝试从 options 中提取 isAnswer=true
            if (correctOptIds.isEmpty()) {
                subject.options.filter { it.isAnswer }.forEach { correctOptIds.add(it.id) }
            }

            val isMulti = subject.type == "multiple_selection"
            val isTrueFalse = subject.type == "true_false"
            val density = context.resources.displayMetrics.density

            // 预定义色彩
            val primaryColor = ContextCompat.getColor(context, R.color.primary)
            val surfaceColor = ContextCompat.getColor(context, R.color.surface)
            val borderColor = ContextCompat.getColor(context, R.color.border)
            val textPrimaryColor = ContextCompat.getColor(context, R.color.text_primary)
            val textSecondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
            val textTertiaryColor = ContextCompat.getColor(context, R.color.text_tertiary)
            val surfaceContainerHigh = ContextCompat.getColor(context, R.color.surface_container_high)

            // 缓存所有选项 CardBinding，便于点选时即时更新互斥状态
            val optionBindings = mutableListOf<ItemTronclassOptionCardBinding>()

            fun updateOptionCardVisual(
                optBinding: ItemTronclassOptionCardBinding,
                opt: TronClassSubjectOption,
                badgeText: String
            ) {
                val isMyChoice = userChosenOptIds.contains(opt.id)
                val isCorrectChoice = correctOptIds.contains(opt.id)

                if (isReviewMode) {
                    optBinding.cardOptionRoot.isClickable = false
                    optBinding.cardOptionRoot.isFocusable = false
                    optBinding.cardOptionRoot.foreground = null

                    when {
                        isCorrectChoice && isMyChoice -> {
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
                        isCorrectChoice -> {
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
                        isMyChoice -> {
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
                            // 未选中的普通选项
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
                            optBinding.tvOptionBadge.setTextColor(textTertiaryColor)
                            optBinding.tvOptionContent.setTextColor(textSecondaryColor)
                            optBinding.tvOptionContent.setTypeface(null, Typeface.NORMAL)

                            optBinding.tvOptionReviewTag.visibility = View.GONE
                        }
                    }
                } else {
                    // 作答状态 (Answering Mode)
                    optBinding.tvOptionReviewTag.visibility = View.GONE
                    if (isMyChoice) {
                        optBinding.cardOptionRoot.setCardBackgroundColor(Color.parseColor("#0C8C1D27"))
                        optBinding.cardOptionRoot.strokeColor = primaryColor
                        optBinding.cardOptionRoot.strokeWidth = (1.8f * density).toInt()

                        if (isMulti) {
                            optBinding.cbOption.isChecked = true
                            optBinding.cbOption.buttonTintList = ColorStateList.valueOf(primaryColor)
                        } else {
                            optBinding.rbOption.isChecked = true
                            optBinding.rbOption.buttonTintList = ColorStateList.valueOf(primaryColor)
                        }

                        optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(primaryColor)
                        optBinding.tvOptionBadge.setTextColor(Color.WHITE)
                        optBinding.tvOptionContent.setTextColor(textPrimaryColor)
                        optBinding.tvOptionContent.setTypeface(null, Typeface.BOLD)
                    } else {
                        optBinding.cardOptionRoot.setCardBackgroundColor(surfaceColor)
                        optBinding.cardOptionRoot.strokeColor = borderColor
                        optBinding.cardOptionRoot.strokeWidth = (1.0f * density).toInt()

                        if (isMulti) {
                            optBinding.cbOption.isChecked = false
                            optBinding.cbOption.buttonTintList = ColorStateList.valueOf(textSecondaryColor)
                        } else {
                            optBinding.rbOption.isChecked = false
                            optBinding.rbOption.buttonTintList = ColorStateList.valueOf(textSecondaryColor)
                        }

                        optBinding.tvOptionBadge.backgroundTintList = ColorStateList.valueOf(surfaceContainerHigh)
                        optBinding.tvOptionBadge.setTextColor(textSecondaryColor)
                        optBinding.tvOptionContent.setTextColor(textPrimaryColor)
                        optBinding.tvOptionContent.setTypeface(null, Typeface.NORMAL)
                    }
                }
            }

            // 循环构建各选项 CardView
            subject.options.forEachIndexed { idx, opt ->
                val optBinding = ItemTronclassOptionCardBinding.inflate(
                    LayoutInflater.from(context),
                    binding.llOptionsContainer,
                    false
                )
                optionBindings.add(optBinding)

                // 选项序号徽章文字
                val badgeText = if (isTrueFalse) {
                    val rawClean = opt.getCleanContent()
                    when {
                        rawClean.contains("正确") || rawClean.contains("对") || rawClean.equals("true", ignoreCase = true) || rawClean == "1" -> "✓"
                        rawClean.contains("错误") || rawClean.contains("错") || rawClean.equals("false", ignoreCase = true) || rawClean == "0" -> "✕"
                        idx < optionLetters.size -> optionLetters[idx]
                        else -> "${idx + 1}"
                    }
                } else {
                    if (idx < optionLetters.size) optionLetters[idx] else "${idx + 1}"
                }
                optBinding.tvOptionBadge.text = badgeText

                // 选项类型组件可见性 (RadioGroup 互斥 / CheckBox 多选)
                if (isMulti) {
                    optBinding.rbOption.visibility = View.GONE
                    optBinding.cbOption.visibility = View.VISIBLE
                } else {
                    optBinding.rbOption.visibility = View.VISIBLE
                    optBinding.cbOption.visibility = View.GONE
                }

                // 选项富文本 HTML 解析，并挂载图片加载器
                val imageGetter = TronClassHtmlImageGetter(optBinding.tvOptionContent)
                val optSpanned: CharSequence = try {
                    Html.fromHtml(opt.content ?: "", Html.FROM_HTML_MODE_LEGACY, imageGetter, null).trimEnd()
                } catch (_: Exception) {
                    opt.getCleanContent()
                }
                optBinding.tvOptionContent.text = if (optSpanned.isNotBlank()) optSpanned else opt.getCleanContent()

                // 应用当前状态视觉样式
                updateOptionCardVisual(optBinding, opt, badgeText)

                // 作答交互事件处理
                if (!isReviewMode) {
                    optBinding.cardOptionRoot.setOnClickListener {
                        optBinding.cardOptionRoot.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                        if (isMulti) {
                            // 多选题：点击切换当前项的选中与取消
                            if (userChosenOptIds.contains(opt.id)) {
                                userChosenOptIds.remove(opt.id)
                            } else {
                                userChosenOptIds.add(opt.id)
                            }
                            updateOptionCardVisual(optBinding, opt, badgeText)
                            val chosenList = userChosenOptIds.sorted()
                            currentAnswers[subject.id.toString()] = chosenList
                            onAnswerSelected?.invoke(subject.id, chosenList.joinToString(","))
                        } else {
                            // 单选题 / 判断题：单选互斥
                            userChosenOptIds.clear()
                            userChosenOptIds.add(opt.id)
                            // 即时更新同题目下的所有选项卡片状态
                            optionBindings.forEachIndexed { i, ob ->
                                val siblingOpt = subject.options[i]
                                val siblingBadge = if (isTrueFalse) {
                                    val sc = siblingOpt.getCleanContent()
                                    when {
                                        sc.contains("正确") || sc.contains("对") || sc.equals("true", ignoreCase = true) -> "✓"
                                        sc.contains("错误") || sc.contains("错") || sc.equals("false", ignoreCase = true) -> "✕"
                                        i < optionLetters.size -> optionLetters[i]
                                        else -> "${i + 1}"
                                    }
                                } else {
                                    if (i < optionLetters.size) optionLetters[i] else "${i + 1}"
                                }
                                updateOptionCardVisual(ob, siblingOpt, siblingBadge)
                            }
                            currentAnswers[subject.id.toString()] = opt.id
                            onAnswerSelected?.invoke(subject.id, opt.id.toString())
                        }
                    }
                }

                binding.llOptionsContainer.addView(optBinding.root)
            }

            // 结果与解析卡片 (评阅模式)
            if (isReviewMode && (userChosenOptIds.isNotEmpty() || correctOptIds.isNotEmpty())) {
                binding.layoutAnswerResult.visibility = View.VISIBLE
                binding.layoutSimpleAnswers.visibility = View.VISIBLE

                val myDisplay = mapIdsToDisplayAnswer(subject, userChosenOptIds)
                val corDisplay = mapIdsToDisplayAnswer(subject, correctOptIds)

                binding.tvMyAnswer.text = "我的作答: ${if (myDisplay.isNotBlank()) myDisplay else "未作答"}"
                if (corDisplay.isNotBlank()) {
                    binding.tvCorrectAnswer.visibility = View.VISIBLE
                    binding.tvCorrectAnswer.text = "标准答案: $corDisplay"
                } else {
                    binding.tvCorrectAnswer.visibility = View.GONE
                }

                if (!subject.explanation.isNullOrBlank()) {
                    binding.tvExplanation.visibility = View.VISIBLE
                    val cleanExp = try {
                        val expGetter = TronClassHtmlImageGetter(binding.tvExplanation)
                        Html.fromHtml(subject.explanation, Html.FROM_HTML_MODE_LEGACY, expGetter, null).trimEnd()
                    } catch (_: Exception) {
                        subject.explanation.trim()
                    }
                    binding.tvExplanation.text = "解析: $cleanExp"
                }
            }
        }

        /**
         * 渲染填空题 (fill_in_blank)
         */
        private fun renderFillInBlankSubject(subject: TronClassSubject, userRaw: Any?, correctRaw: Any?) {
            val userBlanks = extractStringList(userRaw)
            val correctBlanks = extractStringList(correctRaw)

            if (isReviewMode) {
                binding.layoutAnswerResult.visibility = View.VISIBLE
                binding.tvDetailedAnswer.visibility = View.VISIBLE

                val sb = SpannableStringBuilder()

                sb.append("我的作答:\n")
                if (userBlanks.isEmpty()) {
                    sb.append("  (未作答)\n")
                } else {
                    userBlanks.forEachIndexed { i, ans ->
                        val cleanAns = if (ans.isBlank()) "空" else ans
                        sb.append("  空 ${i + 1}: $cleanAns\n")
                    }
                }

                if (correctBlanks.isNotEmpty()) {
                    sb.append("\n标准答案:\n")
                    correctBlanks.forEachIndexed { i, ans ->
                        sb.append("  空 ${i + 1}: $ans\n")
                    }
                }

                binding.tvDetailedAnswer.text = sb.toString().trimEnd()

                if (!subject.teacherComment.isNullOrBlank()) {
                    binding.tvTeacherComment.visibility = View.VISIBLE
                    binding.tvTeacherComment.text = "教师评语: ${subject.teacherComment.trim()}"
                }

                if (!subject.explanation.isNullOrBlank()) {
                    binding.tvExplanation.visibility = View.VISIBLE
                    val cleanExp = try {
                        Html.fromHtml(subject.explanation, Html.FROM_HTML_MODE_LEGACY).trimEnd()
                    } catch (_: Exception) {
                        subject.explanation.trim()
                    }
                    binding.tvExplanation.text = "解析: $cleanExp"
                }
            }
        }

        /**
         * 渲染主观题 / 简答题 (short_answer / essay)
         */
        private fun renderShortAnswerSubject(subject: TronClassSubject, userRaw: Any?, correctRaw: Any?) {
            val userAnsText = when (userRaw) {
                is String -> userRaw
                is List<*> -> userRaw.joinToString("\n")
                else -> userRaw?.toString() ?: ""
            }.trim()

            val correctAnsText = when (correctRaw) {
                is String -> correctRaw
                is List<*> -> correctRaw.joinToString("\n")
                else -> correctRaw?.toString() ?: ""
            }.trim()

            if (isReviewMode) {
                binding.layoutAnswerResult.visibility = View.VISIBLE
                binding.tvDetailedAnswer.visibility = View.VISIBLE

                val sb = SpannableStringBuilder()
                sb.append("我的作答:\n")
                if (userAnsText.isBlank()) {
                    sb.append("  (未作答)\n")
                } else {
                    val cleanAns = try {
                        Html.fromHtml(userAnsText, Html.FROM_HTML_MODE_LEGACY).trimEnd()
                    } catch (_: Exception) {
                        userAnsText
                    }
                    sb.append("  $cleanAns\n")
                }

                val refText = if (correctAnsText.isNotBlank()) correctAnsText else subject.explanation
                if (!refText.isNullOrBlank()) {
                    sb.append("\n参考答案 / 答题要点:\n")
                    val cleanRef = try {
                        Html.fromHtml(refText, Html.FROM_HTML_MODE_LEGACY).trimEnd()
                    } catch (_: Exception) {
                        refText.trim()
                    }
                    sb.append("  $cleanRef\n")
                }

                binding.tvDetailedAnswer.text = sb.toString().trimEnd()

                if (!subject.teacherComment.isNullOrBlank()) {
                    binding.tvTeacherComment.visibility = View.VISIBLE
                    binding.tvTeacherComment.text = "教师批语: ${subject.teacherComment.trim()}"
                }

                if (!subject.explanation.isNullOrBlank() && correctAnsText.isNotBlank()) {
                    binding.tvExplanation.visibility = View.VISIBLE
                    val cleanExp = try {
                        Html.fromHtml(subject.explanation, Html.FROM_HTML_MODE_LEGACY).trimEnd()
                    } catch (_: Exception) {
                        subject.explanation.trim()
                    }
                    binding.tvExplanation.text = "解析: $cleanExp"
                }
            }
        }

        private fun extractOptionIds(raw: Any?): Set<Long> {
            val set = mutableSetOf<Long>()
            if (raw == null) return set
            when (raw) {
                is Number -> set.add(raw.toLong())
                is List<*> -> {
                    for (item in raw) {
                        when (item) {
                            is Number -> set.add(item.toLong())
                            is String -> item.toLongOrNull()?.let { set.add(it) }
                        }
                    }
                }
                is String -> {
                    raw.toLongOrNull()?.let { set.add(it) }
                    if (set.isEmpty() && raw.contains(",")) {
                        raw.split(",").forEach { s ->
                            s.trim().toLongOrNull()?.let { set.add(it) }
                        }
                    }
                }
            }
            return set
        }

        private fun extractStringList(raw: Any?): List<String> {
            val list = mutableListOf<String>()
            if (raw == null) return list
            when (raw) {
                is List<*> -> {
                    for (item in raw) {
                        when (item) {
                            is String -> list.add(item)
                            is Map<*, *> -> {
                                val c = item["content"]?.toString() ?: item["answer"]?.toString() ?: ""
                                list.add(c)
                            }
                            else -> list.add(item.toString())
                        }
                    }
                }
                is String -> {
                    if (raw.isNotBlank()) list.add(raw)
                }
            }
            return list
        }

        private fun mapIdsToDisplayAnswer(subject: TronClassSubject, ids: Set<Long>): String {
            if (ids.isEmpty() || subject.options.isEmpty()) return ""
            if (subject.type == "true_false") {
                val answers = mutableListOf<String>()
                subject.options.forEach { opt ->
                    if (ids.contains(opt.id)) {
                        val c = opt.getCleanContent()
                        val label = when {
                            c.contains("正确") || c.contains("对") || c.equals("true", ignoreCase = true) || c == "1" -> "正确"
                            c.contains("错误") || c.contains("错") || c.equals("false", ignoreCase = true) || c == "0" -> "错误"
                            else -> c
                        }
                        answers.add(label)
                    }
                }
                if (answers.isNotEmpty()) return answers.joinToString(", ")
            }
            return mapIdsToLetters(subject.options, ids)
        }

        private fun mapIdsToLetters(options: List<TronClassSubjectOption>, ids: Set<Long>): String {
            if (ids.isEmpty() || options.isEmpty()) return ""
            val letters = mutableListOf<String>()
            val optionLetters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
            options.forEachIndexed { index, opt ->
                if (ids.contains(opt.id)) {
                    val l = if (index < optionLetters.size) optionLetters[index] else "${index + 1}"
                    letters.add(l)
                }
            }
            return letters.joinToString(", ")
        }
    }
}
