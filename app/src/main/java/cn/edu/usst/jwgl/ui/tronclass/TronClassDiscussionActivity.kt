package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import cn.edu.usst.jwgl.data.model.TronClassDiscussion
import cn.edu.usst.jwgl.ui.MainActivity

class TronClassDiscussionActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ACTIVITY_ID = "extra_activity_id"
        private const val EXTRA_COURSE_ID = "extra_course_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_COURSE_NAME = "extra_course_name"
        private const val EXTRA_TOPIC_ID = "extra_topic_id"
        private const val EXTRA_DISCUSSION = "extra_discussion"

        fun start(
            context: Context,
            activityId: Long,
            courseId: Long,
            title: String,
            courseName: String,
            topicId: Long = 0L,
            discussion: TronClassDiscussion? = null
        ) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassDiscussion(activityId, courseId, title, courseName, topicId, discussion)
            } else {
                val intent = Intent(context, TronClassDiscussionActivity::class.java).apply {
                    putExtra(EXTRA_ACTIVITY_ID, activityId)
                    putExtra(EXTRA_COURSE_ID, courseId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_COURSE_NAME, courseName)
                    putExtra(EXTRA_TOPIC_ID, topicId)
                    discussion?.let { putExtra(EXTRA_DISCUSSION, it) }
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode

        if (savedInstanceState == null) {
            val activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, 0L)
            val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
            val titleText = intent.getStringExtra(EXTRA_TITLE) ?: "课堂讨论"
            val courseNameText = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
            val topicId = intent.getLongExtra(EXTRA_TOPIC_ID, 0L)
            @Suppress("DEPRECATION")
            val discussion = intent.getSerializableExtra(EXTRA_DISCUSSION) as? TronClassDiscussion

            supportFragmentManager.beginTransaction()
                .replace(
                    android.R.id.content,
                    TronClassDiscussionFragment.newInstance(
                        activityId = activityId,
                        courseId = courseId,
                        title = titleText,
                        courseName = courseNameText,
                        topicId = topicId,
                        discussion = discussion
                    )
                )
                .commit()
        }
    }
}
