package com.konumkamera.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Haritaya eklenecek nokta (WGS84 enlem/boylam). */
data class ImportPoint(val name: String?, val lat: Double, val lon: Double, val alt: Double? = null)

/**
 * Dosyadan / yapıştırılan metinden okunan ham nokta.
 * projected = false: a = enlem, b = boylam
 * projected = true : a = sağa (6 haneli), b = yukarı (7 haneli)
 */
data class RawPoint(val name: String?, val a: Double, val b: Double, val z: Double?, val projected: Boolean) {
    fun toImport(crs: Crs): ImportPoint =
        if (!projected) ImportPoint(name, a, b, z)
        else Geo.toWgs84(a, b, crs).let { (lat, lon) -> ImportPoint(name, lat, lon, z) }
}

/**
 * Desteklenen: yapıştırılan metin / CSV / TXT
 *   - "isim, enlem, boylam"  (ondalık derece)
 *   - "no  sağa(X)  yukarı(Y)  kot(Z)"  (ITRF96 / ED50 proje koordinatı)
 * ve KML / KMZ (Placemark > Point).
 */
object Importer {

    private val number = Regex("[-+]?\\d+(?:\\.\\d+)?")
    private val commaDecimal = Regex("(\\d),(\\d)")

    private fun cleanName(s: String): String? =
        s.trim().trim(',', ';', '\t', ' ', ':', '-', '=', '|').trim().takeIf { it.isNotEmpty() }

    fun parseLine(raw: String): RawPoint? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        // "41,0123 28,9765" gibi virgüllü ondalıkları noktaya çevir (satırda hiç nokta yoksa)
        if (!line.contains('.') && Regex("\\d+,\\d+").findAll(line).count() >= 2) {
            line = commaDecimal.replace(line, "$1.$2")
        }
        val nums = number.findAll(line).toList()
        if (nums.size < 2) return null

        // Proje koordinatı: 6 haneli sağa + 7 haneli yukarı
        val east = nums.firstOrNull { abs(it.value.toDouble()) in 100000.0..999999.999 }
        val north = nums.firstOrNull { abs(it.value.toDouble()) in 1000000.0..9999999.999 }
        if (east != null && north != null) {
            val after = maxOf(east.range.last, north.range.last)
            val z = nums.firstOrNull { it.range.first > after }?.value?.toDoubleOrNull()
            val start = minOf(east.range.first, north.range.first)
            return RawPoint(cleanName(line.substring(0, start)), east.value.toDouble(), north.value.toDouble(), z, true)
        }

        // Coğrafi: ondalıklı ilk iki sayı enlem, boylam
        val dec = nums.filter { it.value.contains('.') }
        if (dec.size < 2) return null
        var a = dec[0].value.toDouble()
        var b = dec[1].value.toDouble()
        if (abs(a) > 90 && abs(b) <= 90) { val t = a; a = b; b = t }   // "boylam, enlem" sırası
        if (abs(a) > 90 || abs(b) > 180) return null
        val z = dec.getOrNull(2)?.value?.toDoubleOrNull()
        return RawPoint(cleanName(line.substring(0, dec[0].range.first)), a, b, z, false)
    }

    fun parseText(text: String): List<RawPoint> = text.lines().mapNotNull { parseLine(it) }

    fun parseKml(xml: String): List<RawPoint> {
        val out = mutableListOf<RawPoint>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")
        var inPlacemark = false
        var inPoint = false
        var name: String? = null
        var coord: Triple<Double, Double, Double?>? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val tag = p.name?.substringAfter(':')
            if (ev == XmlPullParser.START_TAG) {
                if (tag == "Placemark") { inPlacemark = true; inPoint = false; name = null; coord = null }
                else if (tag == "Point") inPoint = true
                else if (tag == "name" && inPlacemark && name == null) name = p.nextText().trim()
                else if (tag == "coordinates" && inPlacemark && inPoint) {
                    val parts = p.nextText().trim().split(Regex("\\s+")).firstOrNull()?.split(",")
                    if (parts != null && parts.size >= 2) {
                        val lon = parts[0].toDoubleOrNull()
                        val lat = parts[1].toDoubleOrNull()
                        if (lat != null && lon != null) coord = Triple(lat, lon, parts.getOrNull(2)?.toDoubleOrNull())
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (tag == "Point") inPoint = false
                else if (tag == "Placemark") {
                    coord?.let { out += RawPoint(name, it.first, it.second, it.third, false) }
                    inPlacemark = false
                }
            }
            ev = p.next()
        }
        return out
    }

    /** Dosya içeriğine bakarak türünü anlar (KMZ / KML / metin). */
    fun parseBytes(bytes: ByteArray): List<RawPoint> {
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name.lowercase().endsWith(".kml")) {
                        return parseKml(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    e = zip.nextEntry
                }
            }
            return emptyList()
        }
        var text = bytes.toString(Charsets.UTF_8)
        if (text.contains('�')) text = bytes.toString(Charsets.ISO_8859_1)   // Türkçe eski kodlama
        text = text.removePrefix("﻿")
        return if (text.contains("<kml", ignoreCase = true) || text.contains("<Placemark"))
            parseKml(text) else parseText(text)
    }
}
