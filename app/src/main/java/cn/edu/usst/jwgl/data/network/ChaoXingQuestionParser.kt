package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.ChaoXingQuestionItem
import cn.edu.usst.jwgl.data.model.ChaoXingQuestionOption
import org.jsoup.Jsoup

/**
 * 超星作业与考试题目 Jsoup 解析器
 */
object ChaoXingQuestionParser {
    private const val TAG = "ChaoXingQuestionParser"

    /**
     * 解析答卷或做题页面 HTML 中的全部题目结构与选项
     */
    fun parseQuestionsFromHtml(html: String): List<ChaoXingQuestionItem> {
        val list = mutableListOf<ChaoXingQuestionItem>()
        if (html.isBlank()) return list
        try {
            val doc = Jsoup.parse(html)
            val rawCandidates = doc.select(".singleQuesId, div[class*='singleQues'], .questionLi, .questionBlock, .TiMu:not(.fanyaMarking):not(.mark_item), .padlr30:not(.mark_item)")
            val effectiveCandidates = if (rawCandidates.isNotEmpty()) rawCandidates else doc.select(".TiMu, .mark_item")
            val rawSet = effectiveCandidates.toSet()
            val topElements = effectiveCandidates.filter { el ->
                el.parents().none { it in rawSet }
            }

            Log.d(TAG, "parseQuestionsFromHtml: topElements size=${topElements.size}")

            val seenSignatures = mutableSetOf<String>()
            var questionIndex = 1

            for (qEl in topElements) {
                val typeScoreEl = qEl.select(".colorShallow, .newZy_TItle, .stemGray, .question-type, .type, .colorDeep, .type_tit, .color888, .font14").firstOrNull()
                val typeScoreText = typeScoreEl?.text()?.trim().orEmpty()
                val totalScoreEl = qEl.select(".totalScore, .score, .numspan, .mark_score").firstOrNull()
                val scoreMatch = Regex("""(\d+(?:\.\d+)?)\s*分""").find(typeScoreText)
                    ?: Regex("""(\d+(?:\.\d+)?)\s*分""").find(totalScoreEl?.text().orEmpty())
                    ?: Regex("""[(（\[【]\s*(\d+(?:\.\d+)?)\s*分\s*[)）\]】]""").find(qEl.text())
                    ?: Regex("""(?:分值[:：\s]*|共\s*)(\d+(?:\.\d+)?)\s*分""").find(qEl.text())
                val scoreText = scoreMatch?.groupValues?.get(1)?.let { "$it 分" } ?: ""

                val stemEl = qEl.select(".qtContent, .mark_name, .mark_title, .Zy_TItle, .newZy_TItle, .stemDiv .Picdiv, .Picdiv, .stemDiv, .workTextWrap, .font14, .stem_title, .title, .sub-title, .questionContent, .subject_title, .stem, .textWrap").firstOrNull()
                var cleanStem = ""
                var stemHtml = ""

                if (stemEl != null) {
                    val stemClone = stemEl.clone()
                    stemClone.select(".colorShallow, .totalScore, .score, .numspan, .type, .question-type, .color888, .stemGray, .mark_score").remove()
                    for (img in stemClone.select("img")) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-original") }
                        if (src.isNotBlank()) {
                            img.attr("src", ChaoXingHtmlParser.normalizeChaoXingImageUrl(src))
                        }
                    }
                    cleanStem = stemClone.text().trim()
                        .replace(Regex("""^\d+[.\u3001\s]+"""), "")
                        .replace(Regex("""^[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "")
                        .replace(Regex("""[(（\[【]?\d+(?:\.\d+)?\s*分[)）\]】]?\s*$"""), "").trim()

                    var sHtml = stemClone.html().trim()
                    sHtml = sHtml.replaceFirst(Regex("""^\s*\d+[.\u3001\s]+"""), "")
                    sHtml = sHtml.replaceFirst(Regex("""^\s*[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "")
                    sHtml = sHtml.replaceFirst(Regex("""^(<(?:p|span|div|b|strong)[^>]*>)\s*\d+[.\u3001\s]+"""), "$1")
                    sHtml = sHtml.replaceFirst(Regex("""^(<(?:p|span|div|b|strong)[^>]*>)\s*[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "$1")
                    stemHtml = sHtml.trim()
                }

                if (cleanStem.isBlank()) {
                    val fallbackEl = qEl.clone()
                    fallbackEl.select(
                        "script, style, .mark_letter, .Zy_ulTop, .Cy_ulTop, .Zy_ulTk, .ulOption, .stem_answer, " +
                        ".Py_answer, .Py_tk, .stuAnswerContent, .optionDiv, .myAnswer, .my-answer, .stuAnswer, " +
                        ".true_answer, .rightAnswer, .rightAnswerContent, .standardAnswer, .correctAnswer, .trueAnswer, " +
                        ".analysis, .myAnalysis, .analysisDiv, .explain, .fenxi, .grayDiv, .pingyu, .remark, .comment, .teach-comment, " +
                        ".colorShallow, .totalScore, .score, .numspan, .mark_score, textarea, input, button"
                    ).remove()
                    for (img in fallbackEl.select("img")) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-original") }
                        if (src.isNotBlank()) img.attr("src", ChaoXingHtmlParser.normalizeChaoXingImageUrl(src))
                    }
                    val fbText = fallbackEl.text().trim()
                        .replace(Regex("""^\d+[.\u3001\s]+"""), "")
                        .replace(Regex("""^[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "")
                        .replace(Regex("""[(（\[【]?\d+(?:\.\d+)?\s*分[)）\]】]?\s*$"""), "").trim()
                    if (fbText.isNotBlank()) {
                        cleanStem = fbText
                        var fbHtml = fallbackEl.html().trim()
                        fbHtml = fbHtml.replaceFirst(Regex("""^\s*\d+[.\u3001\s]+"""), "")
                        fbHtml = fbHtml.replaceFirst(Regex("""^\s*[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "")
                        fbHtml = fbHtml.replaceFirst(Regex("""^(<(?:p|span|div|b|strong)[^>]*>)\s*\d+[.\u3001\s]+"""), "$1")
                        fbHtml = fbHtml.replaceFirst(Regex("""^(<(?:p|span|div|b|strong)[^>]*>)\s*[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "$1")
                        stemHtml = fbHtml.trim()
                    }
                }

                if (stemHtml.isBlank()) stemHtml = cleanStem

                var qId = qEl.attr("data").trim()
                if (qId.isBlank()) qId = Regex("""\d+""").find(qEl.attr("id"))?.value.orEmpty()
                if (qId.isBlank()) qId = qEl.select("[id^='qb']").firstOrNull()?.let { Regex("""\d+""").find(it.attr("id"))?.value }.orEmpty()
                if (qId.isBlank()) qId = qEl.attr("questionid").trim()
                if (qId.isBlank()) {
                    qId = qEl.select("input[name*='id'], input[id*='id'], [data-singlequesid]").firstOrNull()?.let {
                        it.attr("data-singlequesid").ifBlank { it.attr("value") }
                    }.orEmpty()
                }

                val signature = if (qId.isNotBlank()) "id_$qId" else "stem_${cleanStem.take(60)}"
                if (cleanStem.isNotBlank() && !seenSignatures.add(signature)) {
                    continue
                }

                val teacherAtts = mutableListOf<ChaoXingCourseAttachment>()
                val attIframes = mutableListOf<org.jsoup.nodes.Element>()
                stemEl?.let { attIframes.addAll(it.select("iframe.attach-iframe, iframe[objectid]")) }
                attIframes.addAll(qEl.select("iframe.attach-iframe, iframe[objectid]").filter { it !in attIframes })
                for (iframe in attIframes) {
                    val fn = iframe.attr("filename").ifBlank { iframe.attr("name") }
                    val oid = iframe.attr("objectid")
                    val fsStr = iframe.attr("filesize")
                    if (oid.isNotBlank() && fn.isNotBlank()) {
                        teacherAtts.add(
                            ChaoXingCourseAttachment(
                                objectId = oid,
                                name = fn,
                                size = ChaoXingHtmlParser.parseSizeStrToBytes(fsStr)
                            )
                        )
                    }
                }

                val options = parseOptionsFromQuestionElement(qEl)

                var cleanAns = ""
                var ansHtml = ""
                val pyAns = qEl.select(".Py_answer, .Py_tk").firstOrNull()
                if (pyAns != null) {
                    for (img in pyAns.select("img")) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }
                        if (src.isNotBlank()) img.attr("src", ChaoXingHtmlParser.normalizeChaoXingImageUrl(src))
                    }
                    val m = Regex("""(?:我的答案|学生答案)[:：\s]*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(pyAns.text())
                    if (m != null) {
                        cleanAns = m.groupValues[1].trim()
                    }
                    ansHtml = pyAns.html().trim()
                }
                if (cleanAns.isBlank()) {
                    val ansEl = qEl.select(".stuAnswerContent, .optionDiv, .myAnswer, .my-answer, .stuAnswer").firstOrNull()
                    if (ansEl != null) {
                        for (img in ansEl.select("img")) {
                            val src = img.attr("src").ifBlank { img.attr("data-src") }
                            if (src.isNotBlank()) img.attr("src", ChaoXingHtmlParser.normalizeChaoXingImageUrl(src))
                        }
                    }
                    ansHtml = ansEl?.html()?.trim().orEmpty()
                    cleanAns = if (ansEl != null) {
                        ansEl.text().replace(Regex("""^(?:我的答案|我的作答|学生答案)[:：\s]*"""), "").trim()
                    } else {
                        val m = Regex("""(?:我的答案|我的作答|学生答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(qEl.text())
                        m?.groupValues?.get(1)?.trim().orEmpty()
                    }
                }
                if (cleanAns.isBlank() && options.isNotEmpty()) {
                    val chosenLetters = options.filter { it.isSelected }.map { it.letter }
                    if (chosenLetters.isNotEmpty()) {
                        cleanAns = chosenLetters.joinToString(", ")
                    }
                }

                var correctAns = ""
                if (pyAns != null) {
                    val m = Regex("""(?:正确答案|标准答案|参考答案)[:：\s]*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:我的答案|学生答案|答案解析|解析|教师批阅|教师评语|$))""").find(pyAns.text())
                    if (m != null) {
                        correctAns = m.groupValues[1].trim()
                    }
                }
                if (correctAns.isBlank()) {
                    val rightEl = qEl.select(".true_answer, .rightAnswer, .rightAnswerContent, .standardAnswer, .correctAnswer, .trueAnswer").firstOrNull()
                    correctAns = if (rightEl != null) {
                        rightEl.text().replace(Regex("""^(?:正确答案|标准答案|参考答案)[:：\s]*"""), "").trim()
                    } else {
                        val m = Regex("""(?:正确答案|标准答案|参考答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:答案解析|解析|学生答案|我的答案|教师批阅|教师评语|$))""").find(qEl.text())
                        m?.groupValues?.get(1)?.trim().orEmpty()
                    }
                }
                if (correctAns.isBlank() && options.isNotEmpty()) {
                    val rightLetters = options.filter { it.isCorrect }.map { it.letter }
                    if (rightLetters.isNotEmpty()) {
                        correctAns = rightLetters.joinToString(", ")
                    }
                }

                val expEl = qEl.select(".analysis, .myAnalysis, .analysisDiv, .explain, .fenxi, .Py_tk").firstOrNull()
                val explanation = if (expEl != null) {
                    expEl.text().replace(Regex("""^(?:答案解析|解析|题目分析)[:：\s]*"""), "").trim()
                } else {
                    val m = Regex("""(?:答案解析|解析|题目分析)[:：]\s*([^\r\n]+)""").find(qEl.text())
                    m?.groupValues?.get(1)?.trim().orEmpty()
                }

                val studentAtts = mutableListOf<ChaoXingCourseAttachment>()
                val searchAnsEl = qEl.select(".stuAnswerContent, .optionDiv, .myAnswer, .my-answer, .stuAnswer, .Py_answer").firstOrNull()
                if (searchAnsEl != null) {
                    for (iframe in searchAnsEl.select("iframe.attach-iframe, iframe[objectid]")) {
                        val fn = iframe.attr("filename").ifBlank { iframe.attr("name") }
                        val oid = iframe.attr("objectid")
                        val fsStr = iframe.attr("filesize")
                        if (oid.isNotBlank() && fn.isNotBlank()) {
                            studentAtts.add(
                                ChaoXingCourseAttachment(
                                    objectId = oid,
                                    name = fn,
                                    size = ChaoXingHtmlParser.parseSizeStrToBytes(fsStr)
                                )
                            )
                        }
                    }
                }

                val commentEl = qEl.select(".grayDiv, .pingyu, .remark, .comment, .teach-comment").firstOrNull()
                val commentText = ChaoXingHtmlParser.cleanTeacherComment(commentEl)
                val (typeCode, typeName) = resolveQuestionType(qEl, typeScoreText, options)

                list.add(
                    ChaoXingQuestionItem(
                        index = questionIndex++,
                        id = qId,
                        typeCode = typeCode,
                        typeName = typeName,
                        scoreText = scoreText,
                        stemHtml = stemHtml,
                        cleanStem = cleanStem,
                        options = options,
                        correctAnswer = correctAns,
                        explanation = explanation,
                        myAnswerHtml = ansHtml,
                        cleanMyAnswer = cleanAns,
                        teacherComment = commentText,
                        studentAttachments = studentAtts,
                        teacherAttachments = teacherAtts
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseQuestionsFromHtml error: ${e.message}")
        }
        return list
    }

    /**
     * 解析超星单道题目的全部选项卡片数据
     */
    fun parseOptionsFromQuestionElement(qEl: org.jsoup.nodes.Element): List<ChaoXingQuestionOption> {
        val options = mutableListOf<ChaoXingQuestionOption>()

        val pyAnsText = qEl.select(".Py_answer, .Py_tk").text().trim()
        val myAnsText = qEl.select(".stuAnswer, .myAnswer, .my-answer, .stuAnswerContent").text().trim()
        val rightAnsText = qEl.select(".true_answer, .rightAnswer, .rightAnswerContent, .standardAnswer, .correctAnswer, .trueAnswer").text().trim()
        val myFallback = Regex("""(?:我的答案|我的作答|学生答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(qEl.text())?.groupValues?.get(1).orEmpty()
        val rightFallback = Regex("""(?:正确答案|标准答案|参考答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:答案解析|解析|学生答案|我的答案|教师批阅|教师评语|$))""").find(qEl.text())?.groupValues?.get(1).orEmpty()

        val myLetters = extractChoiceLetters("$pyAnsText $myAnsText $myFallback")
        val rightLetters = extractChoiceLetters("$pyAnsText $rightAnsText $rightFallback")

        val optEls = qEl.select("""
            ul.mark_letter > li,
            ul.mark_letter li,
            ul.qtDetail > li,
            ul.qtDetail li,
            ul[class*='letter'] li,
            ul[class*='qtDetail'] li,
            ul.Zy_ulTop > li,
            ul.Zy_ulTop li,
            ul.Cy_ulTop > li,
            ul.Cy_ulTop li,
            ul.Zy_ulTk > li,
            ul.Zy_ulTk li,
            ul.ulOption > li,
            ul.ulOption li,
            .stem_answer li,
            .stem_answer .clearfix,
            .answerList .clearfix,
            .answerList .answerList_item,
            .answerList > div,
            .answerList li,
            .options li,
            .optionList li,
            .option_list li,
            ul.tiMu_ul li,
            .choiceList .choice,
            .choiceList li,
            div[class*='answerBg'],
            ul[class*='answer'] li,
            ul[class*='option'] li,
            ul[class*='ulTop'] li,
            ul[class*='ulTk'] li
        """.trimIndent())

        val letterList = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")

        if (optEls.isNotEmpty()) {
            for ((idx, optEl) in optEls.withIndex()) {
                val badgeEl = optEl.select(".num_option, .num, i.fl, em.fl, .option_num, span.fl, .letter, i, em").firstOrNull()
                var letter = badgeEl?.attr("data")?.trim().orEmpty()
                if (letter.isBlank()) {
                    letter = optEl.attr("data").trim()
                }
                if (letter.isBlank()) {
                    letter = badgeEl?.text()?.replace(Regex("""[^A-Za-z✓✕对错正确错误]"""), "")?.trim()?.uppercase().orEmpty()
                }
                val rawText = optEl.text().trim()

                if (letter.isBlank()) {
                    val match = Regex("""^([A-Z])[.\u3001\s]""").find(rawText)
                    if (match != null) {
                        letter = match.groupValues[1]
                    }
                }
                if (letter.isBlank()) {
                    letter = if (idx < letterList.size) letterList[idx] else "${idx + 1}"
                }

                val contentEl = optEl.select("a.after, .answer_p, .option_content, .text, .fl:not(i):not(em):not(.num_option), p, a, div, span:not(.num_option)").firstOrNull()
                var content: String
                if (contentEl != null && contentEl.select("img").isNotEmpty()) {
                    for (img in contentEl.select("img")) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }
                        if (src.isNotBlank()) img.attr("src", ChaoXingHtmlParser.normalizeChaoXingImageUrl(src))
                    }
                    content = contentEl.html().trim()
                } else {
                    content = contentEl?.text()?.trim() ?: rawText
                    content = content.replace(Regex("""^[A-Za-z][.\u3001\s]*"""), "").trim()
                    if (content.isBlank() && rawText.isNotBlank()) {
                        content = rawText.replace(Regex("""^[A-Za-z][.\u3001\s]*"""), "").trim()
                    }
                }

                val isSelected = myLetters.contains(letter) ||
                        optEl.hasClass("check_answer") || optEl.hasClass("check") ||
                        optEl.hasClass("cur") || optEl.hasClass("active") ||
                        optEl.hasClass("selected") || optEl.select("input[checked]").isNotEmpty()

                val isCorrect = rightLetters.contains(letter) ||
                        optEl.hasClass("rightAnswer") || optEl.hasClass("correct") ||
                        optEl.select(".rightAnswer, .colorGreen").isNotEmpty()

                if (content.isNotBlank() || letter.isNotBlank()) {
                    options.add(
                        ChaoXingQuestionOption(
                            letter = letter,
                            content = content,
                            isSelected = isSelected,
                            isCorrect = isCorrect
                        )
                    )
                }
            }
        } else {
            val pElements = qEl.select(".Zy_TItle p, .stemDiv p, .Picdiv p, p")
            val pOptions = mutableListOf<Pair<String, String>>()
            for (p in pElements) {
                val text = p.text().trim()
                val m = Regex("""^([A-J])[.\u3001\s]+(.*)$""").find(text)
                if (m != null) {
                    pOptions.add(Pair(m.groupValues[1].uppercase(), m.groupValues[2].trim()))
                }
            }
            if (pOptions.size >= 2) {
                for ((l, c) in pOptions) {
                    options.add(
                        ChaoXingQuestionOption(
                            letter = l,
                            content = c,
                            isSelected = myLetters.contains(l),
                            isCorrect = rightLetters.contains(l)
                        )
                    )
                }
            }
        }

        return options
    }

    private fun extractChoiceLetters(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val clean = Regex("""(?:我的答案|正确答案|标准答案|参考答案|学生答案)[:：\s]*""").replace(text, "").trim()
        val set = mutableSetOf<String>()
        Regex("""[A-Za-z]""").findAll(clean).forEach {
            set.add(it.value.uppercase())
        }
        if (set.isEmpty()) {
            if (clean.contains("对") || clean.contains("正确") || clean.equals("true", ignoreCase = true)) set.add("✓")
            if (clean.contains("错") || clean.contains("错误") || clean.equals("false", ignoreCase = true)) set.add("✕")
        }
        return set
    }

    /**
     * 智能判定题目类型编码与题型名称
     */
    fun resolveQuestionType(
        qEl: org.jsoup.nodes.Element,
        typeScoreText: String,
        options: List<ChaoXingQuestionOption>
    ): Pair<String, String> {
        val allText = qEl.text()
        val typeTextCandidates = qEl.select(".newZy_TItle, .Zy_TItle, .stemGray, .question-type, .type, .colorDeep, .color888, .type_tit, .stem_type, .font14, .stemDiv, .title").text()
        val fullScan = "$typeScoreText $typeTextCandidates $allText"

        val attrTypeId = qEl.attr("questiontypeid").ifBlank { qEl.attr("type") }.ifBlank {
            qEl.select("input[name*='type'], input[id*='type'], [questiontypeid], [data-type]").firstOrNull()?.let {
                it.attr("questiontypeid").ifBlank { it.attr("data-type") }.ifBlank { it.attr("value") }
            }.orEmpty()
        }

        val typeName = when {
            fullScan.contains("多选题") || fullScan.contains("多选") || allText.contains("【多选题】") || allText.contains("(多选题") || allText.contains("（多选题") -> "多选题"
            fullScan.contains("单选题") || fullScan.contains("单选") || allText.contains("【单选题】") || allText.contains("(单选题") || allText.contains("（单选题") -> "单选题"
            fullScan.contains("判断题") || fullScan.contains("判断") || allText.contains("【判断题】") || allText.contains("(判断题") || allText.contains("（判断题") -> "判断题"
            fullScan.contains("填空题") || fullScan.contains("填空") || allText.contains("【填空题】") || allText.contains("(填空题") || allText.contains("（填空题") -> "填空题"
            fullScan.contains("简答题") || fullScan.contains("简答") || allText.contains("【简答题】") || allText.contains("(简答题") || allText.contains("（简答题") -> "简答题"
            fullScan.contains("名词解释") -> "名词解释"
            fullScan.contains("论述题") -> "论述题"
            fullScan.contains("计算题") -> "计算题"
            attrTypeId == "0" -> "单选题"
            attrTypeId == "1" -> "多选题"
            attrTypeId == "2" -> "填空题"
            attrTypeId == "3" -> "判断题"
            attrTypeId == "4" -> "简答题"
            options.size == 2 && (allText.contains("正确") || allText.contains("错误") || allText.contains("对") || allText.contains("错") || options.any { it.letter in listOf("✓", "✕", "对", "错") }) -> "判断题"
            options.isNotEmpty() && (qEl.select("input[type='checkbox']").isNotEmpty() || fullScan.contains("多选")) -> "多选题"
            options.isNotEmpty() -> "单选题"
            typeScoreText.isNotBlank() -> typeScoreText.replace(Regex("""[(（\[【\d.\s分)）\]】]"""), "").trim().ifBlank { "主观题" }
            else -> "主观题"
        }

        val typeCode = when (typeName) {
            "单选题" -> "0"
            "多选题" -> "1"
            "填空题" -> "2"
            "判断题" -> "3"
            "简答题" -> "4"
            "名词解释" -> "5"
            "论述题" -> "6"
            "计算题" -> "7"
            else -> "4"
        }

        return Pair(typeCode, typeName)
    }
}
