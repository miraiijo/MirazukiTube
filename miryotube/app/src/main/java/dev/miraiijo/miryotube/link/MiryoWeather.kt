package dev.miraiijo.miryotube.link

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather of miryo's bar (miryo-link v3, content://dev.miraiijo.miryo.profiles/weather): miryo
 * gives its city, miryo tube asks Open-Meteo (free, no key) for the current temperature. No trusted
 * miryo, or no city set there: no weather in the bar (miryo tube has no city setting of its own).
 */
object MiryoWeather {
    private val URI: Uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/weather")

    /** [icon]: SUN, PARTLY_CLOUDY, CLOUD, FOG, RAIN, SNOW or STORM (miryo's names). */
    data class Now(val temperature: Int, val icon: String)

    /** Blocking (network): null without miryo, without a city, or offline. */
    fun current(context: Context): Now? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        val (lat, lon) = runCatching {
            context.contentResolver.query(URI, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
                val la = c.getColumnIndex("latitude")
                val lo = c.getColumnIndex("longitude")
                if (la < 0 || lo < 0 || !c.moveToFirst()) null else c.getDouble(la) to c.getDouble(lo)
            }
        }.getOrNull() ?: return null
        return runCatching {
            val url = URL("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&timezone=auto&latitude=$lat&longitude=$lon")
            val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
            val body = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
            val current = JSONObject(body).getJSONObject("current")
            Now(Math.round(current.optDouble("temperature_2m", 0.0)).toInt(), icon(current.optInt("weather_code", -1)))
        }.getOrNull()
    }

    /** WMO weather codes, as in miryo. */
    private fun icon(code: Int): String = when (code) {
        0 -> "SUN"
        1, 2 -> "PARTLY_CLOUDY"
        45, 48 -> "FOG"
        in 51..57, in 61..67, in 80..82 -> "RAIN"
        in 71..77, 85, 86 -> "SNOW"
        in 95..99 -> "STORM"
        else -> "CLOUD"
    }
}
