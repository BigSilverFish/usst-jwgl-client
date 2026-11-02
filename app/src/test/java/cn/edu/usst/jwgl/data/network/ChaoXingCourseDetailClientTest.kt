package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ChaoXingCourseDetailClientTest {

    @Test
    fun testParseAttachmentsFromHtml() {
        val html = """
            <html>
            <script>
            var mArg = "";
            mArg = {
                "attachments": [
                    {
                        "type": "document",
                        "property": {
                            "objectid": "doc_123456",
                            "name": "第1章 Web技术架构.pptx",
                            "size": 5242880,
                            "type": ".pptx"
                        }
                    },
                    {
                        "type": "video",
                        "property": {
                            "objectid": "vid_654321",
                            "name": "第1章 教学导引录像.mp4",
                            "size": 52428800,
                            "type": ".mp4"
                        }
                    }
                ]
            };
            </script>
            </html>
        """.trimIndent()

        val attachments = ChaoXingCourseDetailClient.parseAttachmentsFromHtml(html)
        assertEquals(2, attachments.size)

        val doc = attachments[0]
        assertEquals("doc_123456", doc.objectId)
        assertEquals("第1章 Web技术架构.pptx", doc.name)
        assertEquals(5242880L, doc.size)
        assertFalse(doc.isVideo)
        assertEquals("5.00 MB", doc.getFormattedSize())

        val video = attachments[1]
        assertEquals("vid_654321", video.objectId)
        assertEquals("第1章 教学导引录像.mp4", video.name)
        assertEquals(52428800L, video.size)
        assertTrue(video.isVideo)
    }

    @Test
    fun testParseExamsFromHtml() {
        val html = """
            <ul class="exam-list">
                <li>
                    <p class="overHidden2 fl">Web期末阶段性自测 (第一轮)</p>
                    <p class="status fl">已完成</p>
                    <span class="numspan">96.5</span>
                    <a href="https://stat2-ans.chaoxing.com/report?examId=998877&amp;cpi=123">智能答卷分析</a>
                    <button onclick="viewExamAnswer('998877', 1)">查看答卷</button>
                </li>
                <li>
                    <p class="overHidden2 fl">期中在线闭卷测试</p>
                    <p class="status fl">未参加</p>
                    <div class="time">剩余 3 天 12 小时</div>
                    <button onclick="goTest('token123', 887766)">进入答题</button>
                </li>
            </ul>
        """.trimIndent()

        val exams = ChaoXingCourseDetailClient.parseExamsFromHtml(html)
        assertEquals(2, exams.size)

        val exam1 = exams[0]
        assertEquals("998877", exam1.id)
        assertEquals("Web期末阶段性自测 (第一轮)", exam1.title)
        assertEquals("已完成", exam1.status)
        assertEquals("96.5", exam1.score)
        assertEquals("https://stat2-ans.chaoxing.com/report?examId=998877&cpi=123", exam1.analysisUrl)

        val exam2 = exams[1]
        assertEquals("887766", exam2.id)
        assertEquals("期中在线闭卷测试", exam2.title)
        assertEquals("未参加", exam2.status)
        assertEquals("剩余 3 天 12 小时", exam2.remaining)
    }

    @Test
    fun testParseWorkListHtml() {
        val course = ChaoXingCourse(
            courseId = "12345",
            classId = "67890",
            courseName = "Web应用开发"
        )

        val html = """
            <ul class="work-list">
                <li onclick="goTask(this);"
                    data="/mooc2/work/task?courseId=12345&amp;workId=554433&amp;enc=testtoken"
                    aria-label="第3次课后作业: DOM事件与AJAX ; 已批阅">
                    <p class="overHidden2 fl">第3次课后作业: DOM事件与AJAX</p>
                    <p class="status fl">已批阅</p>
                    <div class="time notOver">截止时间: 2026-11-15 23:59</div>
                </li>
                <li onclick="goTask(this);"
                    data="/mooc2/work/task?courseId=12345&amp;workId=554434&amp;enc=testtoken2"
                    aria-label="第4次实验报告: Vue3组件设计 ; 未交">
                    <p class="overHidden2 fl">第4次实验报告: Vue3组件设计</p>
                    <p class="status fl">未交</p>
                    <div class="time notOver">截止时间: 2026-11-20 23:59</div>
                </li>
            </ul>
        """.trimIndent()

        val works = ChaoXingCourseDetailClient.parseWorkListHtml(html, course)
        assertEquals(2, works.size)

        val work1 = works[0]
        assertEquals("554433", work1.workId)
        assertEquals("第3次课后作业: DOM事件与AJAX", work1.title)
        assertEquals("已批阅", work1.statusText)
        assertEquals("2026-11-15 23:59", work1.deadline)
        assertEquals("/mooc2/work/task?courseId=12345&workId=554433&enc=testtoken", work1.taskUrl)

        val work2 = works[1]
        assertEquals("554434", work2.workId)
        assertEquals("第4次实验报告: Vue3组件设计", work2.title)
        assertEquals("未交", work2.statusText)
    }

    @Test
    fun testExtractDeadlineFromRightBordHtml() {
        val snippet = """<div class="borderBom padBom20 detailsHead" style="overflow: hidden;"><p class="rightBord">作答时间:<em>10-21 17:58</em>至<em>10-27 17:58</em></p></div>"""
        val ddl = ChaoXingCourseDetailClient.extractDeadlineFromText(snippet, snippet)
        assertEquals("10-27 17:58", ddl)
    }

    @Test
    fun testCleanDeadlineRange() {
        val r1 = ChaoXingCourseDetailClient.cleanDeadline("作答时间: 10-21 17:58 至 10-27 17:58")
        assertEquals("10-27 17:58", r1)

        val r2 = ChaoXingCourseDetailClient.cleanDeadline("起止时间: 2026年10月21日 17:58 到 2026年10月27日 17:58")
        assertEquals("2026-10-27 17:58", r2)

        val r3 = ChaoXingCourseDetailClient.cleanDeadline("截止时间: 2026-11-15 23:59")
        assertEquals("2026-11-15 23:59", r3)
    }

    @Test
    fun testCleanTeacherCommentTextMultiLine() {
        val fakeComments = "第1题批语: 教师批阅\n第2题批语: 教师批阅"
        val cleaned = ChaoXingCourseDetailClient.cleanTeacherCommentText(fakeComments)
        assertEquals("", cleaned)

        val realComment = "第1题批语: 教师批阅\n第2题批语: 架构设计合理，代码风格优良！"
        val cleanedReal = ChaoXingCourseDetailClient.cleanTeacherCommentText(realComment)
        assertEquals("第2题批语: 架构设计合理，代码风格优良！", cleanedReal)
    }

    @Test
    fun testFuseCoursesTheoryAndLabSeparation() {
        // TronClass 理论课与实验课
        val tcTheory = TronClassCourse(
            id = 152768L,
            name = "Web应用开发",
            courseCode = "CS301",
            instructors = listOf("张老师")
        )
        val tcLab = TronClassCourse(
            id = 147005L,
            name = "Web应用开发实验",
            courseCode = "CS302",
            instructors = listOf("张老师")
        )

        // ChaoXing 顺序颠倒：实验课在理论课前面
        val cxLab = ChaoXingCourse(
            courseId = "267484770",
            classId = "155710701",
            courseName = "Web应用开发实验",
            teacherName = "张老师"
        )
        val cxTheory = ChaoXingCourse(
            courseId = "267465694",
            classId = "155659413",
            courseName = "Web应用开发",
            teacherName = "张老师"
        )

        val fused = UnifiedCourse.fuseCourses(
            tcCourses = listOf(tcTheory, tcLab),
            cxCourses = listOf(cxLab, cxTheory)
        )

        assertEquals(2, fused.size)

        val theoryFused = fused.first { it.cleanName == "Web应用开发" }
        assertEquals("152768", theoryFused.originalCourseId)
        assertEquals("155659413", theoryFused.originalClassId) // 必须精准对应 155659413 (Web应用开发)，绝不能错配成 155710701

        val labFused = fused.first { it.cleanName == "Web应用开发实验" }
        assertEquals("147005", labFused.originalCourseId)
        assertEquals("155710701", labFused.originalClassId) // 必须精准对应 155710701 (Web应用开发实验)
    }

    @Test
    fun testFuseCoursesDeduplication() {
        // TronClass 课程
        val tcCourse = TronClassCourse(
            id = 10001L,
            name = "Web应用开发 (2026-2027-1)",
            courseCode = "CS302",
            cover = "https://tronclass.usst.edu.cn/cover.jpg",
            department = TronClassCourse.DepartmentInfo(name = "光电信息与计算机工程学院"),
            instructors = listOf("张老师")
        )

        // ChaoXing 课程 (同一门课)
        val cxCourse = ChaoXingCourse(
            courseId = "99001",
            classId = "88001",
            courseName = "Web应用开发",
            teacherName = "张老师",
            coverUrl = "https://chaoxing.com/cover.jpg",
            schoolName = "上海理工大学",
            cpi = "cpi_123"
        )

        // 仅在超星存在的独立实验课
        val cxLabCourse = ChaoXingCourse(
            courseId = "99002",
            classId = "88002",
            courseName = "电路与电子学实验",
            teacherName = "李老师",
            schoolName = "上海理工大学"
        )

        val fused = UnifiedCourse.fuseCourses(
            tcCourses = listOf(tcCourse),
            cxCourses = listOf(cxCourse, cxLabCourse),
            fallbackSemesterCode = "2026-2027-1"
        )

        // 应该融合为 2 门课，而不是 3 门课（Web应用开发融合，电路与电子学实验保留）
        assertEquals(2, fused.size)

        val webCourse = fused.first { it.cleanName == "Web应用开发" }
        assertEquals(LearningPlatform.DUAL, webCourse.platform)
        assertTrue(webCourse.platform.isTronClass)
        assertTrue(webCourse.platform.isChaoXing)
        assertEquals("10001", webCourse.originalCourseId)
        assertEquals("88001", webCourse.originalClassId)
        assertNotNull(webCourse.rawTronClassCourse)
        assertNotNull(webCourse.rawChaoXingCourse)

        val labCourse = fused.first { it.cleanName == "电路与电子学实验" }
        assertEquals(LearningPlatform.CHAOXING, labCourse.platform)
        assertFalse(labCourse.platform.isDual)
        assertNull(labCourse.rawTronClassCourse)
        assertNotNull(labCourse.rawChaoXingCourse)
    }

    @Test
    fun testExtractWorkEnc() {
        val html1 = """<input type="hidden" id="workEnc" name="workEnc" value="enc_abc123" />"""
        assertEquals("enc_abc123", ChaoXingCourseDetailClient.extractWorkEnc(html1))

        val html2 = """<input type="hidden" value="enc_xyz789" id="workEnc" />"""
        assertEquals("enc_xyz789", ChaoXingCourseDetailClient.extractWorkEnc(html2))

        val html3 = """<script>var workEnc = 'enc_script456';</script>"""
        assertEquals("enc_script456", ChaoXingCourseDetailClient.extractWorkEnc(html3))

        val html4 = """<a href="/mooc2/work/list?courseId=123&enc=enc_link777">作业</a>"""
        assertEquals("enc_link777", ChaoXingCourseDetailClient.extractWorkEnc(html4))
    }

    @Test
    fun testParseStuWorkHtmlForCourse() {
        val course = ChaoXingCourse(
            courseId = "267465694",
            classId = "155659413",
            courseName = "Web应用开发"
        )

        val html = """
            <ul class="stu-work-list">
                <li data="https://mooc1.chaoxing.com/work/task?courseId=267465694&workId=9001&enc=tokenA">
                    <span class="course-name">Web应用开发</span>
                    <h3 class="title">第一次作业：HTML与CSS基础</h3>
                    <span class="status">已批阅</span>
                    <span class="time">截止时间: 2026-10-20 23:59</span>
                </li>
                <li data="https://mooc1.chaoxing.com/work/task?courseId=267465694&workId=9002&enc=tokenB">
                    <span class="course-name">Web应用开发</span>
                    <h3 class="title">第二次作业：JavaScript异步与Fetch</h3>
                    <span class="status">已交</span>
                    <span class="time">截止时间: 2026-10-28 23:59</span>
                </li>
                <li data="https://mooc1.chaoxing.com/work/task?courseId=888888&workId=9003&enc=tokenC">
                    <span class="course-name">大学英语</span>
                    <h3 class="title">Unit 1 Vocabulary</h3>
                    <span class="status">已交</span>
                    <span class="time">截止时间: 2026-10-30 23:59</span>
                </li>
            </ul>
        """.trimIndent()

        val works = ChaoXingCourseDetailClient.parseStuWorkHtmlForCourse(html, course)
        assertEquals(2, works.size)
        assertEquals("9001", works[0].workId)
        assertEquals("第一次作业：HTML与CSS基础", works[0].title)
        assertEquals("已批阅", works[0].statusText)

        assertEquals("9002", works[1].workId)
        assertEquals("第二次作业：JavaScript异步与Fetch", works[1].title)
        assertEquals("已交", works[1].statusText)
    }

    @Test
    fun testParseRealChaoXingStuWorkHtml() {
        val course = ChaoXingCourse(
            courseId = "267465694",
            classId = "155659413",
            courseName = "Web应用开发"
        )

        val html = """
            <li data="https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial?taskrefId=57311946&amp;msgId=0&amp;courseId=267465694&amp;userId=341187823&amp;clazzId=155659413&amp;type=work&amp;enc_task=8a509f4fb98e27cc1a35fb5c49907a0b" onclick="goTask(this);">
                <span class="spanImg">
                    <img aria-hidden="true" src="//mooc1-api.chaoxing.com/mooc-ans/images/work/phone/task-work-gray.png" />
                </span>
                <div role="option">
                    <p aria-hidden="true">http 协议与应用开发</p>
                    <span aria-hidden="true">已交</span>
                    <span aria-hidden="true">《Web应用开发...》</span>
                    <span aria-label="作业名称http 协议与应用开发作业状态已交课程Web应用开发..."></span>
                </div>
            </li>
            <li data="https://mooc1-api.chaoxing.com/mooc-ans/android/mtaskmsgspecial?taskrefId=56918192&amp;msgId=0&amp;courseId=267465694&amp;userId=341187823&amp;clazzId=155659413&amp;type=work&amp;enc_task=4e939720309e07730ba21b4ddd7c565d" onclick="goTask(this);">
                <span class="spanImg">
                    <img aria-hidden="true" src="//mooc1-api.chaoxing.com/mooc-ans/images/work/phone/task-work-gray.png" />
                </span>
                <div role="option">
                    <p aria-hidden="true">实验：servlet开发</p>
                    <span aria-hidden="true">已交</span>
                    <span aria-hidden="true">《Web应用开发...》</span>
                    <span aria-label="作业名称实验：servlet开发作业状态已交课程Web应用开发..."></span>
                </div>
            </li>
        """.trimIndent()

        val works = ChaoXingCourseDetailClient.parseStuWorkHtmlForCourse(html, course)
        assertEquals(2, works.size)
        assertEquals("57311946", works[0].workId)
        assertEquals("http 协议与应用开发", works[0].title)
        assertEquals("已交", works[0].statusText)

        assertEquals("56918192", works[1].workId)
        assertEquals("实验：servlet开发", works[1].title)
        assertEquals("已交", works[1].statusText)
    }

    @Test
    fun testParseQuestionsFromHtml() {
        val html = """
            <div class="wid750">
                <div class="singleQuesId" id="q_1">
                    <div class="padlr30 whiteBg">
                        <div class="stemDiv">
                            1.<span class="stemGray">(简答题，50分)</span>
                            <div class="Picdiv workTextWrap qtContent">
                                <p>基于本节课所学知识与要求，实现一个基于Java Servlet的登录验证系统。</p>
                            </div>
                        </div>
                        <span role="option">
                            <p class="Color888 line30">我的答案</p>
                            <div class="optionDiv workTextWrap stuAnswerContent" id="qb215283943">
                                <p>已完成核心Servlet代码并打包附件。</p>
                                <div class="editor-iframe">
                                    <iframe class="attach-iframe" filename="servlet-captcha-login.zip" filesize="8.49K" objectid="947babc1412b9ce532df444860018bab"></iframe>
                                </div>
                            </div>
                        </span>
                    </div>
                    <div class="grayDiv">教师批语：代码逻辑清晰，注解规范，通过测试。</div>
                </div>
                <div class="singleQuesId" id="q_2">
                    <div class="padlr30 whiteBg">
                        <div class="stemDiv">
                            2.<span class="stemGray">(简答题，50分)</span>
                            <div class="Picdiv workTextWrap qtContent">
                                <p>编写Java Servlet插播视频广告系统。</p>
                            </div>
                        </div>
                        <span role="option">
                            <p class="Color888 line30">我的答案</p>
                            <div class="optionDiv workTextWrap stuAnswerContent" id="qb215283944">
                                <p>已完成实验。</p>
                                <div class="editor-iframe">
                                    <iframe class="attach-iframe" filename="video_ad_system.zip" filesize="131.88K" objectid="9eb3f4c14170345a85d56a45f22b69e4"></iframe>
                                </div>
                            </div>
                        </span>
                    </div>
                    <div class="grayDiv">教师批语：功能完善，运行顺畅。</div>
                </div>
            </div>
        """.trimIndent()

        val questions = ChaoXingCourseDetailClient.parseQuestionsFromHtml(html)
        assertEquals(2, questions.size)

        val q1 = questions[0]
        assertEquals(1, q1.index)
        assertEquals("1", q1.id)
        assertEquals("4", q1.typeCode)
        assertTrue(q1.typeName.contains("简答题"))
        assertEquals("50 分", q1.scoreText)
        assertTrue(q1.cleanStem.contains("登录验证系统"))
        assertTrue(q1.cleanMyAnswer.contains("核心Servlet代码"))
        assertEquals(1, q1.studentAttachments.size)
        assertEquals("servlet-captcha-login.zip", q1.studentAttachments[0].name)
        assertEquals("947babc1412b9ce532df444860018bab", q1.studentAttachments[0].objectId)
        assertEquals((8.49 * 1024).toLong(), q1.studentAttachments[0].size)
        assertTrue(q1.teacherComment.contains("代码逻辑清晰"))

        val q2 = questions[1]
        assertEquals(2, q2.index)
        assertEquals("2", q2.id)
        assertEquals("4", q2.typeCode)
        assertTrue(q2.typeName.contains("简答题"))
        assertEquals("50 分", q2.scoreText)
        assertTrue(q2.cleanStem.contains("插播视频广告系统"))
        assertEquals(1, q2.studentAttachments.size)
        assertEquals("video_ad_system.zip", q2.studentAttachments[0].name)
        assertEquals("9eb3f4c14170345a85d56a45f22b69e4", q2.studentAttachments[0].objectId)
        assertTrue(q2.teacherComment.contains("功能完善"))
    }

    @Test
    fun testParseObjectiveQuestionsWithChoices() {
        val html = """
            <div class="singleQuesId" id="q_101" data="101" questiontypeid="0">
                <div class="padlr30 whiteBg">
                    <div class="stemDiv">
                        1.<span class="stemGray">(单选题, 2分)</span>
                        <div class="Picdiv workTextWrap qtContent">
                            <p>民族管弦乐团的编制中，有一个重要声部是在西洋管弦乐团中无法匹配的是</p>
                        </div>
                    </div>
                    <ul class="stem_answer">
                        <li class="clearfix"><i class="fl">A. </i><div class="fl">吹管乐器组</div></li>
                        <li class="clearfix"><i class="fl">B. </i><div class="fl">弹拨乐器组</div></li>
                        <li class="clearfix"><i class="fl">C. </i><div class="fl">拉弦乐器组</div></li>
                        <li class="clearfix"><i class="fl">D. </i><div class="fl">打击乐器组</div></li>
                    </ul>
                    <div class="stuAnswer">我的答案: B</div>
                    <div class="rightAnswer">正确答案: B</div>
                    <div class="myAnalysis">答案解析: 弹拨乐器组是中国民族管弦乐团独有的声部。</div>
                </div>
            </div>
        """.trimIndent()

        val questions = ChaoXingCourseDetailClient.parseQuestionsFromHtml(html)
        assertEquals(1, questions.size)

        val q = questions[0]
        assertEquals(1, q.index)
        assertEquals("0", q.typeCode)
        assertEquals("单选题", q.typeName)
        assertEquals("2 分", q.scoreText)
        assertTrue(q.cleanStem.contains("民族管弦乐团"))
        assertFalse(q.cleanStem.startsWith("1."))
        assertEquals(4, q.options.size)
        assertEquals("A", q.options[0].letter)
        assertEquals("吹管乐器组", q.options[0].content)
        assertEquals("B", q.options[1].letter)
        assertEquals("弹拨乐器组", q.options[1].content)
        assertTrue(q.options[1].isSelected)
        assertTrue(q.options[1].isCorrect)
        assertEquals("B", q.cleanMyAnswer)
        assertEquals("B", q.correctAnswer)
        assertTrue(q.explanation.contains("弹拨乐器组"))
    }

    @Test
    fun testParsePCStyleQuestionsWithZyUlTopAndPyAnswer() {
        val html = """
            <div class="TiMu" data="9901" id="q_9901">
                <div class="Zy_TItle">
                    <div class="clearfix">
                        <span class="newZy_TItle">【单选题】</span>
                        <span>民族管弦乐团的编制中，有一个重要声部是在西洋管弦乐团中无法匹配的是 （2.0分）</span>
                    </div>
                </div>
                <ul class="Zy_ulTop">
                    <li>
                        <span class="num_option" data="A">A.</span>
                        <a class="after">吹管乐器组</a>
                    </li>
                    <li class="check_answer">
                        <span class="num_option" data="B">B.</span>
                        <a class="after">弹拨乐器组</a>
                    </li>
                    <li>
                        <span class="num_option" data="C">C.</span>
                        <a class="after">拉弦乐器组</a>
                    </li>
                    <li>
                        <span class="num_option" data="D">D.</span>
                        <a class="after">打击乐器组</a>
                    </li>
                </ul>
                <div class="Py_answer clearfix">
                    <span>我的答案：</span><i class="colorGreen">B</i>
                    <span>正确答案：</span><i class="colorGreen">B</i>
                </div>
                <div class="Py_tk">
                    <span>答案解析：</span>弹拨乐器组（如琵琶、筝、阮、扬琴等）是华夏民族管弦乐独有之编制。
                </div>
            </div>
        """.trimIndent()

        val questions = ChaoXingCourseDetailClient.parseQuestionsFromHtml(html)
        assertEquals(1, questions.size)

        val q = questions[0]
        assertEquals(1, q.index)
        assertEquals("单选题", q.typeName)
        assertEquals("2.0 分", q.scoreText)
        assertTrue(q.cleanStem.contains("民族管弦乐团"))
        assertFalse(q.cleanStem.contains("【单选题】"))
        assertFalse(q.cleanStem.contains("2.0分"))
        assertEquals(4, q.options.size)
        assertEquals("A", q.options[0].letter)
        assertEquals("吹管乐器组", q.options[0].content)
        assertEquals("B", q.options[1].letter)
        assertEquals("弹拨乐器组", q.options[1].content)
        assertTrue(q.options[1].isSelected)
        assertTrue(q.options[1].isCorrect)
        assertEquals("B", q.cleanMyAnswer)
        assertEquals("B", q.correctAnswer)
        assertTrue(q.explanation.contains("弹拨乐器组"))
    }

    @Test
    fun testNestedElementsDeduplication() {
        val html = """
            <div class="questionBlock" id="qb_1">
                <div class="TiMu" data="888" id="timu_888">
                    <div class="singleQuesId" id="single_888">
                        <div class="padlr30 whiteBg">
                            <div class="stemDiv">
                                <span class="stemGray">(单选题, 5分)</span>
                                <div class="Picdiv">民族管弦乐团的编制中，有一个重要声部是在西洋管弦乐团中无法匹配的是</div>
                            </div>
                            <ul class="Zy_ulTop">
                                <li><span class="num_option" data="A">A.</span><a class="after">吹管乐器组</a></li>
                                <li><span class="num_option" data="B">B.</span><a class="after">弹拨乐器组</a></li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val questions = ChaoXingCourseDetailClient.parseQuestionsFromHtml(html)
        // 严格杜绝由于嵌套容器导致的题目重复 3 次 Bug！必须去重为唯一的 1 道题！
        assertEquals(1, questions.size)
        val q = questions[0]
        assertEquals(1, q.index)
        assertEquals("单选题", q.typeName)
        assertEquals(2, q.options.size)
        assertEquals("吹管乐器组", q.options[0].content)
        assertEquals("弹拨乐器组", q.options[1].content)
    }

    @Test
    fun testParseSizeStrToBytes() {
        assertEquals(1024L, ChaoXingCourseDetailClient.parseSizeStrToBytes("1K"))
        assertEquals(2048L, ChaoXingCourseDetailClient.parseSizeStrToBytes("2KB"))
        assertEquals(1048576L, ChaoXingCourseDetailClient.parseSizeStrToBytes("1MB"))
        assertEquals(5242880L, ChaoXingCourseDetailClient.parseSizeStrToBytes("5M"))
        assertEquals(100L, ChaoXingCourseDetailClient.parseSizeStrToBytes("100B"))
        assertEquals(0L, ChaoXingCourseDetailClient.parseSizeStrToBytes(""))
    }

    @Test
    fun testFormatSizeForChaoxing() {
        assertEquals("0B", ChaoXingCourseDetailClient.formatSizeForChaoxing(0L))
        assertEquals("512B", ChaoXingCourseDetailClient.formatSizeForChaoxing(512L))
        assertEquals("8.49K", ChaoXingCourseDetailClient.formatSizeForChaoxing((8.49 * 1024).toLong()))
        assertEquals("2.50M", ChaoXingCourseDetailClient.formatSizeForChaoxing((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testBuildAttachmentIframeHtml() {
        val att = ChaoXingCourseAttachment(
            objectId = "947babc1412b9ce532df444860018bab",
            name = "servlet-captcha-login.zip",
            size = (8.49 * 1024).toLong()
        )
        val iframeHtml = ChaoXingCourseDetailClient.buildAttachmentIframeHtml(att)
        assertTrue(iframeHtml.contains("class=\"attach-iframe\""))
        assertTrue(iframeHtml.contains("filename=\"servlet-captcha-login.zip\""))
        assertTrue(iframeHtml.contains("filesize=\"8.49K\""))
        assertTrue(iframeHtml.contains("filetype=\"zip\""))
        assertTrue(iframeHtml.contains("objectid=\"947babc1412b9ce532df444860018bab\""))
        assertTrue(iframeHtml.contains("src=\"/ananas/common-modules/attachment/insertCloud.html\""))

        // Verify name parameter base64 decoding (use \sname to avoid matching filename)
        val nameMatch = Regex("""\sname="([^"]+)"""").find(iframeHtml)
        assertNotNull(nameMatch)
        val encodedName = nameMatch!!.groupValues[1]
        val decodedUrl = java.net.URLDecoder.decode(String(java.util.Base64.getDecoder().decode(encodedName), Charsets.UTF_8), "UTF-8")
        assertTrue(decodedUrl.contains("\"objectid\":\"947babc1412b9ce532df444860018bab\""))
        assertTrue(decodedUrl.contains("\"name\":\"servlet-captcha-login.zip\""))
        assertTrue(decodedUrl.contains("\"type\":\"zip\""))
        assertTrue(decodedUrl.contains("\"size\":\"8.49K\""))
    }

    @Test
    fun testChaoXingExamNativeConversion() {
        val course = ChaoXingCourse(
            courseId = "202501",
            classId = "303001",
            courseName = "中国当代音乐名家名作"
        )

        val q1 = ChaoXingQuestionItem(
            index = 1,
            id = "q_101",
            typeCode = "0",
            typeName = "单选题",
            scoreText = "2 分",
            cleanStem = "民族管弦乐团的编制中，有一个重要声部是在西洋管弦乐团中无法匹配的是",
            options = listOf(
                ChaoXingQuestionOption("A", "吹管乐器组", isSelected = false, isCorrect = false),
                ChaoXingQuestionOption("B", "弹拨乐器组", isSelected = true, isCorrect = true),
                ChaoXingQuestionOption("C", "拉弦乐器组", isSelected = false, isCorrect = false),
                ChaoXingQuestionOption("D", "打击乐器组", isSelected = false, isCorrect = false)
            ),
            correctAnswer = "B",
            cleanMyAnswer = "B",
            explanation = "弹拨乐器组是中国民族管弦乐团独有的特色声部。"
        )

        val q2 = ChaoXingQuestionItem(
            index = 2,
            id = "q_102",
            typeCode = "3",
            typeName = "判断题",
            scoreText = "1 分",
            cleanStem = "《梁祝》小提琴协奏曲创作于1959年。",
            options = listOf(
                ChaoXingQuestionOption("✓", "正确", isSelected = true, isCorrect = true),
                ChaoXingQuestionOption("✕", "错误", isSelected = false, isCorrect = false)
            ),
            correctAnswer = "✓",
            cleanMyAnswer = "✓"
        )

        val examDetail = ChaoXingExamDetail(
            examId = "998877",
            title = "当代音乐期末随堂测验",
            courseName = course.courseName,
            statusText = "已完成",
            score = "98",
            timeRange = "2026-11-02 14:00 至 2026-11-02 15:30",
            limitTime = 90,
            rawQuestions = listOf(q1, q2),
            analysisUrl = "https://stat2-ans.chaoxing.com/report?examId=998877",
            isSubmitted = true,
            isGraded = true
        )

        val tcDetail = examDetail.toTronClassExamDetail(course)

        // 验证考试元数据
        assertEquals("当代音乐期末随堂测验", tcDetail.exam.getCleanTitle())
        assertEquals(98.0, tcDetail.finalScore)
        assertEquals(98.0, tcDetail.currentScore)
        assertEquals(90, tcDetail.exam.limitTime)
        assertTrue(tcDetail.exam.isClosed)
        assertEquals(2, tcDetail.subjects.size)

        // 验证单选题转换
        val s1 = tcDetail.subjects[0]
        assertEquals("single_selection", s1.type)
        assertEquals("民族管弦乐团的编制中，有一个重要声部是在西洋管弦乐团中无法匹配的是", s1.getCleanTitle())
        assertEquals(4, s1.options.size)
        assertEquals("B. 弹拨乐器组", s1.options[1].content)
        assertTrue(s1.options[1].isAnswer)
        assertEquals("B", tcDetail.myAnswers[s1.id.toString()])
        assertEquals("B", tcDetail.correctAnswers[s1.id.toString()])
        assertEquals(true, tcDetail.isCorrectMap[s1.id.toString()])

        // 验证判断题转换
        val s2 = tcDetail.subjects[1]
        assertEquals("true_false", s2.type)
        assertEquals(2, s2.options.size)
        assertEquals("✓", tcDetail.myAnswers[s2.id.toString()])
        assertEquals("✓", tcDetail.correctAnswers[s2.id.toString()])
        assertEquals(true, tcDetail.isCorrectMap[s2.id.toString()])
    }
}
