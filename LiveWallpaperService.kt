package com.example.livewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveEngine()

    private inner class LiveEngine : Engine() {
        private val ctx: Context get() = this@LiveWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private val prefs = Store.prefs(this@LiveWallpaperService)
        private val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fadeSteps = 12

        private var items: List<File> = emptyList()
        private var index = 0
        private var visible = false
        private var surfW = 0
        private var surfH = 0
        private var current: Bitmap? = null
        private var fadeFrom: Bitmap? = null
        private var fadeStep = 0
        private var player: MediaPlayer? = null
        private var ready = false

        private val nextRun = Runnable { advance() }

        private val fadeTick = object : Runnable {
            override fun run() {
                val to = current ?: return
                fadeStep++
                drawFrame(fadeFrom, to, minOf(255, fadeStep * 255 / fadeSteps))
                if (fadeStep < fadeSteps) handler.postDelayed(this, 33) else fadeFrom = null
            }
        }

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Store.KEY_PAUSE) update() else reload()
        }

        private val saverReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = update()
        }

        // ---------- lifecycle ----------
        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            items = Store.list(ctx)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(saverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(saverReceiver, filter)
            }
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            ctx.unregisterReceiver(saverReceiver)
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfW = width; surfH = height
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
            current = null; fadeFrom = null
            update()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
            current = null; fadeFrom = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            update()
        }

        // ---------- pause / resume logic ----------
        private fun saverActive() =
            prefs.getBoolean(Store.KEY_PAUSE, true) && pm.isPowerSaveMode

        private fun running() = visible && !saverActive()

        private fun update() {
            if (!visible) { pausePlayback(); return }
            if (saverActive()) {
                pausePlayback()
                // Keep a still frame on screen while paused.
                if (current == null && player == null) show()
                return
            }
            resume()
        }

        private fun pausePlayback() {
            handler.removeCallbacks(nextRun)
            handler.removeCallbacks(fadeTick)
            runCatching { player?.pause() }
        }

        private fun resume() {
            if (items.isEmpty()) { drawMessage(); return }
            val p = player
            if (p != null) { if (ready) runCatching { p.start() }; return }
            val cur = current
            if (cur != null) {
                drawFrame(null, cur, 255)
                if (items.size > 1) schedule()
            } else show()
        }

        private fun reload() {
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
            items = Store.list(ctx)
            index = 0; current = null; fadeFrom = null
            update()
        }

        // ---------- playlist ----------
        private fun interval() = prefs.getInt(Store.KEY_INTERVAL, 10) * 1000L

        private fun schedule() {
            handler.removeCallbacks(nextRun)
            handler.postDelayed(nextRun, interval())
        }

        private fun advance() {
            if (items.isEmpty() || !visible) return
            index = (index + 1) % items.size
            show()
        }

        private fun skip() { if (items.size > 1) handler.postDelayed(nextRun, 1000) }

        private fun show() {
            if (!visible || surfW == 0) return
            handler.removeCallbacks(nextRun)
            releasePlayer()
            if (items.isEmpty()) { drawMessage(); return }
            if (index >= items.size) index = 0
            val f = items[index]
            if (Store.isVideo(f)) playVideo(f) else showImage(f)
        }

        // ---------- photos ----------
        private fun showImage(f: File) {
            val bmp = decode(f) ?: run { skip(); return }
            val old = current
            current = bmp
            handler.removeCallbacks(fadeTick)
            fadeFrom = old; fadeStep = 0
            if (old == null) drawFrame(null, bmp, 255) else handler.post(fadeTick)
            if (items.size > 1 && running()) schedule()
        }

        private fun decode(f: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return null
            var s = 1
            while (bounds.outWidth / (s * 2) >= surfW && bounds.outHeight / (s * 2) >= surfH) s *= 2
            var src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                ?: return null
            val rot = try {
                when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) { 0f }
            if (rot != 0f) {
                src = Bitmap.createBitmap(src, 0, 0, src.width, src.height,
                    Matrix().apply { postRotate(rot) }, true)
            }
            // Center-crop to the screen.
            val scale = maxOf(surfW / src.width.toFloat(), surfH / src.height.toFloat())
            val out = Bitmap.createBitmap(surfW, surfH, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                postScale(scale, scale)
                postTranslate((surfW - src.width * scale) / 2f, (surfH - src.height * scale) / 2f)
            }
            Canvas(out).drawBitmap(src, m, paint)
            src.recycle()
            return out
        }

        // ---------- videos ----------
        private fun playVideo(f: File) {
            ready = false
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setSurface(surfaceHolder.surface)
                mp.setDataSource(f.path)
                mp.setVolume(0f, 0f) // wallpapers stay silent
                mp.isLooping = items.size == 1
                mp.setOnPreparedListener {
                    ready = true
                    it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    if (running()) it.start() else it.seekTo(0) // still first frame when paused
                }
                mp.setOnCompletionListener { advance() }
                mp.setOnErrorListener { _, _, _ -> skip(); true }
                mp.prepareAsync()
            } catch (e: Exception) {
                skip()
            }
        }

        private fun releasePlayer() {
            ready = false
            player?.let { runCatching { it.release() } }
            player = null
        }

        // ---------- drawing ----------
        private fun withCanvas(block: (Canvas) -> Unit) {
            val holder = surfaceHolder
            val c = (try { holder.lockCanvas() } catch (e: Exception) { null }) ?: return
            try { block(c) } finally { holder.unlockCanvasAndPost(c) }
        }

        private fun drawFrame(from: Bitmap?, to: Bitmap, alpha: Int) = withCanvas { c ->
            if (from != null) { paint.alpha = 255; c.drawBitmap(from, 0f, 0f, paint) }
            else c.drawColor(Color.BLACK)
            paint.alpha = alpha
            c.drawBitmap(to, 0f, 0f, paint)
            paint.alpha = 255
        }

        private fun drawMessage() = withCanvas { c ->
            c.drawColor(Color.BLACK)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY; textSize = 42f; textAlign = Paint.Align.CENTER
            }
            c.drawText("Open LiveWall and add photos or videos", c.width / 2f, c.height / 2f, p)
        }
    }
}
