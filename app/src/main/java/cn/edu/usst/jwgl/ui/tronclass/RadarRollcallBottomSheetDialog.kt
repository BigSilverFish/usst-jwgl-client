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

    private var selectedLatitude: Double = 31.29170
    private var selectedLongitude: Double = 121.55420
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
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRadarRollcallBinding.inflate(inflater, container, false)
        return binding.root
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

        // 1. 初始化教学楼下拉框
        val buildings = CoordinateUtils.USST_BUILDINGS
        val buildingNames = buildings.map { "${it.name} (${it.campus})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, buildingNames)
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
            if (checkedId == R.id.rbModeBuilding) {
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
        if (binding.rbModeBuilding.isChecked) {
            binding.layoutBuildingSelector.visibility = View.VISIBLE
            binding.layoutGpsStatus.visibility = View.GONE
        } else {
            binding.layoutBuildingSelector.visibility = View.GONE
            binding.layoutGpsStatus.visibility = View.VISIBLE
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

        // 尝试从 GPS 和 Network 获取最近的已缓存位置
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
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

        // 同时发起一次实时单次定位监听以获得最新数据
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

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, listener)
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
        // 原生 GPS 产生 WGS-84 坐标，必须转换为 GCJ-02 火星坐标上报
        val (gcjLat, gcjLng) = CoordinateUtils.wgs84ToGcj02(loc.latitude, loc.longitude)
        selectedLatitude = gcjLat
        selectedLongitude = gcjLng
        selectedAccuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 20.0

        b.tvGpsStatus.text = String.format(
            Locale.CHINA,
            "定位成功 (GPS原生转火星坐标，精度 ±%.0fm)",
            selectedAccuracy
        )
        b.tvCoordinatesDisplay.text = String.format(
            Locale.CHINA,
            "纬度: %.6f, 经度: %.6f",
            selectedLatitude,
            selectedLongitude
        )
        b.tvAccuracyTip.text = String.format(Locale.CHINA, "定位精度: ±%.0fm", selectedAccuracy)
        b.tvSelectedBuildingCampus.text = "来源: 移动设备传感器实时定位"
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

        lifecycleScope.launch {
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

        fun newInstance(
            rollcallId: Long,
            courseName: String? = null,
            classroom: String? = null
        ): RadarRollcallBottomSheetDialog {
            return RadarRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, rollcallId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                }
            }
        }
    }
}
