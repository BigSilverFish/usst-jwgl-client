package cn.edu.usst.jwgl.ui.tronclass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogRadarRollcallBinding
import cn.edu.usst.jwgl.devaddon.DevAddonManager
import cn.edu.usst.jwgl.devapi.HostLocation
import cn.edu.usst.jwgl.devapi.RadarDialogAddonHandle
import cn.edu.usst.jwgl.devapi.RadarDialogHost
import cn.edu.usst.jwgl.util.CoordinateSystem
import cn.edu.usst.jwgl.util.RadarLocationSelection
import cn.edu.usst.jwgl.util.RollcallLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var chaoXingCourseId: String? = null
    private var chaoXingClassId: String? = null
    private var locationAddress: String? = null
    private var radarAddonHandle: RadarDialogAddonHandle? = null

    private val isChaoXingMode: Boolean
        get() = !chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()

    private var selectedLatitude: Double = 31.292508
    private var selectedLongitude: Double = 121.557765
    private var selectedAccuracy: Double = 15.0
    private var selectedAddress: String = "上海理工大学"

    private val locationSelection = RadarLocationSelection()
    private var deviceLocationTimeoutJob: Job? = null

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
            if (radarAddonHandle?.onGpsPermissionDenied() == true) {
                b.tvGpsStatus.text = "定位权限被拒绝，已自动回退到扩展包默认基准"
                updateModeVisibility()
            } else {
                b.tvGpsStatus.text = "定位权限被拒绝，请在系统设置中允许定位权限"
                Toast.makeText(requireContext(), "雷达签到须允许本机定位权限", Toast.LENGTH_SHORT).show()
            }
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
            chaoXingCourseId = it.getString(ARG_CHAOXING_COURSE_ID)
            chaoXingClassId = it.getString(ARG_CHAOXING_CLASS_ID)
            locationAddress = it.getString(ARG_LOCATION_ADDRESS)
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
        binding.tvRadarCourseInfo.text = if (isChaoXingMode) "$cName · 超星位置签到" else "$cName · 正在雷达点名中"
        binding.tvRadarRollcallIdBadge.text = "ID: $rollcallId"
        binding.btnSubmitRadar.text = if (isChaoXingMode) "立即位置签到" else "立即雷达签到"

        if (!isChaoXingMode && rollcallId > 0) {
            binding.tvRadarAttendanceStats.visibility = View.VISIBLE
            binding.tvRadarAttendanceStats.text = "👥 正在查询已签到人数..."
            val client = TronClassClient(requireContext().applicationContext)
            viewLifecycleOwner.lifecycleScope.launch {
                val statsResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.fetchRollcallAttendanceStats(rollcallId)
                }
                if (!isAdded || _binding == null) return@launch
                statsResult.onSuccess { stats ->
                    _binding?.tvRadarAttendanceStats?.apply {
                        visibility = View.VISIBLE
                        text = stats.formatBadgeText()
                    }
                }.onFailure {
                    _binding?.tvRadarAttendanceStats?.visibility = View.GONE
                }
            }
        } else {
            binding.tvRadarAttendanceStats.visibility = View.GONE
        }

        // 挂载开发者扩展包点位模式与多账号选项（未安装/未启用时自动折叠隐藏）
        val host = object : RadarDialogHost {
            override val rgLocationMode get() = binding.rgLocationMode
            override val extraPanelSlot get() = binding.devRadarExtraContainer
            override val batchSlot get() = binding.devRadarBatchContainer
            override val gpsRadioButtonId get() = R.id.rbModeCurrentGps
            override val rollcallIdProvider: () -> Long = { rollcallId }
            override val courseName: String? get() = this@RadarRollcallBottomSheetDialog.courseName
            override val classroom: String? get() = this@RadarRollcallBottomSheetDialog.classroom
            override val isChaoXingMode: Boolean get() = this@RadarRollcallBottomSheetDialog.isChaoXingMode
            override val teacherLat: Double? get() = this@RadarRollcallBottomSheetDialog.teacherLat
            override val teacherLng: Double? get() = this@RadarRollcallBottomSheetDialog.teacherLng
            override val teacherScope: Double? get() = this@RadarRollcallBottomSheetDialog.teacherScope
            override val locationAddress: String? get() = this@RadarRollcallBottomSheetDialog.locationAddress

            override fun onSelectVirtualLocation(location: HostLocation, displayTitle: String, accuracyTip: String) {
                if (_binding == null || !isAdded) return
                stopDeviceLocationUpdates()
                selectedLatitude = location.latitude
                selectedLongitude = location.longitude
                selectedAccuracy = location.accuracyMeters
                selectedAddress = location.source.ifBlank {
                    this@RadarRollcallBottomSheetDialog.locationAddress?.takeIf { it.isNotBlank() } ?: "上海理工大学"
                }
                locationSelection.select(
                    RadarLocationSelection.Mode.TEACHER,
                    RollcallLocation(
                        latitude = selectedLatitude,
                        longitude = selectedLongitude,
                        coordinateSystem = CoordinateSystem.GCJ02,
                        source = location.source,
                        accuracyMeters = selectedAccuracy
                    )
                )
                binding.tvCoordinatesDisplay.text = String.format(
                    Locale.CHINA,
                    "纬度: %.6f, 经度: %.6f",
                    selectedLatitude,
                    selectedLongitude
                )
                binding.tvSelectedBuildingCampus.text = displayTitle
                binding.tvAccuracyTip.text = accuracyTip
                updateModeVisibility()
            }

            override fun onSelectGpsMode() {
                if (_binding == null || !isAdded) return
                updateModeVisibility()
                checkLocationPermissionAndFetch()
            }

            override fun onSetSubmitEnabled(enabled: Boolean) {
                val b = _binding ?: return
                b.btnSubmitRadar.isEnabled = enabled
                b.btnSubmitRadar.text = if (enabled) {
                    if (isChaoXingMode) "立即位置签到" else "立即雷达签到"
                } else ""
                b.progressRadarSubmitting.visibility = if (enabled) View.GONE else View.VISIBLE
                b.btnCancelRadar.isEnabled = enabled
            }

            override fun onSignSucceeded() {
                onSignSuccessListener?.invoke()
            }

            override fun onDismiss() {
                if (isAdded) dismiss()
            }

            override fun ensureRollcallId(onReady: (Long) -> Unit) {
                if (rollcallId > 0L) {
                    onReady(rollcallId)
                } else {
                    promptForRollcallId(onReady)
                }
            }

            override fun getCurrentGpsLocation(): HostLocation? {
                val loc = locationSelection.snapshotForSubmission(System.currentTimeMillis()) ?: return null
                return HostLocation(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyMeters = loc.accuracyMeters ?: 15.0,
                    source = loc.source
                )
            }
        }
        radarAddonHandle = DevAddonManager.getEntry(requireContext()).radarRollcallExtension
            .mountRadarDialogSlots(requireActivity(), host)

        binding.tvLocationModeTitle.text = if (radarAddonHandle != null) {
            "选择签到定位基准"
        } else {
            "签到定位基准：手机本机真实定位"
        }

        // 默认签到方式设为本机定位模式 (手机原生 GPS / 基站定位)
        binding.rbModeCurrentGps.isChecked = true
        checkLocationPermissionAndFetch()
        updateModeVisibility()
    }

    private fun setupListeners() {
        binding.rgLocationMode.setOnCheckedChangeListener { _, checkedId ->
            updateModeVisibility()
            if (checkedId == R.id.rbModeCurrentGps) {
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
        val isVirtual = radarAddonHandle?.isVirtualModeSelected() == true
        binding.layoutGpsStatus.visibility = if (isVirtual) View.GONE else View.VISIBLE
    }

    private fun stopDeviceLocationUpdates() {
        deviceLocationTimeoutJob?.cancel()
        deviceLocationTimeoutJob = null
        activeLocationListener?.let { listener ->
            try {
                val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(listener)
            } catch (_: Exception) {}
        }
        activeLocationListener = null
    }

    private fun toDeviceRollcallLocation(loc: Location): RollcallLocation {
        val providerName = when (loc.provider) {
            LocationManager.GPS_PROVIDER -> "卫星高精GPS"
            LocationManager.NETWORK_PROVIDER -> "基站/Wi-Fi网络"
            else -> "融合定位传感器"
        }
        return RollcallLocation(
            latitude = loc.latitude,
            longitude = loc.longitude,
            coordinateSystem = CoordinateSystem.WGS84,
            source = providerName,
            observedAtMillis = loc.time,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        )
    }

    private fun checkLocationPermissionAndFetch() {
        stopDeviceLocationUpdates()
        locationSelection.select(RadarLocationSelection.Mode.DEVICE, null)
        selectedLatitude = 0.0
        selectedLongitude = 0.0
        selectedAccuracy = 0.0

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
        stopDeviceLocationUpdates()
        val requestGen = locationSelection.beginDeviceRequest()
        selectedLatitude = 0.0
        selectedLongitude = 0.0
        selectedAccuracy = 0.0
        binding.tvGpsStatus.text = "正在通过系统 GPS / 基站获取高精度坐标..."

        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            locationSelection.cancelDeviceRequest()
            binding.tvGpsStatus.text = "设备无可用定位模块，建议切回教学楼基准"
            return
        }

        val now = System.currentTimeMillis()
        var acceptedRawLoc: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null && locationSelection.acceptDeviceLocation(requestGen, toDeviceRollcallLocation(loc), now)) {
                        acceptedRawLoc = loc
                    }
                }
            } catch (_: Exception) {}
        }

        val cachedAccepted = locationSelection.selected
        if (cachedAccepted != null && acceptedRawLoc != null) {
            renderAcceptedDeviceLocation(cachedAccepted, acceptedRawLoc)
            if ((cachedAccepted.accuracyMeters ?: Double.MAX_VALUE) <= RadarLocationSelection.TARGET_DEVICE_ACCURACY_METERS) {
                return
            }
        }

        // 同时发起实时单次定位监听以获得最新高精数据（严格校验代次、2分钟有效期与100m精度门槛）
        var updateRequested = false
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (_binding == null || !isAdded || !locationSelection.isCurrentRequest(requestGen)) {
                        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                        if (activeLocationListener === this) activeLocationListener = null
                        return
                    }
                    val candidate = toDeviceRollcallLocation(loc)
                    if (locationSelection.acceptDeviceLocation(requestGen, candidate, System.currentTimeMillis())) {
                        val normalized = locationSelection.selected
                        if (normalized != null) {
                            renderAcceptedDeviceLocation(normalized, loc)
                        }
                        if ((normalized?.accuracyMeters ?: Double.MAX_VALUE) <= RadarLocationSelection.TARGET_DEVICE_ACCURACY_METERS) {
                            stopDeviceLocationUpdates()
                        }
                    } else if (locationSelection.selected == null && loc.hasAccuracy() && loc.accuracy > RadarLocationSelection.MAX_DEVICE_ACCURACY_METERS) {
                        _binding?.tvGpsStatus?.text = String.format(
                            Locale.CHINA,
                            "已收到弱定位信号 (±%.0fm > 100m)，正在等待更精准定位...",
                            loc.accuracy
                        )
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            activeLocationListener = listener

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
                updateRequested = true
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, listener)
                updateRequested = true
            }

            if (!updateRequested && locationSelection.selected == null) {
                locationSelection.cancelDeviceRequest()
                activeLocationListener = null
                binding.tvGpsStatus.text = "GPS与网络定位服务均未开启，请先在系统设置中开启定位"
                return
            }
        } catch (_: Exception) {
            activeLocationListener = null
            if (locationSelection.selected == null) {
                locationSelection.cancelDeviceRequest()
                _binding?.tvGpsStatus?.text = "GPS定位启动失败或未开启，建议使用教学楼基准定位"
            }
            return
        }

        if (updateRequested) {
            deviceLocationTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(DEVICE_LOCATION_TIMEOUT_MS)
                if (!isAdded || _binding == null || !locationSelection.isCurrentRequest(requestGen)) return@launch
                activeLocationListener?.let { listener ->
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                }
                activeLocationListener = null
                if (locationSelection.snapshotForSubmission(System.currentTimeMillis()) == null) {
                    locationSelection.cancelDeviceRequest()
                    _binding?.tvGpsStatus?.text = radarAddonHandle?.onGpsTimeoutHint()
                        ?: "本机定位超时 (20s) 或精度不足 (>100m)，请移至窗边后点击刷新重试"
                }
            }
        }
    }

    private fun renderAcceptedDeviceLocation(accepted: RollcallLocation, rawLoc: Location) {
        val b = _binding ?: return
        if (!isAdded) return

        // RadarLocationSelection.toGcj02() 已将 WGS-84 纠偏为 GCJ-02 火星坐标，直接使用避免二次偏转
        selectedLatitude = accepted.latitude
        selectedLongitude = accepted.longitude
        selectedAccuracy = accepted.accuracyMeters ?: 15.0
        selectedAddress = locationAddress?.takeIf { it.isNotBlank() } ?: "上海理工大学"

        val providerName = accepted.source
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

        // 同步通知扩展包最近一次真实 GPS 位置（如教学楼自动吸附）
        radarAddonHandle?.onGpsLocationUpdated(rawLoc.latitude, rawLoc.longitude)
    }

    private fun promptForRollcallId(onReady: (Long) -> Unit) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "请输入雷达点名 ID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("输入点名 ID")
            .setMessage("当前未检测到进行中的点名活动，请向教师获取数字点名 ID 后提交：")
            .setView(input)
            .setPositiveButton("确定提交") { _, _ ->
                val enteredId = input.text.toString().trim().toLongOrNull() ?: 0L
                if (enteredId > 0L) {
                    rollcallId = enteredId
                    onReady(enteredId)
                } else {
                    Toast.makeText(ctx, "请输入有效的点名 ID", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitRadarRollcall() {
        val isVirtualMode = radarAddonHandle?.isVirtualModeSelected() == true
        if (!isVirtualMode) {
            val snapshot = locationSelection.snapshotForSubmission(System.currentTimeMillis())
            if (snapshot == null) {
                if (activeLocationListener != null) {
                    Toast.makeText(requireContext(), "正在获取有效本机定位（需2分钟内且精度≤100m），请稍候...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "尚未获取到有效定位或定位已过期，请点击刷新获取", Toast.LENGTH_SHORT).show()
                    checkLocationPermissionAndFetch()
                }
                return
            }
            selectedLatitude = snapshot.latitude
            selectedLongitude = snapshot.longitude
            selectedAccuracy = snapshot.accuracyMeters ?: 15.0
        }

        if (rollcallId <= 0L) {
            promptForRollcallId {
                submitRadarRollcall()
            }
            return
        }

        if (radarAddonHandle?.handleCustomSubmit(rollcallId, selectedLatitude, selectedLongitude, selectedAccuracy) == true) {
            return
        }

        binding.btnSubmitRadar.isEnabled = false
        binding.btnSubmitRadar.text = ""
        binding.progressRadarSubmitting.visibility = View.VISIBLE
        binding.btnCancelRadar.isEnabled = false

        val client = TronClassClient(requireContext().applicationContext)

        lifecycleScope.launch {
            if (isChaoXingMode) {
                val cxClient = cn.edu.usst.jwgl.data.network.ChaoXingClient(requireContext().applicationContext)
                val result = cxClient.submitSign(
                    courseId = chaoXingCourseId!!,
                    classId = chaoXingClassId!!,
                    activeId = rollcallId,
                    signType = "位置签到",
                    latitude = selectedLatitude,
                    longitude = selectedLongitude,
                    address = selectedAddress,
                    allowRejectedRetry = true
                )

                if (!isAdded) return@launch
                val b = _binding ?: return@launch
                b.btnSubmitRadar.isEnabled = true
                b.btnSubmitRadar.text = "立即位置签到"
                b.progressRadarSubmitting.visibility = View.GONE
                b.btnCancelRadar.isEnabled = true

                val res = result.getOrNull()
                if (res != null && res.isSuccess) {
                    Toast.makeText(requireContext(), "🎉 位置签到成功", Toast.LENGTH_SHORT).show()
                    onSignSuccessListener?.invoke()
                    dismiss()
                } else {
                    val errMsg = res?.message ?: result.exceptionOrNull()?.message ?: "未知网络错误"
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("位置签到未通过")
                        .setMessage(errMsg)
                        .setPositiveButton("调整基准重试", null)
                        .setNegativeButton("放弃", null)
                        .show()
                }
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
                Toast.makeText(requireContext(), "🎉 雷达签到成功", Toast.LENGTH_SHORT).show()
                onSignSuccessListener?.invoke()
                dismiss()
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

    fun setOnSignSuccessListener(listener: () -> Unit) {
        this.onSignSuccessListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        radarAddonHandle?.destroy()
        radarAddonHandle = null
        stopDeviceLocationUpdates()
        locationSelection.cancelDeviceRequest()
        _binding = null
    }

    companion object {
        private const val DEVICE_LOCATION_TIMEOUT_MS = 20_000L
        private const val ARG_ROLLCALL_ID = "arg_rollcall_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CLASSROOM = "arg_classroom"
        private const val ARG_TEACHER_LAT = "arg_teacher_lat"
        private const val ARG_TEACHER_LNG = "arg_teacher_lng"
        private const val ARG_TEACHER_SCOPE = "arg_teacher_scope"
        private const val ARG_CHAOXING_COURSE_ID = "arg_chaoxing_course_id"
        private const val ARG_CHAOXING_CLASS_ID = "arg_chaoxing_class_id"
        private const val ARG_LOCATION_ADDRESS = "arg_location_address"

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

        fun newChaoXingLocationInstance(
            activeId: Long,
            courseId: String,
            classId: String,
            courseName: String? = null,
            classroom: String? = null,
            teacherLat: Double? = null,
            teacherLng: Double? = null,
            locationAddress: String? = null
        ): RadarRollcallBottomSheetDialog {
            return RadarRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, activeId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                    putString(ARG_CHAOXING_COURSE_ID, courseId)
                    putString(ARG_CHAOXING_CLASS_ID, classId)
                    putString(ARG_LOCATION_ADDRESS, locationAddress)
                    if (teacherLat != null) putDouble(ARG_TEACHER_LAT, teacherLat)
                    if (teacherLng != null) putDouble(ARG_TEACHER_LNG, teacherLng)
                }
            }
        }
    }
}
