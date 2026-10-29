package cn.edu.usst.jwgl.ui.tronclass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.RollcallSubmitResult
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogRadarRollcallBinding
import cn.edu.usst.jwgl.util.CoordinateUtils
import kotlinx.coroutines.launch
import java.util.Locale

class RadarRollcallBottomSheetDialog : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        cn.edu.usst.jwgl.util.TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    private var _binding: DialogRadarRollcallBinding? = null
    private val binding get() = _binding!!

    private var rollcallId: Long = 0L
    private var courseName: String? = null
    private var classroom: String? = null
    private var teacherLat: Double? = null
    private var teacherLng: Double? = null
    private var teacherScope: Double? = null
    private var teacherRadioButtonId: Int? = null

    private var selectedLatitude: Double = 31.292508
    private var selectedLongitude: Double = 121.557765
    private var selectedAccuracy: Double = 15.0

    private var onSignSuccessListener: (() -> Unit)? = null
    private var activeLocationListener: LocationListener? = null

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchDeviceLocation()
        } else {
            val b = _binding ?: return@registerForActivityResult
            b.tvGpsStatus.text = "定位权限被拒绝，已自动回退到上理教学楼基准"
            b.rbModeBuilding.isChecked = true
            updateModeVisibility()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            rollcallId = it.getLong(ARG_ROLLCALL_ID, 0L)
            courseName = it.getString(ARG_COURSE_NAME)
            classroom = it.getString(ARG_CLASSROOM)
            if (it.containsKey(ARG_TEACHER_LAT)) {
                teacherLat = it.getDouble(ARG_TEACHER_LAT)
            }
            if (it.containsKey(ARG_TEACHER_LNG)) {
                teacherLng = it.getDouble(ARG_TEACHER_LNG)
            }
            if (it.containsKey(ARG_TEACHER_SCOPE)) {
                teacherScope = it.getDouble(ARG_TEACHER_SCOPE)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRadarRollcallBinding.inflate(inflater, container, false)
        val scrollView = androidx.core.widget.NestedScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
            addView(binding.root)
        }
        return scrollView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        val cName = courseName ?: "当前教学课程"
        binding.tvRadarCourseInfo.text = "$cName · 正在雷达点名中"
        binding.tvRadarRollcallIdBadge.text = "ID: $rollcallId"

        // 1. 初始化教学楼下拉框（适配深色与浅色模式文字与背景）
        val buildings = CoordinateUtils.USST_BUILDINGS
        val buildingNames = buildings.map { "${it.name} (${it.campus})" }
        val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, buildingNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? android.widget.TextView)?.apply {
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    textSize = 13f
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? android.widget.TextView)?.apply {
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                    setPadding(36, 28, 36, 28)
                    textSize = 13f
                }
                return v
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerBuildings.adapter = adapter

        // 2. 智能匹配：根据传入的教室名称或课程匹配最近的教学楼
        val matched = CoordinateUtils.findMatchingBuilding(classroom) ?: CoordinateUtils.findMatchingBuilding(courseName)
        val defaultIndex = if (matched != null) {
            buildings.indexOf(matched).coerceAtLeast(0)
        } else {
            0
        }
        binding.spinnerBuildings.setSelection(defaultIndex)
        applyBuildingLocation(buildings[defaultIndex])

        // 3. 教师基准点直连优先模式（最高优先级通道，0偏差必过）
        val hasTeacherBeacon = teacherLat != null && teacherLng != null && teacherLat != 0.0 && teacherLng != 0.0
        if (hasTeacherBeacon) {
            val rbTeacher = android.widget.RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = "🎯 教师基准点直连 (100% 必过 · 距教师约 0m)"
                setTextColor(ContextCompat.getColor(context, R.color.grade_blue_text))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            binding.rgLocationMode.addView(rbTeacher, 0)
            teacherRadioButtonId = rbTeacher.id
            rbTeacher.isChecked = true
            applyTeacherBeaconLocation()
        } else {
            // 若无教师坐标，尝试轻量获取当前基站/GPS定位智能吸附最近教学楼
            trySilentAutoSnapToNearestBuilding()
        }

        // 4. 多账号签到支持
        try {
            val accounts = cn.edu.usst.jwgl.data.local.AccountVault.getInstance(requireContext()).getAccounts()
            if (accounts.size > 1) {
                binding.cbRadarSignAll.visibility = View.VISIBLE
                binding.cbRadarSignAll.isChecked = true
                binding.cbRadarSignAll.text = "同时为全部已保存账号签到 (共 ${accounts.size} 人 · 独立设备码)"
            } else {
                binding.cbRadarSignAll.visibility = View.GONE
            }
        } catch (_: Exception) {
            binding.cbRadarSignAll.visibility = View.GONE
        }

        updateModeVisibility()
    }

    private fun setupListeners() {
        binding.spinnerBuildings.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val building = CoordinateUtils.USST_BUILDINGS.getOrNull(position) ?: return
                if (binding.rbModeBuilding.isChecked) {
                    applyBuildingLocation(building)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.rgLocationMode.setOnCheckedChangeListener { _, checkedId ->
            updateModeVisibility()
            if (teacherRadioButtonId != null && checkedId == teacherRadioButtonId) {
                applyTeacherBeaconLocation()
            } else if (checkedId == R.id.rbModeBuilding) {
                val pos = binding.spinnerBuildings.selectedItemPosition
                val building = CoordinateUtils.USST_BUILDINGS.getOrNull(pos) ?: CoordinateUtils.USST_BUILDINGS[0]
                applyBuildingLocation(building)
            } else if (checkedId == R.id.rbModeCurrentGps) {
                checkLocationPermissionAndFetch()
            }
        }

        binding.btnRefreshGps.setOnClickListener {
            checkLocationPermissionAndFetch()
        }

        binding.btnCancelRadar.setOnClickListener {
            dismiss()
        }

        binding.btnSubmitRadar.setOnClickListener {
            submitRadarRollcall()
        }
    }

    private fun updateModeVisibility() {
        val isTeacherMode = teacherRadioButtonId != null && binding.rgLocationMode.checkedRadioButtonId == teacherRadioButtonId
        if (isTeacherMode) {
            binding.layoutBuildingSelector.visibility = View.GONE
            binding.layoutGpsStatus.visibility = View.GONE
        } else if (binding.rbModeBuilding.isChecked) {
            binding.layoutBuildingSelector.visibility = View.VISIBLE
            binding.layoutGpsStatus.visibility = View.GONE
        } else {
            binding.layoutBuildingSelector.visibility = View.GONE
            binding.layoutGpsStatus.visibility = View.VISIBLE
        }
    }

    private fun applyTeacherBeaconLocation() {
        val baseLat = teacherLat ?: return
        val baseLng = teacherLng ?: return
        // 叠加自然物理微扰动 (±1.5 米, 约 ±0.000015 度)，防止被服务端风控判定为 0 距离假点
        val jitterLat = (Math.random() - 0.5) * 0.00003
        val jitterLng = (Math.random() - 0.5) * 0.00003
        selectedLatitude = baseLat + jitterLat
        selectedLongitude = baseLng + jitterLng
        selectedAccuracy = 5.0

        binding.tvCoordinatesDisplay.text = String.format(
            Locale.CHINA,
            "纬度: %.6f, 经度: %.6f",
            selectedLatitude,
            selectedLongitude
        )
        val scopeText = if (teacherScope != null) " · 范围 ±${teacherScope?.toInt()}m" else ""
        binding.tvSelectedBuildingCampus.text = "基准: 教师端实时点名基准点 (直连 100% 必过通道$scopeText)"
        binding.tvAccuracyTip.text = "直连精度: ±5m (0m 偏差)"
    }

    private fun trySilentAutoSnapToNearestBuilding() {
        val finePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (finePerm != PackageManager.PERMISSION_GRANTED && coarsePerm != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val now = System.currentTimeMillis()
        var bestLoc: Location? = null
        for (p in providers) {
            try {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (now - loc.time) <= 15 * 60 * 1000L) {
                        if (bestLoc == null || loc.accuracy < bestLoc.accuracy) {
                            bestLoc = loc
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        bestLoc?.let { autoSnapToNearestBuilding(it) }
    }

    private fun autoSnapToNearestBuilding(loc: Location) {
        if (_binding == null || !isAdded) return
        if (teacherRadioButtonId != null && binding.rgLocationMode.checkedRadioButtonId == teacherRadioButtonId) {
            return
        }
        val (nearest, dist) = CoordinateUtils.findNearestBuildingFromWgs84(loc.latitude, loc.longitude)
        if (dist <= 800.0) { // 800米以内属于校区覆盖半径
            val idx = CoordinateUtils.USST_BUILDINGS.indexOf(nearest)
            if (idx >= 0) {
                binding.spinnerBuildings.setSelection(idx)
                if (binding.rbModeBuilding.isChecked) {
                    applyBuildingLocation(nearest)
                    binding.tvSelectedBuildingCampus.text = "已自动为您吸附至最近教学楼: ${nearest.name} (距您仅 ${dist.toInt()}m)"
                }
            }
        }
    }

    private fun applyBuildingLocation(building: CoordinateUtils.CampusBuilding) {
        selectedLatitude = building.latitude
        selectedLongitude = building.longitude
        selectedAccuracy = 15.0

        binding.tvCoordinatesDisplay.text = String.format(
            Locale.CHINA,
            "纬度: %.6f, 经度: %.6f",
            selectedLatitude,
            selectedLongitude
        )
        binding.tvSelectedBuildingCampus.text = "基准: 上海理工大学 ${building.name} (${building.campus})"
        binding.tvAccuracyTip.text = "基准精度: ±15m"
    }

    private fun checkLocationPermissionAndFetch() {
        val finePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)

        if (finePerm == PackageManager.PERMISSION_GRANTED || coarsePerm == PackageManager.PERMISSION_GRANTED) {
            fetchDeviceLocation()
        } else {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchDeviceLocation() {
        binding.tvGpsStatus.text = "正在通过系统 GPS / 基站获取高精度坐标..."
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            binding.tvGpsStatus.text = "设备无可用定位模块，建议切回教学楼基准"
            return
        }

        var bestLocation: Location? = null
        val maxCacheAgeMs = 10 * 60 * 1000L // 只采纳10分钟以内的有效近期缓存，避免使用校外历史旧坐标
        val now = System.currentTimeMillis()

        // 尝试从 GPS 和 Network 获取最近的已缓存位置
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null && (now - loc.time) <= maxCacheAgeMs) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        if (bestLocation != null) {
            handleReceivedLocation(bestLocation)
        }

        // 同时发起实时单次定位监听以获得最新数据（并发向卫星和网络定位申请更新）
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (_binding == null || !isAdded) {
                        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                        activeLocationListener = null
                        return
                    }
                    handleReceivedLocation(loc)
                    try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    activeLocationListener = null
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            activeLocationListener = listener

            var updateRequested = false
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
                updateRequested = true
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, listener)
                updateRequested = true
            }

            if (!updateRequested && bestLocation == null) {
                binding.tvGpsStatus.text = "GPS与网络定位服务均未开启，请先在系统设置中开启定位"
            }
        } catch (e: Exception) {
            val b = _binding
            if (b != null && bestLocation == null) {
                b.tvGpsStatus.text = "定位获取异常: ${e.message}，建议切回教学楼基准"
            }
        }
    }

    private fun handleReceivedLocation(loc: Location) {
        val b = _binding ?: return
        if (!isAdded) return

        // 统一对齐官方 TronClass 规则：
        // Android 系统底层原生定位输出均基于大地测量学 WGS-84 标准。
        // 一网畅学官方前端在上传前无条件调用 handleWgs2gcj() 转为 GCJ-02 火星坐标。
        // 此处严格转换为高精 GCJ-02，彻底消除 470 米跨坐标系断层！
        val (finalLat, finalLng) = CoordinateUtils.wgs84ToGcj02(loc.latitude, loc.longitude)

        selectedLatitude = finalLat
        selectedLongitude = finalLng
        selectedAccuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 15.0

        val providerName = when (loc.provider) {
            LocationManager.GPS_PROVIDER -> "卫星高精GPS"
            LocationManager.NETWORK_PROVIDER -> "基站/Wi-Fi网络"
            else -> "融合定位传感器"
        }

        b.tvGpsStatus.text = String.format(
            Locale.CHINA,
            "定位成功 (%s · 精度 ±%.0fm)",
            providerName,
            selectedAccuracy
        )
        b.tvCoordinatesDisplay.text = String.format(
            Locale.CHINA,
            "纬度: %.6f, 经度: %.6f",
            selectedLatitude,
            selectedLongitude
        )
        b.tvAccuracyTip.text = String.format(Locale.CHINA, "定位精度: ±%.0fm (%s)", selectedAccuracy, providerName)
        b.tvSelectedBuildingCampus.text = "来源: 移动设备传感器实时定位 ($providerName · 已纠偏至GCJ-02)"

        // 同步为教学楼模式提供智能吸附
        autoSnapToNearestBuilding(loc)
    }

    private fun submitRadarRollcall() {
        if (rollcallId <= 0L) {
            Toast.makeText(requireContext(), "点名 ID 无效", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmitRadar.isEnabled = false
        binding.btnSubmitRadar.text = ""
        binding.progressRadarSubmitting.visibility = View.VISIBLE
        binding.btnCancelRadar.isEnabled = false

        val client = TronClassClient(requireContext().applicationContext)
        val isBatch = binding.cbRadarSignAll.visibility == View.VISIBLE && binding.cbRadarSignAll.isChecked

        lifecycleScope.launch {
            if (isBatch) {
                val results = client.batchAnswerRadarRollcall(
                    rollcallId = rollcallId,
                    latitude = selectedLatitude,
                    longitude = selectedLongitude,
                    accuracy = selectedAccuracy
                )

                if (!isAdded) return@launch
                val b = _binding ?: return@launch
                b.btnSubmitRadar.isEnabled = true
                b.btnSubmitRadar.text = "立即雷达签到"
                b.progressRadarSubmitting.visibility = View.GONE
                b.btnCancelRadar.isEnabled = true

                if (results.any { it.success }) {
                    onSignSuccessListener?.invoke()
                }
                dismiss()
                showMultiAccountRollcallSummaryDialog(requireActivity(), results)
                return@launch
            }

            val result = client.answerRadarRollcall(
                rollcallId = rollcallId,
                latitude = selectedLatitude,
                longitude = selectedLongitude,
                accuracy = selectedAccuracy
            )

            if (!isAdded) return@launch
            val b = _binding ?: return@launch

            b.btnSubmitRadar.isEnabled = true
            b.btnSubmitRadar.text = "立即雷达签到"
            b.progressRadarSubmitting.visibility = View.GONE
            b.btnCancelRadar.isEnabled = true

            val res = result.getOrNull()
            if (res != null && res.success) {
                onSignSuccessListener?.invoke()
                dismiss()
                MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("🎉 雷达签到成功！")
                    .setMessage(res.message)
                    .setPositiveButton("完成", null)
                    .show()
            } else {
                val errMsg = res?.message ?: result.exceptionOrNull()?.message ?: "未知网络错误"
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("雷达签到未通过")
                    .setMessage(errMsg)
                    .setPositiveButton("调整基准重试", null)
                    .setNegativeButton("放弃", null)
                    .show()
            }
        }
    }

    private fun showMultiAccountRollcallSummaryDialog(
        activity: android.app.Activity,
        results: List<cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult>
    ) {
        val successCount = results.count { it.success }
        val totalCount = results.size
        val title = if (successCount == totalCount) {
            "🎉 雷达全员签到成功 ($successCount/$totalCount)"
        } else {
            "📋 雷达签到结果 ($successCount/$totalCount 成功)"
        }

        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "🟢" else "🔴"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("完成", null)
            .show()
    }

    fun setOnSignSuccessListener(listener: () -> Unit) {
        this.onSignSuccessListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeLocationListener?.let {
            try {
                val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(it)
            } catch (_: Exception) {}
        }
        activeLocationListener = null
        _binding = null
    }

    companion object {
        private const val ARG_ROLLCALL_ID = "arg_rollcall_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CLASSROOM = "arg_classroom"
        private const val ARG_TEACHER_LAT = "arg_teacher_lat"
        private const val ARG_TEACHER_LNG = "arg_teacher_lng"
        private const val ARG_TEACHER_SCOPE = "arg_teacher_scope"

        fun newInstance(
            rollcallId: Long,
            courseName: String? = null,
            classroom: String? = null,
            teacherLat: Double? = null,
            teacherLng: Double? = null,
            teacherScope: Double? = null
        ): RadarRollcallBottomSheetDialog {
            return RadarRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, rollcallId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                    if (teacherLat != null) putDouble(ARG_TEACHER_LAT, teacherLat)
                    if (teacherLng != null) putDouble(ARG_TEACHER_LNG, teacherLng)
                    if (teacherScope != null) putDouble(ARG_TEACHER_SCOPE, teacherScope)
                }
            }
        }
    }
}
