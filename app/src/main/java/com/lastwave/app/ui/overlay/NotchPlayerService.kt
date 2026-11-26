package com.lastwave.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.lastwave.app.MainActivity
import com.lastwave.app.widget.ActiveMediaSessionHolder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

object NotchPlayerPrefs {
    private const val FILE = "notch_player_prefs"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }
}

/**
 * Liquid-glass "notch player": a Dynamic Island style pill floating just
 * below the camera cutout whenever Nexora holds a media session. Collapsed
 * it is a small glass pill with artwork, live sound bars and a progress
 * edge; tap to expand into a full glass card with seek and transport
 * controls. Swipe the pill up to hide it until the next track.
 */
class NotchPlayerService : Service() {

    companion object {
        @Volatile
        var running = false
            private set

        fun maybeStart(context: Context) {
            if (running) return
            if (!NotchPlayerPrefs.enabled(context)) return
            if (!android.provider.Settings.canDrawOverlays(context)) return
            runCatching { context.startService(Intent(context, NotchPlayerService::class.java)) }
        }

        fun maybeStop(context: Context) {
            if (!running) return
            runCatching { context.stopService(Intent(context, NotchPlayerService::class.java)) }
        }
    }

    private var controller: MediaController? = null
    private var view: NotchPillView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var added = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            pushState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pushState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        connect(0)
    }

    private fun connect(tryCount: Int) {
        val c = ActiveMediaSessionHolder.controller ?: ActiveMediaSessionHolder.ownToken?.let { token ->
            runCatching { MediaController(this, token) }.getOrNull()
        }
        if (c != null) {
            controller = c
            runCatching { c.registerCallback(callback, handler) }
            view = NotchPillView(this, c, ::hidePill)
            pushState()
        } else if (tryCount < 10) {
            handler.postDelayed({ connect(tryCount + 1) }, 500)
        } else {
            stopSelf()
        }
    }

    private fun pushState() {
        val c = controller ?: return
        val v = view ?: return
        val md = c.metadata
        val ps = c.playbackState
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (md == null || duration <= 0L) {
            removePill()
            return
        }
        val art = runCatching { md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) }.getOrNull()
        v.applyState(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            posBaseMs = ps?.position ?: 0L,
            posAtMs = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = if (ps != null && ps.playbackSpeed > 0f) ps.playbackSpeed else 1f,
            durationMs = duration,
            art = art,
        )
        addPill()
    }

    private fun addPill() {
        if (added || view == null) return
        if (params == null) {
            val density = resources.displayMetrics.density
            val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBar = if (sbId > 0) resources.getDimensionPixelSize(sbId) else (24 * density).toInt()
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBar + (6 * density).toInt()
            }
        }
        runCatching {
            wm?.addView(view, params)
            added = true
        }
    }

    private fun removePill() {
        if (added) runCatching { wm?.removeView(view) }
        added = false
    }

    private fun hidePill() {
        removePill()
        view?.sessionHidden = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { controller?.unregisterCallback(callback) }
        controller = null
        removePill()
        view = null
        params = null
        super.onDestroy()
    }
}

