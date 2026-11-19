package cn.edu.usst.jwgl.util

import kotlin.math.*

/**
 * 畅课雷达点名 · 三点定位测距反解器 (Trilateration Solver)
 *
 * 核心原理：
 * 畅课雷达签到接口在提交点超出范围时，服务端会返回超出范围错误，并携带提交点与教师端真实坐标的球面大圆距离 (distance，单位: 米)。
 * 通过在上理校园内选取 3 个不共线的基准锚点（或围绕预估点生成三角锚点）进行探测，
 * 收集 3 组 (经纬度, 测距距离) 观测值，利用三边测量几何封闭解 (Trilateration Closed-form) + 高斯牛顿最小二乘优化，
 * 反解出教师端的精确经纬度坐标 (精度达 1~5 米内)。
 */
object TrilaterationSolver {

    private const val EARTH_RADIUS = 6378137.0 // 地球平均半径 (米)

    /**
     * 单个基准锚点的测距观测数据
     * @param latitude 锚点 GCJ-02 纬度
     * @param longitude 锚点 GCJ-02 经度
     * @param distanceMeters 服务端返回的到教师端的距离（米）
     */
    data class AnchorObservation(
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        val name: String = ""
    )

    /**
     * 反解计算结果
     * @param latitude 解算出的教师端 GCJ-02 纬度
     * @param longitude 解算出的教师端 GCJ-02 经度
     * @param residualRmse 测距拟合均方根误差 (米)
     */
    data class SolveResult(
        val latitude: Double,
        val longitude: Double,
        val residualRmse: Double
    )

    /**
     * 根据 3 个及以上的观测锚点解算目标精确坐标
     * @param observations 至少 3 个有效测距点
     * @return 解算结果，若点共线或测距不足则返回 null
     */
    fun solve(observations: List<AnchorObservation>): SolveResult? {
        if (observations.size < 3) return null

        // 1. 选取第 1 个点作为局部切平面的原点 (0, 0)
        val p1 = observations[0]
        val lat0 = p1.latitude
        val lon0 = p1.longitude
        val r1 = p1.distanceMeters

        val radLat0 = Math.toRadians(lat0)
        val cosLat0 = cos(radLat0)

        // 2. 将球面坐标转换为以 p1 为原点的平面直角坐标 (x_i, y_i)
        // x 为东向位移 (米), y 为北向位移 (米)
        fun toLocalPlane(lat: Double, lon: Double): Pair<Double, Double> {
            val dLat = Math.toRadians(lat - lat0)
            val dLon = Math.toRadians(lon - lon0)
            val y = dLat * EARTH_RADIUS
            val x = dLon * EARTH_RADIUS * cosLat0
            return Pair(x, y)
        }

        fun toGeoCoord(x: Double, y: Double): Pair<Double, Double> {
            val dLat = (y / EARTH_RADIUS) * 180.0 / Math.PI
            val dLon = (x / (EARTH_RADIUS * cosLat0)) * 180.0 / Math.PI
            return Pair(lat0 + dLat, lon0 + dLon)
        }

        val p2 = observations[1]
        val p3 = observations[2]

        val (x2, y2) = toLocalPlane(p2.latitude, p2.longitude)
        val (x3, y3) = toLocalPlane(p3.latitude, p3.longitude)
        val r2 = p2.distanceMeters
        val r3 = p3.distanceMeters

        // 3. 求解线性方程组：
        // 2*x2*x + 2*y2*y = r1^2 - r2^2 + x2^2 + y2^2  (C1)
        // 2*x3*x + 2*y3*y = r1^2 - r3^2 + x3^2 + y3^2  (C2)
        val a11 = 2.0 * x2
        val a12 = 2.0 * y2
        val c1 = (r1.pow(2) - r2.pow(2)) + (x2.pow(2) + y2.pow(2))

        val a21 = 2.0 * x3
        val a22 = 2.0 * y3
        val c2 = (r1.pow(2) - r3.pow(2)) + (x3.pow(2) + y3.pow(2))

        val det = a11 * a22 - a12 * a21
        // 如果三点共线或退化，行列式接近 0
        if (abs(det) < 1e-6) {
            // 如果提供的 3 点退化，尝试其它点组合
            if (observations.size > 3) {
                for (i in 3 until observations.size) {
                    val altP = observations[i]
                    val (altX, altY) = toLocalPlane(altP.latitude, altP.longitude)
                    val altR = altP.distanceMeters
                    val altA21 = 2.0 * altX
                    val altA22 = 2.0 * altY
                    val altC2 = (r1.pow(2) - altR.pow(2)) + (altX.pow(2) + altY.pow(2))
                    val altDet = a11 * altA22 - a12 * altA21
                    if (abs(altDet) >= 1e-6) {
                        val initX = (c1 * altA22 - altC2 * a12) / altDet
                        val initY = (a11 * altC2 - altA21 * c1) / altDet
                        return refineAndBuildResult(initX, initY, observations, ::toGeoCoord)
                    }
                }
            }
            return null
        }

        // 初步闭式解 (initX, initY)
        val initX = (c1 * a22 - c2 * a12) / det
        val initY = (a11 * c2 - a21 * c1) / det

        return refineAndBuildResult(initX, initY, observations, ::toGeoCoord)
    }

