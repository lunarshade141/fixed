package com.naturalcam

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class NaturalImageProcessor {

    companion object {
        private const val TAG = "NaturalProcessor"
        private const val ALBUM_NAME = "NaturalCam"
    }

    // =========================
    // JPEG PIPELINE (SAFE BASIC VERSION)
    // =========================
    fun processAndSaveJpeg(image: Image, context: Context) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return

            val processed = applyBasicNaturalLook(bitmap)

            val file = createOutputFile(context, "jpg")

            FileOutputStream(file).use { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            bitmap.recycle()
            processed.recycle()

            Log.d(TAG, "JPEG saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "JPEG processing failed", e)
        }
    }

    // =========================
    // RAW DNG (CORRECT & SAFE)
    // =========================
    fun saveRawDng(
        image: Image,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        context: Context
    ) {
        try {
            val file = createOutputFile(context, "dng")

            FileOutputStream(file).use { out ->
                val dngCreator = DngCreator(characteristics, result)
                dngCreator.writeImage(out, image)
                dngCreator.close()
            }

            Log.d(TAG, "DNG saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "DNG save failed", e)
        }
    }

    // =========================
    // SIMPLE NATURAL LOOK (SAFE BASELINE)
    // =========================
    private fun applyBasicNaturalLook(input: Bitmap): Bitmap {
        val bmp = input.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        for (i in pixels.indices) {
            val c = pixels[i]

            var r = Color.red(c)
            var g = Color.green(c)
            var b = Color.blue(c)

            // mild contrast lift (very subtle)
            r = ((r - 128) * 1.05 + 128).toInt()
            g = ((g - 128) * 1.05 + 128).toInt()
            b = ((b - 128) * 1.05 + 128).toInt()

            pixels[i] = Color.argb(
                Color.alpha(c),
                r.coerceIn(0, 255),
                g.coerceIn(0, 255),
                b.coerceIn(0, 255)
            )
        }

        val result = Bitmap.createBitmap(bmp.width, bmp.height, bmp.config)
        result.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        bmp.recycle()
        return result
    }

    // =========================
    // FILE SYSTEM
    // =========================
    private fun createOutputFile(context: Context, ext: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM_NAME)
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM_NAME)
        }

        if (!dir.exists()) dir.mkdirs()

        return File(dir, "NC_$ts.$ext")
    }
}
