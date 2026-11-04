package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import cn.edu.usst.jwgl.ui.MainActivity

class TronClassDownloadActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val mainActivity = context as? MainActivity
                ?: (context as? android.content.ContextWrapper)?.baseContext as? MainActivity
            if (mainActivity != null) {
                mainActivity.openTronClassDownloads()
            } else {
                val intent = Intent(context, TronClassDownloadActivity::class.java)
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
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, TronClassDownloadFragment.newInstance())
                .commit()
        }
    }
}
