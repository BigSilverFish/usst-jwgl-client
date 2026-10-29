package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import cn.edu.usst.jwgl.ui.JwglWebActivity

/**
 * 超星学习通免密直通车导航助手
 *
 * 核心功能：
 * 1. 统一课程章节主页调度 (visit/stucoursemiddle)：服务端根据 UID 自动派发至最新章节
 * 2. 统一移动端作业做题界面调度 (work/phone/work)：自适应手机端做题与富文本公式提交
 * 3. 自动结合 CookieSyncHelper 进行免密 Cookie 灌入
 */
object ChaoXingNavigator {

    /**
     * 打开超星任意 Web 页面（课程、作业、公告等），全自动注入免密 Cookie
     */
    fun openChaoXingWebPage(context: Context, url: String, title: String? = null) {
        val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://mooc1.chaoxing.com" + if (url.startsWith("/")) url else "/$url"
        }
        JwglWebActivity.start(
            context = context,
            url = safeUrl,
            title = title
        )
    }

    /**
     * 直达超星课程章节与学习主页
     */
    fun openCourse(context: Context, courseId: String, classId: String, courseName: String) {
        // 利用 stucoursemiddle 中间调度入口，服务端根据 Cookie 自动派发至最新章节
        val url = "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=$courseId&clazzid=$classId&vc=1"
        openChaoXingWebPage(context, url, courseName)
    }

    /**
     * 直达超星作业做题界面 (优先移动端 phone/work，兼容 detailUrl)
     */
    fun openHomework(
        context: Context,
        courseId: String,
        classId: String?,
        workId: String,
        title: String,
        detailUrl: String? = null
    ) {
        val targetUrl = when {
            !detailUrl.isNullOrBlank() -> detailUrl
            else -> "https://mooc1.chaoxing.com/work/phone/work?courseId=$courseId&classId=${classId ?: ""}&workId=$workId"
        }
        openChaoXingWebPage(context, targetUrl, title)
    }
}
