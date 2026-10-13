package com.pawank1302.localcatalogai

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private lateinit var originalPreview: ImageView
    private lateinit var catalogPreview: ImageView
    private lateinit var generateButton: Button
    private lateinit var status: TextView
    private var selectedUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        if (uri != null) {
            originalPreview.setImageURI(uri)
            catalogPreview.setImageDrawable(null)
            generateButton.isEnabled = true
            status.text = "Photo loaded locally. Tap Generate Catalog Image."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        originalPreview = findViewById(R.id.originalPreview)
        catalogPreview = findViewById(R.id.catalogPreview)
        generateButton = findViewById(R.id.generateCatalog)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.selectPhoto).setOnClickListener { pickImage.launch("image/*") }
        generateButton.setOnClickListener { generateCatalogPreview() }
    }

    private fun generateCatalogPreview() {
        val uri = selectedUri ?: return
        generateButton.isEnabled = false
        status.text = "Processing locally…"
        Thread {
            try {
                val source = loadUpright(uri)
                val result = try {
                    val cutout = OnDeviceSegmenter.cutOut(this, source)
                    CatalogProcessor.composeCutout(cutout)
                } catch (segmentationError: Exception) {
                    val fallback = CatalogProcessor.process(source)
                    CatalogProcessor.Result(fallback.bitmap, "AI unavailable or uncertain: ${segmentationError.message}. " + fallback.message)
                }
                runOnUiThread {
                    catalogPreview.setImageBitmap(result.bitmap)
                    status.text = result.message
                    generateButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Processing failed: ${e.message ?: "unknown error"}"
                    generateButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun loadUpright(uri: Uri): Bitmap {
        val bitmap = contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Unable to read image" }
            BitmapFactory.decodeStream(stream) ?: error("Unable to decode image")
        }
        val orientation = contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

/**
 * Conservative offline baseline, not an ML model.
 * If foreground/background separation is uncertain, it leaves the photo intact.
 * A dedicated on-device segmentation model will replace the masking stage.
 */
internal object CatalogProcessor {
    data class Result(val bitmap: Bitmap, val message: String)

    fun composeCutout(cutout: Bitmap): Result {
        val output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(249, 249, 249))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cropped = cropToVisibleBounds(cutout)
        val cleaned = cleanAlphaEdge(cropped)
        drawCenteredWithShadow(canvas, cleaned, paint, 800f)
        return Result(output, "AI background removal complete on-device. Product centered with safe padding, edge cleanup, and a subtle natural shadow. Original product colors and label pixels are preserved.")
    }

    fun process(original: Bitmap): Result {
        val maxSide = 1200f
        val shrink = min(1f, maxSide / max(original.width, original.height))
        val src = Bitmap.createScaledBitmap(original, max(1, (original.width * shrink).roundToInt()),
            max(1, (original.height * shrink).roundToInt()), true)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Estimate background from outer corners, which must contain background.
        val corners = intArrayOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1])
        val br = corners.map { Color.red(it) }.average()
        val bg = corners.map { Color.green(it) }.average()
        val bb = corners.map { Color.blue(it) }.average()
        val spread = corners.maxOf { distance(it, br, bg, bb) }
        val threshold = max(38.0, spread * 2.2 + 15.0)

        // A background unlike the corners (or an object touching the edge) is unsafe to erase.
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0
        var count = 0
        val step = max(1, min(w, h) / 300)
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (distance(pixels[y * w + x], br, bg, bb) > threshold) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    count++
                }
            }
        }
        val fraction = count.toDouble() * step * step / (w * h)
        val safe = count > 20 && fraction in 0.008..0.35 &&
            minX > w * 0.03 && maxX < w * 0.97 &&
            minY > h * 0.03 && maxY < h * 0.97 &&
            (maxX - minX) > w * 0.05 && (maxY - minY) > h * 0.05

        val output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(249, 249, 249))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (safe) {
            val margin = (max(w, h) * 0.025f).roundToInt()
            val box = Rect(max(0, minX - margin), max(0, minY - margin),
                min(w, maxX + margin + 1), min(h, maxY + margin + 1))
            val cut = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height(), null, false)
            val cutPixels = IntArray(cut.width * cut.height)
            cut.getPixels(cutPixels, 0, cut.width, 0, 0, cut.width, cut.height)
            for (i in cutPixels.indices) {
                val d = distance(cutPixels[i], br, bg, bb)
                val alpha = ((d - threshold * 0.65) / (threshold * 0.7) * 255).roundToInt().coerceIn(0, 255)
                cutPixels[i] = (cutPixels[i] and 0x00FFFFFF) or (alpha shl 24)
            }
            val masked = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
            masked.setPixels(cutPixels, 0, cut.width, 0, 0, cut.width, cut.height)
            drawCentered(canvas, masked, paint)
            return Result(output, "Offline background isolation applied (experimental). Check edges and label carefully.")
        }
        drawCentered(canvas, src, paint)
        return Result(output, "Orientation corrected. Background was too complex for safe automatic removal; original pixels preserved.")
    }

    private fun cropToVisibleBounds(image: Bitmap): Bitmap {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (Color.alpha(pixels[y * image.width + x]) > 12) {
                minX = min(minX, x); minY = min(minY, y)
                maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return image
        val margin = max(4, (max(image.width, image.height) * 0.015f).roundToInt())
        val left = max(0, minX - margin); val top = max(0, minY - margin)
        val right = min(image.width - 1, maxX + margin); val bottom = min(image.height - 1, maxY + margin)
        return Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1)
    }

    private fun cleanAlphaEdge(image: Bitmap): Bitmap {
        val w = image.width
        val h = image.height
        val src = IntArray(w * h)
        image.getPixels(src, 0, w, 0, 0, w, h)
        val out = src.copyOf()

        // Refine only semi-transparent boundary pixels. RGB values are never changed.
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val a = Color.alpha(src[i])
            if (a in 1..254) {
                var alphaSum = 0
                var opaqueNeighbors = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val na = Color.alpha(src[(y + dy) * w + (x + dx)])
                    alphaSum += na
                    if (na >= 224) opaqueNeighbors++
                }
                val localAlpha = alphaSum / 9
                val refined = when {
                    opaqueNeighbors >= 6 -> max(a, localAlpha)
                    opaqueNeighbors <= 1 -> min(a, localAlpha)
                    else -> ((a * 3 + localAlpha) / 4)
                }.coerceIn(0, 255)
                out[i] = (src[i] and 0x00FFFFFF) or (refined shl 24)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun drawCenteredWithShadow(canvas: Canvas, image: Bitmap, paint: Paint, maxSize: Float = 820f) {
        val scale = min(maxSize / image.width, maxSize / image.height)
        val width = image.width * scale
        val height = image.height * scale
        val left = (1024f - width) / 2
        val top = (1024f - height) / 2
        val dst = RectF(left, top, left + width, top + height)

        // Shadow comes only from the cutout alpha silhouette, never from generated product content.
        val alpha = image.extractAlpha()
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.argb(42, 0, 0, 0)
            maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(0f, 12f)
        canvas.drawBitmap(alpha, null, dst, shadowPaint)
        canvas.restore()
        canvas.drawBitmap(image, null, dst, paint)
        alpha.recycle()
    }

    private fun drawCentered(canvas: Canvas, image: Bitmap, paint: Paint, maxSize: Float = 820f) {
        val scale = min(maxSize / image.width, maxSize / image.height)
        val width = image.width * scale
        val height = image.height * scale
        val left = (1024f - width) / 2
        val top = (1024f - height) / 2
        canvas.drawBitmap(image, null, RectF(left, top, left + width, top + height), paint)
    }

    private fun distance(color: Int, r: Double, g: Double, b: Double): Double {
        val dr = Color.red(color) - r
        val dg = Color.green(color) - g
        val db = Color.blue(color) - b
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
