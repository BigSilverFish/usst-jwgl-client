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
        val (dLat, dLng) = transformDelta(lat - 35.0, lng - 105.0)
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
        val (dLat, dLng) = transformDelta(lat - 35.0, lng - 105.0)
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
        val aliases: List<String> = emptyList()
    ) {
        override fun toString(): String = "$name ($campus)"
    }

    /**
     * 上海理工大学各教学楼及签到点位常用 GCJ-02 坐标库 (来源于 OpenStreetMap 实测坐标转换 GCJ-02)
     */
    val USST_BUILDINGS = listOf(
        // 军工路516号校区
        CampusBuilding("b_516_1j_m", "一教中", "军工路516号", 31.29464, 121.55350, listOf("一教中", "一教中区", "第一教学楼中")),
        CampusBuilding("b_516_1j_e", "一教东", "军工路516号", 31.29480, 121.55410, listOf("一教东", "一教东区", "第一教学楼东")),
        CampusBuilding("b_516_1j_w", "一教西", "军工路516号", 31.29420, 121.55317, listOf("一教西", "一教西区", "第一教学楼西")),
        CampusBuilding("b_516_3j_e", "三教东", "军工路516号", 31.29795, 121.55377, listOf("三教东", "三教东区", "第三教学楼东")),
        CampusBuilding("b_516_3j_w", "三教西", "军工路516号", 31.29773, 121.55332, listOf("三教西", "三教西区", "第三教学楼西")),
        CampusBuilding("b_516_4j_e", "四教东", "军工路516号", 31.28953, 121.55543, listOf("四教东", "四教东区", "第四教学楼东")),
        CampusBuilding("b_516_4j_w", "四教西", "军工路516号", 31.28953, 121.55486, listOf("四教西", "四教西区", "第四教学楼西")),
        // 综合楼A/B/C/D区为四座相互独立的教学楼
        CampusBuilding("b_516_zh_a", "综合楼A区", "军工路516号", 31.29286, 121.55070, listOf("综合楼A区", "综合楼A", "综A", "综楼A", "综合A", "教学综合楼A区", "教学综合楼A")),
        CampusBuilding("b_516_zh_b", "综合楼B区", "军工路516号", 31.29330, 121.55136, listOf("综合楼B区", "综合楼B", "综B", "综楼B", "综合B", "教学综合楼B区", "教学综合楼B")),
        CampusBuilding("b_516_zh_c", "综合楼C区", "军工路516号", 31.29241, 121.55112, listOf("综合楼C区", "综合楼C", "综C", "综楼C", "综合C", "教学综合楼C区", "教学综合楼C")),
        CampusBuilding("b_516_zh_d", "综合楼D区", "军工路516号", 31.29285, 121.55145, listOf("综合楼D区", "综合楼D", "综D", "综楼D", "综合D", "教学综合楼D区", "教学综合楼D")),
        CampusBuilding("b_516_zy", "卓越楼", "军工路516号", 31.29168, 121.55278, listOf("卓越楼", "卓越", "卓越工程大楼")),
        CampusBuilding("b_516_ggsy", "公共实验楼", "军工路516号", 31.29362, 121.55280, listOf("公共实验楼", "公实楼", "实验楼")),
        CampusBuilding("b_516_jsz", "计算中心", "军工路516号", 31.29829, 121.55274, listOf("计算中心", "计算机中心", "机房")),
        CampusBuilding("b_516_gys", "光仪所", "军工路516号", 31.29620, 121.55153, listOf("光仪所", "上海光学仪器研究所", "光仪所辅房")),
        CampusBuilding("b_516_gh", "国合楼", "军工路516号", 31.29045, 121.55408, listOf("国合楼", "国合", "国际交流合作楼")),
        CampusBuilding("b_516_580_ty", "580体育活动中心", "军工路516号", 31.29549, 121.55000, listOf("580体育活动中心", "580体育馆", "580操场", "体育活动中心", "体育馆", "风雨操场")),
        CampusBuilding("b_516_xty", "新体育健身中心", "军工路516号", 31.29410, 121.55573, listOf("新体育健身中心", "健身中心", "新体育中心", "体育健身中心")),
        CampusBuilding("b_516_bxty", "北校田径场", "军工路516号", 31.29412, 121.55474, listOf("北校田径场", "北田径场", "北区操场", "北操", "北操场")),

        // 军工路1100号校区
        CampusBuilding("b_1100_qx", "勤学楼", "军工路1100号", 31.30242, 121.54448, listOf("勤学", "勤学楼")),
        CampusBuilding("b_1100_bx", "博学楼", "军工路1100号", 31.30244, 121.54500, listOf("博学", "博学楼")),
        CampusBuilding("b_1100_lx", "乐学楼", "军工路1100号", 31.30221, 121.54440, listOf("乐学", "乐学楼")),
        CampusBuilding("b_1100_gw", "格物楼", "军工路1100号", 31.30272, 121.54374, listOf("格物", "格物楼")),
        CampusBuilding("b_1100_dl", "达理楼", "军工路1100号", 31.30219, 121.54382, listOf("达理", "达理楼", "1100号图书馆", "1100图")),
        CampusBuilding("b_1100_hd", "1100学生活动中心", "军工路1100号", 31.30280, 121.54428, listOf("1100学生活动中心", "1100学活", "学生活动中心", "学活")),
        CampusBuilding("b_1100_jt", "1100申阶梯", "军工路1100号", 31.30328, 121.54493, listOf("1100申阶梯", "申阶梯", "阶梯教室")),
        CampusBuilding("b_1100_ty", "1100体育活动中心", "军工路1100号", 31.30288, 121.54499, listOf("1100体育活动中心", "1100乒操馆", "乒操馆", "1100体育馆"))
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

        // 3. 多覆盖点单体建筑智能分流（一教、三教、四教是单栋大体量建筑，多个点位用于解决定位覆盖度问题）
        val roomOffset = extractRoomOffset(lower)

        // 3.1 第一教学楼（一教）：东、中、西多点覆盖
        if (lower.contains("一教") || lower.contains("第一教学楼") || lower.contains("1教")) {
            if (lower.contains("东")) return USST_BUILDINGS.find { it.id == "b_516_1j_e" }
            if (lower.contains("西")) return USST_BUILDINGS.find { it.id == "b_516_1j_w" }
            if (lower.contains("中")) return USST_BUILDINGS.find { it.id == "b_516_1j_m" }
            if (roomOffset != null) {
                return when {
                    roomOffset <= 19 -> USST_BUILDINGS.find { it.id == "b_516_1j_e" }
                    roomOffset in 20..35 -> USST_BUILDINGS.find { it.id == "b_516_1j_m" }
                    else -> USST_BUILDINGS.find { it.id == "b_516_1j_w" }
                } ?: USST_BUILDINGS.find { it.id == "b_516_1j_m" }
            }
            return USST_BUILDINGS.find { it.id == "b_516_1j_m" }
        }

        // 3.2 第三教学楼（三教）：东、西多点覆盖
        if (lower.contains("三教") || lower.contains("第三教学楼") || lower.contains("3教")) {
            if (lower.contains("西")) return USST_BUILDINGS.find { it.id == "b_516_3j_w" }
            if (lower.contains("东")) return USST_BUILDINGS.find { it.id == "b_516_3j_e" }
            if (roomOffset != null) {
                return if (roomOffset <= 10) {
                    USST_BUILDINGS.find { it.id == "b_516_3j_w" }
                } else {
                    USST_BUILDINGS.find { it.id == "b_516_3j_e" }
                } ?: USST_BUILDINGS.find { it.id == "b_516_3j_e" }
            }
            return USST_BUILDINGS.find { it.id == "b_516_3j_e" }
        }

        // 3.3 第四教学楼（四教）：东、西多点覆盖
        if (lower.contains("四教") || lower.contains("第四教学楼") || lower.contains("4教")) {
            if (lower.contains("西")) return USST_BUILDINGS.find { it.id == "b_516_4j_w" }
            if (lower.contains("东")) return USST_BUILDINGS.find { it.id == "b_516_4j_e" }
            if (roomOffset != null) {
                return if (roomOffset <= 12) {
                    USST_BUILDINGS.find { it.id == "b_516_4j_w" }
                } else {
                    USST_BUILDINGS.find { it.id == "b_516_4j_e" }
                } ?: USST_BUILDINGS.find { it.id == "b_516_4j_e" }
            }
            return USST_BUILDINGS.find { it.id == "b_516_4j_e" }
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

        return null
    }

    private fun extractRoomOffset(input: String): Int? {
        val matches = Regex("""(\d{3,4})""").findAll(input).toList()
        if (matches.isEmpty()) return null
        val lastNum = matches.last().value.toIntOrNull() ?: return null
        return lastNum % 100
    }
}
