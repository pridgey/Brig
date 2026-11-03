package dev.pridgey.brig.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset as minutes after local midnight. Null when the sun doesn't rise or set that day. */
data class SunTimes(val sunrise: Int?, val sunset: Int?, val polarDay: Boolean = false) {
    /** True during daylight. Polar day counts as day, polar night as night. */
    fun isDay(minute: Int, offset: Int = 0): Boolean {
        val rise = sunrise
        val set = sunset
        if (rise == null || set == null) return polarDay
        return ProfileEngine.inWindow(minute, Math.floorMod(rise + offset, 1440), Math.floorMod(set + offset, 1440))
    }
}

/** The "Almanac for Computers" sunrise algorithm. Accurate to a minute or two, which is plenty here. */
object Sun {
    private const val ZENITH = 90.833
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    /** Returns UTC hours, or null if the sun never rises (or sets) that day; [polar] says which. */
    private fun utcHour(dayOfYear: Int, lat: Double, lon: Double, rising: Boolean, polar: (Boolean) -> Unit): Double? {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1) { polar(false); return null }
        if (cosH < -1) { polar(true); return null }
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localMean = h + ra - 0.06571 * t - 6.622
        return norm(localMean - lngHour, 24.0)
    }

    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId = ZoneId.systemDefault()): SunTimes {
        val offsetHours = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        var polarDay = false
        fun toLocal(utc: Double?) = utc?.let { Math.floorMod(Math.round((it + offsetHours) * 60).toInt(), 1440) }
        val rise = toLocal(utcHour(date.dayOfYear, lat, lon, true) { polarDay = it })
        val set = toLocal(utcHour(date.dayOfYear, lat, lon, false) { polarDay = it })
        return if (rise == null || set == null) SunTimes(null, null, polarDay) else SunTimes(rise, set)
    }
}
