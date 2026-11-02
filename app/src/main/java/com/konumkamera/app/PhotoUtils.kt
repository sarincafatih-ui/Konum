package com.konumkamera.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object PhotoUtils {

    /** Kameradan gelen dosyayı doğru yöne çevirip (en fazla 3200 px) açar. */
    fun loadRotated(file: File, maxSide: Int = 3200): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inMutable = true
        }) ?: error("Görüntü okunamadı")
        val deg = when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return if (raw.isMutable) raw else raw.copy(Bitmap.Config.ARGB_8888, true)
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true)
        if (rotated != raw) raw.recycle()
        return if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Galeriden seçilen resmi küçülterek açar. */
    fun loadFromUri(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Küçük önizleme ya da KMZ için küçültülmüş dosya okuma. */
    fun decodeScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun jpegBytes(path: String, maxSide: Int): ByteArray? {
        val bmp = decodeScaled(path, maxSide) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Sol üstte nokta adı rozeti, altta bilgi şeridi. */
    fun stamp(bmp: Bitmap, title: String, lines: List<String>) {
        val canvas = Canvas(bmp)
        val shortSide = minOf(bmp.width, bmp.height)
        val textSize = shortSide * 0.033f
        val pad = textSize * 0.8f

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = shortSide * 0.07f
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val bh = badgePaint.textSize * 1.4f
        val bw = max(bh, badgePaint.measureText(title) + badgePaint.textSize)
        val m = shortSide * 0.03f
        val rect = RectF(m, m, m + bw, m + bh)
        canvas.drawRoundRect(rect, bh * 0.25f, bh * 0.25f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 200, 30, 30) })
        val fm = badgePaint.fontMetrics
        canvas.drawText(title, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, badgePaint)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        val text = lines.joinToString("\n")
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (bmp.width - pad * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
        val top = bmp.height - layout.height - pad * 2
        canvas.drawRect(0f, top, bmp.width.toFloat(), bmp.height.toFloat(),
            Paint().apply { color = Color.argb(140, 0, 0, 0) })
        canvas.save(); canvas.translate(pad, top + pad); layout.draw(canvas); canvas.restore()
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun writeExif(path: String, lat: Double?, lon: Double?, alt: Double?, date: Date) {
        runCatching {
            ExifInterface(path).apply {
                if (lat != null && lon != null) setLatLong(lat, lon)
                if (alt != null) setAltitude(alt)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date))
                saveAttributes()
            }
        }
    }

    /** Fotoğrafın bir kopyasını telefonun galerisine (Resimler/Konum) koyar. */
    fun copyToGallery(context: Context, file: File, name: String, date: Date) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, date.time)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Konum")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }
}
