package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.NumberCodeLookupResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.scanner.MlKitScannerActivity
import cn.edu.usst.jwgl.util.CoordinateUtils
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课堂签到即时强弹窗 Activity
 * 支持锁屏直接唤醒点亮弹出、亮屏桌面顶层覆盖提醒，直接在弹窗内输入口令或选点完成签到。
 */
class RollcallAlertActivity : AppCompatActivity() {

    private var rollcallId: Long = 0L
    private var courseName: String? = null
    private var rollcallType: String = TYPE_NUMBER
    private var classroom: String? = null
    private var buildingName: String? = null
    private var targetLatitude: Double? = null
    private var targetLongitude: Double? = null

    private lateinit var tvAlertCourseName: TextView
    private lateinit var tvRollcallTypeBadge: TextView
    private lateinit var tvRollcallStatusTip: TextView
    private lateinit var layoutNumberSection: LinearLayout
    private lateinit var layoutLocationSection: LinearLayout
    private lateinit var layoutGenericSection: LinearLayout
    private lateinit var tvLocationDesc: TextView
    private lateinit var tvTargetBuilding: TextView
    private lateinit var tvGenericMessage: TextView
    private lateinit var layoutSubmitting: LinearLayout
    private lateinit var tvSubmittingText: TextView
    private lateinit var btnAutoFetchCode: MaterialButton
    private lateinit var btnDismiss: MaterialButton
    private lateinit var btnConfirmSign: MaterialButton
    private lateinit var btnClose: ImageView
    private lateinit var etHiddenCode: EditText

    private lateinit var boxPins: Array<FrameLayout>
    private lateinit var tvPins: Array<TextView>

    private val tronClassClient by lazy { TronClassClient(applicationContext) }
    private val prefs by lazy { TronClassPreferences.getInstance(applicationContext) }

    private var isSubmitting = false

