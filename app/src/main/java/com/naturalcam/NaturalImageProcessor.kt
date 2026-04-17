package com.naturalcam

import android.hardware.camera2.TotalCaptureResult
import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * NaturalImageProcessor
 *
 * The entire philosophy in one class:
 *
 * 1. DON'T over-sharpen   → We apply a subtle unsharp mask at the very end, once
 * 2. DON'T crush blacks    → Tone curve lifts shadows slightly like film
 * 3. DON'T blow highlights → Gentle rolloff, never hard clip
 * 4. DON'T oversaturate   → Vibrance not saturation (protects skin tones)
 * 5. DON'T over-NR         → Preserve fine texture and detail
 *
 * Result: Photos that look like what you actually saw, not like a render.
 */
class NaturalImageProcessor {

    companion object {
        private const val TAG = "NaturalProcessor"

        // Output directory
        private const val ALBUM_NAME = "NaturalCam"

        // Processing constants
        private const val SHARPENING_RADIUS = 1.2f    // Very subtle — not the Android crunch
        private const val SHARPENING_AMOUNT = 0.25f   // 25% — you'll feel it but not see it
        private const val VIBRANCE_AMOUNT   = 0.08f   // Subtle vibrance, protects skin tones
        private const val CLARITY_AMOUNT    = 0.05f   // Micro-contrast, barely there
    }

    /**
     * Process and save JPEG from Camera2 ImageReader
     *
     * Processing order matters:
     * 1. Decode
     * 2. White balance fine-tune (if needed)
     * 3. Tone curve (natural shadow lift + highlight rolloff)
     * 4. Vibrance (NOT saturation)
     * 5. Subtle sharpening (LAST — always sharpen last)
     * 6. Save
     */
    fun processAndSaveJpeg(image: Image, context: Context) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Decode to bitmap for processing
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run {
                    Log.e(TAG, "Failed to decode image")
                    return
                }

            // Apply our natural processing pipeline
            bitmap = applyNaturalPipeline(bitmap)

