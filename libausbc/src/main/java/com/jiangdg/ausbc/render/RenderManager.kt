/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.internal.*
import com.jiangdg.ausbc.utils.*
import com.jiangdg.ausbc.utils.bus.BusKey
import com.jiangdg.ausbc.utils.bus.EventBus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Render manager
 *
 * @property surfaceWidth camera preview width
 * @property surfaceHeight camera preview height
 *
 * @param context context
 *
 * @author Created by jiangdg on 2021/12/28
 */
class RenderManager(
    context: Context,
    private val surfaceWidth: Int,         // render surface width
    private val surfaceHeight: Int,        // render surface height
    private val mPreviewDataCbList: CopyOnWriteArrayList<IPreviewDataCallBack>?=null
) : SurfaceTexture.OnFrameAvailableListener, Handler.Callback {
    private var mPreviewByteBuffer: ByteBuffer? = null
    private var mEOSTextureId: Int? = null
    private var mRenderThread: HandlerThread? = null
    private var mRenderHandler: Handler? = null
    private var mRenderCodecThread: HandlerThread? = null
    private var mRenderCodecHandler: Handler? = null
    private var mCameraRender: CameraRender? = null
    private var mScreenRender: ScreenRender? = null
    private var mEncodeRender: EncodeRender? = null
    private var mCaptureRender: CaptureRender? = null
    private var mCameraSurfaceTexture: SurfaceTexture? = null
    private var mTransformMatrix: FloatArray = FloatArray(16)
    private var mWidth: Int = 0
    private var mHeight: Int = 0
    private var mFBOBufferId: Int = 0
    // Current adjustment values for software fallback on still captures
    @Volatile private var mAdjBrightness: Float = 1.0f
    @Volatile private var mAdjContrast: Float = 1.0f
    @Volatile private var mAdjSaturation: Float = 1.0f
    @Volatile private var mAdjGamma: Float = 1.0f
    private var mContext: Context = context
    private var mEffectList = arrayListOf<AbstractEffect>()
    private var mCacheEffectList = arrayListOf<AbstractEffect>()
    private var mCaptureDataCb: ICaptureCallBack? = null
    private var mFrameRate = 0
    private var mEndTime: Long = 0L
    private var mStartTime = System.currentTimeMillis()
    private val mStFuture by lazy {
        SettableFuture<SurfaceTexture>()
    }
    private val mMainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }
    private val mCaptureState: AtomicBoolean by lazy {
        AtomicBoolean(false)
    }
    private val mDateFormat by lazy {
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
    }
    private val mCameraDir by lazy {
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
    }

    init {
        this.mCameraRender = CameraRender(context)
        this.mScreenRender = ScreenRender(context)
        this.mCaptureRender = CaptureRender(context)
        Logger.i(TAG, "create RenderManager, Open ES version is ${Utils.getGLESVersion(context)}")
    }

    /**
     * Rendering processing logic
     *
     * Note: EGL must be initialized first, otherwise GL cannot run
     */
    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_GL_INIT -> {
                (msg.obj as Triple<*, *, *>).apply {
                    val w = first as Int
                    val h = second as Int
                    val surface = third as? Surface
                    mScreenRender?.initEGLEvn()
                    mScreenRender?.setupSurface(surface, w, h)
                    mScreenRender?.initGLES()
                    mCameraRender?.initGLES()
                    mCaptureRender?.initGLES()
                    mEOSTextureId = mCameraRender?.getCameraTextureId()?.apply {
                        mStFuture.set(SurfaceTexture(this))
                    }
                    EventBus.with<Boolean>(BusKey.KEY_RENDER_READY).postMessage(true)
                }
            }
            MSG_GL_CHANGED_SIZE -> {
                (msg.obj as Pair<*, *>).apply {
                    mWidth = first as Int
                    mHeight = second as Int
                    mCameraRender?.setSize(mWidth, mHeight)
                    mScreenRender?.setSize(mWidth, mHeight)
                    mCaptureRender?.setSize(mWidth, mHeight)
                    mCameraSurfaceTexture?.setDefaultBufferSize(mWidth, mHeight)
                }
            }
            MSG_GL_SAVE_IMAGE -> {
                saveImageInternal(msg.obj as? String)
            }
            MSG_GL_START_RENDER_CODEC -> {
                (msg.obj as Triple<*, *, *>).apply {
                    val surface = first as Surface
                    val width = second as Int
                    val height = third as Int
                    startRenderCodecInternal(surface, width, height)
                }
            }
            MSG_GL_STOP_RENDER_CODEC -> {
                stopRenderCodecInternal()
            }
            MSG_GL_ROUTE_ANGLE -> {
                (msg.obj as? RotateType)?.apply {
                    mCameraRender?.setRotateAngle(this)
                }
            }
            MSG_GL_DRAW -> {
                //Render camera data to SurfaceTexture
                //Set the correction matrix of the image at the same time
                mCameraSurfaceTexture?.updateTexImage()
                mCameraSurfaceTexture?.getTransformMatrix(mTransformMatrix)
                mCameraRender?.setTransformMatrix(mTransformMatrix)
                val textureId = mEOSTextureId?.let { mCameraRender?.drawFrame(it) }
                //Filter FBO and rendering
                textureId?.let { fboId ->
                    var effectId = fboId
                    mEffectList.forEach { effectRender ->
                        effectId = effectRender.drawFrame(effectId)
                    }
                    effectId
                }?.also { id ->
                    mScreenRender?.drawFrame(id)
                    drawFrame2Capture(id)
                    drawFrame2Codec(id, mCameraSurfaceTexture?.timestamp ?: 0)
                }
                mScreenRender?.swapBuffers(mCameraSurfaceTexture?.timestamp ?: 0)
            }
            MSG_GL_ADD_EFFECT -> {
                (msg.obj as? AbstractEffect)?.let { effect->
                    if (mEffectList.contains(effect)) {
                        return@let
                    }
                    effect.initGLES()
                    effect.setSize(mWidth, mHeight)
                    mEffectList.add(effect)
                    mCacheEffectList.add(effect)
                    Logger.i(TAG, "add effect, name = ${effect.javaClass.simpleName}, size = ${mEffectList.size}")
                }
            }
            MSG_GL_REMOVE_EFFECT -> {
                (msg.obj as? AbstractEffect)?.let {
                    if (! mEffectList.contains(it)) {
                        return@let
                    }
                    it.releaseGLES()
                    mEffectList.remove(it)
                    mCacheEffectList.remove(it)
                    Logger.i(TAG, "remove effect, name = ${it.javaClass.simpleName}, size = ${mEffectList.size}")
                }
            }
            MSG_GL_SET_ADJUSTMENTS -> {
                @Suppress("UNCHECKED_CAST")
                (msg.obj as? FloatArray)?.let { values ->
                    if (values.size >= 6) {
                        // Apply to screen (preview), capture (stills), and encode (video)
                        mScreenRender?.brightness = values[0]
                        mScreenRender?.contrast = values[1]
                        mScreenRender?.saturation = values[2]
                        mScreenRender?.hue = values[3]
                        mScreenRender?.gamma = values[4]
                        mScreenRender?.sharpness = values[5]

                        mCaptureRender?.brightness = values[0]
                        mCaptureRender?.contrast = values[1]
                        mCaptureRender?.saturation = values[2]
                        mCaptureRender?.hue = values[3]
                        mCaptureRender?.gamma = values[4]
                        mCaptureRender?.sharpness = values[5]

                        mEncodeRender?.brightness = values[0]
                        mEncodeRender?.contrast = values[1]
                        mEncodeRender?.saturation = values[2]
                        mEncodeRender?.hue = values[3]
                        mEncodeRender?.gamma = values[4]
                        mEncodeRender?.sharpness = values[5]

                        // Store for software fallback on still captures
                        mAdjBrightness = values[0]
                        mAdjContrast = values[1]
                        mAdjSaturation = values[2]
                        mAdjGamma = values[4]
                    }
                }
            }
            MSG_GL_SET_ZOOM_PAN -> {
                (msg.obj as? FloatArray)?.let { values ->
                    if (values.size >= 3) {
                        mScreenRender?.zoom = values[0]
                        mScreenRender?.panX = values[1]
                        mScreenRender?.panY = values[2]

                        mCaptureRender?.zoom = values[0]
                        mCaptureRender?.panX = values[1]
                        mCaptureRender?.panY = values[2]

                        mEncodeRender?.zoom = values[0]
                        mEncodeRender?.panX = values[1]
                        mEncodeRender?.panY = values[2]
                    }
                }
            }
            MSG_GL_SET_CROP_ZOOM -> {
                (msg.obj as? FloatArray)?.let { values ->
                    if (values.size >= 2) {
                        mScreenRender?.cropZoomX = values[0]
                        mScreenRender?.cropZoomY = values[1]

                        mCaptureRender?.cropZoomX = values[0]
                        mCaptureRender?.cropZoomY = values[1]

                        mEncodeRender?.cropZoomX = values[0]
                        mEncodeRender?.cropZoomY = values[1]
                    }
                }
            }
            MSG_GL_RELEASE -> {
                EventBus.with<Boolean>(BusKey.KEY_RENDER_READY).postMessage(false)
                mEffectList.forEach { effect ->
                    effect.releaseGLES()
                }
                mEffectList.clear()
                mCameraRender?.releaseGLES()
                mScreenRender?.releaseGLES()
                mCaptureRender?.releaseGLES()
                mCameraSurfaceTexture?.setOnFrameAvailableListener(null)
                mCameraSurfaceTexture = null
            }
        }
        return true
    }

    private var mLastInputTextureId: Int = -1

    private fun drawFrame2Capture(fboId: Int) {
        mLastInputTextureId = fboId
        mCaptureRender?.drawFrame(fboId)?.let {
            mCaptureRender!!.getFrameBufferId()
        }?.also { id ->
            mFBOBufferId = id
            // opengl preview data, format is rgba
            val renderWidth = mCaptureRender?.getRenderWidth() ?: mWidth
            val renderHeight = mCaptureRender?.getRenderHeight() ?: mHeight
            val rgbaLen = renderWidth * renderHeight * 4
            mPreviewDataCbList?.forEach { callback ->
                if (mPreviewByteBuffer==null || mPreviewByteBuffer?.remaining() != rgbaLen) {
                    mPreviewByteBuffer = ByteBuffer.allocateDirect(rgbaLen)
                    mPreviewByteBuffer?.order(ByteOrder.LITTLE_ENDIAN)
                }
                mPreviewByteBuffer?.let {
                    it.clear()
                    GLBitmapUtils.readPixelToByteBuffer(id,renderWidth, renderHeight, mPreviewByteBuffer)
                    callback.onPreviewData(it.array(),renderWidth, renderHeight, IPreviewDataCallBack.DataFormat.RGBA)
                }
            }
        }
    }

    /**
     * Start render screen
     *
     * @param w surface width
     * @param h surface height
     * @param outSurface render surface
     * @param listener acquire camera surface texture, see [CameraSurfaceTextureListener]
     */
    fun startRenderScreen(w: Int, h: Int, outSurface: Surface?, listener: CameraSurfaceTextureListener? = null) {
        mRenderThread = HandlerThread(RENDER_THREAD)
        mRenderThread?.start()
        mRenderHandler = Handler(mRenderThread!!.looper, this@RenderManager)
        Triple(w, h, outSurface).apply {
            mRenderHandler?.obtainMessage(MSG_GL_INIT, this)?.sendToTarget()
        }
        // wait camera SurfaceTexture created
        try {
            mStFuture.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Logger.e(TAG, "wait for creating camera SurfaceTexture failed")
            null
        }?.apply {
            setDefaultBufferSize(w, h)
            setOnFrameAvailableListener(this@RenderManager)
            mCameraSurfaceTexture = this
        }.also {
            listener?.onSurfaceTextureAvailable(it)
            Logger.i(TAG, "create camera SurfaceTexture: $it")
        }
        setRenderSize(w, h)
    }

    /**
     * Stop render screen
     */
    fun stopRenderScreen() {
        mRenderHandler?.obtainMessage(MSG_GL_RELEASE)?.sendToTarget()
        mRenderThread?.quitSafely()
        mRenderThread = null
        mRenderHandler = null
    }

    /**
     * Start render codec
     *
     * @param inputSurface mediacodec input surface, see [android.media.MediaCodec]
     * @param width camera preview width
     * @param height camera preview height
     */
    fun startRenderCodec(inputSurface: Surface, width: Int, height: Int) {
        Triple(inputSurface, width, height).apply {
            mRenderHandler?.obtainMessage(MSG_GL_START_RENDER_CODEC, this)?.sendToTarget()
        }
    }

    /**
     * Stop render codec
     */
    fun stopRenderCodec() {
        mRenderHandler?.obtainMessage(MSG_GL_STOP_RENDER_CODEC)?.sendToTarget()
    }

    /**
     * Set render size
     *
     * @param w surface width
     * @param h surface height
     */
    fun setRenderSize(w: Int, h: Int) {
        mRenderHandler?.obtainMessage(MSG_GL_CHANGED_SIZE, Pair(w, h))?.sendToTarget()
    }

    /**
     * Add render effect
     *
     * @param effect add a effect, see [AbstractEffect]
     */
    fun addRenderEffect(effect: AbstractEffect?) {
        mRenderHandler?.obtainMessage(MSG_GL_ADD_EFFECT, effect)?.sendToTarget()
    }

    /**
     * Remove render effect
     *
     * @param effect a effect removed, see [AbstractEffect]
     */
    fun removeRenderEffect(effect: AbstractEffect?) {
        mRenderHandler?.obtainMessage(MSG_GL_REMOVE_EFFECT, effect)?.sendToTarget()
    }

    /**
     * Rotate camera render angle
     *
     * @param type rotate angle, null means rotating nothing
     * see [RotateType.ANGLE_90], [RotateType.ANGLE_270],...etc.
     */
    fun setRotateType(type: RotateType?) {
        mRenderHandler?.obtainMessage(MSG_GL_ROUTE_ANGLE, type)?.sendToTarget()
    }

    /**
     * Set image adjustment parameters (applied via GPU shader)
     *
     * @param brightness 0.0-2.0, default 1.0 (normal)
     * @param contrast 0.0-2.0, default 1.0 (normal)
     * @param saturation 0.0-3.0, default 1.0 (normal)
     * @param hue rotation in radians, default 0.0
     * @param gamma 0.2-3.0, default 1.0 (normal)
     */
    fun setImageAdjustments(brightness: Float, contrast: Float, saturation: Float, hue: Float, gamma: Float, sharpness: Float = 0.0f) {
        val values = floatArrayOf(brightness, contrast, saturation, hue, gamma, sharpness)
        mRenderHandler?.obtainMessage(MSG_GL_SET_ADJUSTMENTS, values)?.sendToTarget()
    }

    /**
     * Set zoom and pan (applied via GPU vertex shader on texture coordinates)
     *
     * @param zoom 1.0-5.0, default 1.0 (no zoom)
     * @param panX normalized pan offset X, clamped by caller
     * @param panY normalized pan offset Y, clamped by caller
     */
    fun setZoomPan(zoom: Float, panX: Float, panY: Float) {
        val values = floatArrayOf(zoom, panX, panY)
        mRenderHandler?.obtainMessage(MSG_GL_SET_ZOOM_PAN, values)?.sendToTarget()
    }

    /**
     * Set crop zoom for aspect ratio center-crop.
     * When the view aspect ratio differs from the camera source,
     * this crops the source from center instead of stretching.
     *
     * @param cropZoomX X-axis crop factor (>=1.0, 1.0 = no crop)
     * @param cropZoomY Y-axis crop factor (>=1.0, 1.0 = no crop)
     */
    fun setCropZoom(cropZoomX: Float, cropZoomY: Float) {
        val values = floatArrayOf(cropZoomX, cropZoomY)
        mRenderHandler?.obtainMessage(MSG_GL_SET_CROP_ZOOM, values)?.sendToTarget()
    }

    /**
     * Get cache render effect list
     * @return current effects
     */
    fun getCacheEffectList() = mCacheEffectList

    /**
     * Save image
     *
     * @param callBack capture image status, see [ICaptureCallBack]
     * @param path custom image path
     */
    fun saveImage(callBack: ICaptureCallBack?, path: String?) {
        this.mCaptureDataCb = callBack
        mRenderHandler?.obtainMessage(MSG_GL_SAVE_IMAGE, path)?.sendToTarget()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        emitFrameRate()
        mRenderHandler?.obtainMessage(MSG_GL_DRAW)?.sendToTarget()
    }

    private fun startRenderCodecInternal(surface: Surface, w: Int, h: Int) {
        stopRenderCodecInternal()
        mRenderCodecThread = HandlerThread(RENDER_CODEC_THREAD)
        mRenderCodecThread?.start()
        mRenderCodecHandler = Handler(mRenderCodecThread!!.looper) { message ->
            when (message.what) {
                MSG_GL_RENDER_CODEC_INIT -> {
                    (message.obj as Pair<*, *>).apply {
                        val shareContext = first as EGLContext
                        val inputSurface = second as Surface
                        mEncodeRender = EncodeRender(mContext)
                        mEncodeRender?.initEGLEvn(shareContext)
                        mEncodeRender?.setupSurface(inputSurface)
                        mEncodeRender?.initGLES()
                        // Sync current adjustment values to new encode render
                        mScreenRender?.let { sr ->
                            mEncodeRender?.brightness = sr.brightness
                            mEncodeRender?.contrast = sr.contrast
                            mEncodeRender?.saturation = sr.saturation
                            mEncodeRender?.hue = sr.hue
                            mEncodeRender?.gamma = sr.gamma
                            mEncodeRender?.sharpness = sr.sharpness
                            mEncodeRender?.texelWidth = sr.texelWidth
                            mEncodeRender?.texelHeight = sr.texelHeight
                        }
                    }
                }
                MSG_GL_RENDER_CODEC_CHANGED_SIZE -> {
                    (message.obj as Pair<*, *>).apply {
                        val width = first as Int
                        val height = second as Int
                        mEncodeRender?.setSize(width, height)
                    }
                }
                MSG_GL_RENDER_CODEC_DRAW -> {
                    (message.obj as Pair<*, *>).apply {
                        val textureId = first as Int
                        val timeStamps = second as Long
                        mEncodeRender?.drawFrame(textureId)
                        mEncodeRender?.swapBuffers(timeStamps)
                    }
                }
                MSG_GL_RENDER_CODEC_RELEASE -> {
                    mEncodeRender?.releaseGLES()
                    mEncodeRender = null
                }
            }
            true
        }
        mScreenRender?.getCurrentContext().let {
            if (it == null) {
                throw NullPointerException("Current EGLContext can't be null.")
            }
            mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_INIT, Pair(it, surface))?.sendToTarget()
        }
        mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_CHANGED_SIZE, Pair(w, h))?.sendToTarget()
    }

    private fun drawFrame2Codec(textureId: Int, timeStamps: Long) {
        Pair(textureId, timeStamps).apply {
            mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_DRAW, this)?.sendToTarget()
        }
    }

    private fun stopRenderCodecInternal() {
        mRenderCodecHandler?.obtainMessage(MSG_GL_RENDER_CODEC_RELEASE)?.sendToTarget()
        mRenderCodecThread?.quitSafely()
        mRenderCodecThread = null
        mRenderCodecHandler = null
    }

    private fun saveImageInternal(savePath: String?) {
        if (mCaptureState.get()) {
            return
        }
        mCaptureState.set(true)
        mMainHandler.post {
            mCaptureDataCb?.onBegin()
        }
        val date = mDateFormat.format(System.currentTimeMillis())
        val title = savePath ?: "IMG_AUSBC_$date"
        val displayName = savePath ?: "$title.jpg"
        val path = savePath ?: "$mCameraDir/$displayName"
        val width = mWidth
        val height = mHeight
        // Read raw frame - apply software adjustments
        android.util.Log.e("CAPTURE_DEBUG", "saveImage: b=$mAdjBrightness c=$mAdjContrast s=$mAdjSaturation g=$mAdjGamma")
        // (GL shader adjustments on FBO don't reliably persist to glReadPixels on some devices)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(path)
            GLBitmapUtils.transFrameBufferToBitmap(mFBOBufferId, width, height).let { rawBitmap ->
                applyBitmapAdjustments(rawBitmap, mAdjBrightness, mAdjContrast, mAdjSaturation, mAdjGamma)
            }.apply {
                compress(Bitmap.CompressFormat.JPEG, 100, fos)
                recycle()
            }
        } catch (e: IOException) {
            mMainHandler.post {
                mCaptureDataCb?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "Failed to write file, err = ${e.localizedMessage}", e)
        } finally {
            try {
                fos?.close()
            } catch (e: IOException) {
                Logger.e(TAG, "Failed to write file, err = ${e.localizedMessage}", e)
            }
        }
        //Judge whether it is saved successfully
        //Update gallery if successful
        val file = File(path)
        if (file.length() == 0L) {
            Logger.e(TAG, "Failed to save file $path")
            file.delete()
            mCaptureState.set(false)
            return
        }
        // MediaStore insertion disabled — caller handles gallery registration
        // via proper scoped storage copy (the legacy DATA-based insert creates
        // ghost entries on Android 10+ scoped storage).
        // val values = ContentValues()
        // values.put(MediaStore.Images.ImageColumns.TITLE, title)
        // values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, displayName)
        // values.put(MediaStore.Images.ImageColumns.DATA, path)
        // values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, date)
        // values.put(MediaStore.Images.ImageColumns.WIDTH, width)
        // values.put(MediaStore.Images.ImageColumns.HEIGHT, height)
        // mContext.contentResolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        mMainHandler.post {
            mCaptureDataCb?.onComplete(path)
        }
        mCaptureState.set(false)
        if (Utils.debugCamera) {
            Logger.i(TAG, "captureImageInternal save path = $path")
        }
    }

    private fun emitFrameRate() {
        mFrameRate++
        mEndTime = System.currentTimeMillis()
        if (mEndTime - mStartTime >= 1000) {
            if (Utils.debugCamera) {
                Logger.i(TAG, "camera render frame rate is $mFrameRate fps-->${Thread.currentThread().name}")
            }
            EventBus.with<Int>(BusKey.KEY_FRAME_RATE).postMessage(mFrameRate)
            mStartTime = mEndTime
            mFrameRate = 0
        }
    }

    /**
     * Camera surface texture listener
     *
     * @constructor Create empty Camera surface texture listener
     */
    interface CameraSurfaceTextureListener {
        /**
         * On surface texture available
         *
         * @param surfaceTexture camera render surface texture
         */
        fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?)
    }

    /**
     * Apply brightness, contrast, saturation, gamma to a bitmap in software.
     * Matches the GPU shader logic for WYSIWYG still captures.
     */
    private fun applyBitmapAdjustments(
        bitmap: Bitmap, brightness: Float, contrast: Float, saturation: Float, gamma: Float
    ): Bitmap {
        // Skip if all defaults (1.0)
        if (Math.abs(brightness - 1.0f) < 0.01f &&
            Math.abs(contrast - 1.0f) < 0.01f &&
            Math.abs(saturation - 1.0f) < 0.01f &&
            Math.abs(gamma - 1.0f) < 0.01f) {
            return bitmap
        }

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Build gamma LUT for speed
        val gammaLut = if (Math.abs(gamma - 1.0f) > 0.01f) {
            val invGamma = 1.0f / gamma
            IntArray(256) { i ->
                (Math.pow(i / 255.0, invGamma.toDouble()) * 255.0).toInt().coerceIn(0, 255)
            }
        } else null

        for (i in pixels.indices) {
            val pixel = pixels[i]
            var r = (pixel shr 16) and 0xFF
            var g = (pixel shr 8) and 0xFF
            var b = (pixel) and 0xFF
            val a = (pixel shr 24) and 0xFF

            // 1. Brightness (multiply)
            var rf = r * brightness / 255.0f
            var gf = g * brightness / 255.0f
            var bf = b * brightness / 255.0f

            // 2. Contrast (scale around 0.5)
            rf = (rf - 0.5f) * contrast + 0.5f
            gf = (gf - 0.5f) * contrast + 0.5f
            bf = (bf - 0.5f) * contrast + 0.5f

            // 3. Saturation
            val luma = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
            rf = luma + (rf - luma) * saturation
            gf = luma + (gf - luma) * saturation
            bf = luma + (bf - luma) * saturation

            // Clamp to 0-255
            r = (rf * 255.0f).toInt().coerceIn(0, 255)
            g = (gf * 255.0f).toInt().coerceIn(0, 255)
            b = (bf * 255.0f).toInt().coerceIn(0, 255)

            // 4. Gamma (via LUT)
            if (gammaLut != null) {
                r = gammaLut[r]
                g = gammaLut[g]
                b = gammaLut[b]
            }

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    companion object {
        private const val TAG = "RenderManager"
        private const val RENDER_THREAD = "gl_render"
        private const val RENDER_CODEC_THREAD = "gl_render_codec"
        // render
        private const val MSG_GL_INIT = 0x00
        private const val MSG_GL_DRAW = 0x01
        private const val MSG_GL_RELEASE = 0x02
        private const val MSG_GL_START_RENDER_CODEC = 0x03
        private const val MSG_GL_STOP_RENDER_CODEC = 0x04
        private const val MSG_GL_CHANGED_SIZE = 0x05
        private const val MSG_GL_ADD_EFFECT = 0x06
        private const val MSG_GL_REMOVE_EFFECT = 0x07
        private const val MSG_GL_SAVE_IMAGE = 0x08
        private const val MSG_GL_ROUTE_ANGLE = 0x09
        private const val MSG_GL_SET_ADJUSTMENTS = 0x0A
        private const val MSG_GL_SET_ZOOM_PAN = 0x0B
        private const val MSG_GL_SET_CROP_ZOOM = 0x0C

        // codec
        private const val MSG_GL_RENDER_CODEC_INIT = 0x11
        private const val MSG_GL_RENDER_CODEC_CHANGED_SIZE = 0x12
        private const val MSG_GL_RENDER_CODEC_DRAW = 0x13
        private const val MSG_GL_RENDER_CODEC_RELEASE = 0x14
    }
}