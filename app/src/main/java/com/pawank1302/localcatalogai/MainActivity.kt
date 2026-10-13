package com.pawank1302.localcatalogai

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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

    @Suppress("DEPRECATION")
    private fun generateCatalogPreview() {
        val uri = selectedUri ?: return
        status.text = "Processing locally…"
        Thread {
            try {
                val source = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val output = makeCatalogCanvas(source)
                runOnUiThread {
                    catalogPreview.setImageBitmap(output)
                    status.text = "Catalog preview generated on-device. Product pixels are unchanged in this first processing stage."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not process this photo: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun makeCatalogCanvas(source: Bitmap): Bitmap {
        val size = 1024
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(248, 248, 248))
        val maxW = size * 0.78f
        val maxH = size * 0.78f
        val scale = minOf(maxW / source.width, maxH / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(35, 0, 0, 0)
            maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(left + 10f, top + 18f, left + w + 10f, top + h + 18f, 18f, 18f, shadowPaint)
        canvas.drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
