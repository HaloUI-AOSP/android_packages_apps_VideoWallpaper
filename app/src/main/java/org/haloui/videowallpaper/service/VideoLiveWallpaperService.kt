/*
 * SPDX-FileCopyrightText: 2026 haloUI
 * SPDX-License-Identifier: Apache-2.0
 */

package org.haloui.videowallpaper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoLiveWallpaperService : WallpaperService() {

    internal inner class VideoEngine : Engine() {
        private var mediaPlayer: MediaPlayer? = null
        private var isPrepared = false
        private var isPreparing = false
        private var engineVisible = false
        private var lastPosition = 0
        private var glThread: HandlerThread? = null
        private var glHandler: Handler? = null
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var oesTextureId = 0
        private var programId = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uSTMatrixLoc = 0

        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var videoWidth = 1
        private var videoHeight = 1
        private var scaledWidth = 1
        private var scaledHeight = 1
        private var panX = 0.5f
        private var panY = 0.5f
        private var zoom = 1f

        private val scaleDetector = ScaleGestureDetector(
            this@VideoLiveWallpaperService,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                    return true
                }
            }
        )

        private val panDetector = GestureDetector(
            this@VideoLiveWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    val rangeX = scaledWidth * zoom - surfaceWidth
                    val rangeY = scaledHeight * zoom - surfaceHeight
                    if (rangeX > 0) panX = (panX + dx / rangeX).coerceIn(0f, 1f)
                    if (rangeY > 0) panY = (panY - dy / rangeY).coerceIn(0f, 1f)
                    return true
                }
            }
        )

        private val stMatrix = FloatArray(16)
        private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 0f,
                        1f, -1f, 1f, 0f,
                        -1f, 1f, 0f, 1f,
                        1f, 1f, 1f, 1f
                    )
                )
                position(0)
            }

        private val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                glHandler?.post {
                    when (intent.action) {
                        Intent.ACTION_USER_BACKGROUND -> {
                            if (isPrepared) {
                                lastPosition = mediaPlayer?.currentPosition ?: lastPosition
                            }
                            releasePlayer()
                        }
                        Intent.ACTION_USER_FOREGROUND -> {
                            val pos = if (isPrepared) mediaPlayer?.currentPosition ?: lastPosition else lastPosition
                            initPlayer(pos)
                        }
                        Intent.ACTION_USER_UNLOCKED,
                        Intent.ACTION_USER_PRESENT -> {
                            if (mediaPlayer == null || (!isPrepared && !isPreparing)) {
                                initPlayer(lastPosition)
                            } else if (isPrepared && (engineVisible || isVisible)) {
                                runCatching {
                                    if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
                                    drawFrame()
                                }.onFailure {
                                    initPlayer(lastPosition)
                                }
                            }
                        }
                        else -> {
                            if (intent.getBooleanExtra(KEY_RELOAD, false)) {
                                lastPosition = 0
                                loadTransform()
                                initPlayer(0)
                            } else {
                                val pos = if (isPrepared) mediaPlayer?.currentPosition ?: lastPosition else lastPosition
                                initPlayer(pos)
                            }
                        }
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(isPreview)
            loadTransform()
            glThread = HandlerThread("WallpaperGL").apply { start() }
            glHandler = Handler(glThread!!.looper)

            val filter = IntentFilter(VIDEO_PARAMS_CONTROL_ACTION).apply {
                addAction(Intent.ACTION_USER_BACKGROUND)
                addAction(Intent.ACTION_USER_FOREGROUND)
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(broadcastReceiver, filter)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glHandler?.post {
                initGL(holder.surface)
                initPlayer(lastPosition)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glHandler?.post {
                surfaceWidth = width.coerceAtLeast(1)
                surfaceHeight = height.coerceAtLeast(1)
                updateDimensions()
                drawFrame()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (!isPreview) return
            scaleDetector.onTouchEvent(event)
            panDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                saveTransform(this@VideoLiveWallpaperService, panX, panY, zoom)
                glHandler?.post { drawFrame() }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            glHandler?.post {
                engineVisible = visible
                if (visible) {
                    loadTransform()
                    val player = mediaPlayer
                    if (player == null || (!isPrepared && !isPreparing)) {
                        initPlayer(lastPosition)
                    } else if (isPrepared) {
                        runCatching {
                            if (!player.isPlaying) player.start()
                            drawFrame()
                        }.onFailure {
                            initPlayer(lastPosition)
                        }
                    }
                } else {
                    val player = mediaPlayer ?: return@post
                    if (isPrepared && player.isPlaying) {
                        lastPosition = player.currentPosition
                        player.pause()
                    }
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glHandler?.post {
                if (isPrepared) {
                    lastPosition = mediaPlayer?.currentPosition ?: lastPosition
                }
                releasePlayer()
                releaseGL()
            }
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (_: IllegalArgumentException) {
            }
            glHandler?.post {
                releasePlayer()
                releaseGL()
            }
            glThread?.quitSafely()
            glThread = null
            glHandler = null
            super.onDestroy()
        }

        private fun updateDimensions() {
            val vRatio = videoWidth.toFloat() / videoHeight.toFloat().coerceAtLeast(1f)
            val sRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat().coerceAtLeast(1f)
            if (vRatio > sRatio) {
                scaledHeight = surfaceHeight
                scaledWidth = (surfaceHeight * vRatio).toInt().coerceAtLeast(surfaceWidth)
            } else {
                scaledWidth = surfaceWidth
                scaledHeight = (surfaceWidth / vRatio).toInt().coerceAtLeast(surfaceHeight)
            }
        }

        private fun initPlayer(seekMs: Int) {
            releasePlayer()
            val outSurface = decoderSurface ?: return
            val deContext = createDeviceProtectedStorageContext()
            val videoFile = File(deContext.filesDir, "wallpaper.mp4")
            if (!videoFile.exists() || videoFile.length() == 0L) return

            val unmuted = File(deContext.filesDir, "unmute").exists()
            loadTransform()

            mediaPlayer = MediaPlayer().apply {
                try {
                    setSurface(outSurface)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                if (unmuted) AudioAttributes.USAGE_MEDIA
                                else AudioAttributes.USAGE_ALARM
                            )
                            .setContentType(
                                if (unmuted) AudioAttributes.CONTENT_TYPE_MOVIE
                                else AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .setAllowedCapturePolicy(
                                if (unmuted) AudioAttributes.ALLOW_CAPTURE_BY_ALL
                                else AudioAttributes.ALLOW_CAPTURE_BY_NONE
                            )
                            .build()
                    )
                    FileInputStream(videoFile).use { setDataSource(it.fd) }
                    isLooping = true
                    val volume = if (unmuted) 1f else 0f
                    setVolume(volume, volume)
                    setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) {
                            this@VideoEngine.videoWidth = w
                            this@VideoEngine.videoHeight = h
                            updateDimensions()
                            drawFrame()
                        }
                    }
                    setOnPreparedListener { mp ->
                        isPreparing = false
                        isPrepared = true
                        mp.setVolume(volume, volume)
                        if (!unmuted) {
                            runCatching {
                                mp.trackInfo?.forEachIndexed { index, info ->
                                    if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                                        mp.deselectTrack(index)
                                    }
                                }
                            }
                        }
                        if (seekMs > 0) mp.seekTo(seekMs)
                        if (engineVisible || isVisible) mp.start()
                    }
                    setOnErrorListener { _, _, _ ->
                        releasePlayer()
                        true
                    }
                    isPreparing = true
                    prepareAsync()
                } catch (_: Exception) {
                    releasePlayer()
                }
            }
        }

        private fun releasePlayer() {
            isPreparing = false
            isPrepared = false
            mediaPlayer?.run {
                runCatching { reset() }
                runCatching { release() }
            }
            mediaPlayer = null
        }

        private fun initGL(nativeSurface: Surface) {
            releaseGL()
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, nativeSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
                GLES20.glShaderSource(it, VERTEX_SHADER)
                GLES20.glCompileShader(it)
            }
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
                GLES20.glShaderSource(it, FRAGMENT_SHADER)
                GLES20.glCompileShader(it)
            }
            programId = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uSTMatrixLoc = GLES20.glGetUniformLocation(programId, "uSTMatrix")

            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener({ drawFrame() }, glHandler)
            }
            decoderSurface = Surface(surfaceTexture)
        }

        private fun drawFrame() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            val st = surfaceTexture ?: return
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)

                val finalW = (scaledWidth * zoom).toInt()
                val finalH = (scaledHeight * zoom).toInt()
                val offsetX = -((finalW - surfaceWidth) * panX.coerceIn(0f, 1f)).toInt()
                val offsetY = -((finalH - surfaceHeight) * panY.coerceIn(0f, 1f)).toInt()
                GLES20.glViewport(offsetX, offsetY, finalW, finalH)

                GLES20.glUseProgram(programId)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

                quadBuffer.position(0)
                GLES20.glEnableVertexAttribArray(aPositionLoc)
                GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

                quadBuffer.position(2)
                GLES20.glEnableVertexAttribArray(aTexCoordLoc)
                GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

                GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        }

        private fun loadTransform() {
            val prefs = createDeviceProtectedStorageContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
            panX = prefs.getFloat("pan_x", 0.5f)
            panY = prefs.getFloat("pan_y", 0.5f)
            zoom = prefs.getFloat("zoom", 1f)
        }

        private fun releaseGL() {
            decoderSurface?.release()
            decoderSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
    }

    override fun onCreateEngine(): Engine = VideoEngine()

    companion object {
        const val VIDEO_PARAMS_CONTROL_ACTION = "org.haloui.videowallpaper"
        private const val KEY_ACTION = "music"
        private const val KEY_RELOAD = "reload"
        private const val ACTION_MUSIC_UNMUTE = false
        private const val ACTION_MUSIC_MUTE = true

        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uSTMatrix * aTexCoord).xy;\n" +
            "}\n"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n"

        fun saveTransform(context: Context, panX: Float, panY: Float, zoom: Float) {
            val deContext = context.createDeviceProtectedStorageContext()
            deContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit()
                .putFloat("pan_x", panX.coerceIn(0f, 1f))
                .putFloat("pan_y", panY.coerceIn(0f, 1f))
                .putFloat("zoom", zoom.coerceIn(1f, 4f))
                .apply()
        }

        fun resetTransform(context: Context) {
            saveTransform(context, 0.5f, 0.5f, 1f)
        }

        fun muteMusic(context: Context) {
            Intent(VIDEO_PARAMS_CONTROL_ACTION).apply {
                setPackage(context.packageName)
                putExtra(KEY_ACTION, ACTION_MUSIC_MUTE)
            }.also { context.sendBroadcast(it) }
        }

        fun unmuteMusic(context: Context) {
            Intent(VIDEO_PARAMS_CONTROL_ACTION).apply {
                setPackage(context.packageName)
                putExtra(KEY_ACTION, ACTION_MUSIC_UNMUTE)
            }.also { context.sendBroadcast(it) }
        }

        fun reload(context: Context) {
            Intent(VIDEO_PARAMS_CONTROL_ACTION).apply {
                setPackage(context.packageName)
                putExtra(KEY_RELOAD, true)
            }.also { context.sendBroadcast(it) }
        }
    }
}
