package com.konumkamera.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Proje koordinat sistemi (sağa/yukarı → enlem/boylam). */
data class Crs(
    val id: String,
    val label: String,
    val lon0: Double,          // dilim orta meridyeni
    val k0: Double,            // ölçek (3° = 1.0, UTM = 0.9996)
    val ed50: Boolean          // ED50 ise WGS84'e kaydırılır
)

object Geo {
    private const val GRS80_A = 6378137.0
    private const val GRS80_F = 1 / 298.257222101
    private const val INTL_A = 6378388.0
    private const val INTL_F = 1 / 297.0

    val systems: List<Crs> = buildList {
        for (cm in listOf(27, 30, 33, 36, 39, 42, 45))
            add(Crs("ITRF96_3_$cm", "ITRF96 – 3° dilim, orta meridyen $cm", cm.toDouble(), 1.0, false))
        for ((z, cm) in listOf(35 to 27, 36 to 33, 37 to 39, 38 to 45))
            add(Crs("WGS84_UTM_$z", "WGS84 – 6° UTM $z (orta meridyen $cm)", cm.toDouble(), 0.9996, false))
        for ((z, cm) in listOf(35 to 27, 36 to 33, 37 to 39, 38 to 45))
            add(Crs("ED50_UTM_$z", "ED50 – 6° UTM $z (orta meridyen $cm)", cm.toDouble(), 0.9996, true))
        for (cm in listOf(27, 30, 33, 36, 39, 42, 45))
            add(Crs("ED50_3_$cm", "ED50 – 3° dilim, orta meridyen $cm", cm.toDouble(), 1.0, true))
    }

    fun byId(id: String?): Crs = systems.firstOrNull { it.id == id } ?: systems.first { it.id == "ITRF96_3_33" }

    /** easting = sağa (6 haneli), northing = yukarı (7 haneli). Dönüş: enlem, boylam (WGS84). */
    fun toWgs84(easting: Double, northing: Double, crs: Crs): Pair<Double, Double> {
        val a = if (crs.ed50) INTL_A else GRS80_A
        val f = if (crs.ed50) INTL_F else GRS80_F
        val (lat, lon) = tmInverse(easting, northing, crs.lon0, crs.k0, 500000.0, a, f)
        return if (crs.ed50) ed50ToWgs84(lat, lon) else lat to lon
    }

    private fun tmInverse(e: Double, n: Double, lon0: Double, k0: Double, fe: Double, a: Double, f: Double): Pair<Double, Double> {
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val m = n / k0
        val mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))
        val s = sqrt(1 - e2)
        val e1 = (1 - s) / (1 + s)
        val p1 = mu + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) +
            (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)
        val sp = sin(p1); val cp = cos(p1); val tp = tan(p1)
        val c1 = ep2 * cp * cp
        val t1 = tp * tp
        val n1 = a / sqrt(1 - e2 * sp * sp)
        val r1 = a * (1 - e2) / Math.pow(1 - e2 * sp * sp, 1.5)
        val d = (e - fe) / (n1 * k0)
        val d2 = d * d; val d4 = d2 * d2; val d6 = d4 * d2
        val lat = p1 - (n1 * tp / r1) * (d2 / 2 -
            (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d4 / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d6 / 720)
        val lon = Math.toRadians(lon0) + (d - (1 + 2 * t1 + c1) * d2 * d / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d4 * d / 120) / cp
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    /** ED50 → WGS84 (Avrupa ortalaması 3 parametre: -87, -98, -121 m). */
    private fun ed50ToWgs84(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val e2 = INTL_F * (2 - INTL_F)
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        val nn = INTL_A / sqrt(1 - e2 * sin(la) * sin(la))
        val x = nn * cos(la) * cos(lo) - 87.0
        val y = nn * cos(la) * sin(lo) - 98.0
        val z = nn * (1 - e2) * sin(la) - 121.0
        val a2 = 6378137.0
        val f2 = 1 / 298.257223563
        val e22 = f2 * (2 - f2)
        val p = hypot(x, y)
        val lon2 = atan2(y, x)
        var lat2 = atan2(z, p * (1 - e22))
        repeat(6) {
            val n2 = a2 / sqrt(1 - e22 * sin(lat2) * sin(lat2))
            val h = p / cos(lat2) - n2
            lat2 = atan2(z, p * (1 - e22 * n2 / (n2 + h)))
        }
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }
}
