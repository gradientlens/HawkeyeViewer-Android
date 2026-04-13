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
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HawkeyeGallery"
        const val EXTRA_STORAGE_DIR = "storage_dir"
        const val EXTRA_STORAGE_LABEL = "storage_label"
    }

    private lateinit var mediaGrid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var itemCount: TextView
    private lateinit var storageIndicator: View
    private lateinit var storageLabel: TextView
    private lateinit var selectButton: TextView
    private lateinit var actionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var deleteSelectedButton: TextView
    private lateinit var moveToUsbButton: TextView
    private lateinit var cancelSelectButton: TextView

    private val mediaFiles = mutableListOf<MediaItem>()
    private lateinit var adapter: MediaAdapter
    private var storageDir: File? = null

    // Selection state
    private var isSelectMode = false
    private val selectedPositions = mutableSetOf<Int>()

    data class MediaItem(
        val file: File,
        val isVideo: Boolean,
        val name: String,
        val sizeStr: String,
        val dateStr: String,
        val storageTag: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        mediaGrid = findViewById(R.id.mediaGrid)
        emptyText = findViewById(R.id.emptyText)
        itemCount = findViewById(R.id.itemCount)
        storageIndicator = findViewById(R.id.storageIndicator)
        storageLabel = findViewById(R.id.storageLabel)
        selectButton = findViewById(R.id.selectButton)
        actionBar = findViewById(R.id.actionBar)
        selectionCount = findViewById(R.id.selectionCount)
        selectAllButton = findViewById(R.id.selectAllButton)
        deleteSelectedButton = findViewById(R.id.deleteSelectedButton)
        moveToUsbButton = findViewById(R.id.moveToUsbButton)
        cancelSelectButton = findViewById(R.id.cancelSelectButton)

        val storagePath = intent.getStringExtra(EXTRA_STORAGE_DIR)
        storageDir = if (storagePath != null) File(storagePath) else getExternalFilesDir(null)

        val label = intent.getStringExtra(EXTRA_STORAGE_LABEL)
        if (label != null) {
            storageIndicator.visibility = View.VISIBLE
            storageLabel.text = label
        }

        // Back button
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        // Select button
        selectButton.setOnClickListener { enterSelectMode() }

        // Action bar buttons
        selectAllButton.setOnClickListener { toggleSelectAll() }
        deleteSelectedButton.setOnClickListener { deleteSelected() }
        moveToUsbButton.setOnClickListener { moveSelectedToUsb() }
        cancelSelectButton.setOnClickListener { exitSelectMode() }

        // Show "Move to USB" only if USB storage is available
        if (hasUsbStorage()) {
            moveToUsbButton.visibility = View.VISIBLE
        }

        // Setup grid
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 4 else 3
        mediaGrid.layoutManager = GridLayoutManager(this, spanCount)
        adapter = MediaAdapter()
        mediaGrid.adapter = adapter

        loadMedia()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (isSelectMode) {
                exitSelectMode()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            // Refresh: picks up new files, drops files from removed USB, etc.
            if (isSelectMode) exitSelectMode()
            loadMedia()
            // Update "Move to USB" visibility in case USB was added/removed
            moveToUsbButton.visibility = if (hasUsbStorage()) View.VISIBLE else View.GONE
        }
    }

    // ========================
    // Select Mode
    // ========================

    private fun enterSelectMode() {
        isSelectMode = true
        selectedPositions.clear()
        selectButton.text = getString(R.string.gallery_cancel)
        selectButton.setOnClickListener { exitSelectMode() }
        actionBar.visibility = View.VISIBLE
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedPositions.clear()
        selectButton.text = getString(R.string.gallery_select)
        selectButton.setOnClickListener { enterSelectMode() }
        actionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        adapter.notifyItemChanged(position)
        updateSelectionCount()
    }

    private fun toggleSelectAll() {
        if (selectedPositions.size == mediaFiles.size) {
            // Deselect all
            selectedPositions.clear()
        } else {
            // Select all
            selectedPositions.clear()
            for (i in mediaFiles.indices) {
                selectedPositions.add(i)
            }
        }
        adapter.notifyDataSetChanged()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        val count = selectedPositions.size
        selectionCount.text = getString(R.string.gallery_selected_count, count)
        deleteSelectedButton.alpha = if (count > 0) 1.0f else 0.4f
        moveToUsbButton.alpha = if (count > 0) 1.0f else 0.4f
    }

    // ========================
    // Batch Operations
    // ========================

    private fun deleteSelected() {
        val count = selectedPositions.size
        if (count == 0) return

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete")
            .setMessage("Delete $count file${if (count != 1) "s" else ""}?")
            .setPositiveButton("Delete") { _, _ ->
                // Delete in reverse order to maintain valid positions
                val sorted = selectedPositions.sortedDescending()
                var deleted = 0
                for (pos in sorted) {
                    if (pos < mediaFiles.size) {
                        val item = mediaFiles[pos]
                        try {
                            if (item.file.delete()) {
                                removeFromMediaStore(item)
                                mediaFiles.removeAt(pos)
                                deleted++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete ${item.name}: ${e.message}")
                        }
                    }
                }
                selectedPositions.clear()
                adapter.notifyDataSetChanged()
                updateCountAndEmpty()
                updateSelectionCount()
                Toast.makeText(this, "$deleted file${if (deleted != 1) "s" else ""} deleted", Toast.LENGTH_SHORT).show()
                if (mediaFiles.isEmpty()) {
                    exitSelectMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveSelectedToUsb() {
        val count = selectedPositions.size
        if (count == 0) return

        val usbDir = getUsbStorageBase() ?: run {
            Toast.makeText(this, "No USB storage found", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Move to USB")
            .setMessage("Move $count file${if (count != 1) "s" else ""} to USB storage?")
            .setPositiveButton("Move") { _, _ ->
                val sorted = selectedPositions.sortedDescending()
                var moved = 0
                for (pos in sorted) {
                    if (pos < mediaFiles.size) {
                        val item = mediaFiles[pos]
                        try {
                            val subDir = if (item.isVideo) "Movies" else "Pictures"
                            val destDir = File(usbDir, subDir)
                            if (!destDir.exists()) destDir.mkdirs()
                            val destFile = File(destDir, item.name)

                            // Copy file
                            FileInputStream(item.file).use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }

                            // Verify copy succeeded, then delete original
                            if (destFile.exists() && destFile.length() == item.file.length()) {
                                item.file.delete()
                                removeFromMediaStore(item)
                                mediaFiles.removeAt(pos)
                                moved++
                            } else {
                                destFile.delete() // Clean up failed copy
                                Log.e(TAG, "Move failed for ${item.name}: size mismatch")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Move failed for ${item.name}: ${e.message}")
                        }
                    }
                }
                selectedPositions.clear()
                // Reload to pick up files in new location
                loadMedia()
                updateSelectionCount()
                Toast.makeText(this, "$moved file${if (moved != 1) "s" else ""} moved to USB", Toast.LENGTH_SHORT).show()
                if (mediaFiles.isEmpty()) {
                    exitSelectMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ========================
    // USB Storage Detection
    // ========================

    private fun hasUsbStorage(): Boolean {
        val dirs = getExternalFilesDirs(null)
        return dirs.size > 1 && dirs.drop(1).any { it?.canWrite() == true }
    }

    private fun getUsbStorageBase(): File? {
        val dirs = getExternalFilesDirs(null)
        if (dirs.size > 1) {
            for (i in 1 until dirs.size) {
                val dir = dirs[i]
                if (dir != null && dir.canWrite()) return dir
            }
        }
        return null
    }

    // ========================
    // Media Loading
    // ========================

    private fun loadMedia() {
        mediaFiles.clear()
        val dateFmt = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)

        val allDirs = getExternalFilesDirs(null)
        val scannedPaths = mutableSetOf<String>()
        val internalPath = allDirs.firstOrNull()?.absolutePath ?: ""

        for ((index, baseDir) in allDirs.withIndex()) {
            if (baseDir == null) continue
            val tag = if (index == 0) "Internal" else "USB"
            scanMediaDir(baseDir, "Pictures", ".jpg", false, dateFmt, scannedPaths, tag)
            scanMediaDir(baseDir, "Movies", ".mp4", true, dateFmt, scannedPaths, tag)
        }

        storageDir?.let { baseDir ->
            val tag = if (baseDir.absolutePath == internalPath) "Internal" else "USB"
            scanMediaDir(baseDir, "Pictures", ".jpg", false, dateFmt, scannedPaths, tag)
            scanMediaDir(baseDir, "Movies", ".mp4", true, dateFmt, scannedPaths, tag)
        }

        mediaFiles.sortByDescending { it.file.lastModified() }
        adapter.notifyDataSetChanged()
        updateCountAndEmpty()
    }

    private fun scanMediaDir(
        baseDir: File, subDir: String, extension: String,
        isVideo: Boolean, dateFmt: SimpleDateFormat,
        scannedPaths: MutableSet<String>, storageTag: String
    ) {
        try {
            val dir = File(baseDir, subDir)
            if (!dir.exists()) return
            dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(extension, true) && it.length() > 0
            }?.forEach { file ->
                val path = file.absolutePath
                if (path !in scannedPaths) {
                    scannedPaths.add(path)
                    mediaFiles.add(MediaItem(
                        file = file, isVideo = isVideo, name = file.name,
                        sizeStr = formatFileSize(file.length()),
                        dateStr = dateFmt.format(Date(file.lastModified())),
                        storageTag = storageTag
                    ))
                }
            }
        } catch (e: Exception) {
            // Storage may have been removed — skip this directory
            Log.w(TAG, "Failed to scan $baseDir/$subDir: ${e.message}")
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

    // ========================
    // Single Item Operations
    // ========================

    private fun deleteItem(position: Int) {
        if (position < 0 || position >= mediaFiles.size) return
        val item = mediaFiles[position]

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete")
            .setMessage("Delete ${item.name}?\n${item.sizeStr} - ${item.dateStr}")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = item.file.delete()
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
        if (!item.file.exists()) {
            Toast.makeText(this, "File no longer available", Toast.LENGTH_SHORT).show()
            loadMedia()
            return
        }
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
            val selectCheckBox: CheckBox = view.findViewById(R.id.selectCheckBox)
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

            loadThumbnail(holder.thumbnail, item)

            if (isSelectMode) {
                // Select mode: show checkbox, hide delete button
                holder.selectCheckBox.visibility = View.VISIBLE
                holder.selectCheckBox.isChecked = selectedPositions.contains(position)
                holder.deleteButton.visibility = View.GONE

                holder.container.setOnClickListener {
                    toggleSelection(holder.adapterPosition)
                }
                holder.container.setOnLongClickListener { false }
            } else {
                // Normal mode: hide checkbox, show delete button
                holder.selectCheckBox.visibility = View.GONE
                holder.deleteButton.visibility = View.VISIBLE

                holder.container.setOnClickListener {
                    openItem(item)
                }
                holder.deleteButton.setOnClickListener {
                    deleteItem(holder.adapterPosition)
                }
                holder.container.setOnLongClickListener {
                    deleteItem(holder.adapterPosition)
                    true
                }
            }
        }

        override fun getItemCount() = mediaFiles.size
    }

    // ========================
    // Thumbnail Loading
    // ========================

    private fun loadThumbnail(imageView: ImageView, item: MediaItem) {
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
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
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
