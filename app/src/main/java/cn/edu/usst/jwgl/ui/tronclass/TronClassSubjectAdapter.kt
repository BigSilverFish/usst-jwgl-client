package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
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
import cn.edu.usst.jwgl.databinding.ItemTronclassSubjectBinding
import java.util.Locale

class TronClassSubjectAdapter(
    private var subjects: List<TronClassSubject> = emptyList(),
    private var myAnswers: Map<String, Any> = emptyMap(),
    private var correctAnswers: Map<String, Any> = emptyMap(),
    private var isReviewMode: Boolean = true,
    private val onAnswerSelected: ((subjectId: Long, answer: String) -> Unit)? = null
) : RecyclerView.Adapter<TronClassSubjectAdapter.SubjectViewHolder>() {

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
        notifyDataSetChanged()
    }

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
            val userRaw = subject.myAnswer ?: myAnswers[sidStr]
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
         * 渲染单选题 / 多选题 / 判断题
         */
        private fun renderChoiceSubject(subject: TronClassSubject, userRaw: Any?, correctRaw: Any?) {
            val context = binding.root.context
            binding.llOptionsContainer.visibility = View.VISIBLE

            val optionLetters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
            val userChosenOptIds = extractOptionIds(userRaw)
            val correctOptIds = extractOptionIds(correctRaw)

            subject.options.forEachIndexed { idx, opt ->
                val letter = if (idx < optionLetters.size) optionLetters[idx] else "${idx + 1}"
                val optText = try {
                    Html.fromHtml(opt.content ?: "", Html.FROM_HTML_MODE_LEGACY).trimEnd()
                } catch (_: Exception) {
                    opt.getCleanContent()
                }

                val isMyChoice = userChosenOptIds.contains(opt.id)
                val isCorrectChoice = correctOptIds.contains(opt.id)

                val optView = TextView(context).apply {
                    val sb = StringBuilder()
                    sb.append("$letter. $optText")
                    if (isReviewMode) {
                        if (isCorrectChoice) sb.append("  ✓ (标准答案)")
                        else if (isMyChoice) sb.append("  ✕ (我的选择)")
                    }
                    text = sb.toString()
                    textSize = 14f
                    setPadding(32, 22, 32, 22)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 10, 0, 10)
                    }

                    if (isReviewMode) {
                        when {
                            isCorrectChoice && isMyChoice -> {
                                setBackgroundColor(Color.parseColor("#E8F5E9"))
                                setTextColor(Color.parseColor("#2E7D32"))
                                setTypeface(null, Typeface.BOLD)
                            }
                            isCorrectChoice -> {
                                setBackgroundColor(Color.parseColor("#E8F5E9"))
                                setTextColor(Color.parseColor("#2E7D32"))
                                setTypeface(null, Typeface.BOLD)
                            }
                            isMyChoice -> {
                                setBackgroundColor(Color.parseColor("#FFEBEE"))
                                setTextColor(Color.parseColor("#C62828"))
                                setTypeface(null, Typeface.BOLD)
                            }
                            else -> {
                                setBackgroundColor(Color.parseColor("#FAFAFA"))
                                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            }
                        }
                    } else {
                        if (isMyChoice) {
                            setBackgroundResource(R.drawable.bg_badge_rounded)
                            setTextColor(ContextCompat.getColor(context, R.color.primary))
                            setTypeface(null, Typeface.BOLD)
                        } else {
                            setBackgroundColor(Color.parseColor("#FAFAFA"))
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        setOnClickListener {
                            onAnswerSelected?.invoke(subject.id, opt.id.toString())
                        }
                    }
                }
                binding.llOptionsContainer.addView(optView)
            }

            // 结果卡片
            if (isReviewMode && (userChosenOptIds.isNotEmpty() || correctOptIds.isNotEmpty())) {
                binding.layoutAnswerResult.visibility = View.VISIBLE
                binding.layoutSimpleAnswers.visibility = View.VISIBLE

                val myLetters = mapIdsToLetters(subject.options, userChosenOptIds)
                val corLetters = mapIdsToLetters(subject.options, correctOptIds)

                binding.tvMyAnswer.text = "我的作答: ${if (myLetters.isNotBlank()) myLetters else "未作答"}"
                if (corLetters.isNotBlank()) {
                    binding.tvCorrectAnswer.visibility = View.VISIBLE
                    binding.tvCorrectAnswer.text = "标准答案: $corLetters"
                } else {
                    binding.tvCorrectAnswer.visibility = View.GONE
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
