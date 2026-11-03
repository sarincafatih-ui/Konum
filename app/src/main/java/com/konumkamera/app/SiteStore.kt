package com.konumkamera.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Haritadaki bir nokta (daire). Not veya fotoğraf varsa "yapıldı" sayılır. */
data class Site(
    val id: String,
    var name: String,
    var lat: Double,
    var lon: Double,
    var note: String = "",
    val photos: MutableList<String> = mutableListOf(),   // uygulama klasöründeki dosya yolları
    var updated: Long = 0L,
    val alt: Double? = null                               // kot (m), listede varsa
) {
    val isDone: Boolean get() = note.isNotBlank() || photos.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("lat", lat); put("lon", lon)
        put("note", note); put("updated", updated)
        put("photos", JSONArray().apply { photos.forEach { put(it) } })
        alt?.let { put("alt", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Site {
            val arr = o.optJSONArray("photos") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list += arr.getString(i)
            return Site(
                o.getString("id"), o.optString("name"),
                o.getDouble("lat"), o.getDouble("lon"),
                o.optString("note"), list, o.optLong("updated"),
                if (o.has("alt")) o.getDouble("alt") else null
            )
        }

        fun create(name: String, lat: Double, lon: Double, alt: Double? = null) =
            Site(UUID.randomUUID().toString(), name, lat, lon, alt = alt)
    }
}

/** Noktaları telefonun içinde bir JSON dosyasında saklar (internetsiz çalışır). */
class SiteStore(context: Context) {
    private val file = File(context.filesDir, "noktalar.json")
    val sites = mutableListOf<Site>()

    init { load() }

    private fun load() {
        sites.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) sites += Site.fromJson(arr.getJSONObject(i))
        }
    }

    fun save() {
        val arr = JSONArray().apply { sites.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }

    fun find(id: String): Site? = sites.firstOrNull { it.id == id }

    /** Aynı yerde (yaklaşık 0,5 m) aynı isimde nokta varsa tekrar eklemez. Eklenen sayısını döner. */
    fun addAll(items: List<ImportPoint>): Int {
        var added = 0
        for (p in items) {
            val n = p.name?.takeIf { it.isNotBlank() } ?: ("N" + (sites.size + 1))
            val dup = sites.any {
                it.name == n && Math.abs(it.lat - p.lat) < 0.000005 && Math.abs(it.lon - p.lon) < 0.000005
            }
            if (!dup) { sites += Site.create(n, p.lat, p.lon, p.alt); added++ }
        }
        if (added > 0) save()
        return added
    }
}
