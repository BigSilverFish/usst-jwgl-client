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
        assertEquals("截止时间: 2026-11-15 23:59", work1.deadline)
        assertEquals("/mooc2/work/task?courseId=12345&workId=554433&enc=testtoken", work1.taskUrl)

        val work2 = works[1]
        assertEquals("554434", work2.workId)
        assertEquals("第4次实验报告: Vue3组件设计", work2.title)
        assertEquals("未交", work2.statusText)
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
}
