package com.subburner.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var burner: VideoBurner

    private lateinit var btnPickVideo: Button
    private lateinit var btnPickSrt: Button
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button
    private lateinit var btnOpen: Button
    private lateinit var tvVideoName: TextView
    private lateinit var tvSrtName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var progressBar: ProgressBar

    private var videoUri: Uri? = null
    private var srtUri: Uri? = null
    private var outputUri: Uri? = null
    private var running = false

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                videoUri = uri
                tvVideoName.text = displayName(uri)
                clearResult()
                updateButtons()
            }
        }

    private val pickSrt =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = displayName(uri)
                if (!name.lowercase().endsWith(".srt")) {
                    Toast.makeText(this, "اختار ملف بصيغة SRT فقط", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                srtUri = uri
                tvSrtName.text = name
                clearResult()
                updateButtons()
            }
        }

    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startBurning()
            } else {
                tvStatus.text = "لازم تسمح بالتخزين عشان يتحفظ الفيديو"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        burner = VideoBurner(applicationContext)

        btnPickVideo = findViewById(R.id.btnPickVideo)
        btnPickSrt = findViewById(R.id.btnPickSrt)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnOpen = findViewById(R.id.btnOpen)
        tvVideoName = findViewById(R.id.tvVideoName)
        tvSrtName = findViewById(R.id.tvSrtName)
        tvStatus = findViewById(R.id.tvStatus)
        tvPercent = findViewById(R.id.tvPercent)
        progressBar = findViewById(R.id.progressBar)

        btnPickVideo.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        btnPickSrt.setOnClickListener { pickSrt.launch(arrayOf("*/*")) }
        btnStart.setOnClickListener { onStartClicked() }
        btnCancel.setOnClickListener { cancelBurning() }
        btnOpen.setOnClickListener { openOutput() }

        updateButtons()
    }

    override fun onDestroy() {
        if (isFinishing) burner.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- //

    private fun onStartClicked() {
        if (videoUri == null || srtUri == null || running) return

        // أندرويد 9 وأقل: لازم صلاحية الكتابة وقت التشغيل
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startBurning()
    }

    private fun startBurning() {
        val video = videoUri ?: return
        val srt = srtUri ?: return

        clearResult()
        setRunning(true)
        tvStatus.text = "جاري الدمج... متقفلش التطبيق"

        burner.start(video, srt, object : VideoBurner.Callback {
            override fun onProgress(percent: Int) {
                progressBar.progress = percent
                tvPercent.text = "$percent%"
            }

            override fun onSuccess(outputUri: Uri) {
                setRunning(false)
                this@MainActivity.outputUri = outputUri
                progressBar.progress = 100
                tvPercent.text = "100%"
                tvStatus.text = "تم الدمج ✔\nاتحفظ في: Movies/SubtitleBurner"
                // الفتح المباشر مضمون بس مع روابط content (أندرويد 10 وأعلى)
                btnOpen.visibility =
                    if (outputUri.scheme == "content") View.VISIBLE else View.GONE
            }

            override fun onError(message: String) {
                setRunning(false)
                tvStatus.text = message
            }
        })
    }

    private fun cancelBurning() {
        burner.cancel()
        setRunning(false)
        progressBar.progress = 0
        tvPercent.text = ""
        tvStatus.text = "اتلغى الدمج"
    }

    private fun openOutput() {
        val uri = outputUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "مفيش تطبيق يفتح الفيديو، افتحه من المعرض", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- //

    private fun setRunning(value: Boolean) {
        running = value
        if (value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        btnCancel.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.visibility = if (value) View.VISIBLE else View.INVISIBLE
        updateButtons()
    }

    private fun updateButtons() {
        btnPickVideo.isEnabled = !running
        btnPickSrt.isEnabled = !running
        btnStart.isEnabled = !running && videoUri != null && srtUri != null
    }

    private fun clearResult() {
        outputUri = null
        btnOpen.visibility = View.GONE
        tvStatus.text = ""
        tvPercent.text = ""
        progressBar.progress = 0
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "ملف"
    }
}
