package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingWork
import cn.edu.usst.jwgl.ui.MainActivity

class TronClassHomeworkActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ACTIVITY_ID = "extra_activity_id"
        private const val EXTRA_COURSE_ID = "extra_course_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_COURSE_NAME = "extra_course_name"
        private const val EXTRA_PLATFORM = "extra_platform"
        private const val EXTRA_CHAOXING_WORK = "extra_chaoxing_work"
        private const val EXTRA_CHAOXING_COURSE = "extra_chaoxing_course"

        fun start(context: Context, activityId: Long, courseId: Long, title: String, courseName: String) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassHomework(activityId, courseId, title, courseName)
            } else {
                val intent = Intent(context, TronClassHomeworkActivity::class.java).apply {
                    putExtra(EXTRA_PLATFORM, "TRONCLASS")
                    putExtra(EXTRA_ACTIVITY_ID, activityId)
                    putExtra(EXTRA_COURSE_ID, courseId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_COURSE_NAME, courseName)
                }
                context.startActivity(intent)
            }
        }

        fun startForChaoXing(context: Context, work: ChaoXingWork, course: ChaoXingCourse? = null) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openChaoXingHomework(work, course)
            } else {
                val intent = Intent(context, TronClassHomeworkActivity::class.java).apply {
                    putExtra(EXTRA_PLATFORM, "CHAOXING")
                    putExtra(EXTRA_CHAOXING_WORK, work)
                    course?.let { putExtra(EXTRA_CHAOXING_COURSE, it) }
                    putExtra(EXTRA_TITLE, work.title)
                    putExtra(EXTRA_COURSE_NAME, course?.courseName ?: work.courseName)
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
            val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: "TRONCLASS"
            val fragment = if (platform == "CHAOXING") {
                val work = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CHAOXING_WORK, ChaoXingWork::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CHAOXING_WORK) as? ChaoXingWork
                }
                val course = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CHAOXING_COURSE, ChaoXingCourse::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CHAOXING_COURSE) as? ChaoXingCourse
                }
                if (work != null) {
                    TronClassHomeworkFragment.newInstanceForChaoXing(work, course)
                } else {
                    val activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, 0L)
                    val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
                    val titleText = intent.getStringExtra(EXTRA_TITLE) ?: "作业详情"
                    val courseNameText = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
                    TronClassHomeworkFragment.newInstance(activityId, courseId, titleText, courseNameText)
                }
            } else {
                val activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, 0L)
                val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
                val titleText = intent.getStringExtra(EXTRA_TITLE) ?: "作业详情"
                val courseNameText = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
                TronClassHomeworkFragment.newInstance(activityId, courseId, titleText, courseNameText)
            }

            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }
}
