package com.hawkeyeborescopes.viewer

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HawkeyePreview"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_FILE_NAME = "file_name"
        /** Result code indicating file was deleted */
        const val RESULT_DELETED = 100
    }

    private var filePath: String? = null
    private var isVideo = false
    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_preview)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Preview"

        val imagePreview = findViewById<ImageView>(R.id.imagePreview)
        videoView = findViewById(R.id.videoPreview)
        val previewTitle = findViewById<TextView>(R.id.previewTitle)
        val previewStatus = findViewById<TextView>(R.id.previewStatus)
        val closeButton = findViewById<ImageView>(R.id.closeButton)
        val deleteButton = findViewById<ImageView>(R.id.previewDeleteButton)

        previewTitle.text = fileName

        closeButton.setOnClickListener { finish() }

        deleteButton.setOnClickListener { confirmDelete() }

        val path = filePath
        if (path == null || !File(path).exists()) {
            previewStatus.visibility = View.VISIBLE
            previewStatus.text = "File not found"
            return
        }

        if (isVideo) {
            loadVideo(path)
        } else {
            loadImage(path, imagePreview, previewStatus)
        }
    }

    private fun loadImage(path: String, imageView: ImageView, statusView: TextView) {
        try {
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
            } else {
                statusView.visibility = View.VISIBLE
                statusView.text = "Cannot decode image"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image load error: ${e.message}", e)
            statusView.visibility = View.VISIBLE
            statusView.text = "Error loading image"
        }
    }

    private fun loadVideo(path: String) {
        val vv = videoView ?: return
        try {
            vv.visibility = View.VISIBLE
            vv.setVideoURI(Uri.fromFile(File(path)))

            // Add media controller for play/pause/seek
            val controller = MediaController(this)
            controller.setAnchorView(vv)
            vv.setMediaController(controller)

            vv.setOnPreparedListener { mp ->
                mp.isLooping = false
                vv.start()
                // Show controller briefly
                controller.show(3000)
            }

            vv.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video playback error: what=$what extra=$extra")
                val statusView = findViewById<TextView>(R.id.previewStatus)
                statusView.visibility = View.VISIBLE
                statusView.text = "Video playback error"
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video load error: ${e.message}", e)
            val statusView = findViewById<TextView>(R.id.previewStatus)
            statusView.visibility = View.VISIBLE
            statusView.text = "Error loading video"
        }
    }

    private fun confirmDelete() {
        val path = filePath ?: return
        val file = File(path)
        val name = file.name

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete")
            .setMessage("Delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_DELETED)
                    finish()
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        videoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView?.stopPlayback()
    }
}
