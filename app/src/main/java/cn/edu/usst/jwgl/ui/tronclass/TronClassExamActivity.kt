package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
                    putExtra("exam_id", examId)
                    putExtra("course_id", courseId)
                    putExtra("title", title)
                    putExtra("course_name", courseName)
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
            val examId = intent.getLongExtra("exam_id", 0)
            val courseId = intent.getLongExtra("course_id", 0)
            val examTitle = intent.getStringExtra("title") ?: "测验详情"
            val courseName = intent.getStringExtra("course_name") ?: ""

            supportFragmentManager.beginTransaction()
                .replace(
                    android.R.id.content,
                    TronClassExamFragment.newInstance(examId, courseId, examTitle, courseName)
                )
                .commit()
        }
    }
}
