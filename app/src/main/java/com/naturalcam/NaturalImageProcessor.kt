package com.naturalcam

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class NaturalImageProcessor {

    fun processAndSaveJpeg(image: Image, context: Context) {

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return

        val out = applyNaturalLook(bitmap)

        val file = createFile(context, "jpg")

        FileOutputStream(file).use {
            out.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }

        bitmap.recycle()
        out.recycle()
    }

    fun saveRawDng(
        image: Image,
        characteristics: CameraCharacteristics,
        context: Context
    ) {
        val file = createFile(context, "dng")

        FileOutputStream(file).use { out ->
            val dng = DngCreator(characteristics, image)
            dng.writeImage(out, image)
            dng.close()
        }
    }

    private fun applyNaturalLook(input: Bitmap): Bitmap {
        val bmp = input.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        for (i in pixels.indices) {
            val c = pixels[i]

            val r = (Color.red(c) * 0.98).toInt().coerceIn(0, 255)
            val g = (Color.green(c) * 1.00).toInt().coerceIn(0, 255)
            val b = (Color.blue(c) * 1.02).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(r, g, b)
        }

        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    private fun createFile(context: Context, ext: String): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "NaturalCam"
        )
        dir.mkdirs()

        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "NC_$time.$ext")
    }
}
