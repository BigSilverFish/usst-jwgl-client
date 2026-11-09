package cn.edu.usst.jwgl.util

import kotlin.math.*

/**
 * 坐标系转换与距离计算工具类
 * 包含：
 * 1. WGS-84 (GPS标准) 与 GCJ-02 (火星坐标系) 互转算法（与一网畅学前端 bundle 模块 72238 完全一致）
 * 2. 两点间球面距离计算 (Haversine 算法)
 * 3. 上海理工大学校区主要教学楼常用坐标预设与模糊匹配
 */
object CoordinateUtils {

    const val BUILDING_POINT_VERSION = "legacy-22-v1"

    private const val EARTH_RADIUS = 6378137.0 // 地球长半轴 (米)
    private const val EE = 0.006693421622965943 // 椭球偏心率平方

    /**
     * 判断坐标是否在中国境外（境外不需要转换为 GCJ-02）
     */
    fun isOutOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    /**
     * WGS-84 (系统原生 GPS) 转 GCJ-02 (火星坐标，国内高德/腾讯/一网畅学服务端接收坐标)
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutOfChina(lat, lng)) {
            return Pair(lat, lng)
        }
        val (dLat, dLng) = transformDelta(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1.0 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val deltaLat = (dLat * 180.0) / ((EARTH_RADIUS * (1.0 - EE)) / (magic * sqrtMagic) * Math.PI)
        val deltaLng = (dLng * 180.0) / (EARTH_RADIUS / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(lat + deltaLat, lng + deltaLng)
    }

    /**
     * GCJ-02 转 WGS-84
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutOfChina(lat, lng)) {
            return Pair(lat, lng)
        }
        val (dLat, dLng) = transformDelta(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1.0 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val deltaLat = (dLat * 180.0) / ((EARTH_RADIUS * (1.0 - EE)) / (magic * sqrtMagic) * Math.PI)
        val deltaLng = (dLng * 180.0) / (EARTH_RADIUS / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(lat - deltaLat, lng - deltaLng)
    }

    private fun transformDelta(x: Double, y: Double): Pair<Double, Double> {
        val xy = x * y
        val absX = sqrt(abs(x))
        val xPi = x * Math.PI
        val yPi = y * Math.PI

        var lat = 20.0 * sin(6.0 * xPi) + 20.0 * sin(2.0 * xPi)
        var lng = lat

        lat += 20.0 * sin(yPi) + 40.0 * sin(yPi / 3.0)
        lng += 20.0 * sin(xPi) + 40.0 * sin(xPi / 3.0)

        lat += 160.0 * sin(yPi / 12.0) + 320.0 * sin(yPi / 30.0)
        lng += 150.0 * sin(xPi / 12.0) + 300.0 * sin(xPi / 30.0)

        lat *= 2.0 / 3.0
        lng *= 2.0 / 3.0

        lat += 2.0 * x - 100.0 + 3.0 * y + 0.2 * y * y + 0.1 * xy + 0.2 * absX
        lng += 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * xy + 0.1 * absX

        return Pair(lat, lng)
    }

    /**
     * 计算两点间的球面大圆距离 (单位: 米)
     */
    fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)
        val a = radLat1 - radLat2
        val b = Math.toRadians(lng1) - Math.toRadians(lng2)
        val s = 2 * asin(
            sqrt(
                sin(a / 2).pow(2) + cos(radLat1) * cos(radLat2) * sin(b / 2).pow(2)
            )
        )
        return s * EARTH_RADIUS
    }

    /**
     * 教学楼地理模型
     */
    data class CampusBuilding(
        val id: String,
        val name: String,
        val campus: String,
        val latitude: Double, // GCJ-02 纬度
        val longitude: Double, // GCJ-02 经度
        val aliases: List<String> = emptyList(),
        val coordinateSystem: CoordinateSystem = CoordinateSystem.GCJ02,
        val source: String = "旧版内置点位，原始采集来源未记录",
        val version: String = BUILDING_POINT_VERSION,
        val calibratedAt: String? = null,
        val calibrationErrorMeters: Double? = null
    ) {
        override fun toString(): String = "$name ($campus)"

        fun asRollcallLocation(): RollcallLocation = RollcallLocation(
            latitude, longitude, coordinateSystem, source, pointVersion = version
        )
    }

    /**
     * 保留旧版22个点的数值与别名，坐标按原库声明为GCJ-02，不能再次纠偏。
     * 原始测量、校准日期及误差均无可核验记录；合并楼宇仍为一个参考点，待实地校准。
     */
    val USST_BUILDINGS = listOf(
        // 本部北校区（军工路516号北区）
        CampusBuilding("b_516_1j_e", "一教东", "本部北校区", 31.291731, 121.556084, listOf("一教东", "一教东区", "第一教学楼东", "一教中", "第一教学楼中")),
        CampusBuilding("b_516_1j_w", "一教西", "本部北校区", 31.291376, 121.555443, listOf("一教西", "一教西区", "第一教学楼西")),
        CampusBuilding("b_516_3j_e", "三教", "本部北校区", 31.294850, 121.555663, listOf("三教", "第三教学楼", "3教", "三教东", "三教西", "三教东区", "三教西区", "第三教学楼东", "第三教学楼西")),
        CampusBuilding("b_516_zh_a", "综合楼A区", "本部北校区", 31.290008, 121.552855, listOf("综合楼A区", "综合楼A", "综A", "综楼A", "综合A", "教学综合楼A区", "教学综合楼A")),
        CampusBuilding("b_516_zh_b", "综合楼B区", "本部北校区", 31.290425, 121.553512, listOf("综合楼B区", "综合楼B", "综B", "综楼B", "综合B", "教学综合楼B区", "教学综合楼B")),
        CampusBuilding("b_516_zh_c", "综合楼C区", "本部北校区", 31.289549, 121.553327, listOf("综合楼C区", "综合楼C", "综C", "综楼C", "综合C", "教学综合楼C区", "教学综合楼C")),
        CampusBuilding("b_516_zh_d", "综合楼D区", "本部北校区", 31.289939, 121.553901, listOf("综合楼D区", "综合楼D", "综D", "综楼D", "综合D", "教学综合楼D区", "教学综合楼D")),
        CampusBuilding("b_516_ggsy", "公共实验楼", "本部北校区", 31.290732, 121.554982, listOf("公共实验楼", "公实楼", "实验楼")),
        CampusBuilding("b_516_jsz", "计算中心", "本部北校区", 31.295343, 121.554926, listOf("计算中心", "计算机中心", "机房")),
        CampusBuilding("b_516_gys", "光仪所", "本部北校区", 31.293320, 121.553673, listOf("光仪所", "上海光学仪器研究所", "光仪所辅房")),
        CampusBuilding("b_516_580_ty", "580体育活动中心", "本部北校区", 31.292575, 121.552187, listOf("580体育活动中心", "580体育馆", "580操场", "体育活动中心", "体育馆", "风雨操场")),
        CampusBuilding("b_516_xty", "新体育健身中心", "本部北校区", 31.291248, 121.557887, listOf("新体育健身中心", "健身中心", "新体育中心", "体育健身中心")),
        CampusBuilding("b_516_bxty", "北校田径场", "本部北校区", 31.291041, 121.556672, listOf("北校田径场", "北田径场", "北区操场", "北操", "北操场")),

        // 本部南校区（军工路516号南区）
        CampusBuilding("b_516_zy", "卓越楼", "本部南校区", 31.288889, 121.554837, listOf("卓越楼", "卓越", "卓越工程大楼")),
        CampusBuilding("b_516_gh", "国合楼", "本部南校区", 31.287511, 121.556312, listOf("国合楼", "国合", "国际交流合作楼")),
        CampusBuilding("b_516_4j_w", "四教", "本部南校区", 31.286636, 121.557302, listOf("四教", "第四教学楼", "4教", "四教东", "四教西", "四教东区", "四教西区", "第四教学楼东", "第四教学楼西")),

        // 军工路1100号校区（保留合并点位，尚未分楼校准）
        CampusBuilding("b_1100_bx", "博学楼", "军工路1100号", 31.299551, 121.547103, listOf("博学楼", "博学")),
        CampusBuilding("b_1100_dl", "达理楼·勤学楼·乐学楼", "军工路1100号", 31.299427, 121.546237, listOf("达理楼", "达理", "勤学楼", "勤学", "乐学楼", "乐学", "1100号图书馆", "1100图", "1100图书馆")),
        CampusBuilding("b_1100_hd", "格物楼·1100学生活动中心", "军工路1100号", 31.299917, 121.546015, listOf("格物楼", "格物", "1100学生活动中心", "学生活动中心", "1100学活", "学活")),
        CampusBuilding("b_1100_jt", "1100申阶梯·体育活动中心", "军工路1100号", 31.300101, 121.547137, listOf("1100申阶梯", "申阶梯", "阶梯教室", "1100体育活动中心", "1100乒操馆", "乒操馆", "1100体育馆", "体育活动中心", "体育馆")),

        // 外校区教学楼
        CampusBuilding("b_fx_jxl", "复兴路教学楼", "复兴路校区", 31.211925, 121.460448, listOf("复兴路教学楼", "复兴路", "复兴校区", "复兴教学楼")),
        CampusBuilding("b_yk_jxl", "营口路教学楼", "营口路校区", 31.289567, 121.534146, listOf("营口路教学楼", "营口路", "营口校区", "营口教学楼"))
    )

    /**
     * 根据教室名称或课程地点智能模糊匹配最近或对应的教学楼/签到覆盖点位
     */
    fun findMatchingBuilding(placeOrRoom: String?): CampusBuilding? {
        if (placeOrRoom.isNullOrBlank()) return null
        val lower = placeOrRoom.trim().lowercase()

        // 1. 全名按长度降序优先匹配（避免短名提前拦截长名，如“580体育活动中心”与“1100体育活动中心”）
        val sortedByName = USST_BUILDINGS.sortedByDescending { it.name.length }
        for (b in sortedByName) {
            if (lower.contains(b.name.lowercase())) return b
        }

        // 2. 别名匹配（按别名长度倒序，优先命中具体别名，避免通用词提前拦截）
        val allAliases = USST_BUILDINGS.flatMap { b ->
            b.aliases.map { alias -> alias to b }
        }.sortedByDescending { it.first.length }

        for ((alias, b) in allAliases) {
            if (lower.contains(alias.lowercase())) return b
        }

        // 3. 专属单体与组合楼宇智能规则分流
        val roomOffset = extractRoomOffset(lower)

        // 3.1 第一教学楼（一教）：东、西双点高精覆盖
        if (lower.contains("一教") || lower.contains("第一教学楼") || lower.contains("1教")) {
            if (lower.contains("西")) return USST_BUILDINGS.find { it.id == "b_516_1j_w" }
            if (lower.contains("东")) return USST_BUILDINGS.find { it.id == "b_516_1j_e" }
            if (roomOffset != null) {
                return if (roomOffset <= 25) {
                    USST_BUILDINGS.find { it.id == "b_516_1j_e" }
                } else {
                    USST_BUILDINGS.find { it.id == "b_516_1j_w" }
                } ?: USST_BUILDINGS.find { it.id == "b_516_1j_e" }
            }
            return USST_BUILDINGS.find { it.id == "b_516_1j_e" }
        }

        // 3.2 第三教学楼（三教）：单点统一覆盖
        if (lower.contains("三教") || lower.contains("第三教学楼") || lower.contains("3教")) {
            return USST_BUILDINGS.find { it.id == "b_516_3j_e" }
        }

        // 3.3 第四教学楼（四教）：单点统一覆盖
        if (lower.contains("四教") || lower.contains("第四教学楼") || lower.contains("4教")) {
            return USST_BUILDINGS.find { it.id == "b_516_4j_w" }
        }

        // 3.4 军工路1100号校区精简组团规则
        if (lower.contains("达理") || lower.contains("勤学") || lower.contains("乐学")) {
            return USST_BUILDINGS.find { it.id == "b_1100_dl" }
        }
        if (lower.contains("格物") || lower.contains("学活") || (lower.contains("1100") && lower.contains("活动"))) {
            return USST_BUILDINGS.find { it.id == "b_1100_hd" }
        }
        if (lower.contains("申阶梯") || lower.contains("阶梯") || (lower.contains("1100") && (lower.contains("体") || lower.contains("操")))) {
            return USST_BUILDINGS.find { it.id == "b_1100_jt" }
        }
        if (lower.contains("博学")) {
            return USST_BUILDINGS.find { it.id == "b_1100_bx" }
        }

        // 3.4 综合楼独立4座楼兜底（若未在别名命中，按分区字母匹配）
        if (lower.contains("综合楼") || lower.contains("教学综合楼") || lower.contains("综楼")) {
            return when {
                lower.contains("a") -> USST_BUILDINGS.find { it.id == "b_516_zh_a" }
                lower.contains("b") -> USST_BUILDINGS.find { it.id == "b_516_zh_b" }
                lower.contains("c") -> USST_BUILDINGS.find { it.id == "b_516_zh_c" }
                lower.contains("d") -> USST_BUILDINGS.find { it.id == "b_516_zh_d" }
                else -> USST_BUILDINGS.find { it.id == "b_516_zh_a" }
            }
        }

        // 3.5 外校区教学楼直接识别
        if (lower.contains("复兴")) {
            return USST_BUILDINGS.find { it.id == "b_fx_jxl" }
        }
        if (lower.contains("营口")) {
            return USST_BUILDINGS.find { it.id == "b_yk_jxl" }
        }

        return null
    }

    private fun extractRoomOffset(input: String): Int? {
        val matches = Regex("""(\d{3,4})""").findAll(input).toList()
        if (matches.isEmpty()) return null
        val lastNum = matches.last().value.toIntOrNull() ?: return null
        return lastNum % 100
    }

    /**
     * 根据 GCJ-02 火星坐标自动寻找物理距离最近的教学楼及实际距离（米）
     */
    fun findNearestBuilding(lat: Double, lng: Double): Pair<CampusBuilding, Double> {
        var minDistance = Double.MAX_VALUE
        var nearest = USST_BUILDINGS.first()
        for (b in USST_BUILDINGS) {
            val dist = calculateDistance(lat, lng, b.latitude, b.longitude)
            if (dist < minDistance) {
                minDistance = dist
                nearest = b
            }
        }
        return Pair(nearest, minDistance)
    }

    /**
     * 根据传感器原生 WGS-84 原始坐标自动纠偏并寻找物理距离最近的教学楼及实际距离（米）
     */
    fun findNearestBuildingFromWgs84(wgsLat: Double, wgsLng: Double): Pair<CampusBuilding, Double> {
        val (gcjLat, gcjLng) = wgs84ToGcj02(wgsLat, wgsLng)
        return findNearestBuilding(gcjLat, gcjLng)
    }
}
