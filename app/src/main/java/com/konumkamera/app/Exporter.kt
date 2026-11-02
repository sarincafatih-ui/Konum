package com.konumkamera.app

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Noktaları KML (sadece konum + not) ya da KMZ (fotoğraflı) olarak dışa aktarır. */
object Exporter {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun buildKml(title: String, sites: List<Site>, photoPaths: Map<String, List<String>>): String {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("  <name>").append(esc(title)).append("</name>\n")
        sb.append("  <Style id=\"yapildi\"><IconStyle><scale>1.1</scale><Icon><href>")
            .append("http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon></IconStyle></Style>\n")
        sb.append("  <Style id=\"bos\"><IconStyle><scale>1.1</scale><Icon><href>")
            .append("http://maps.google.com/mapfiles/kml/paddle/wht-circle.png</href></Icon></IconStyle></Style>\n")
        for (s in sites) {
            val html = StringBuilder()
            html.append("<b>").append(esc(s.name)).append("</b> — ")
                .append(if (s.isDone) "Yapıldı" else "Yapılmadı").append("<br/>")
            html.append(String.format(Locale.US, "%.7f, %.7f", s.lat, s.lon)).append("<br/>")
            s.alt?.let { html.append(String.format(Locale.US, "Kot: %.2f m", it)).append("<br/>") }
            if (s.updated > 0) html.append("Güncelleme: ").append(df.format(Date(s.updated))).append("<br/>")
            if (s.note.isNotBlank()) html.append("<br/>").append(esc(s.note).replace("\n", "<br/>")).append("<br/>")
            photoPaths[s.id]?.forEach { p ->
                html.append("<br/><img src=\"").append(p).append("\" width=\"480\"/>")
            }
            sb.append("  <Placemark>\n")
            sb.append("    <name>").append(esc(s.name)).append("</name>\n")
            sb.append("    <description><![CDATA[").append(html).append("]]></description>\n")
            sb.append("    <styleUrl>#").append(if (s.isDone) "yapildi" else "bos").append("</styleUrl>\n")
            sb.append("    <Point><coordinates>")
                .append(String.format(Locale.US, "%.8f,%.8f,0", s.lon, s.lat))
                .append("</coordinates></Point>\n")
            sb.append("  </Placemark>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    /** Dönen değer: (kmz baytları, eklenen fotoğraf sayısı) */
    fun buildKmz(title: String, sites: List<Site>): Pair<ByteArray, Int> {
        val paths = linkedMapOf<String, MutableList<String>>()
        val files = linkedMapOf<String, ByteArray>()
        sites.forEachIndexed { i, s ->
            s.photos.forEachIndexed { j, p ->
                PhotoUtils.jpegBytes(p, 1600)?.let { bytes ->
                    val name = "files/" + (i + 1) + "_" + (j + 1) + ".jpg"
                    files[name] = bytes
                    paths.getOrPut(s.id) { mutableListOf() } += name
                }
            }
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(buildKml(title, sites, paths).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b); zip.closeEntry() }
        }
        return bos.toByteArray() to files.size
    }
}