            // Save
            val file = createOutputFile(context, "jpg")
            FileOutputStream(file).use { out ->
                // Save at 95 quality — 97+ is diminishing returns on file size
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            bitmap.recycle()
            Log.d(TAG, "Saved natural JPEG: ${file.name}")

        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
        }
    }

    /**
     * Save RAW DNG — no processing at all, pure sensor data
     * This is your "negative" — process later in Lightroom/Darktable
     */
    fun saveRawDng(image: Image,
    result: android.hardware.camera2.TotalCaptureResult,
    characteristics: CameraCharacteristics,
    context: Context) {
        try {
            val file = createOutputFile(context, "dng")
            FileOutputStream(file).use { out ->
                // DngCreator embeds all the metadata — ISO, shutter, WB, lens info
                // So your DNG opens correctly in any RAW editor
                val dngCreator = DngCreator(characteristics,result)
                dngCreator.writeImage(out, image)
                dngCreator.close()
            }
            Log.d(TAG, "Saved RAW DNG: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "DNG save failed", e)
        }
    }

    /**
     * The full natural processing pipeline
     */
    private fun applyNaturalPipeline(input: Bitmap): Bitmap {
        var bmp = input.copy(Bitmap.Config.ARGB_8888, true)

        // Step 1: Apply natural tone curve (most important step)
        bmp = applyToneCurve(bmp)

        // Step 2: Subtle vibrance (not saturation)
        bmp = applyVibrance(bmp, VIBRANCE_AMOUNT)

        // Step 3: Micro-contrast / clarity (very subtle)
        // bmp = applyClarityLayer(bmp, CLARITY_AMOUNT)  // Enable if you want more pop

        // Step 4: Sharpening — ALWAYS LAST
        bmp = applySubtleSharpening(bmp, SHARPENING_RADIUS, SHARPENING_AMOUNT)

        return bmp
    }

    /**
     * Natural tone curve — the heart of the iPhone 6s look
     *
     * Built as a LUT (lookup table) for performance — process every possible
     * input value (0-255) once, then just do array lookups per pixel.
     */
    private fun applyToneCurve(input: Bitmap): Bitmap {
        // Build LUT from our curve control points
        val lut = buildToneLUT()

        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = Color.alpha(pixel)
            val r = lut[Color.red(pixel)]
            val g = lut[Color.green(pixel)]
            val b = lut[Color.blue(pixel)]
            pixels[i] = Color.argb(a, r, g, b)
        }

        val result = input.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    /**
     * Build a 256-entry LUT from the natural tone curve control points
     * Uses cubic spline interpolation between control points
     */
    private fun buildToneLUT(): IntArray {
        // Control points: (input, output) normalized 0-1
        // This is the iPhone 6s characteristic curve
        val controlPoints = arrayOf(
            Pair(0.000f, 0.030f),   // Black: lifted slightly — no pure black
            Pair(0.060f, 0.088f),   // Deep shadows: gentle lift
            Pair(0.150f, 0.160f),   // Shadows: natural
            Pair(0.300f, 0.300f),   // Shadow-mid: honest
            Pair(0.500f, 0.495f),   // Midtones: barely touched
            Pair(0.700f, 0.690f),   // Upper mids: very slight compression
            Pair(0.850f, 0.830f),   // Highlights: rolloff begins
            Pair(0.950f, 0.920f),   // Bright highlights: natural rolloff
            Pair(1.000f, 0.970f)    // Specular: never fully clip
        )

        val lut = IntArray(256)

        for (i in 0..255) {
            val x = i / 255f
            // Find surrounding control points
            var output = x  // Default: linear (no change)

            for (j in 0 until controlPoints.size - 1) {
                val p0 = controlPoints[j]
                val p1 = controlPoints[j + 1]
                if (x >= p0.first && x <= p1.first) {
                    // Linear interpolation between control points
                    // For better quality, use cubic spline — but linear is fine for this curve
                    val t = (x - p0.first) / (p1.first - p0.first)
                    output = p0.second + t * (p1.second - p0.second)
                    break
                }
            }

            lut[i] = (output * 255).toInt().coerceIn(0, 255)
        }

        return lut
    }

    /**
     * Vibrance — smarter than saturation
     *
     * Saturation: boosts ALL colors equally → skin turns orange, skies go fake
     * Vibrance: only boosts MUTED colors, leaves already-saturated colors alone
     *
     * This is exactly how Lightroom's vibrance works.
     * This is exactly what makes iPhone 6s skin tones look natural.
     */
    private fun applyVibrance(input: Bitmap, amount: Float): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            Color.colorToHSV(pixel, hsv)

            // Vibrance: the lower the saturation, the more boost it gets
            // Highly saturated colors (skin tones ~0.5-0.7 sat) get very little boost
            val currentSat = hsv[1]
            val vibranceBoost = amount * (1f - currentSat)  // Inverse relationship
            hsv[1] = (currentSat + vibranceBoost).coerceIn(0f, 1f)

            pixels[i] = Color.HSVToColor(Color.alpha(pixel), hsv)
        }

        val result = input.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    /**
     * Subtle unsharp mask sharpening
     *
     * Unsharp mask works by:
     * 1. Create a blurred copy of the image
     * 2. Subtract it from the original to get an "edge layer"
     * 3. Add a fraction of that edge layer back to the original
     *
     * The result: edges enhanced, flat areas untouched.
     * This is NOT the aggressive Android "crunch" — it's invisible but you notice
     * when it's absent. Like iPhone 6s sharpening.
     */
    private fun applySubtleSharpening(input: Bitmap, radius: Float, amount: Float): Bitmap {
        // Create blurred version using RenderScript-like approach
        val blurred = blurBitmap(input, radius)

        val pixels = IntArray(input.width * input.height)
        val blurredPixels = IntArray(blurred.width * blurred.height)

        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        blurred.getPixels(blurredPixels, 0, blurred.width, 0, 0, blurred.width, blurred.height)

        for (i in pixels.indices) {
            val orig = pixels[i]
            val blur = blurredPixels[i]

            // Unsharp mask formula: output = original + amount * (original - blurred)
            val r = (Color.red(orig)   + amount * (Color.red(orig)   - Color.red(blur))).toInt().coerceIn(0, 255)
            val g = (Color.green(orig) + amount * (Color.green(orig) - Color.green(blur))).toInt().coerceIn(0, 255)
            val b = (Color.blue(orig)  + amount * (Color.blue(orig)  - Color.blue(blur))).toInt().coerceIn(0, 255)

            pixels[i] = Color.argb(Color.alpha(orig), r, g, b)
        }

        val result = input.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        blurred.recycle()
        return result
    }

    /**
     * Simple box blur — fast approximation for unsharp mask
     * For production you'd use RenderScript ScriptIntrinsicBlur or Vulkan
     */
    private fun blurBitmap(input: Bitmap, radius: Float): Bitmap {
        val paint = Paint().apply {
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        val result = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(input, 0f, 0f, paint)
        return result
    }

    private fun createOutputFile(context: Context, extension: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM_NAME)
        } else {
            File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM_NAME)
        }
        dir.mkdirs()
        return File(dir, "NC_${timestamp}.$extension")
    }
}
