package com.geocam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var gpsText: TextView
    private lateinit var seek: SeekBar
    private lateinit var modeBtn: Button
    private lateinit var flashBtn: Button
    private lateinit var stampBtn: Button
    private lateinit var lm: LocationManager
    private var provider: ProcessCameraProvider? = null
    private var ext: ExtensionsManager? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var front = false
    private var stamp = true
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var loc: Location? = null
    private val io = Executors.newSingleThreadExecutor()

    private val modes = listOf(
        "AUTO" to ExtensionMode.NONE, "HDR" to ExtensionMode.HDR,
        "NIGHT" to ExtensionMode.NIGHT, "PORTRAIT" to ExtensionMode.BOKEH,
        "BEAUTY" to ExtensionMode.FACE_RETOUCH
    )
    private var modeIdx = 0

    private val listener = LocationListener { l ->
        val cur = loc
        if (cur == null || l.accuracy <= cur.accuracy + 20 || l.time - cur.time > 10_000) {
            loc = l
            gpsText.text = "%.6f, %.6f  ±%.0fm  alt %.0fm".format(l.latitude, l.longitude, l.accuracy, l.altitude)
        }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) startCamera() else toast("Camera permission needed")
        startGps()
    }

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        gpsText = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt())
            setPadding(24, 64, 24, 16); text = "Waiting for GPS…"
        }
        root.addView(gpsText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt()); setPadding(16, 16, 16, 48)
        }
        seek = SeekBar(this)
        bar.addView(seek)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; setOnClickListener { f() }
            row.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        modeBtn = btn("AUTO") { modeIdx = (modeIdx + 1) % modes.size; bind() }
        flashBtn = btn("FLASH OFF") {
            flash = (flash + 1) % 3
            capture?.flashMode = flash
            flashBtn.text = listOf("FLASH AUTO", "FLASH ON", "FLASH OFF")[flash]
        }
        btn("● SHOOT") { shoot() }
        stampBtn = btn("STAMP ON") { stamp = !stamp; stampBtn.text = if (stamp) "STAMP ON" else "STAMP OFF" }
        btn("FLIP") { front = !front; bind() }
        bar.addView(row)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(z * d.scaleFactor); return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val p = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build()); return true
            }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); tap.onTouchEvent(e); true }
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
            ef.addListener({ ext = ef.get(); bind() }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        var sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val (name, mode) = modes[modeIdx]
        if (mode != ExtensionMode.NONE) {
            val em = ext
            if (em != null && em.isExtensionAvailable(sel, mode)) sel = em.getExtensionEnabledCameraSelector(sel, mode)
            else { toast("$name not supported on this camera"); modeIdx = 0 }
        }
        modeBtn.text = modes[modeIdx].first
        val rs = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).build()
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100).setFlashMode(flash).setResolutionSelector(rs).build()
        p.unbindAll()
        camera = try { p.bindToLifecycle(this, sel, pv, capture) } catch (e: Exception) { toast("Camera error: ${e.message}"); null }
        camera?.let { c ->
            val r = c.cameraInfo.exposureState.exposureCompensationRange
            seek.isEnabled = c.cameraInfo.exposureState.isExposureCompensationSupported
            seek.max = r.upper - r.lower; seek.progress = -r.lower
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { c.cameraControl.setExposureCompensationIndex(v + r.lower) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    private fun shoot() {
        val c = capture ?: return
        val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoCam")
        }
        val md = ImageCapture.Metadata().apply { location = loc }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            .setMetadata(md).build()
        val l = loc
        c.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                toast("Saved to Pictures/GeoCam")
                val u = r.savedUri
                if (stamp && l != null && u != null) io.execute { runCatching { burn(u, l) } }
            }
            override fun onError(e: ImageCaptureException) { toast("Capture failed: ${e.message}") }
        })
    }

    // Burns lat/long/time text into the pixels and keeps GPS EXIF.
    private fun burn(uri: Uri, l: Location) {
        val ori = contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        val deg = when (ori) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(bmp)
        val ts = bmp.width / 38f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ts }
        val bg = Paint().apply { color = 0x99000000.toInt() }
        val lines = listOf(
            "%.6f, %.6f".format(l.latitude, l.longitude),
            "Alt %.0fm  ±%.0fm".format(l.altitude, l.accuracy),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        )
        val h = ts * 1.3f * lines.size + ts
        cv.drawRect(0f, bmp.height - h, bmp.width.toFloat(), bmp.height.toFloat(), bg)
        lines.forEachIndexed { i, s -> cv.drawText(s, ts, bmp.height - h + ts * 1.5f + i * ts * 1.3f - ts * 0.3f, tp) }
        contentResolver.openOutputStream(uri, "wt")!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        contentResolver.openFileDescriptor(uri, "rw")?.use {
            val e = ExifInterface(it.fileDescriptor); e.setGpsInfo(l); e.saveAttributes()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsText.text = "Location permission denied"; return
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach {
            if (lm.isProviderEnabled(it)) {
                lm.requestLocationUpdates(it, 1000L, 0f, listener)
                if (loc == null) lm.getLastKnownLocation(it)?.let { l -> listener.onLocationChanged(l) }
            }
        }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    override fun onDestroy() { super.onDestroy(); lm.removeUpdates(listener); io.shutdown() }
}
