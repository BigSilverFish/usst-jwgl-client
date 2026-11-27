package cn.edu.usst.jwgl.data.model

import cn.edu.usst.jwgl.ui.tronclass.TronClassCourseDetailFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TronClassActivityTypeTest {

    @Test
    fun `live activity types resolve to LIVE category`() {
        val types = listOf("lecture_live", "live", "virtual_classroom", "tencent_meeting", "zoom", "classroom_live")
        for (type in types) {
            val act = TronClassActivityItem(id = 1L, title = "直播课", type = type)
            assertEquals("Expected LIVE for $type", TronClassActivityCategory.LIVE, act.getCategory())
            assertTrue(act.isLiveActivity())
            assertFalse(act.isTabInteractionEligible())
        }
    }

    @Test
    fun `recorded lesson types resolve to RECORDED_LESSON category`() {
        val types = listOf("lesson", "lesson_replay", "recorded_lesson", "recording", "replay", "classroom_record")
        for (type in types) {
            val act = TronClassActivityItem(id = 2L, title = "录播课", type = type)
            assertEquals("Expected RECORDED_LESSON for $type", TronClassActivityCategory.RECORDED_LESSON, act.getCategory())
            assertTrue(act.isRecordedLessonActivity())
            assertFalse(act.isTabInteractionEligible())
        }
    }

    @Test
    fun `interaction types resolve to INTERACTION category and are eligible for interaction tab`() {
        val expectedLabels = mapOf(
            "interaction" to "课堂互动",
            "classroom" to "课堂互动",
            "classroom_interaction" to "课堂互动",
            "questionnaire" to "问卷调查",
            "feedback" to "课堂反馈",
            "vote" to "随堂投票",
            "voting" to "随堂投票",
            "poll" to "随堂投票",
            "brainstorming" to "头脑风暴",
            "group_discuss" to "分组讨论",
            "race" to "课堂抢答",
            "select_student" to "随机选人"
        )
        for ((type, label) in expectedLabels) {
            val act = TronClassActivityItem(id = 3L, title = "互动活动", type = type)
            assertEquals("Expected INTERACTION for $type", TronClassActivityCategory.INTERACTION, act.getCategory())
            assertTrue(act.isInteractionActivity())
            assertTrue("Expected $type to be eligible for interaction tab", act.isTabInteractionEligible())
            assertEquals(label, act.getInteractionTypeLabel())
        }
    }

    @Test
    fun `standard activity types map cleanly to their categories`() {
        val cases = mapOf(
            "material" to TronClassActivityCategory.MATERIAL,
            "online_video" to TronClassActivityCategory.ONLINE_VIDEO,
            "homework" to TronClassActivityCategory.HOMEWORK,
            "exam" to TronClassActivityCategory.EXAM,
            "quiz" to TronClassActivityCategory.EXAM,
            "forum" to TronClassActivityCategory.FORUM,
            "web_link" to TronClassActivityCategory.WEB_LINK,
            "page" to TronClassActivityCategory.PAGE,
            "chaoxing_chapter" to TronClassActivityCategory.CHAOXING_CHAPTER
        )
        for ((type, expected) in cases) {
            val act = TronClassActivityItem(id = 4L, type = type)
            assertEquals(expected, act.getCategory())
            assertEquals(type == "forum", act.isTabInteractionEligible())
        }
    }

    @Test
    fun `getFormattedScheduleTime formats same-day UTC ISO start and end`() {
        val act = TronClassActivityItem(
            id = 5L,
            type = "lesson",
            startTime = "2025-09-26T01:55:00Z",
            endTime = "2025-09-26T02:40:00Z"
        )
        val formatted = act.getFormattedScheduleTime()
        assertTrue("Expected non-blank formatted schedule time", formatted.isNotBlank())
        assertTrue("Expected range separator '-' in $formatted", formatted.contains("-"))
    }

    @Test
    fun `interaction tab merger includes interaction and forum activities without topic id collision`() {
        val interactionAct = TronClassActivityItem(id = 100L, title = "随堂投票1", type = "vote").apply {
            completeness = "full"
        }
        val forumAct = TronClassActivityItem(id = 200L, title = "专题讨论", type = "forum")
        val lessonAct = TronClassActivityItem(id = 300L, title = "录播", type = "lesson")
        val liveAct = TronClassActivityItem(id = 400L, title = "直播", type = "lecture_live")

        // Topic with id=100 must NOT collide with interactionAct id=100 because topicDetail != null
        val collidingTopic = TronClassDiscussion.fromTopic(
            TronClassTopicDetail(id = 100L, title = "学生发帖", content = "hello")
        )
        val forumHeaderFromApi = TronClassDiscussion.fromActivity(forumAct).apply {
            categoryId = 99L
        }

        val merged = TronClassCourseDetailFragment.InteractionTabMerger.merge(
            activities = listOf(interactionAct, forumAct, lessonAct, liveAct),
            apiDiscussions = listOf(forumHeaderFromApi, collidingTopic)
        )

        assertEquals(3, merged.size)
        assertEquals(100L, merged[0].activityItem?.id)
        assertTrue(merged[0].activityItem?.isInteractionActivity() == true)
        assertEquals("full", merged[0].activityItem?.completeness)
        assertEquals(200L, merged[1].activityItem?.id)
        assertEquals(99L, merged[1].categoryId)
        assertEquals(100L, merged[2].topicDetail?.id)
    }
}
