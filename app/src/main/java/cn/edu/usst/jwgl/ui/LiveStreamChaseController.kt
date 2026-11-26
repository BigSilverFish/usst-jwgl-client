package cn.edu.usst.jwgl.ui

import android.util.Log
import android.view.MenuItem
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 直播课低延迟自动追帧与手动同步控制器
 */
internal class LiveStreamChaseController(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView
) {
    companion object {
        private const val TAG = "LiveStreamChaseCtrl"
    }

    var isLiveChaseFrameEnabled = true

    fun startLiveChaseFrameGuard() {
        if (!isLiveChaseFrameEnabled) return
        val js = """
            (function() {
                window.__usst_live_chase_enabled = true;
                if (window.__usst_live_chase_initialized) {
                    return;
                }
                window.__usst_live_chase_initialized = true;

                console.log("[USST Live] 🚀 启动直播低延迟自适应追帧守护引擎 (目标延迟: < 1.5s)");

                function getAllVideos() {
                    var list = Array.from(document.querySelectorAll('video'));
                    var iframes = document.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length; f++) {
                        try {
                            var fVideos = iframes[f].contentDocument.querySelectorAll('video');
                            for (var fv = 0; fv < fVideos.length; fv++) {
                                list.push(fVideos[fv]);
                            }
                        } catch(e) {}
                    }
                    return list;
                }

                function isLiveStream(v) {
                    if (!v) return false;
                    var hash = window.location.hash || '';
                    var href = window.location.href || '';
                    // 过滤明确的点播回放页面
                    if (hash.indexOf('play-video') !== -1 || hash.indexOf('videoList') !== -1 || href.indexOf('vod') !== -1) {
                        return false;
                    }
                    if (hash.indexOf('live') !== -1 || href.indexOf('live') !== -1 || (document.title && document.title.indexOf('直播') !== -1)) {
                        return true;
                    }
                    if (v.duration === Infinity || isNaN(v.duration)) return true;
                    if (v.seekable && v.seekable.length > 0 && v.seekable.start(0) > 0) return true;
                    return false;
                }

                function checkAndChase(v) {
                    if (!window.__usst_live_chase_enabled) {
                        if (v.playbackRate !== 1.0) v.playbackRate = 1.0;
                        return;
                    }
                    if (v.paused || v.ended || v.seeking || (v.readyState || 0) < 2) return;
                    if (!isLiveStream(v)) return;
                    if (!v.seekable || v.seekable.length === 0) return;

                    var liveEnd = v.seekable.end(v.seekable.length - 1);
                    var curr = v.currentTime;
                    var lag = liveEnd - curr;

                    // 1. 低延迟巡航区 (<= 1.8 秒)：恢复 1.0x 原速，保证音画平稳无抖动
                    if (lag <= 1.8) {
                        if (v.playbackRate !== 1.0) {
                            console.log("[USST Live] 延迟已处于极低安全区间 (" + lag.toFixed(2) + "s)，恢复 1.0x 原速");
                            v.playbackRate = 1.0;
                        }
                        return;
                    }

                    // 2. 超大滞后区 (>= 4.5 秒，如校外初始加载或网络恢复后积累的 15-20s 延迟)：
                    // 毫秒级闪击跳转，直接空降至最新前沿（保留 0.8s 极小缓冲防卡顿）
                    if (lag >= 4.5) {
                        var target = Math.max(0, liveEnd - 0.8);
                        console.log("[USST Live] ⚡ 检测到超大延迟 (" + lag.toFixed(2) + "s)，执行瞬时追帧跳跃 -> " + target.toFixed(2) + "s");
                        v.currentTime = target;
                        v.playbackRate = 1.0;
                        return;
                    }

                    // 3. 轻度滞后区 (1.8s < lag < 4.5s)：
                    // 自适应 1.25x 平滑追赶，不损伤音调，悄无声息消灭积攒延迟
                    if (lag > 1.8) {
                        if (v.playbackRate !== 1.25) {
                            console.log("[USST Live] ⏩ 检测到轻度滞后 (" + lag.toFixed(2) + "s)，开启 1.25x 平滑追赶");
                            v.playbackRate = 1.25;
                        }
                    }
                }

                // 周期守护心跳：每 1.5 秒轮询检测
                if (window.__usst_live_chase_timer) {
                    clearInterval(window.__usst_live_chase_timer);
                }
                window.__usst_live_chase_timer = setInterval(function() {
                    var videos = getAllVideos();
                    for (var i = 0; i < videos.length; i++) {
                        checkAndChase(videos[i]);
                    }
                }, 1500);

                // 注册主动同步函数供 Native 一键调用
                window.__usst_sync_live_now = function() {
                    var videos = getAllVideos();
                    var list = [];
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.seekable && v.seekable.length > 0) {
                            var liveEnd = v.seekable.end(v.seekable.length - 1);
                            var beforeLag = liveEnd - v.currentTime;
                            var target = Math.max(0, liveEnd - 0.7);
                            v.currentTime = target;
                            v.playbackRate = 1.0;
                            var afterLag = liveEnd - target;
                            list.push({
                                index: i + 1,
                                beforeLag: beforeLag,
                                afterLag: afterLag
                            });
                        }
                    }
                    return JSON.stringify(list);
                };
            })();
        """.trimIndent()
        webViewProvider().evaluateJavascript(js, null)
    }

    fun toggleLiveChaseFrame(item: android.view.MenuItem) {
        isLiveChaseFrameEnabled = !isLiveChaseFrameEnabled
        item.title = if (isLiveChaseFrameEnabled) "🚀 直播低延迟追帧 (已开启)" else "🚀 直播低延迟追帧 (已暂停)"
        val enabledStr = isLiveChaseFrameEnabled.toString()
        webViewProvider().evaluateJavascript(
            "window.__usst_live_chase_enabled = $enabledStr; if(!$enabledStr) { Array.from(document.querySelectorAll('video')).forEach(function(v){ v.playbackRate = 1.0; }); }",
            null
        )
        if (isLiveChaseFrameEnabled) {
            startLiveChaseFrameGuard()
            Toast.makeText(activity, "已开启直播低延迟追帧：智能消除 15-20s 缓冲延迟", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "已暂停直播低延迟追帧：恢复播放器默认缓冲", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerManualSyncLiveNow() {
        val js = "window.__usst_sync_live_now ? window.__usst_sync_live_now() : '[]'"
        webViewProvider().evaluateJavascript(js) { res ->
            try {
                val clean = if (res != null && res != "null" && res.startsWith("\"") && res.endsWith("\"")) {
                    org.json.JSONTokener(res).nextValue().toString()
                } else res ?: "[]"
                val array = org.json.JSONArray(clean)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    val before = first.optDouble("beforeLag", 0.0)
                    val after = first.optDouble("afterLag", 0.0)
                    if (before > 1.5) {
                        Toast.makeText(                            activity,
                            "⚡ 已将直播画面从 ${String.format("%.1f", before)}秒 延迟追平至最新 (${String.format("%.1f", after)}秒)！",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(activity, "当前直播已处于极低延迟状态 (约 ${String.format("%.1f", before)}秒)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(activity, "未检测到可追帧的活跃直播流", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing sync live result: $res", e)
                Toast.makeText(activity, "已向播放器发送追帧对齐指令", Toast.LENGTH_SHORT).show()
            }
        }
    }

}
