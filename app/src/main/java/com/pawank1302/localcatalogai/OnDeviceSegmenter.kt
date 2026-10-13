package com.pawank1302.localcatalogai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.*

/** U2NetP foreground segmentation; all inference runs locally. */
internal object OnDeviceSegmenter {
    fun cutOut(context: Context, source: Bitmap): Bitmap {
        val inputSize = 320
        val resized = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
        val rgb = IntArray(inputSize * inputSize)
        resized.getPixels(rgb, 0, inputSize, 0, 0, inputSize, inputSize)
        val values = FloatArray(3 * inputSize * inputSize)
        val means = floatArrayOf(.485f, .456f, .406f)
        val stds = floatArrayOf(.229f, .224f, .225f)
        for (i in rgb.indices) {
            val c = rgb[i]
            val channels = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
            for (ch in 0..2) values[ch * rgb.size + i] = (channels[ch] / 255f - means[ch]) / stds[ch]
        }
        val model = context.assets.open("u2netp.onnx").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        val predictions: FloatArray
        env.createSession(model, opts).use { session ->
            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 3, 320, 320)).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val mask = result[0].value as Array<Array<Array<FloatArray>>>
                    val rows = mask[0][0]\n                    predictions = FloatArray(inputSize * inputSize) { i ->\n                        rows[i / inputSize][i % inputSize]\n                    }
                }
            }
        }
        val lo = predictions.minOrNull() ?: 0f
        val hi = predictions.maxOrNull() ?: 1f
        val range = (hi - lo).coerceAtLeast(0.000001f)
        val alphaPixels = IntArray(rgb.size) { i ->
            val a = (((predictions[i] - lo) / range) * 255).roundToInt().coerceIn(0, 255)
            Color.argb(a, 255, 255, 255)
        }
        val mask = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        mask.setPixels(alphaPixels, 0, 320, 0, 0, 320, 320)
        val scaledMask = Bitmap.createScaledBitmap(mask, source.width, source.height, true)
        val sourcePixels = IntArray(source.width * source.height)
        val maskPixels = IntArray(sourcePixels.size)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        scaledMask.getPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)
        var visible = 0
        for (i in sourcePixels.indices) {
            val a = Color.alpha(maskPixels[i])
            if (a > 128) visible++
            sourcePixels[i] = (sourcePixels[i] and 0x00ffffff) or (a shl 24)
        }
        val fraction = visible.toFloat() / sourcePixels.size
        require(fraction in 0.01f..0.75f) { "AI mask uncertain; original preserved" }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }
}
