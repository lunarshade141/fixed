package com.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageFormat
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NaturalCam"
        private const val REQUEST_PERMISSIONS = 100
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReaderJpeg: ImageReader? = null
    private var imageReaderRaw: ImageReader? = null
    private lateinit var cameraCharacteristics: CameraCharacteristics

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var textureView: TextureView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var tvISO: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvMode: TextView
    private lateinit var seekExposure: SeekBar

    private var cameraId: String = ""
    private var isCapturing = false
    private var currentISO = 0
    private var currentShutter = 0L
    private var isManualMode = false
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var previewSurface: Surface? = null

    private lateinit var naturalProcessor: NaturalImageProcessor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_camera)
        setupUI()
        naturalProcessor = NaturalImageProcessor()
        if (hasPermissions()) {
            startBackgroundThread()
            if (textureView.isAvailable) openCamera()
        } else {
            requestPermissions()
        }
    }

    private fun setupUI() {
        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)
        btnFlip = findViewById(R.id.btnFlip)
        tvISO = findViewById(R.id.tvISO)
        tvShutter = findViewById(R.id.tvShutter)
        tvMode = findViewById(R.id.tvMode)
        seekExposure = findViewById(R.id.seekExposure)

        btnCapture.setOnClickListener { if (!isCapturing) capturePhoto() }
        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            closeCamera(); openCamera()
        }
        btnCapture.setOnLongClickListener { toggleManualMode(); true }

        textureView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) tapToFocus(event.x, event.y)
            true
        }

        seekExposure.max = 12
        seekExposure.progress = 6
        seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateExposureCompensation(progress - 6)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) { configureTransform(w, h) }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun openCamera() {
        if (!hasPermissions()) return
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = ""
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing) { cameraId = id; break }
        }
        if (cameraId.isEmpty()) return
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val photoSize = map.getOutputSizes(ImageFormat.JPEG)
            .maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1920, 1080)
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1280, 720)

        imageReaderJpeg = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
        imageReaderJpeg!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            backgroundHandler?.post {
                naturalProcessor.processAndSaveJpeg(image, this@CameraActivity)
                image.close()
                runOnUiThread { isCapturing = false }
            }
        }, backgroundHandler)

        if (rawSize != null) {
            imageReaderRaw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            imageReaderRaw!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                backgroundHandler?.post {
                    naturalProcessor.saveRawDng(image, cameraCharacteristics, this@CameraActivity)
                    image.close()
                }
            }, backgroundHandler)
        }

        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)
        configureTransform(textureView.width, textureView.height)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { cameraDevice = camera; createCaptureSession() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null; Log.e(TAG, "Camera error $error") }
        }, backgroundHandler)
    }

    private fun createCaptureSession() {
        val surface = previewSurface ?: return
        val surfaces = mutableListOf(surface, imageReaderJpeg!!.surface)
        imageReaderRaw?.let { surfaces.add(it.surface) }
        cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { captureSession = session; startPreview() }
            override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "Session config failed") }
        }, backgroundHandler)
    }

    private fun startPreview() {
        val surface = previewSurface ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(surface)
        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        req.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        req.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        req.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        session.setRepeatingRequest(req.build(), captureCallback, backgroundHandler)
    }

    private fun capturePhoto() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        isCapturing = true
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        req.addTarget(imageReaderJpeg!!.surface)
        imageReaderRaw?.let { req.addTarget(it.surface) }

        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 90; Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270; Surface.ROTATION_270 -> 180; else -> 90
        }
        req.set(CaptureRequest.JPEG_ORIENTATION, rotation)
        req.set(CaptureRequest.JPEG_QUALITY, 95.toByte())

        if (isManualMode && currentISO > 0 && currentShutter > 0) {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            req.set(CaptureRequest.SENSOR_SENSITIVITY, currentISO)
            req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutter)
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // The natural look — no over-sharpening, no aggressive HDR
        req.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        req.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        req.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        req.set(CaptureRequest.TONEMAP_CURVE, buildNaturalToneCurve())
        req.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        req.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)

        session.capture(req.build(), null, backgroundHandler)
    }

    private fun buildNaturalToneCurve(): android.hardware.camera2.params.TonemapCurve {
        val curve = floatArrayOf(
            0.0000f, 0.0300f,
            0.0600f, 0.0880f,
            0.1500f, 0.1600f,
            0.3000f, 0.3000f,
            0.5000f, 0.4950f,
            0.7000f, 0.6900f,
            0.8500f, 0.8300f,
            0.9500f, 0.9200f,
            1.0000f, 0.9700f
        )
        return android.hardware.camera2.params.TonemapCurve(curve, curve, curve)
    }

    private fun tapToFocus(x: Float, y: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val fx = (x / textureView.width * sensorSize.width()).toInt().coerceIn(0, sensorSize.width() - 1)
        val fy = (y / textureView.height * sensorSize.height()).toInt().coerceIn(0, sensorSize.height() - 1)
        val sz = 150
        val region = android.hardware.camera2.params.MeteringRectangle(
            maxOf(0, fx - sz), maxOf(0, fy - sz),
            minOf(sz * 2, sensorSize.width() - maxOf(0, fx - sz)),
            minOf(sz * 2, sensorSize.height() - maxOf(0, fy - sz)),
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
        )
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(surface)
            req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            req.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(req.build(), null, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Focus failed", e) }
    }

    private fun updateExposureCompensation(stops: Int) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val aeRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        val compensation = stops.coerceIn(aeRange.lower, aeRange.upper)
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req.addTarget(surface)
            req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            session.setRepeatingRequest(req.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Exposure update failed", e) }
    }

    private fun toggleManualMode() {
        isManualMode = !isManualMode
        tvMode.text = if (isManualMode) "M" else "A"
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            runOnUiThread {
                iso?.let { tvISO.text = "ISO $it" }
                exposure?.let {
                    val ms = it / 1_000_000.0
                    tvShutter.text = if (ms < 1.0) "1/${(1000.0 / ms).toInt()}s" else "${ms.toInt()}s"
                }
            }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            val cx = viewWidth / 2f; val cy = viewHeight / 2f
            matrix.postRotate((90 * (rotation - 2)).toFloat(), cx, cy)
        }
        textureView.setTransform(matrix)
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReaderJpeg?.close(); imageReaderJpeg = null
        imageReaderRaw?.close(); imageReaderRaw = null
        previewSurface?.release(); previewSurface = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { Log.e(TAG, "Thread interrupted", e) }
        backgroundThread = null; backgroundHandler = null
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQUEST_PERMISSIONS && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBackgroundThread(); if (textureView.isAvailable) openCamera()
        }
    }

    override fun onResume() { super.onResume(); startBackgroundThread(); if (textureView.isAvailable) openCamera() }
    override fun onPause() { closeCamera(); stopBackgroundThread(); super.onPause() }

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