    /**
     * 高斯牛顿非线性迭代优化，消除平面切面投影高阶残差
     */
    private fun refineAndBuildResult(
        initX: Double,
        initY: Double,
        observations: List<AnchorObservation>,
        toGeoCoord: (Double, Double) -> Pair<Double, Double>
    ): SolveResult {
        var currX = initX
        var currY = initY

        val maxIterations = 15
        val learningRate = 0.3

        for (iter in 0 until maxIterations) {
            val (cLat, cLng) = toGeoCoord(currX, currY)
            var gradX = 0.0
            var gradY = 0.0

            for (obs in observations) {
                val d = CoordinateUtils.calculateDistance(cLat, cLng, obs.latitude, obs.longitude)
                val diff = d - obs.distanceMeters

                if (d > 0.1) {
                    // 对局部 x, y 求偏导
                    val dx = (cLng - obs.longitude) * Math.toRadians(1.0) * EARTH_RADIUS * cos(Math.toRadians(cLat))
                    val dy = (cLat - obs.latitude) * Math.toRadians(1.0) * EARTH_RADIUS
                    gradX += 2.0 * diff * (dx / d)
                    gradY += 2.0 * diff * (dy / d)
                }
            }

            val step = learningRate / (1.0 + iter * 0.1)
            currX -= step * (gradX / observations.size).coerceIn(-50.0, 50.0)
            currY -= step * (gradY / observations.size).coerceIn(-50.0, 50.0)
        }

        val (optLat, optLng) = toGeoCoord(currX, currY)

        // 计算最终 RMSE
        var totalSqErr = 0.0
        for (obs in observations) {
            val d = CoordinateUtils.calculateDistance(optLat, optLng, obs.latitude, obs.longitude)
            totalSqErr += (d - obs.distanceMeters).pow(2)
        }
        val rmse = sqrt(totalSqErr / observations.size)

        return SolveResult(optLat, optLng, rmse)
    }

    /**
     * 智能获取校园三点基准锚点
     * 默认军工路 516 号校区大三角（一教东、综合楼A、四教）
     */
    fun getCampusAnchors(classroom: String? = null, courseName: String? = null): List<CoordinateUtils.CampusBuilding> {
        val text = "${classroom.orEmpty()} ${courseName.orEmpty()}".lowercase()

        // 1. 1100号校区专属三角锚点
        if (text.contains("1100") || text.contains("博学") || text.contains("格物") || text.contains("达理") || text.contains("勤学") || text.contains("乐学")) {
            val b1 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_1100_bx" } // 博学楼
            val b2 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_1100_dl" } // 达理楼
            val b3 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_1100_jt" } // 1100申阶梯
            if (b1 != null && b2 != null && b3 != null) {
                return listOf(b1, b2, b3)
            }
        }

        // 2. 复兴路校区专属三角锚点
        if (text.contains("复兴")) {
            val fx = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_fx_jxl" }
            if (fx != null) {
                return listOf(
                    fx.copy(id = "fx_a", name = "复兴路主楼", latitude = fx.latitude + 0.0010, longitude = fx.longitude),
                    fx.copy(id = "fx_b", name = "复兴路东侧", latitude = fx.latitude - 0.0005, longitude = fx.longitude + 0.0010),
                    fx.copy(id = "fx_c", name = "复兴路西侧", latitude = fx.latitude - 0.0005, longitude = fx.longitude - 0.0010)
                )
            }
        }

        // 3. 军工路516号本部（默认黄金大三角：一教东 + 综合楼A + 四教）
        val a1 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_e" }
            ?: CoordinateUtils.USST_BUILDINGS[0] // 一教东
        val a2 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_zh_a" }
            ?: CoordinateUtils.USST_BUILDINGS[3] // 综合楼A
        val a3 = CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_4j_w" }
            ?: CoordinateUtils.USST_BUILDINGS[15] // 四教

        return listOf(a1, a2, a3)
    }
}