    companion object {
        const val EXTRA_ROLLCALL_ID = "extra_rollcall_id"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_ROLLCALL_TYPE = "extra_rollcall_type"
        const val EXTRA_CLASSROOM = "extra_classroom"
        const val EXTRA_BUILDING_NAME = "extra_building_name"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"

        const val TYPE_NUMBER = "number"
        const val TYPE_RADAR = "radar"
        const val TYPE_QR = "qr"
        const val TYPE_LIVE_QR = "live_qr"
        const val TYPE_CHAOXING_LOCATION = "cx_location"
        const val TYPE_GENERIC = "generic"

        fun createIntent(
            context: Context,
            rollcallId: Long,
            courseName: String?,
            type: String = TYPE_NUMBER,
            classroom: String? = null,
            buildingName: String? = null,
            latitude: Double? = null,
            longitude: Double? = null
        ): Intent {
            return Intent(context, RollcallAlertActivity::class.java).apply {
                putExtra(EXTRA_ROLLCALL_ID, rollcallId)
                putExtra(EXTRA_COURSE_NAME, courseName)
                putExtra(EXTRA_ROLLCALL_TYPE, type)
                putExtra(EXTRA_CLASSROOM, classroom)
                putExtra(EXTRA_BUILDING_NAME, buildingName)
                if (latitude != null) putExtra(EXTRA_LATITUDE, latitude)
                if (longitude != null) putExtra(EXTRA_LONGITUDE, longitude)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowFlags()
        setContentView(R.layout.activity_rollcall_alert)

        parseIntentData(intent)
        initViews()
        setupListeners()
        bindDataToUI()
        triggerAlertFeedback()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntentData(intent)
        bindDataToUI()
    }

    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun parseIntentData(intent: Intent?) {
        if (intent == null) return
        rollcallId = intent.getLongExtra(EXTRA_ROLLCALL_ID, 0L)
        courseName = intent.getStringExtra(EXTRA_COURSE_NAME)
        rollcallType = intent.getStringExtra(EXTRA_ROLLCALL_TYPE) ?: TYPE_NUMBER
        classroom = intent.getStringExtra(EXTRA_CLASSROOM)
        buildingName = intent.getStringExtra(EXTRA_BUILDING_NAME)
        targetLatitude = if (intent.hasExtra(EXTRA_LATITUDE)) intent.getDoubleExtra(EXTRA_LATITUDE, 0.0).takeIf { it != 0.0 } else null
        targetLongitude = if (intent.hasExtra(EXTRA_LONGITUDE)) intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0).takeIf { it != 0.0 } else null
    }

    private fun initViews() {
        tvAlertCourseName = findViewById(R.id.tvAlertCourseName)
        tvRollcallTypeBadge = findViewById(R.id.tvRollcallTypeBadge)
        tvRollcallStatusTip = findViewById(R.id.tvRollcallStatusTip)
        layoutNumberSection = findViewById(R.id.layoutNumberSection)
        layoutLocationSection = findViewById(R.id.layoutLocationSection)
        layoutGenericSection = findViewById(R.id.layoutGenericSection)
        tvLocationDesc = findViewById(R.id.tvLocationDesc)
        tvTargetBuilding = findViewById(R.id.tvTargetBuilding)
        tvGenericMessage = findViewById(R.id.tvGenericMessage)
        layoutSubmitting = findViewById(R.id.layoutSubmitting)
        tvSubmittingText = findViewById(R.id.tvSubmittingText)
        btnAutoFetchCode = findViewById(R.id.btnAutoFetchCode)
        btnDismiss = findViewById(R.id.btnDismiss)
        btnConfirmSign = findViewById(R.id.btnConfirmSign)
        btnClose = findViewById(R.id.btnClose)
        etHiddenCode = findViewById(R.id.etHiddenCode)

        boxPins = arrayOf(
            findViewById(R.id.boxPin0),
            findViewById(R.id.boxPin1),
            findViewById(R.id.boxPin2),
            findViewById(R.id.boxPin3)
        )
        tvPins = arrayOf(
            findViewById(R.id.tvPin0),
            findViewById(R.id.tvPin1),
            findViewById(R.id.tvPin2),
            findViewById(R.id.tvPin3)
        )

        // 点击卡片外阴影区域退出弹窗
        findViewById<View>(R.id.layoutAlertRoot).setOnClickListener {
            if (!isSubmitting) finish()
        }
        findViewById<View>(R.id.cardAlert).setOnClickListener {
            // 消费点击事件，避免点卡片内部触发外部关闭
        }
    }

    private fun setupListeners() {
        btnClose.setOnClickListener { finish() }
        btnDismiss.setOnClickListener { finish() }

        // 点击方格自动聚焦隐藏输入框并呼出键盘
        boxPins.forEach { box ->
            box.setOnClickListener {
                focusCodeInput()
            }
        }

        etHiddenCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                updatePinDisplay(text)
                if (text.length == 4 && !isSubmitting) {
                    submitNumberCode(text)
                }
            }
        })

        btnAutoFetchCode.setOnClickListener {
            fetchNumberCodeAutomatically()
        }

        btnConfirmSign.setOnClickListener {
            when (rollcallType) {
                TYPE_NUMBER -> {
                    val code = etHiddenCode.text.toString().trim()
                    if (code.length == 4) {
                        submitNumberCode(code)
                    } else {
                        focusCodeInput()
                        Toast.makeText(this, "请输入4位数字签到码", Toast.LENGTH_SHORT).show()
                    }
                }
                TYPE_RADAR -> {
                    submitRadarLocation()
                }
                TYPE_QR, TYPE_LIVE_QR -> {
                    // 跳转至扫码页面
                    val qrIntent = Intent(this, MlKitScannerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(qrIntent)
                    finish()
                }
                else -> {
                    // 跳转主界面一网畅学页
                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_TAB, 8)
                    }
                    startActivity(mainIntent)
                    finish()
                }
            }
        }
    }

    private fun bindDataToUI() {
        tvAlertCourseName.text = courseName ?: "当前课堂点名"

        when (rollcallType) {
            TYPE_NUMBER -> {
                tvRollcallTypeBadge.text = "4位数字口令"
                tvRollcallStatusTip.text = "教师已发起口令签到，请输入4位数字"
                layoutNumberSection.visibility = View.VISIBLE
                layoutLocationSection.visibility = View.GONE
                layoutGenericSection.visibility = View.GONE
                btnConfirmSign.text = "提交口令"
                focusCodeInput()
            }
            TYPE_RADAR -> {
                tvRollcallTypeBadge.text = "雷达定位签到"
                tvRollcallStatusTip.text = "需确认基准教学楼点位以完成签到"
                layoutNumberSection.visibility = View.GONE
                layoutLocationSection.visibility = View.VISIBLE
                layoutGenericSection.visibility = View.GONE

                val matched = buildingName
                    ?: CoordinateUtils.findMatchingBuilding(classroom)?.name
                    ?: "军工路第一教学楼"
                tvTargetBuilding.text = matched
                btnConfirmSign.text = "一键选点签到"
            }
            TYPE_QR, TYPE_LIVE_QR -> {
                tvRollcallTypeBadge.text = if (rollcallType == TYPE_LIVE_QR) "动态二维码" else "扫码签到"
                tvRollcallStatusTip.text = "教师已展示签到二维码，需相机扫描"
                layoutNumberSection.visibility = View.GONE
                layoutLocationSection.visibility = View.GONE
                layoutGenericSection.visibility = View.VISIBLE
                tvGenericMessage.text = "请对准讲台或投影仪上的签到二维码扫描"
                btnConfirmSign.text = "立即扫码"
            }
            TYPE_CHAOXING_LOCATION -> {
                tvRollcallTypeBadge.text = "超星位置签到"
                tvRollcallStatusTip.text = "学习通课堂位置签到已就绪"
                layoutNumberSection.visibility = View.GONE
                layoutLocationSection.visibility = View.GONE
                layoutGenericSection.visibility = View.VISIBLE
                tvGenericMessage.text = "点击前往确认学习通课堂签到点位"
                btnConfirmSign.text = "前往签到"
            }
            else -> {
                tvRollcallTypeBadge.text = "课堂签到"
                tvRollcallStatusTip.text = "收到签到提醒，请在有效时间内完成"
                layoutNumberSection.visibility = View.GONE
                layoutLocationSection.visibility = View.GONE
                layoutGenericSection.visibility = View.VISIBLE
                tvGenericMessage.text = "请点击下方按钮打开掌上理工签到页"
                btnConfirmSign.text = "前往签到"
            }
        }
    }

    private fun focusCodeInput() {
        etHiddenCode.post {
            etHiddenCode.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etHiddenCode, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updatePinDisplay(code: String) {
        for (i in 0 until 4) {
            val char = code.getOrNull(i)
            tvPins[i].text = char?.toString() ?: ""
            if (i == code.length) {
                boxPins[i].setBackgroundResource(R.drawable.bg_pin_box_active)
            } else {
                boxPins[i].setBackgroundResource(R.drawable.bg_pin_box)
            }
        }
    }

    private fun triggerAlertFeedback() {
        NotificationDiagnosticHelper.wakeScreenBriefly(this, 6000L)
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 300, 100, 300), -1)
            }
        } catch (_: Exception) {}
    }

    private fun submitNumberCode(code: String) {
        if (isSubmitting) return
        isSubmitting = true
        setSubmittingUI(true, "正在向服务器验证签到码...")

        lifecycleScope.launch {
            val result = tronClassClient.answerNumberRollcall(rollcallId, code)
            isSubmitting = false

            if (result.isSuccess && result.getOrNull()?.success == true) {
                handleSignSuccess("🎉 口令签到成功！")
            } else {
                val msg = result.getOrNull()?.message ?: result.exceptionOrNull()?.message ?: "签到码错误或已失效"
                handleSignFailure(msg)
            }
        }
    }

    private fun fetchNumberCodeAutomatically() {
        if (isSubmitting) return
        btnAutoFetchCode.isEnabled = false
        setSubmittingUI(true, "正在智能嗅探点名口令...")

        lifecycleScope.launch {
            try {
                val rollcallsResult = tronClassClient.fetchOngoingRollcalls()
                val targetRollcall = rollcallsResult.getOrNull()?.firstOrNull { it.getEffectiveId() == rollcallId }
                    ?: TronClassRollcall(id = rollcallId, courseName = courseName)

                val lookup = tronClassClient.lookupNumberRollcallCode(targetRollcall)
                setSubmittingUI(false)
                btnAutoFetchCode.isEnabled = true

                when (lookup) {
                    is NumberCodeLookupResult.Found -> {
                        val code = lookup.code
                        Toast.makeText(this@RollcallAlertActivity, "成功获取口令: $code", Toast.LENGTH_SHORT).show()
                        etHiddenCode.setText(code)
                    }
                    is NumberCodeLookupResult.Unavailable -> {
                        Toast.makeText(this@RollcallAlertActivity, "未嗅探到有效口令: ${lookup.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                setSubmittingUI(false)
                btnAutoFetchCode.isEnabled = true
                Toast.makeText(this@RollcallAlertActivity, "查询异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun submitRadarLocation() {
        if (isSubmitting) return
        isSubmitting = true
        setSubmittingUI(true, "正在提交基准教学楼坐标...")

        lifecycleScope.launch {
            // 优先使用传入经纬度，若无则使用匹配建筑或军工路第一教学楼坐标
            val building = CoordinateUtils.findMatchingBuilding(classroom)
                ?: CoordinateUtils.USST_BUILDINGS.firstOrNull()

            val lat = targetLatitude ?: building?.latitude ?: 31.2965
            val lng = targetLongitude ?: building?.longitude ?: 121.5540

            val result = tronClassClient.answerRadarRollcall(
                rollcallId = rollcallId,
                latitude = lat,
                longitude = lng,
                accuracy = 15.0
            )
            isSubmitting = false

            if (result.isSuccess && result.getOrNull()?.success == true) {
                handleSignSuccess("🎉 雷达定位签到成功！")
            } else {
                val msg = result.getOrNull()?.message ?: result.exceptionOrNull()?.message ?: "定位超出范围或活动已结束"
                handleSignFailure(msg)
            }
        }
    }

    private fun handleSignSuccess(tip: String) {
        prefs.markRollcallHandled(rollcallId)
        setSubmittingUI(false)
        tvRollcallStatusTip.text = tip
        tvRollcallStatusTip.setTextColor(ContextCompat.getColor(this, R.color.primary))
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            delay(1000L)
            finish()
        }
    }

    private fun handleSignFailure(reason: String) {
        setSubmittingUI(false)
        tvRollcallStatusTip.text = "⚠️ $reason"
        tvRollcallStatusTip.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()

        // 如果是数字签到码错误，清空输入框并让方格变红
        if (rollcallType == TYPE_NUMBER) {
            etHiddenCode.setText("")
            boxPins.forEach { it.setBackgroundResource(R.drawable.bg_pin_box_error) }
            etHiddenCode.postDelayed({
                updatePinDisplay("")
            }, 800L)
        }
    }

    private fun setSubmittingUI(submitting: Boolean, text: String = "") {
        layoutSubmitting.visibility = if (submitting) View.VISIBLE else View.GONE
        tvSubmittingText.text = text
        btnConfirmSign.isEnabled = !submitting
        btnDismiss.isEnabled = !submitting
    }
}
