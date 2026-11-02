package com.konumkamera.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.konumkamera.app.databinding.ActivityMainBinding
import com.konumkamera.app.databinding.SheetSiteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Esri World Imagery uydu görüntüsü (Google Earth benzeri, anahtar gerektirmez). */
object EsriImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 1, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Uydu görüntüsü © Esri, Maxar, Earthstar Geographics"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val MAX_ACCURACY_M = 10f
        const val MAX_TILES = 40000
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var store: SiteStore
    private lateinit var locationManager: LocationManager
    private lateinit var myLocation: MyLocationNewOverlay
    private val markers = mutableMapOf<String, Marker>()

    private var bestLocation: Location? = null
    private var listening = false

    private var pendingPhotoFile: File? = null
    private var pendingSiteId: String? = null
    private var sheet: BottomSheetDialog? = null
    private var sheetBinding: SheetSiteBinding? = null
    private var sheetSiteId: String? = null

    private val trLocale = Locale("tr", "TR")

    // ---------- Sonuç alıcıları ----------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                runCatching { myLocation.enableMyLocation() }
                startLocationUpdates()
            }
            updateStatus()
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = pendingPhotoFile
            val siteId = pendingSiteId
            if (!ok || file == null || siteId == null || file.length() == 0L) {
                toast("Fotoğraf çekilmedi."); return@registerForActivityResult
            }
            val loc = bestLocation
            if (loc != null && loc.hasAccuracy() && loc.accuracy <= MAX_ACCURACY_M) {
                processPhoto(file, siteId, loc)
            } else {
                val acc = if (loc != null && loc.hasAccuracy()) "±" + loc.accuracy.roundToInt() + " m" else "bilinmiyor"
                AlertDialog.Builder(this)
                    .setTitle("Konum hassasiyeti düşük")
                    .setMessage("GPS hassasiyeti $acc (sınır: " + MAX_ACCURACY_M.roundToInt() + " m).\n\n" +
                        "Açık alanda birkaç saniye bekleyip tekrar çekmeniz önerilir.\n\n" +
                        "Tamam derseniz fotoğraf bu haliyle kaydedilir.")
                    .setCancelable(false)
                    .setPositiveButton("Tamam") { _, _ -> processPhoto(file, siteId, loc) }
                    .setNegativeButton("Tekrar Çek") { _, _ -> file.delete(); takePhotoFor(siteId) }
                    .show()
            }
        }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val siteId = pendingSiteId ?: return@registerForActivityResult
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            addGalleryImages(siteId, uris)
        }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromUri(uri)
        }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onProviderEnabled(provider: String) = updateStatus()
        override fun onProviderDisabled(provider: String) = updateStatus()
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // ---------- Yaşam döngüsü ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            val base = File(filesDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
            tileFileSystemCacheMaxBytes = 3L * 1024 * 1024 * 1024
            tileFileSystemCacheTrimBytes = 2500L * 1024 * 1024
            expirationOverrideDuration = 3650L * 24 * 3600 * 1000   // indirilen karolar eskimesin
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        store = SiteStore(this)
        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        if (!prefs.getBoolean(SeedData.KEY, false)) {        // 1. profil direkleri (ilk açılışta bir kez)
            store.addAll(SeedData.asImportPoints())
            prefs.edit().putBoolean(SeedData.KEY, true).apply()
        }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        savedInstanceState?.let {
            it.getString("pendingPath")?.let { p -> pendingPhotoFile = File(p) }
            pendingSiteId = it.getString("pendingSite")
        }

        setupMap()
        b.fabMyLocation.setOnClickListener { goToMyLocation() }
        b.fabAll.setOnClickListener { zoomToAll() }

        requestNeededPermissions()
        updateStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoFile?.let { outState.putString("pendingPath", it.absolutePath) }
        pendingSiteId?.let { outState.putString("pendingSite", it) }
    }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
        if (hasLocationPermission()) {
            runCatching { myLocation.enableMyLocation() }
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        saveSheetNote()
        b.map.onPause()
        runCatching { myLocation.disableMyLocation() }
        stopLocationUpdates()
    }

    // ---------- Harita ----------

    private fun setupMap() {
        val map = b.map
        map.setTileSource(EsriImagery)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 21.0

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) askAddPointHere(p)
                return true
            }
        }))
        map.overlays.add(CopyrightOverlay(this))
        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(true) })

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(myLocation)

        refreshAllMarkers()

        map.post {
            if (store.sites.isNotEmpty()) {
                zoomToAll()
            } else {
                val last = lastKnown()
                if (last != null) {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
                } else {
                    map.controller.setZoom(6.0)
                    map.controller.setCenter(GeoPoint(39.0, 35.0))
                }
            }
        }
        myLocation.runOnFirstFix {
            runOnUiThread {
                if (store.sites.isEmpty()) myLocation.myLocation?.let {
                    b.map.controller.setZoom(17.0); b.map.controller.animateTo(it)
                }
            }
        }
    }

    /** Boş daire = yapılmadı, içi dolu yeşil daire = not ya da fotoğraf eklendi. */
    private fun markerIcon(site: Site): Pair<BitmapDrawable, Float> {
        val d = resources.displayMetrics.density
        val r = 12f * d
        val stroke = 3.5f * d
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER; setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        }
        val label = if (site.name.length > 16) site.name.take(15) + "…" else site.name
        val circleBox = 2 * r + 2 * stroke + 2 * d
        val w = max(circleBox, tp.measureText(label) + 8 * d).toInt()
        val h = (circleBox + tp.textSize + 6 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val cy = circleBox / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.BLACK
        }
        if (site.isDone) {
            fill.color = Color.rgb(46, 160, 67)
            c.drawCircle(cx, cy, r, fill)
            ring.color = Color.WHITE
            c.drawCircle(cx, cy, r, ring)
        } else {
            fill.color = Color.argb(60, 0, 0, 0)
            c.drawCircle(cx, cy, r, fill)
            ring.color = Color.rgb(255, 193, 7)
            c.drawCircle(cx, cy, r, ring)
        }
        c.drawCircle(cx, cy, r + stroke / 2f + 0.6f * d, edge)
        c.drawText(label, cx, circleBox + tp.textSize, tp)
        return BitmapDrawable(resources, bmp) to (cy / h)
    }

    private fun addOrUpdateMarker(site: Site) {
        val id = site.id
        val m = markers[id] ?: Marker(b.map).also { mk ->
            markers[id] = mk
            b.map.overlays.add(mk)
            mk.setOnMarkerClickListener { _, _ -> openSite(id); true }
        }
        m.position = GeoPoint(site.lat, site.lon)
        val (icon, ay) = markerIcon(site)
        m.icon = icon
        m.setAnchor(Marker.ANCHOR_CENTER, ay)
        b.map.invalidate()
    }

    private fun refreshAllMarkers() {
        val ids = store.sites.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { id ->
            markers.remove(id)?.let { b.map.overlays.remove(it) }
        }
        store.sites.forEach { addOrUpdateMarker(it) }
        b.map.invalidate()
        updateStatus()
    }

    private fun zoomToAll() {
        val sites = store.sites
        when {
            sites.isEmpty() -> toast("Henüz nokta yok. Sağ üstteki + ile koordinat ekleyin.")
            sites.size == 1 -> {
                b.map.controller.setZoom(18.0)
                b.map.controller.animateTo(GeoPoint(sites[0].lat, sites[0].lon))
            }
            else -> {
                val bb = BoundingBox.fromGeoPoints(sites.map { GeoPoint(it.lat, it.lon) })
                b.map.zoomToBoundingBox(bb.increaseByScale(1.3f), true)
            }
        }
    }

    private fun goToMyLocation() {
        val p = myLocation.myLocation ?: bestLocation?.let { GeoPoint(it.latitude, it.longitude) }
        if (p == null) { toast("Konum henüz bulunamadı."); return }
        if (b.map.zoomLevelDouble < 16) b.map.controller.setZoom(18.0)
        b.map.controller.animateTo(p)
    }

    private fun updateStatus() {
        if (!::b.isInitialized) return
        val loc = bestLocation
        val gps = when {
            !hasLocationPermission() -> "GPS izni yok"
            loc == null -> "GPS aranıyor…"
            loc.hasAccuracy() -> "GPS ±" + loc.accuracy.roundToInt() + " m"
            else -> "GPS var"
        }
        val total = if (::store.isInitialized) store.sites.size else 0
        val done = if (::store.isInitialized) store.sites.count { it.isDone } else 0
        b.tvStatus.text = "$gps   •   Yapılan $done / $total"
        val good = loc != null && loc.hasAccuracy() && loc.accuracy <= MAX_ACCURACY_M
        b.tvStatus.setTextColor(if (good) Color.rgb(140, 255, 150) else Color.rgb(255, 210, 120))
    }

    // ---------- Nokta paneli ----------

    private fun openSite(id: String) {
        val site = store.find(id) ?: return
        sheet?.dismiss()
        val sb = SheetSiteBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(sb.root)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        sb.etNote.setText(site.note)
        bindSheet(site, sb)

        sb.tvTitle.setOnClickListener { renameSite(id) }
        sb.btnCamera.setOnClickListener { saveSheetNote(); takePhotoFor(id) }
        sb.btnGallery.setOnClickListener {
            saveSheetNote(); pendingSiteId = id
            runCatching { pickImages.launch("image/*") }.onFailure { toast("Galeri açılamadı.") }
        }
        sb.btnSave.setOnClickListener { saveSheetNote(); toast("Kaydedildi"); dlg.dismiss() }
        sb.btnDelete.setOnClickListener { confirmDeleteSite(id) }
        sb.btnNavigate.setOnClickListener { b.map.controller.animateTo(GeoPoint(site.lat, site.lon)); dlg.dismiss() }

        dlg.setOnDismissListener {
            saveSheetNote()
            if (sheet === dlg) { sheet = null; sheetBinding = null; sheetSiteId = null }
        }
        sheet = dlg; sheetBinding = sb; sheetSiteId = id
        dlg.show()
    }

    private fun bindSheet(site: Site, sb: SheetSiteBinding) {
        sb.tvTitle.text = displayName(site)
        sb.tvState.text = if (site.isDone) "● Yapıldı" else "○ Yapılmadı"
        sb.tvState.setTextColor(if (site.isDone) Color.rgb(46, 160, 67) else Color.rgb(230, 150, 0))
        val sbInfo = StringBuilder(String.format(Locale.US, "%.7f, %.7f", site.lat, site.lon))
        site.alt?.let { sbInfo.append(String.format(Locale.US, "\nKot: %.2f m", it)) }
        bestLocation?.let { loc ->
            val res = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, site.lat, site.lon, res)
            sbInfo.append("\nSize uzaklık: ").append(formatDistance(res[0]))
        }
        if (site.updated > 0) sbInfo.append("\nSon işlem: ")
            .append(SimpleDateFormat("dd.MM.yyyy HH:mm", trLocale).format(Date(site.updated)))
        sb.tvInfo.text = sbInfo

        sb.thumbs.removeAllViews()
        val d = resources.displayMetrics.density
        val size = (96 * d).toInt()
        site.photos.forEach { path ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * d).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(PhotoUtils.decodeScaled(path, 300))
                setOnClickListener { viewPhoto(path) }
                setOnLongClickListener { confirmDeletePhoto(site.id, path); true }
            }
            sb.thumbs.addView(iv)
        }
        sb.tvNoPhoto.visibility = if (site.photos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshSheet(siteId: String) {
        val site = store.find(siteId) ?: return
        val sb = sheetBinding
        if (sheetSiteId == siteId && sb != null) bindSheet(site, sb) else openSite(siteId)
    }

    private fun saveSheetNote() {
        val id = sheetSiteId ?: return
        val sb = sheetBinding ?: return
        val site = store.find(id) ?: return
        val text = sb.etNote.text?.toString()?.trim() ?: ""
        if (text != site.note) {
            site.note = text
            site.updated = System.currentTimeMillis()
            store.save()
            addOrUpdateMarker(site)
            updateStatus()
            bindSheet(site, sb)
        }
    }

    /** Sadece rakamdan oluşan isimler "Direk 12" olarak gösterilir. */
    private fun displayName(s: Site): String =
        if (s.name.isNotEmpty() && s.name.all { it.isDigit() }) "Direk " + s.name else s.name

    private fun formatDistance(m: Float): String =
        if (m < 1000) m.roundToInt().toString() + " m" else String.format(Locale.US, "%.2f km", m / 1000)

    private fun renameSite(id: String) {
        val site = store.find(id) ?: return
        val et = EditText(this).apply { setText(site.name); setSelection(site.name.length) }
        AlertDialog.Builder(this).setTitle("Nokta adı").setView(padded(et))
            .setPositiveButton("Kaydet") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    site.name = n; store.save(); addOrUpdateMarker(site); refreshSheet(id)
                }
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun confirmDeleteSite(id: String) {
        val site = store.find(id) ?: return
        AlertDialog.Builder(this)
            .setTitle(site.name + " silinsin mi?")
            .setMessage("Nokta, notu ve uygulamadaki fotoğrafları silinir. Galerideki kopyalar kalır.")
            .setPositiveButton("Sil") { _, _ ->
                site.photos.forEach { runCatching { File(it).delete() } }
                store.sites.remove(site); store.save()
                sheetSiteId = null; sheetBinding = null
                sheet?.dismiss()
                refreshAllMarkers()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun confirmDeletePhoto(siteId: String, path: String) {
        AlertDialog.Builder(this)
            .setTitle("Fotoğraf silinsin mi?")
            .setMessage("Galerideki kopyası kalır.")
            .setPositiveButton("Sil") { _, _ ->
                val site = store.find(siteId) ?: return@setPositiveButton
                site.photos.remove(path); runCatching { File(path).delete() }
                site.updated = System.currentTimeMillis(); store.save()
                addOrUpdateMarker(site); updateStatus(); refreshSheet(siteId)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun viewPhoto(path: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast("Fotoğraf açılamadı.") }
    }

    // ---------- Fotoğraf ----------

    private fun photoDir(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "noktalar").apply { mkdirs() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)

    private fun takePhotoFor(siteId: String) {
        try {
            pendingSiteId = siteId
            val dir = File(cacheDir, "images").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "cekim_" + System.currentTimeMillis() + ".jpg")
            pendingPhotoFile = f
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        } catch (e: ActivityNotFoundException) {
            toast("Kamera uygulaması bulunamadı.")
        }
    }

    private fun processPhoto(file: File, siteId: String, loc: Location?) {
        val site = store.find(siteId) ?: return
        val date = Date()
        toast("Fotoğraf işleniyor…")
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = PhotoUtils.loadRotated(file)
                    val lines = mutableListOf<String>()
                    lines += "Nokta: " + displayName(site)
                    lines += String.format(Locale.US, "Hedef: %.6f, %.6f", site.lat, site.lon)
                    if (loc != null) {
                        var g = String.format(Locale.US, "GPS:   %.6f, %.6f", loc.latitude, loc.longitude)
                        if (loc.hasAccuracy()) g += "  ±" + loc.accuracy.roundToInt() + " m"
                        lines += g
                        val res = FloatArray(1)
                        Location.distanceBetween(loc.latitude, loc.longitude, site.lat, site.lon, res)
                        lines += "Hedefe uzaklık: " + formatDistance(res[0])
                    } else lines += "GPS: yok"
                    lines += SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", trLocale).format(date)
                    PhotoUtils.stamp(bmp, displayName(site), lines)
                    val name = "Konum_" + safe(site.name) + "_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date) + ".jpg"
                    val f = File(photoDir(), name)
                    PhotoUtils.saveJpeg(bmp, f)
                    bmp.recycle()
                    PhotoUtils.writeExif(f.absolutePath, loc?.latitude ?: site.lat, loc?.longitude ?: site.lon,
                        if (loc != null && loc.hasAltitude()) loc.altitude else null, date)
                    PhotoUtils.copyToGallery(this@MainActivity, f, name, date)
                    f
                }.getOrNull()
            }
            file.delete()
            if (out == null) { toast("Fotoğraf kaydedilemedi."); return@launch }
            val s = store.find(siteId) ?: return@launch
            s.photos += out.absolutePath
            s.updated = System.currentTimeMillis()
            store.save()
            addOrUpdateMarker(s); updateStatus(); refreshSheet(siteId)
            toast("Fotoğraf eklendi")
        }
    }

    private fun addGalleryImages(siteId: String, uris: List<Uri>) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val site = store.find(siteId) ?: return@withContext emptyList<String>()
                uris.mapIndexedNotNull { i, u ->
                    runCatching {
                        val bmp = PhotoUtils.loadFromUri(this@MainActivity, u) ?: return@runCatching null
                        val f = File(photoDir(), "Konum_" + safe(site.name) + "_galeri_" +
                            System.currentTimeMillis() + "_" + i + ".jpg")
                        PhotoUtils.saveJpeg(bmp, f); bmp.recycle()
                        f.absolutePath
                    }.getOrNull()
                }
            }
            val s = store.find(siteId) ?: return@launch
            if (saved.isEmpty()) { toast("Resim eklenemedi."); return@launch }
            s.photos += saved
            s.updated = System.currentTimeMillis()
            store.save()
            addOrUpdateMarker(s); updateStatus(); refreshSheet(siteId)
            toast(saved.size.toString() + " resim eklendi")
        }
    }

    // ---------- Nokta ekleme / içe aktarma ----------

    private fun padded(v: View): View = FrameLayout(this).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v)
    }

    private fun askAddPointHere(p: GeoPoint) {
        val et = EditText(this).apply { hint = "Nokta adı"; setText("N" + (store.sites.size + 1)) }
        AlertDialog.Builder(this)
            .setTitle("Buraya nokta eklensin mi?")
            .setMessage(String.format(Locale.US, "%.7f, %.7f", p.latitude, p.longitude))
            .setView(padded(et))
            .setPositiveButton("Ekle") { _, _ ->
                store.addAll(listOf(ImportPoint(et.text.toString().trim(), p.latitude, p.longitude)))
                refreshAllMarkers()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun showPasteDialog() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP
            hint = "Her satıra bir nokta:\nN1, 41.0123456, 28.9765432\nN2 41.0130 28.9770\n41.0140, 28.9780"
        }
        AlertDialog.Builder(this)
            .setTitle("Koordinat ekle")
            .setMessage("Her satıra bir nokta: İsim, enlem, boylam (ondalık derece) ya da No, sağa (X), yukarı (Y), kot. Proje koordinatında sistem sorulur.")
            .setView(padded(et))
            .setPositiveButton("Ekle") { _, _ -> importText(et.text.toString()) }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun importText(text: String) {
        val lines = text.lines().count { it.isNotBlank() }
        val items = Importer.parseText(text)
        handleRaw(items, max(0, lines - items.size))
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    Importer.parseBytes(bytes)
                }.getOrNull()
            }
            if (items == null) toast("Dosya okunamadı.") else handleRaw(items, 0)
        }
    }

    /** Proje koordinatı (sağa/yukarı) varsa önce koordinat sistemini sorar. */
    private fun handleRaw(items: List<RawPoint>, failed: Int) {
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Koordinat bulunamadı")
                .setMessage("Örnek biçimler:\nN1, 41.0123456, 28.9765432\n1   422916.24   4296887.55   986.30")
                .setPositiveButton("Tamam", null).show()
            return
        }
        if (items.none { it.projected }) {
            finishImport(items.map { it.toImport(Geo.byId(null)) }, failed)
            return
        }
        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        val systems = Geo.systems
        var chosen = systems.indexOf(Geo.byId(prefs.getString("crs", null)))
        AlertDialog.Builder(this)
            .setTitle("Koordinat sistemi (" + items.count { it.projected } + " nokta)")
            .setSingleChoiceItems(systems.map { it.label }.toTypedArray(), chosen) { _, which -> chosen = which }
            .setPositiveButton("Ekle") { _, _ ->
                val crs = systems[chosen]
                prefs.edit().putString("crs", crs.id).apply()
                finishImport(items.map { it.toImport(crs) }, failed)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun finishImport(items: List<ImportPoint>, failed: Int) {
        val added = store.addAll(items)
        refreshAllMarkers()
        zoomToAll()
        var msg = "$added nokta eklendi."
        if (items.size - added > 0) msg += " " + (items.size - added) + " tanesi zaten vardı."
        if (failed > 0) msg += " $failed satır okunamadı."
        toast(msg)
    }

    private fun confirmClearAll() {
        if (store.sites.isEmpty()) { toast("Silinecek nokta yok."); return }
        AlertDialog.Builder(this)
            .setTitle("Tüm noktalar silinsin mi?")
            .setMessage(store.sites.size.toString() + " nokta, notları ve uygulamadaki fotoğrafları silinir. " +
                "Önce KMZ olarak dışa aktarmanız önerilir. Galerideki kopyalar kalır.")
            .setPositiveButton("Hepsini Sil") { _, _ ->
                store.sites.forEach { s -> s.photos.forEach { runCatching { File(it).delete() } } }
                store.sites.clear(); store.save()
                refreshAllMarkers()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    // ---------- Çevrimdışı harita ----------

    private fun downloadArea() {
        val map = b.map
        val zoom = floor(map.zoomLevelDouble).toInt()
        if (zoom < 12) { toast("Önce çalışacağınız bölgeye yakınlaşın, sonra tekrar deneyin."); return }
        val bb = map.boundingBox
        val zMin = max(10, zoom - 4)
        val zMax = 19
        val cm = try { CacheManager(map) } catch (e: Exception) { toast("İndirme başlatılamadı."); return }
        val count = cm.possibleTilesInArea(bb, zMin, zMax)
        if (count > MAX_TILES) {
            AlertDialog.Builder(this).setTitle("Alan çok büyük")
                .setMessage("Bu alan için yaklaşık $count karo gerekiyor. Biraz yakınlaşıp tekrar deneyin " +
                    "ya da alanı parça parça indirin.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val mb = max(1, count * 20 / 1024)
        AlertDialog.Builder(this)
            .setTitle("Bu bölgeyi indir")
            .setMessage("Ekranda görünen alanın uydu görüntüsü telefona kaydedilecek.\n\n" +
                "Yaklaşık $count karo, ~$mb MB\nYakınlaşma: $zMin – $zMax\n\n" +
                "İnternet (tercihen Wi-Fi) gerekli. Sonra sahada internetsiz görünür.")
            .setPositiveButton("İndir") { _, _ ->
                cm.downloadAreaAsync(this, bb, zMin, zMax, object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() { toast("Bölge indirildi ✓ Artık internetsiz görünür.") }
                    override fun onTaskFailed(errors: Int) { toast("$errors karo indirilemedi. İnterneti kontrol edip tekrar deneyin.") }
                    override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                    override fun downloadStarted() {}
                    override fun setPossibleTilesInArea(total: Int) {}
                })
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    // ---------- Dışa aktarma ----------

    private fun export(withPhotos: Boolean) {
        saveSheetNote()
        if (store.sites.isEmpty()) { toast("Dışa aktarılacak nokta yok."); return }
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val name = "Konum_" + stamp + if (withPhotos) ".kmz" else ".kml"
        val mime = if (withPhotos) "application/vnd.google-earth.kmz" else "application/vnd.google-earth.kml+xml"
        val title = "Konum " + SimpleDateFormat("dd.MM.yyyy HH:mm", trLocale).format(now)
        val sites = store.sites.toList()
        if (withPhotos) toast("KMZ hazırlanıyor…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val (bytes, n) = if (withPhotos) Exporter.buildKmz(title, sites)
                    else Exporter.buildKml(title, sites, emptyMap()).toByteArray(Charsets.UTF_8) to 0
                    saveDownload(name, mime, bytes)?.let { it to n }
                }.getOrNull()
            }
            if (res == null) { toast("Dosya kaydedilemedi."); return@launch }
            val (uri, n) = res
            val done = sites.count { it.isDone }
            var msg = sites.size.toString() + " nokta ($done yapıldı)"
            if (withPhotos) msg += ", $n fotoğraf"
            msg += "\n\nİndirilenler/Konum/$name"
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (withPhotos) "KMZ hazır" else "KML hazır")
                .setMessage(msg)
                .setPositiveButton("Paylaş") { _, _ -> shareFile(uri, mime) }
                .setNegativeButton("Tamam", null).show()
        }
    }

    private fun saveDownload(name: String, mime: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Konum")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Konum")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        }
    }

    private fun shareFile(uri: Uri, mime: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Paylaş"))
    }

    // ---------- Menü ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showPasteDialog()
            R.id.menu_import -> runCatching { importFile.launch(arrayOf("*/*")) }
                .onFailure { toast("Dosya seçici açılamadı.") }
            R.id.menu_download -> downloadArea()
            R.id.menu_kmz -> export(true)
            R.id.menu_kml -> export(false)
            R.id.menu_clear -> confirmClearAll()
            R.id.menu_help -> showHelp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Nasıl kullanılır?")
            .setMessage(
                "• + : Koordinat listesini yapıştırın (İsim, enlem, boylam ya da No, sağa, yukarı, kot).\n" +
                "• Dosyadan içe aktar: KML, KMZ, CSV ya da TXT seçin.\n" +
                "• Haritaya uzun basarak da nokta ekleyebilirsiniz.\n\n" +
                "• Daireye dokunun: not yazın, fotoğraf çekin ya da galeriden ekleyin.\n" +
                "• Boş sarı daire = yapılmadı, içi dolu yeşil daire = yapıldı.\n\n" +
                "• İnternet varken \"Bu bölgeyi indir\" ile uydu görüntüsünü kaydedin; sahada internetsiz görünür.\n" +
                "• KMZ dışa aktar: notlar ve fotoğraflarla birlikte Google Earth'te açılır."
            )
            .setPositiveButton("Tamam", null).show()
    }

    // ---------- Konum ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    @Suppress("MissingPermission")
    private fun lastKnown(): Location? {
        if (!hasLocationPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (listening || !hasLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { p ->
            runCatching {
                locationManager.requestLocationUpdates(p, 1000L, 0f, locationListener, Looper.getMainLooper())
            }
        }
        listening = providers.isNotEmpty()
        updateStatus()
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        runCatching { locationManager.removeUpdates(locationListener) }
        listening = false
    }

    private fun onNewLocation(loc: Location) {
        val cur = bestLocation
        val ageDiff = if (cur == null) Long.MAX_VALUE else loc.time - cur.time
        val better = cur == null || ageDiff > 15_000 ||
            (loc.hasAccuracy() && (!cur.hasAccuracy() || loc.accuracy <= cur.accuracy)) ||
            (ageDiff > 0 && loc.provider == LocationManager.GPS_PROVIDER)
        if (better) {
            bestLocation = loc
            updateStatus()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    @Suppress("unused")
    private fun clamp(v: Int, lo: Int, hi: Int) = min(hi, max(lo, v))
}
