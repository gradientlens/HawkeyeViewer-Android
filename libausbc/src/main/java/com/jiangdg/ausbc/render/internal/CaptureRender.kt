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
package com.jiangdg.ausbc.render.internal

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.jiangdg.ausbc.R
import kotlin.math.cos
import kotlin.math.sin

class CaptureRender(context: Context) : AbstractFboRender(context) {
    private var mMVPMatrixHandle: Int = -1
    private var mMVPMatrix = FloatArray(16)

    // Adjustment uniform handles
    private var mBrightnessHandle = -1
    private var mContrastHandle = -1
    private var mSaturationHandle = -1
    private var mHueHandle = -1
    private var mGammaHandle = -1
    private var mSharpnessHandle = -1
    private var mTexelSizeHandle = -1
    private var mZoomHandle = -1
    private var mPanHandle = -1
    private var mCropZoomHandle = -1

    @Volatile var brightness: Float = 1.0f
    @Volatile var contrast: Float = 1.0f
    @Volatile var saturation: Float = 1.0f
    @Volatile var hue: Float = 0.0f
    @Volatile var gamma: Float = 1.0f
    @Volatile var sharpness: Float = 0.0f
    @Volatile var texelWidth: Float = 0.0f
    @Volatile var texelHeight: Float = 0.0f
    @Volatile var zoom: Float = 1.0f
    @Volatile var panX: Float = 0.0f
    @Volatile var panY: Float = 0.0f
    @Volatile var cropZoomX: Float = 1.0f
    @Volatile var cropZoomY: Float = 1.0f
    private var loggedOnce = false

    override fun init() {
        Matrix.setIdentityM(mMVPMatrix, 0)
        val radius = (180 * Math.PI / 180.0).toFloat()
        mMVPMatrix[5] *= cos(radius.toDouble()).toFloat()
        mMVPMatrix[6] += (-sin(radius.toDouble())).toFloat()
        mMVPMatrix[9] += sin(radius.toDouble()).toFloat()
        mMVPMatrix[10] *= cos(radius.toDouble()).toFloat()
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")

        mBrightnessHandle = GLES20.glGetUniformLocation(mProgram, "uBrightness")
        mContrastHandle = GLES20.glGetUniformLocation(mProgram, "uContrast")
        mSaturationHandle = GLES20.glGetUniformLocation(mProgram, "uSaturation")
        mHueHandle = GLES20.glGetUniformLocation(mProgram, "uHue")
        mGammaHandle = GLES20.glGetUniformLocation(mProgram, "uGamma")
        mSharpnessHandle = GLES20.glGetUniformLocation(mProgram, "uSharpness")
        mTexelSizeHandle = GLES20.glGetUniformLocation(mProgram, "uTexelSize")
        mZoomHandle = GLES20.glGetUniformLocation(mProgram, "uZoom")
        mPanHandle = GLES20.glGetUniformLocation(mProgram, "uPan")
        mCropZoomHandle = GLES20.glGetUniformLocation(mProgram, "uCropZoom")
        Log.i("CaptureRender", "init: program=$mProgram brightness=$mBrightnessHandle contrast=$mContrastHandle sat=$mSaturationHandle hue=$mHueHandle gamma=$mGammaHandle sharp=$mSharpnessHandle texel=$mTexelSizeHandle")
    }

    override fun setSize(width: Int, height: Int) {
        super.setSize(width, height)
        if (width > 0 && height > 0) {
            texelWidth = 1.0f / width
            texelHeight = 1.0f / height
        }
    }

    override fun beforeDraw() {
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mMVPMatrix, 0)

        if (mBrightnessHandle >= 0) GLES20.glUniform1f(mBrightnessHandle, brightness)
        if (mContrastHandle >= 0) GLES20.glUniform1f(mContrastHandle, contrast)
        if (mSaturationHandle >= 0) GLES20.glUniform1f(mSaturationHandle, saturation)
        if (mHueHandle >= 0) GLES20.glUniform1f(mHueHandle, hue)
        if (mGammaHandle >= 0) GLES20.glUniform1f(mGammaHandle, gamma)
        if (mSharpnessHandle >= 0) GLES20.glUniform1f(mSharpnessHandle, sharpness)
        if (mTexelSizeHandle >= 0) GLES20.glUniform2f(mTexelSizeHandle, texelWidth, texelHeight)
        if (mZoomHandle >= 0) GLES20.glUniform1f(mZoomHandle, zoom)
        if (mPanHandle >= 0) GLES20.glUniform2f(mPanHandle, panX, panY)
        if (mCropZoomHandle >= 0) GLES20.glUniform2f(mCropZoomHandle, cropZoomX, cropZoomY)

        if (!loggedOnce && brightness != 1.0f) {
            Log.i("CaptureRender", "beforeDraw: b=$brightness c=$contrast s=$saturation h=$hue g=$gamma sh=$sharpness texel=${texelWidth}x${texelHeight}")
            loggedOnce = true
        }
    }

    override fun getVertexSourceId(): Int = R.raw.capture_vertex

    override fun getFragmentSourceId(): Int = R.raw.base_fragment
}
