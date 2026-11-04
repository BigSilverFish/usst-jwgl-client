package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import cn.edu.usst.jwgl.ui.BaseActivity
import cn.edu.usst.jwgl.ui.MainActivity

class TronClassCourseDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_UNIFIED_COURSE = "extra_unified_course"
        const val EXTRA_COURSE = "extra_course"
        const val EXTRA_CHAOXING_COURSE = "extra_chaoxing_course"
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_COURSE_CODE = "extra_course_code"
        const val EXTRA_DEPT_NAME = "extra_dept_name"

        fun start(context: Context, unifiedCourse: UnifiedCourse) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassCourseDetail(unifiedCourse)
            } else {
                val intent = Intent(context, TronClassCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_UNIFIED_COURSE, unifiedCourse)
                    putExtra(EXTRA_COURSE, unifiedCourse.rawTronClassCourse)
                    putExtra(EXTRA_CHAOXING_COURSE, unifiedCourse.rawChaoXingCourse)
                    putExtra(EXTRA_PLATFORM, unifiedCourse.platform)
                    putExtra(EXTRA_COURSE_ID, unifiedCourse.rawTronClassCourse?.id ?: unifiedCourse.originalCourseId.toLongOrNull() ?: 0L)
                    putExtra(EXTRA_COURSE_NAME, unifiedCourse.cleanName)
                    putExtra(EXTRA_COURSE_CODE, unifiedCourse.courseCode ?: "")
                    putExtra(EXTRA_DEPT_NAME, unifiedCourse.department ?: "")
                }
                context.startActivity(intent)
            }
        }

        fun start(context: Context, course: TronClassCourse) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassCourseDetail(course)
            } else {
                val intent = Intent(context, TronClassCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_COURSE, course)
                    putExtra(EXTRA_COURSE_ID, course.id)
                    putExtra(EXTRA_COURSE_NAME, course.getCleanName())
                    putExtra(EXTRA_COURSE_CODE, course.courseCode ?: "")
                    putExtra(EXTRA_DEPT_NAME, course.department?.name ?: "")
                }
                context.startActivity(intent)
            }
        }

        fun start(context: Context, courseId: Long, courseName: String) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassCourseDetail(courseId, courseName)
            } else {
                val intent = Intent(context, TronClassCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_COURSE_ID, courseId)
                    putExtra(EXTRA_COURSE_NAME, courseName)
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
            val fragment = TronClassCourseDetailFragment().apply {
                arguments = intent.extras
            }
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }
}
