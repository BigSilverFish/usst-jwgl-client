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
     * 上海理工大学各主要教学楼常用 GCJ-02 坐标库
     */
    val USST_BUILDINGS = listOf(
        CampusBuilding("b_1j", "第一教学楼", "军工路516号", 31.29170, 121.55420, listOf("一教", "1教", "第一教学楼")),
        CampusBuilding("b_2j", "第二教学楼", "军工路516号", 31.29255, 121.55480, listOf("二教", "2教", "第二教学楼")),
        CampusBuilding("b_zh", "教学综合楼", "军工路516号", 31.29050, 121.55350, listOf("综合楼", "教学综合楼", "综楼")),
        CampusBuilding("b_5j", "第五教学楼 / 基础学院", "军工路516号", 31.29320, 121.55560, listOf("五教", "5教", "第五教学楼", "基础学院")),
        CampusBuilding("b_xj", "先进制造大楼", "军工路516号", 31.28980, 121.55220, listOf("先进制造", "先进制造大楼", "机械大楼")),
        CampusBuilding("b_gd", "光电信息与计算机大楼", "军工路516号", 31.28890, 121.55180, listOf("光电大楼", "光电", "光电信息与计算机工程学院")),
        CampusBuilding("b_jx", "机械工程馆", "军工路516号", 31.28950, 121.55480, listOf("机械馆", "工程馆", "机械工程馆")),
        CampusBuilding("b_ts", "军工路516号图书馆", "军工路516号", 31.29120, 121.55300, listOf("图书馆", "516图")),
        CampusBuilding("b_ty", "体育活动中心 / 操场", "军工路516号", 31.29380, 121.55280, listOf("体育馆", "操场", "风雨操场")),
        CampusBuilding("b_1100_j", "军工路1100号教学楼", "军工路1100号", 31.29760, 121.55050, listOf("1100号", "1100", "水产路", "1100号教学楼")),
        CampusBuilding("b_334_j", "军工路334号校区", "军工路334号", 31.28350, 121.55800, listOf("334号", "334", "出版高等专科"))
    )

    /**
     * 根据教室名称或课程地点模糊匹配最近或对应的教学楼
     */
    fun findMatchingBuilding(placeOrRoom: String?): CampusBuilding? {
        if (placeOrRoom.isNullOrBlank()) return null
        val lower = placeOrRoom.trim().lowercase()

        // 优先精确/别名命中
        for (b in USST_BUILDINGS) {
            if (lower.contains(b.name.lowercase())) return b
            for (alias in b.aliases) {
                if (lower.contains(alias.lowercase())) return b
            }
        }

        // 默认匹配一教
        if (lower.contains("一") || lower.contains("1")) return USST_BUILDINGS[0]
        if (lower.contains("二") || lower.contains("2")) return USST_BUILDINGS[1]
        if (lower.contains("五") || lower.contains("5")) return USST_BUILDINGS[3]

        return null
    }
}
