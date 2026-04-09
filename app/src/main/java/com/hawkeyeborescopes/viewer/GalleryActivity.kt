package com.hawkeyeborescopes.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HawkeyeGallery"
        /** Extra key: pass in the base storage dir (from getExternalFilesDir) */
        const val EXTRA_STORAGE_DIR = "storage_dir"
        /** Extra key: label for the storage type (e.g. "Internal" or "USB") */
        const val EXTRA_STORAGE_LABEL = "storage_label"
    }

    private lateinit var mediaGrid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var itemCount: TextView
    private lateinit var storageIndicator: View
    private lateinit var storageLabel: TextView

    private val mediaFiles = mutableListOf<MediaItem>()
    private lateinit var adapter: MediaAdapter
    private var storageDir: File? = null

    data class MediaItem(
        val file: File,
        val isVideo: Boolean,
        val name: String,
        val sizeStr: String,
        val dateStr: String,
        val storageTag: String  // "Internal" or "USB"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        mediaGrid = findViewById(R.id.mediaGrid)
        emptyText = findViewById(R.id.emptyText)
        itemCount = findViewById(R.id.itemCount)
        storageIndicator = findViewById(R.id.storageIndicator)
        storageLabel = findViewById(R.id.storageLabel)

        // Get storage directory from intent, or use default
        val storagePath = intent.getStringExtra(EXTRA_STORAGE_DIR)
        storageDir = if (storagePath != null) File(storagePath) else getExternalFilesDir(null)

        val label = intent.getStringExtra(EXTRA_STORAGE_LABEL)
        if (label != null) {
            storageIndicator.visibility = View.VISIBLE
            storageLabel.text = label
        }

        // Back button
        findViewById<ImageView>(R.id.backButton).setOnClickListener {
            finish()
        }

        // Setup grid — 3 columns for landscape/tablet, 2 for phone portrait
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 4 else 3
        mediaGrid.layoutManager = GridLayoutManager(this, spanCount)
        adapter = MediaAdapter()
        mediaGrid.adapter = adapter

        loadMedia()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from preview (file may have been deleted)
        if (::adapter.isInitialized) {
            loadMedia()
        }
    }

    private fun loadMedia() {
        mediaFiles.clear()
        val dateFmt = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)

        // Scan all available storage locations (internal + USB/removable)
        val allDirs = getExternalFilesDirs(null)
        val scannedPaths = mutableSetOf<String>()
        val internalPath = allDirs.firstOrNull()?.absolutePath ?: ""

        for ((index, baseDir) in allDirs.withIndex()) {
            if (baseDir == null) continue
            val tag = if (index == 0) "Internal" else "USB"
            scanMediaDir(baseDir, "Pictures", ".jpg", false, dateFmt, scannedPaths, tag)
            scanMediaDir(baseDir, "Movies", ".mp4", true, dateFmt, scannedPaths, tag)
        }

        // Also scan the specific dir passed via intent (in case it's different)
        storageDir?.let { baseDir ->
            val tag = if (baseDir.absolutePath == internalPath) "Internal" else "USB"
            scanMediaDir(baseDir, "Pictures", ".jpg", false, dateFmt, scannedPaths, tag)
            scanMediaDir(baseDir, "Movies", ".mp4", true, dateFmt, scannedPaths, tag)
        }

        // Sort newest first
        mediaFiles.sortByDescending { it.file.lastModified() }

        adapter.notifyDataSetChanged()
        updateCountAndEmpty()
    }

    private fun scanMediaDir(
        baseDir: File,
        subDir: String,
        extension: String,
        isVideo: Boolean,
        dateFmt: SimpleDateFormat,
        scannedPaths: MutableSet<String>,
        storageTag: String
    ) {
        val dir = File(baseDir, subDir)
        if (!dir.exists()) return
        dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(extension, true)
        }?.forEach { file ->
            val path = file.absolutePath
            if (path !in scannedPaths) {
                scannedPaths.add(path)
                mediaFiles.add(MediaItem(
                    file = file,
                    isVideo = isVideo,
                    name = file.name,
                    sizeStr = formatFileSize(file.length()),
                    dateStr = dateFmt.format(Date(file.lastModified())),
                    storageTag = storageTag
                ))
            }
        }
    }

    private fun updateCountAndEmpty() {
        if (mediaFiles.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            mediaGrid.visibility = View.GONE
            itemCount.text = "0 items"
        } else {
            emptyText.visibility = View.GONE
            mediaGrid.visibility = View.VISIBLE
            val images = mediaFiles.count { !it.isVideo }
            val videos = mediaFiles.count { it.isVideo }
            val parts = mutableListOf<String>()
            if (images > 0) parts.add("$images photo${if (images != 1) "s" else ""}")
            if (videos > 0) parts.add("$videos video${if (videos != 1) "s" else ""}")
            itemCount.text = parts.joinToString(", ")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun deleteItem(position: Int) {
        if (position < 0 || position >= mediaFiles.size) return
        val item = mediaFiles[position]

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete")
            .setMessage("Delete ${item.name}?\n${item.sizeStr} - ${item.dateStr}")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    // Delete the app-private copy
                    val deleted = item.file.delete()

                    // Also remove from MediaStore if it was registered there
                    removeFromMediaStore(item)

                    if (deleted) {
                        mediaFiles.removeAt(position)
                        adapter.notifyItemRemoved(position)
                        adapter.notifyItemRangeChanged(position, mediaFiles.size - position)
                        updateCountAndEmpty()
                        Toast.makeText(this, getString(R.string.gallery_deleted), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.gallery_delete_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed: ${e.message}", e)
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeFromMediaStore(item: MediaItem) {
        try {
            val collection = if (item.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            // Try to find and delete by display name
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(item.name)
            val deleted = contentResolver.delete(collection, selection, selectionArgs)
            if (deleted > 0) {
                Log.d(TAG, "Removed ${item.name} from MediaStore ($deleted rows)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove from MediaStore: ${e.message}")
        }
    }

    private fun openItem(item: MediaItem) {
        val intent = Intent(this, MediaPreviewActivity::class.java).apply {
            putExtra(MediaPreviewActivity.EXTRA_FILE_PATH, item.file.absolutePath)
            putExtra(MediaPreviewActivity.EXTRA_IS_VIDEO, item.isVideo)
            putExtra(MediaPreviewActivity.EXTRA_FILE_NAME, item.name)
        }
        startActivity(intent)
    }

    // ========================
    // RecyclerView Adapter
    // ========================

    inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
            val videoOverlay: ImageView = view.findViewById(R.id.videoOverlay)
            val fileName: TextView = view.findViewById(R.id.fileName)
            val fileInfo: TextView = view.findViewById(R.id.fileInfo)
            val deleteButton: ImageView = view.findViewById(R.id.deleteButton)
            val container: View = view.findViewById(R.id.itemContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = mediaFiles[position]

            holder.fileName.text = item.name
            holder.fileInfo.text = "[${item.storageTag}]  ${item.sizeStr}  ${item.dateStr}"
            holder.videoOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            // Load thumbnail
            loadThumbnail(holder.thumbnail, item)

            // Click to open/view
            holder.container.setOnClickListener {
                openItem(item)
            }

            // Delete button
            holder.deleteButton.setOnClickListener {
                deleteItem(holder.adapterPosition)
            }

            // D-pad: handle select/enter as open, and long-press as delete
            holder.container.setOnLongClickListener {
                deleteItem(holder.adapterPosition)
                true
            }
        }

        override fun getItemCount() = mediaFiles.size
    }

    private fun loadThumbnail(imageView: ImageView, item: MediaItem) {
        // Load thumbnails on background thread
        Thread {
            try {
                val bitmap = if (item.isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(item.file, Size(320, 320), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            item.file.absolutePath,
                            MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }
                } else {
                    // For images, decode a scaled-down version
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(item.file.absolutePath, options)
                    options.inSampleSize = calculateInSampleSize(options, 320, 320)
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeFile(item.file.absolutePath, options)
                }

                imageView.post {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail load failed for ${item.name}: ${e.message}")
            }
        }.apply {
            name = "ThumbnailLoader"
            isDaemon = true
            start()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