@SuppressLint("ClickableViewAccessibility")
private class NotchPillView(
    context: Context,
    private var ctrl: MediaController?,
    private val onHide: () -> Unit,
) : View(context) {

    var sessionHidden = false

    private var title = ""
    private var artist = ""
    private var playing = false
    private var posBaseMs = 0L
    private var posAtMs = SystemClock.elapsedRealtime()
    private var speed = 1f
    private var durationMs = 0L
    private var art: Bitmap? = null

    private val density = resources.displayMetrics.density
    private val collapsedW = (118 * density).toInt()
    private val collapsedH = (30 * density).toInt()
    private val expandedH = (162 * density).toInt()
    private val expandedW: Int
        get() = min(
            resources.displayMetrics.widthPixels - (16 * density).toInt(),
            (386 * density).toInt(),
        )

    private var expandT = 0f
    private var expanded = false

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
        duration = 950
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    private var expandAnim: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shape = android.graphics.Path()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 15f * density
    }
    private val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF
        textSize = 11.5f * density
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8CFFFFFF.toInt()
        textSize = 9.5f * density
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0x33FFFFFF
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x99FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (collapsedW + (expandedW - collapsedW) * expandT).toInt()
        val h = (collapsedH + (expandedH - collapsedH) * expandT).toInt()
        setMeasuredDimension(w, h)
    }

    fun applyState(
        title: String,
        artist: String,
        playing: Boolean,
        posBaseMs: Long,
        posAtMs: Long,
        speed: Float,
        durationMs: Long,
        art: Bitmap?,
    ) {
        val newTrack = title != this.title
        this.title = title
        this.artist = artist
        this.playing = playing
        this.posBaseMs = posBaseMs
        this.posAtMs = posAtMs
        this.speed = speed
        this.durationMs = durationMs
        if (art != this.art) {
            this.art = art
            artPaint.shader = art?.let {
                val shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val m = Matrix()
                val s = 1f / min(it.width, it.height).coerceAtLeast(1)
                m.setScale(s, s)
                shader.setLocalMatrix(m)
                shader
            }
        }
        if (newTrack && sessionHidden) {
            sessionHidden = false
        }
        invalidate()
    }

    private fun currentPosMs(): Long {
        if (!playing || posBaseMs <= 0L) return posBaseMs
        val dt = (SystemClock.elapsedRealtime() - posAtMs) * speed
        return posBaseMs + dt.toLong()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = if (expanded) 26f * density else h / 2f
        val alphaTop = if (expanded) 0x6E else 0x5C
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(alphaTop, 54, 62, 70),
                Color.argb(if (expanded) 0xE8 else 0xD8, 9, 11, 15),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            -w / 4f, 0f, w, h,
            intArrayOf(0xB3FFFFFF, 0x14FFFFFF, 0x4DFFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            0f, 0f, w * 0.7f, h,
            intArrayOf(0x26FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        shape.reset()
        shape.addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(0f, 0f, w, h, fill)
        canvas.drawRect(0f, 0f, w, h, sheen)
        canvas.restore()
        canvas.drawPath(shape, rim)
        if (expanded) drawExpanded(canvas, w, h) else drawCollapsed(canvas, w, h)
    }

    private fun drawCollapsed(canvas: Canvas, w: Float, h: Float) {
        val r = (h - 8f * density) / 2f
        val cx = 4f * density + r
        val cy = h / 2f
        drawArt(canvas, cx, cy, r)
        bar.color = 0xCCFFFFFF.toInt()
        var bx = cx + r + 5f * density
        val base = 4f * density
        val maxH = h - 10f * density
        for (i in 0 until 4) {
            val amp = if (playing) 0.5f + 0.5f * abs(sin(phase + i * 0.85f)) else 0.12f
            val bh = base + (maxH - base) * amp
            canvas.drawRoundRect(
                RectF(bx, cy - bh / 2f, bx + 2.6f * density, cy + bh / 2f),
                1.3f * density, 1.3f * density, bar,
            )
            bx += 5f * density
        }
        if (durationMs > 0) {
            track.color = 0x24FFFFFF
            canvas.drawRoundRect(RectF(0f, h - 1.6f * density, w, h), 1f, 1f, track)
            val frac = (currentPosMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            progress.color = 0xE6C6F53F.toInt()
            canvas.drawRoundRect(RectF(0f, h - 1.6f * density, w * frac, h), 1f, 1f, progress)
        }
    }

    private fun drawArt(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (artPaint.shader != null && art != null) {
            canvas.drawCircle(cx, cy, r, artPaint)
            canvas.drawCircle(cx, cy, r, ring)
        } else {
            disc.shader = RadialGradient(
                cx - r / 3f, cy - r / 3f, r * 1.4f,
                intArrayOf(0xFF7CE0A3.toInt(), 0xFF2B8A57.toInt(), 0xFF173A2B.toInt()),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, disc)
            canvas.drawText("\u266A", cx, cy + 4.4f * density, note)
        }
    }

    private fun drawExpanded(canvas: Canvas, w: Float, h: Float) {
        val pad = 18f * density
        val artR = 28f * density
        drawArt(canvas, pad + artR, 20f * density + artR, artR)
        val cxc = w - 22f * density
        val cyc = 26f * density
        val arm = 5f * density
        canvas.drawLine(cxc - arm, cyc - arm, cxc + arm, cyc + arm, closePaint)
        canvas.drawLine(cxc - arm, cyc + arm, cxc + arm, cyc - arm, closePaint)
        val tx = pad + artR * 2 + 12f * density
        val avail = w - tx - 30f * density
        if (avail > 0) {
            val t = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(t, tx, 36f * density, titlePaint)
            val a = TextUtils.ellipsize(artist, artistPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(a, tx, 56f * density, artistPaint)
        }
        val sy = 84f * density
        val sx0 = 24f * density
        val sx1 = w - 24f * density
        track.color = 0x24FFFFFF
        canvas.drawRoundRect(RectF(sx0, sy, sx1, sy + 4f * density), 2f * density, 2f * density, track)
        val frac = if (durationMs > 0) (currentPosMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        progress.color = 0xE6C6F53F.toInt()
        canvas.drawRoundRect(
            RectF(sx0, sy, sx0 + (sx1 - sx0) * frac, sy + 4f * density),
            2f * density, 2f * density, progress,
        )
        thumb.color = Color.WHITE
        canvas.drawCircle(sx0 + (sx1 - sx0) * frac, sy + 2f * density, 4.5f * density, thumb)
        val cur = fmt(currentPosMs())
        val tot = fmt(durationMs)
        canvas.drawText(cur, sx0, sy + 18f * density, timePaint)
        canvas.drawText(tot, sx1 - timePaint.measureText(tot), sy + 18f * density, timePaint)
        val by = h - 34f * density
        val cxp = w / 2f
        drawSkip(canvas, cxp - w * 0.22f, by, forward = false)
        val pr = 21f * density
        glyph.color = 0x1FFFFFFF
        canvas.drawCircle(cxp, by, pr, glyph)
        glyph.color = Color.WHITE
        if (playing) {
            val bw = 3.4f * density
            val bh2 = 13f * density
            canvas.drawRoundRect(RectF(cxp - 4.5f * density, by - bh2 / 2, cxp - 1.1f * density, by + bh2 / 2), 1.6f * density, 1.6f * density, glyph)
            canvas.drawRoundRect(RectF(cxp + 1.1f * density, by - bh2 / 2, cxp + 4.5f * density, by + bh2 / 2), 1.6f * density, 1.6f * density, glyph)
        } else {
            val path = android.graphics.Path()
            path.moveTo(cxp - 4.5f * density, by - 8f * density)
            path.lineTo(cxp + 7.5f * density, by)
            path.lineTo(cxp - 4.5f * density, by + 8f * density)
            path.close()
            canvas.drawPath(path, glyph)
        }
        drawSkip(canvas, cxp + w * 0.22f, by, forward = true)
    }

    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, forward: Boolean) {
        glyph.color = 0xE6FFFFFF.toInt()
        val s = 4.5f * density
        val hgt = 9f * density
        val path = android.graphics.Path()
        if (forward) {
            path.moveTo(cx - s, cy - hgt)
            path.lineTo(cx + s, cy)
            path.lineTo(cx - s, cy + hgt)
        } else {
            path.moveTo(cx + s, cy - hgt)
            path.lineTo(cx - s, cy)
            path.lineTo(cx + s, cy + hgt)
        }
        path.close()
        canvas.drawPath(path, glyph)
        glyph.color = Color.WHITE
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun setExpanded(target: Boolean) {
        expanded = target
        expandAnim?.cancel()
        expandAnim = ValueAnimator.ofFloat(expandT, if (target) 1f else 0f).apply {
            duration = 260
            addUpdateListener {
                expandT = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = ctrl ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (expanded) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val x = event.x
                    val y = event.y
                    val d = density
                    if (abs(x - (w - 22 * d)) < 16 * d && abs(y - 26 * d) < 16 * d) {
                        setExpanded(false)
                    } else if (y in (78 * d)..(94 * d) && durationMs > 0) {
                        val frac = ((x - 24 * d) / (w - 48 * d)).coerceIn(0f, 1f)
                        c.transportControls.seekTo((frac * durationMs).toLong())
                    } else if (dist(x, y, w / 2f, h - 34 * d) < 26 * d) {
                        if (playing) c.transportControls.pause() else c.transportControls.play()
                    } else if (dist(x, y, w / 2f - w * 0.22f, h - 34 * d) < 22 * d) {
                        c.transportControls.skipToPrevious()
                    } else if (dist(x, y, w / 2f + w * 0.22f, h - 34 * d) < 22 * d) {
                        c.transportControls.skipToNext()
                    } else if (dist(x, y, 46 * d, 48 * d) < 34 * d) {
                        openApp()
                    }
                } else {
                    setExpanded(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!expanded && event.y < -44 * density) {
                    onHide()
                    return true
                }
            }
        }
        return true
    }

    private fun dist(x: Float, y: Float, cx: Float, cy: Float): Float =
        kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))

    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        expandAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
