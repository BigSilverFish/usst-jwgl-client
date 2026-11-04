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
import cn.edu.usst.jwgl.data.model.ChaoXingExam
import cn.edu.usst.jwgl.ui.MainActivity

class TronClassExamActivity : AppCompatActivity() {

    companion object {
        fun start(
            context: Context,
            examId: Long,
            courseId: Long,
            title: String,
            courseName: String
        ) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassExam(examId, courseId, title, courseName)
            } else {
                val intent = Intent(context, TronClassExamActivity::class.java).apply {
                    putExtra(TronClassExamFragment.EXTRA_PLATFORM, "TRONCLASS")
                    putExtra("exam_id", examId)
                    putExtra("course_id", courseId)
                    putExtra("title", title)
                    putExtra("course_name", courseName)
                }
                context.startActivity(intent)
            }
        }

        fun startForChaoXing(
            context: Context,
            exam: ChaoXingExam,
            course: ChaoXingCourse? = null
        ) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openChaoXingExam(exam, course)
            } else {
                val intent = Intent(context, TronClassExamActivity::class.java).apply {
                    putExtra(TronClassExamFragment.EXTRA_PLATFORM, "CHAOXING")
                    putExtra(TronClassExamFragment.EXTRA_CHAOXING_EXAM, exam)
                    course?.let { putExtra(TronClassExamFragment.EXTRA_CHAOXING_COURSE, it) }
                    putExtra("title", exam.title)
                    putExtra("course_name", course?.courseName ?: exam.courseName)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        val isDarkMode = cn.edu.usst.jwgl.util.ThemeManager.isNightMode(this)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode

        if (savedInstanceState == null) {
            val platform = intent.getStringExtra(TronClassExamFragment.EXTRA_PLATFORM) ?: "TRONCLASS"
            val fragment = if (platform == "CHAOXING") {
                val exam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(TronClassExamFragment.EXTRA_CHAOXING_EXAM, ChaoXingExam::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(TronClassExamFragment.EXTRA_CHAOXING_EXAM) as? ChaoXingExam
                }
                val course = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(TronClassExamFragment.EXTRA_CHAOXING_COURSE, ChaoXingCourse::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(TronClassExamFragment.EXTRA_CHAOXING_COURSE) as? ChaoXingCourse
                }
                if (exam != null) {
                    TronClassExamFragment.newInstanceForChaoXing(exam, course)
                } else {
                    TronClassExamFragment.newInstance(0L, 0L, intent.getStringExtra("title") ?: "", intent.getStringExtra("course_name") ?: "")
                }
            } else {
                val examId = intent.getLongExtra("exam_id", 0)
                val courseId = intent.getLongExtra("course_id", 0)
                val examTitle = intent.getStringExtra("title") ?: "测验详情"
                val courseName = intent.getStringExtra("course_name") ?: ""
                TronClassExamFragment.newInstance(examId, courseId, examTitle, courseName)
            }

            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }
}
