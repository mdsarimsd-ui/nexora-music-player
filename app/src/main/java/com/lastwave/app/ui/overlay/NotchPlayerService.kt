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
import android.graphics.Rect
import android.os.Build
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object NotchPlayerPrefs {
    private const val FILE = "notch_player_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MIGRATED_OFF = "migrated_off_v5710"
    private const val KEY_OFFSET_X = "offset_x"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_SCALE = "scale_pct"
    private const val KEY_ANIM_STYLE = "anim_style"

    fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // v5.7.10: the notch player ships OFF. One-time reset so installs that
        // had it on from v5.7.9 come up without it; the Settings toggle can
        // still turn it back on.
        if (!prefs.getBoolean(KEY_MIGRATED_OFF, false)) {
            prefs.edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_MIGRATED_OFF, true).apply()
            return false
        }
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Horizontal pill offset in px; 0 = centred on the camera hole. */
    fun offsetX(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_OFFSET_X, 0)

    /** Extra vertical offset in px from the pill's natural status-bar slot. */
    fun offsetY(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_OFFSET_Y, 0)

    /** Pill scale in percent; 100 = the natural notch-pill size. */
    fun scalePct(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SCALE, 100)

    fun setLayout(context: Context, offsetX: Int, offsetY: Int, scalePct: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_OFFSET_X, offsetX)
            .putInt(KEY_OFFSET_Y, offsetY)
            .putInt(KEY_SCALE, scalePct.coerceIn(60, 160))
            .apply()
    }

    /**
     * Collapsed-pill animation style:
     * 0 classic bars, 1 beat bars, 2 progress ring, 3 liquid morph expand.
     */
    fun animStyle(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_ANIM_STYLE, 0)

    fun setAnimStyle(context: Context, style: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ANIM_STYLE, style.coerceIn(0, 3)).apply()
    }
}

/**
 * "Notch player": a Dynamic Island style pill that wraps the camera cutout
 * whenever Nexora holds a media session, sitting in the status-bar row the
 * way the system media pill does. Collapsed it is a near-black capsule with
 * the Nexora icon left of the camera, the track title right of it and a
 * play / pause button at the cap; tap to expand into a glass card with seek
 * and transport controls that drops below the camera. It hides while a
 * Nexora screen is on display, blooms out of the camera with a scale
 * animation and pulses briefly when the track changes. Swipe the pill up to
 * hide it until the next track.
 */
class NotchPlayerService : Service() {

    companion object {
        private const val ACTION_APPLY = "com.lastwave.app.notch.APPLY"
        @Volatile
        var running = false
            private set

        /** Re-apply the saved size / position to a running pill live. */
        fun applyLayout(context: Context) {
            val i = Intent(context, NotchPlayerService::class.java).setAction(ACTION_APPLY)
            runCatching { context.startService(i) }
        }

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
    private var baseY = 0
    private var expandedY = 0
    private var offX = 0
    private var offY = 0
    private var scalePct = 100
    private var natBaseY = 0

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
        // The pill lives outside the app only: it hides while a Nexora
        // screen is on display and slides back in once the app is left.
        com.lastwave.app.util.AppVisibility.visibleListener = {
            handler.post { syncVisibility() }
        }
        connect(0)
    }

    private fun connect(tryCount: Int) {
        val c = ActiveMediaSessionHolder.controller ?: ActiveMediaSessionHolder.ownToken?.let { token ->
            runCatching { MediaController(this, token) }.getOrNull()
        }
        if (c != null) {
            controller = c
            runCatching { c.registerCallback(callback, handler) }
            view = NotchPillView(this, c, ::hidePill, ::shiftWindow, ::moveBy, ::savePosition)
            view?.setAnimStyle(NotchPlayerPrefs.animStyle(this))
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
        if (com.lastwave.app.util.AppVisibility.isVisible) removePill() else addPill()
    }

    private fun addPill() {
        if (added || view == null) return
        // Re-read the pill animation so a style picked in Settings (always
        // while the pill is hidden inside the app) is live the moment the
        // pill returns.
        view?.setAnimStyle(NotchPlayerPrefs.animStyle(this))
        if (params == null) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        layoutPill()
    }

    /**
     * Size and place the pill: island mode (the pill sits IN the status-bar
     * row, centred on the punch-hole camera) plus the user's own size and
     * position, tuned in Settings or by long-press dragging the pill.
     */
    private fun layoutPill() {
        val v = view ?: return
        val density = resources.displayMetrics.density
        var topY = (4 * density).toInt()
        var pillH = NotchPillView.collapsedHeightPx(density)
        var cutR = (13 * density).toInt()
        var expY = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val r = wm?.currentWindowMetrics?.windowInsets?.displayCutout?.boundingRects?.firstOrNull()
                if (r != null && r.height() > 0) {
                    pillH = maxOf(pillH, r.height() + (16 * density).toInt())
                        .coerceAtMost((66 * density).toInt())
                    // A touch lower and taller: the old 46dp pill sat half
                    // inside the status-bar gesture row where HyperOS ate
                    // every tap, and its buttons were too thin to hit.
                    topY = (r.centerY() - pillH / 2 + (6 * density).toInt()).coerceAtLeast(0)
                    cutR = maxOf(cutR, maxOf(r.width(), r.height()) / 2)
                    expY = r.bottom + (8 * density).toInt()
                }
            }
        }
        if (expY == 0) expY = topY + pillH + (6 * density).toInt()

        // User-tuned size and placement (Settings sliders or drag).
        scalePct = NotchPlayerPrefs.scalePct(this).coerceIn(60, 160)
        offX = NotchPlayerPrefs.offsetX(this)
        offY = NotchPlayerPrefs.offsetY(this)
        val scale = scalePct / 100f
        val pillHs = (pillH * scale).toInt().coerceAtLeast((40 * density).toInt())
        // A taller capsule stays centred on the camera hole; the cutout
        // radius itself never scales, the hole is a physical lens.
        val centre = (pillH - pillHs) / 2
        natBaseY = (topY + centre).coerceAtLeast(0)
        baseY = (natBaseY + offY).coerceAtLeast(0)
        expandedY = expY - centre + offY
        val widthS = (minOf(
            (150 * density).toInt() + 2 * (cutR + (6 * density).toInt()),
            (resources.displayMetrics.widthPixels * 0.62f).toInt(),
        ) * scale).toInt().coerceAtLeast((120 * density).toInt())
        v.setCollapsedSize(widthS, pillHs)
        v.setCutoutRadiusPx(cutR)
        v.setScale(scale)
        val p = params ?: return
        p.y = baseY
        p.x = offX
        if (added) {
            runCatching { wm?.updateViewLayout(v, p) }
        } else {
            runCatching {
                wm?.addView(v, p)
                added = true
            }
        }
    }

    /** Live drag from the pill: nudge the window, remembered on release. */
    private fun moveBy(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val scrW = resources.displayMetrics.widthPixels
        offX = (offX + dx).coerceIn(-scrW / 2, scrW / 2)
        offY = (offY + dy).coerceAtLeast(-natBaseY)
        val p = params ?: return
        p.x = offX
        p.y = (baseY + dy).coerceAtLeast(0)
        runCatching { wm?.updateViewLayout(view, p) }
    }

    /** Persist the pill's dragged position; size stays from Settings. */
    private fun savePosition() {
        NotchPlayerPrefs.setLayout(this, offX, offY, scalePct)
    }

    private fun removePill() {
        if (added) runCatching { wm?.removeView(view) }
        added = false
    }

    private fun hidePill() {
        removePill()
        view?.sessionHidden = true
    }

    /** Move the window down when the expanded card opens so it clears the camera. */
    private fun shiftWindow(down: Boolean) {
        val p = params ?: return
        p.y = if (down) expandedY else baseY
        p.x = offX
        runCatching { wm?.updateViewLayout(view, p) }
    }

    /** App-foreground change: hide the pill inside the app, show it outside. */
    private fun syncVisibility() {
        if (com.lastwave.app.util.AppVisibility.isVisible) {
            if (added) removePill()
        } else if (!(view?.sessionHidden ?: false)) {
            pushState()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY) {
            handler.post {
                view?.setAnimStyle(NotchPlayerPrefs.animStyle(this))
                if (added) layoutPill()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        com.lastwave.app.util.AppVisibility.visibleListener = null
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
    private val onShift: (down: Boolean) -> Unit,
    private val onMoveBy: (dx: Int, dy: Int) -> Unit,
    private val onMoveEnd: () -> Unit,
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

    companion object {
        fun collapsedHeightPx(density: Float): Int = (54 * density).toInt()
    }

    private val density = resources.displayMetrics.density
    private var collapsedW = min(
        (resources.displayMetrics.widthPixels * 0.55f).toInt(),
        (240 * density).toInt(),
    )
    private var collapsedH = (54 * density).toInt()
    // Camera-hole radius in px (service-provided); content never crosses it.
    private var camR = 13f * density
    private val expandedH = (210 * density).toInt()
    private val expandedW: Int
        get() = min(
            resources.displayMetrics.widthPixels - (16 * density).toInt(),
            (386 * density).toInt(),
        )

    private var expandT = 0f
    private var expanded = false

    /** Collapsed-pill animation: 0 classic, 1 beat bars, 2 progress ring, 3 liquid morph. */
    private var animStyle = 0
    private var morphing = false

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
        duration = 950
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            marqueeX = if (playing) marqueeX + 1.05f * density else 0f
            invalidate()
        }
        start()
    }

    private var expandAnim: ValueAnimator? = null

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val progRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6C6F53F.toInt()
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private var pulseT = 0f
    private var pulseAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    // Entrance / exit: the bar slides down from under the status bar and
    // fades in when a track starts, and slides back up when dismissed.
    private var appear = 0f
    private var dismissing = false
    private var pressT = 0f
    private var marqueeX = 0f
    private var appearAnim: ValueAnimator? = null

    init {
        appearAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            // The island blooms out of the camera hole: it grows from a dot
            // at the centre with a gentle overshoot, like the system pill.
            duration = 380
            interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener {
                appear = it.animatedValue as Float
                alpha = appear.coerceIn(0f, 1f)
                val sc = 0.22f + 0.78f * appear
                scaleX = sc
                scaleY = sc
            }
            start()
        }
    }

    /** Swipe-away: slide the bar back up, then hand control to the service. */
    fun dismiss(onGone: () -> Unit) {
        if (dismissing) return
        dismissing = true
        appearAnim?.cancel()
        ValueAnimator.ofFloat(appear, 0f).apply {
            duration = 240
            addUpdateListener {
                appear = it.animatedValue as Float
                alpha = appear.coerceIn(0f, 1f)
                val sc = 0.22f + 0.78f * appear
                scaleX = sc
                scaleY = sc
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = onGone()
            })
            start()
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shape = android.graphics.Path()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
    private val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 15f * density
    }
    private val artistPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
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
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
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
        if (newTrack) pulse()
        invalidate()
    }

    /** Camera-hole radius: text never crosses it, so the island hugs the lens. */
    fun setCutoutRadiusPx(r: Int) {
        camR = maxOf(11f * density, r.toFloat())
    }

    fun setCollapsedSize(wPx: Int, hPx: Int) {
        collapsedW = wPx.coerceAtLeast((120 * density).toInt())
        collapsedH = hPx
        requestLayout()
    }

    /** Pick the pill's collapsed animation: 0 classic, 1 beat bars, 2 ring, 3 liquid morph. */
    fun setAnimStyle(style: Int) {
        animStyle = style.coerceIn(0, 3)
        invalidate()
    }

    /** User-tuned pill scale; text sizes follow it, the camera hole does not. */
    var scaleF = 1f
        private set

    fun setScale(s: Float) {
        scaleF = s.coerceIn(0.6f, 1.6f)
        requestLayout()
        invalidate()
    }

    /** Quick white halo pulse when the track changes. */
    private fun pulse() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { p ->
                val t = p.animatedValue as Float
                pulseT = 1f - kotlin.math.abs(2f * t - 1f)
                invalidate()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressT, target).apply {
            duration = if (target > pressT) 90 else 170
            addUpdateListener { pressT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun currentPosMs(): Long {
        if (!playing || posBaseMs <= 0L) return posBaseMs
        val dt = (SystemClock.elapsedRealtime() - posAtMs) * speed
        return posBaseMs + dt.toLong()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = (26f + 8f * expandT) * density
        // Liquid-morph style: while the card opens or closes, the whole pill
        // is revealed through a growing circle centred on the camera hole,
        // so the island melts out of the lens instead of popping in.
        val morph = animStyle == 3 && morphing
        if (morph) {
            val maxR = maxOf(w, expandedH.toFloat()) * 1.1f + 20f * density
            val cr = camR + (maxR - camR) * expandT
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply {
                addCircle(w / 2f, h / 2f, cr, android.graphics.Path.Direction.CW)
            })
        }
        // Collapsed island: near-black like the system media pill, so it
        // reads as one solid capsule wrapping the camera. Expanded it keeps
        // the liquid-glass frosted card with a moving sheen and hairline rim.
        fill.shader = if (expanded) {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.argb(0x66, 168, 184, 202),
                    Color.argb(0xE6, 10, 12, 16),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.argb(0xF2, 13, 13, 16),
                    Color.argb(0xF2, 20, 20, 24),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        rim.shader = LinearGradient(
            -w / 4f, 0f, w, h,
            intArrayOf(0xB3FFFFFF.toInt(), 0x14FFFFFF, 0x4DFFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            w * (0.15f + 0.25f * sin(phase)), 0f, w * (0.55f + 0.25f * sin(phase)), h,
            intArrayOf(0x2EFFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        shape.reset()
        shape.addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW)
        val clipShape = android.graphics.Path()
        clipShape.addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipShape)
        canvas.drawRect(0f, 0f, w, h, fill)
        canvas.drawRect(0f, 0f, w, h, sheen)
        canvas.restore()
        canvas.drawPath(shape, rim)
        if (!expanded && pulseT > 0f) {
            halo.alpha = (0x55 * pulseT).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(
                RectF(-2.5f * density, -2.5f * density, w + 2.5f * density, h + 2.5f * density),
                r + 2.5f * density, r + 2.5f * density, halo,
            )
        }
        if (expanded) drawExpanded(canvas, w, h) else drawCollapsed(canvas, w, h)
        if (morph) canvas.restore()
    }

    private fun drawCollapsed(canvas: Canvas, w: Float, h: Float) {
        // Reference capsule: album art and title / artist sit left of the
        // camera, a pause / play button sits at the right cap. Tap the rest
        // to expand into the card below; long-press and drag to move it.
        val pad = 7f * density
        val artS = h - 2 * pad
        drawArtRect(canvas, pad, pad, artS)
        val camGap = camR + 6f * density
        val tx = w / 2f + camGap
        val textRight = w - pad - 30f * density
        val avail = textRight - tx
        if (avail > 24f * density) {
            titlePaint.textSize = 13f * density * scaleF
            artistPaint.textSize = 10f * density * scaleF
            val tY1 = h / 2f - 1.5f * density
            val tY2 = h / 2f + 13f * density
            val full = title.ifBlank { "Nexora" }
            val sub = artist.ifBlank { "Now playing" }
            canvas.save()
            canvas.clipRect(tx, 0f, textRight, h)
            val tw = titlePaint.measureText(full)
            if (tw > avail && playing) {
                val wrap = tw + 24f * density
                val off = if (wrap > 0f) marqueeX % wrap else 0f
                canvas.drawText(full, tx - off, tY1, titlePaint)
                canvas.drawText(full, tx - off + wrap, tY1, titlePaint)
            } else {
                val t = TextUtils.ellipsize(full, titlePaint, avail, TextUtils.TruncateAt.END).toString()
                canvas.drawText(t, tx, tY1, titlePaint)
            }
            val a = TextUtils.ellipsize(sub, artistPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(a, tx, tY2, artistPaint)
            canvas.restore()
        }
        drawPlayPause(canvas, w - pad - 15f * density, h / 2f)
        if (animStyle == 2 && durationMs > 0) {
            // Progress-ring mode: the song's progress wraps the camera cutout
            // itself, HyperOS style; the bottom bar makes way for it.
            val cx = w / 2f
            val cy = h / 2f
            val rr = camR + 3.4f * density
            progRing.strokeCap = Paint.Cap.ROUND
            progRing.strokeWidth = 3.4f * density
            progRing.color = 0x40FFFFFF
            canvas.drawCircle(cx, cy, rr, progRing)
            val frac = (currentPosMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            if (frac > 0.002f) {
                progRing.color = 0xFFD9FF63.toInt()
                canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * frac, false, progRing)
                val ang = Math.toRadians(-90.0 + 360.0 * frac)
                canvas.drawCircle(
                    cx + rr * cos(ang).toFloat(),
                    cy + rr * sin(ang).toFloat(),
                    2.0f * density, progress,
                )
            }
        } else if (durationMs > 0) {
            track.color = 0x26FFFFFF
            val inset = 12f * density
            canvas.drawRoundRect(RectF(inset, h - 2.4f * density, w - inset, h - 0.8f * density), 1f, 1f, track)
            val frac = (currentPosMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            progress.color = 0xE6C6F53F.toInt()
            canvas.drawRoundRect(RectF(inset, h - 2.4f * density, inset + (w - 2 * inset) * frac, h - 0.8f * density), 1f, 1f, progress)
        }
        // The pulse layer: beat bars and ring mode bloom on the beat, liquid
        // morph breathes slowly. Classic stays clean; its bars carry it.
        if (playing) {
            when (animStyle) {
                1 -> drawBeatHalo(canvas, w / 2f, h / 2f, 26f * density, strong = true)
                2 -> drawBeatHalo(canvas, w / 2f, h / 2f, 26f * density, strong = false)
                3 -> drawBreathe(canvas, w / 2f, h / 2f, 26f * density)
            }
        }
    }

    /**
     * Pause / play control at the right cap of the collapsed pill: two bars
     * while playing, a triangle while paused, on a faint glass disc so the
     * tap target reads at a glance.
     */
    private val ppPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float) {
        val r = 12f * density * scaleF
        ppPaint.color = 0x1FFFFFFF
        canvas.drawCircle(cx, cy, r, ppPaint)
        val bh = 8.4f * density * scaleF
        if (playing) {
            val bw = 3f * density * scaleF
            val gap = 2.6f * density * scaleF
            ppPaint.color = 0xF2FFFFFF.toInt()
            canvas.drawRoundRect(
                RectF(cx - bw - gap / 2f, cy - bh / 2f, cx - gap / 2f, cy + bh / 2f),
                1.4f * density, 1.4f * density, ppPaint,
            )
            canvas.drawRoundRect(
                RectF(cx + gap / 2f, cy - bh / 2f, cx + bw + gap / 2f, cy + bh / 2f),
                1.4f * density, 1.4f * density, ppPaint,
            )
        } else {
            ppPaint.color = 0xCCFFFFFF.toInt()
            val tri = android.graphics.Path()
            tri.moveTo(cx - 3.2f * density, cy - 5.2f * density)
            tri.lineTo(cx + 4.9f * density, cy)
            tri.lineTo(cx - 3.2f * density, cy + 5.2f * density)
            tri.close()
            canvas.drawPath(tri, ppPaint)
        }
    }

    /** Five-bar waveform right of the camera; the bars dance while playing. */
    private fun drawWave(canvas: Canvas, x: Float, w: Float, h: Float) {
        val n = 5
        val bw = 3.2f * density
        val gap = ((w - n * bw) / (n - 1)).coerceAtLeast(1.5f * density)
        val midY = h / 2f
        for (i in 0 until n) {
            val t = when {
                !playing -> 0.26f
                animStyle == 1 -> beatEnergy(i)
                else -> 0.22f + 0.78f * abs(sin(phase * 3.0f + i * 0.85f))
            }
            val bh2 = h * 0.56f * t + 2f * density
            wavePaint.alpha = if (playing) 0xFF else 0x8C
            canvas.drawRoundRect(
                RectF(x + i * (bw + gap), midY - bh2 / 2f, x + i * (bw + gap) + bw, midY + bh2 / 2f),
                bw / 2f, bw / 2f, wavePaint,
            )
        }
    }

    /**
     * Beat-bars mode: the bars ride a ~120 BPM pulse, each bar spiking on the
     * beat and decaying until the next, with a per-bar pattern that keeps the
     * dance organic. Runs on the tracked position, so it freezes with pause.
     */
    private fun beatEnergy(i: Int): Float {
        val beatMs = 500L
        val now = currentPosMs()
        val inBeat = (now % beatMs).toFloat() / beatMs.toFloat()
        val decay = Math.exp(-1.7 * inBeat).toFloat()
        val seed = (((now / beatMs).toInt() * 31 + i * 17) % 100) / 100f
        return (0.26f + 0.74f * decay * (0.55f + 0.45f * seed)).coerceIn(0.18f, 1f)
    }

    /**
     * Beat halo: on every beat a bright ring blooms out of the camera hole
     * toward the pill's edge while a soft light washes over the capsule, so
     * the pulse is felt, not just seen. strong = beat bars, subtle = ring.
     */
    private fun drawBeatHalo(canvas: Canvas, cx: Float, cy: Float, corner: Float, strong: Boolean) {
        val beatMs = 500L
        val now = currentPosMs()
        val inBeat = (now % beatMs).toFloat() / beatMs.toFloat()
        val maxR = height / 2f - 1.5f * density
        val spread = Math.exp(-(if (strong) 2.2 else 3.4) * inBeat).toFloat()
        val r = camR + (maxR - camR) * (1f - spread)
        haloPaint.strokeWidth = (if (strong) 2.8f else 2.0f) * density
        haloPaint.alpha = ((if (strong) 0.62f else 0.34f) * spread * 255f).toInt().coerceIn(0, 255)
        if (haloPaint.alpha > 4) canvas.drawCircle(cx, cy, r, haloPaint)
        val flash = (if (strong) 0.15f else 0.07f) * Math.exp(-5.0 * inBeat).toFloat()
        if (flash > 0.012f) {
            flashPaint.alpha = (flash * 255f).toInt()
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, flashPaint)
        }
    }

    /** Liquid-morph idle: a slow breathing ring around the camera hole. */
    private fun drawBreathe(canvas: Canvas, cx: Float, cy: Float, corner: Float) {
        val k = 0.5f + 0.5f * sin(phase * 1.6f)
        val maxR = height / 2f - 1.5f * density
        haloPaint.strokeWidth = 2.4f * density
        haloPaint.alpha = (0.34f * 255f).toInt()
        canvas.drawCircle(cx, cy, camR + (maxR - camR) * 0.55f * k, haloPaint)
        flashPaint.alpha = (0.05f * 255f).toInt()
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, flashPaint)
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

    private fun drawArtRect(canvas: Canvas, x: Float, y: Float, s: Float) {
        val rad = 12f * density
        val rr = RectF(x, y, x + s, y + s)
        if (artPaint.shader != null && art != null) {
            val clip = android.graphics.Path()
            clip.addRoundRect(rr, rad, rad, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rr, artPaint)
            canvas.restore()
        } else {
            disc.shader = RadialGradient(
                x + s / 3f, y + s / 3f, s * 0.9f,
                intArrayOf(0xFF7CE0A3.toInt(), 0xFF2B8A57.toInt(), 0xFF173A2B.toInt()),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rr, rad, rad, disc)
            canvas.drawText("\u266A", x + s / 2f, y + s / 2f + note.textSize / 3f, note)
        }
        ring.color = 0x4DFFFFFF
        canvas.drawRoundRect(rr, rad, rad, ring)
    }

    private var iconBitmap: android.graphics.Bitmap? = null

    private fun drawAppIcon(canvas: Canvas, x: Float, y: Float, s: Float) {
        val rad = s * 0.32f
        val rr = RectF(x, y, x + s, y + s)
        if (iconPaint.shader == null && iconBitmap == null) {
            try {
                val dw = context.applicationInfo.loadIcon(context.packageManager)
                val bmp = android.graphics.Bitmap.createBitmap(s.toInt().coerceAtLeast(1), s.toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                dw.setBounds(0, 0, bmp.width, bmp.height)
                dw.draw(c)
                iconBitmap = bmp
                iconPaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            } catch (_: Exception) {
            }
        }
        if (iconPaint.shader != null) {
            val clip = android.graphics.Path()
            clip.addRoundRect(rr, rad, rad, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rr, iconPaint)
            canvas.restore()
        } else {
            disc.shader = RadialGradient(
                x + s / 3f, y + s / 3f, s * 0.9f,
                intArrayOf(0xFF7CE0A3.toInt(), 0xFF2B8A57.toInt(), 0xFF173A2B.toInt()),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rr, rad, rad, disc)
        }
    }

    private fun drawExpanded(canvas: Canvas, w: Float, h: Float) {
        // Floating glass card, like the reference: art and titles up top,
        // seek bar with times, transport row centred below.
        val pad = 18f * density
        val artS = 58f * density
        drawArtRect(canvas, pad, pad + 2f * density, artS)
        val tx = pad + artS + 14f * density
        val availT = w - tx - 36f * density
        if (availT > 0) {
            titlePaint.textSize = 16f * density
            canvas.drawText(
                TextUtils.ellipsize(title.ifBlank { "Nexora" }, titlePaint, availT, TextUtils.TruncateAt.END).toString(),
                tx, pad + 24f * density, titlePaint,
            )
            artistPaint.textSize = 11.5f * density
            canvas.drawText(
                TextUtils.ellipsize(artist.ifBlank { "Now playing" }, artistPaint, availT, TextUtils.TruncateAt.END).toString(),
                tx, pad + 43f * density, artistPaint,
            )
        }
        val cxc = w - 24f * density
        val cyc = pad + 12f * density
        val arm = 6f * density
        closePaint.strokeWidth = 2.2f * density
        canvas.drawLine(cxc - arm, cyc - arm, cxc + arm, cyc + arm, closePaint)
        canvas.drawLine(cxc - arm, cyc + arm, cxc + arm, cyc - arm, closePaint)
        val sy = pad + artS + 22f * density
        val sx0 = pad + 6f * density
        val sx1 = w - pad - 6f * density
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
        canvas.drawText(cur, sx0, sy + 20f * density, timePaint)
        canvas.drawText(tot, sx1 - timePaint.measureText(tot), sy + 20f * density, timePaint)
        val by = h - 40f * density
        val cxp = w / 2f
        drawSkip(canvas, cxp - 64f * density, by, forward = false)
        val pr = 24f * density * (1f + 0.05f * pressT)
        glyph.color = 0x26FFFFFF
        canvas.drawCircle(cxp, by, pr, glyph)
        glyph.color = Color.WHITE
        if (playing) {
            val bw = 4f * density
            val bh2 = 16f * density
            canvas.drawRoundRect(RectF(cxp - 5.4f * density, by - bh2 / 2, cxp - 1.4f * density, by + bh2 / 2), 1.8f * density, 1.8f * density, glyph)
            canvas.drawRoundRect(RectF(cxp + 1.4f * density, by - bh2 / 2, cxp + 5.4f * density, by + bh2 / 2), 1.8f * density, 1.8f * density, glyph)
        } else {
            val path = android.graphics.Path()
            path.moveTo(cxp - 5.5f * density, by - 10f * density)
            path.lineTo(cxp + 9f * density, by)
            path.lineTo(cxp - 5.5f * density, by + 10f * density)
            path.close()
            canvas.drawPath(path, glyph)
        }
        drawSkip(canvas, cxp + 64f * density, by, forward = true)
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
        onShift(target)
        expandAnim?.cancel()
        expandAnim = ValueAnimator.ofFloat(expandT, if (target) 1f else 0f).apply {
            if (animStyle == 3) {
                // Liquid morph: a slower, decelerating melt through the
                // circular reveal in onDraw instead of the hard swap.
                duration = 420
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                morphing = true
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        morphing = false
                        invalidate()
                    }
                })
            } else {
                duration = 260
            }
            addUpdateListener {
                expandT = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    // Long-press then drag moves the pill anywhere on screen, Notch-Action
    // style; the service remembers the spot. A plain tap still expands.
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var longFired = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2
    private val uiHandler = Handler(Looper.getMainLooper())
    private val longPressRun = Runnable {
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        animatePress(1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = ctrl ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animatePress(1f)
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                longFired = false
                if (!expanded) uiHandler.postDelayed(longPressRun, 420L)
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                uiHandler.removeCallbacks(longPressRun)
                if (expanded) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val x = event.x
                    val y = event.y
                    val d = density
                    val pad = 18f * d
                    val artS = 58f * d
                    val seekTop = pad + artS + 16f * d
                    val seekBottom = pad + artS + 34f * d
                    when {
                        abs(x - (w - 24f * d)) < 16f * d && abs(y - (pad + 12f * d)) < 16f * d -> setExpanded(false)
                        y in seekTop..seekBottom && durationMs > 0 -> {
                            val sx0 = pad + 6f * d
                            val sx1 = w - pad - 6f * d
                            val frac = ((x - sx0) / (sx1 - sx0)).coerceIn(0f, 1f)
                            c.transportControls.seekTo((frac * durationMs).toLong())
                        }
                        dist(x, y, w / 2f, h - 40f * d) < 30f * d -> {
                            if (playing) c.transportControls.pause() else c.transportControls.play()
                        }
                        dist(x, y, w / 2f - 64f * d, h - 40f * d) < 24f * d -> c.transportControls.skipToPrevious()
                        dist(x, y, w / 2f + 64f * d, h - 40f * d) < 24f * d -> c.transportControls.skipToNext()
                        x < pad + artS + 10f * d && y < pad + artS + 10f * d -> openApp()
                    }
                } else if (dragging) {
                    dragging = false
                    onMoveEnd()
                } else if (longFired) {
                    longFired = false
                } else if (event.x > width - 30f * density) {
                    // Pause / play at the right cap of the pill.
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                } else {
                    setExpanded(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (expanded) return true
                if (longFired) {
                    dragging = true
                    onMoveBy((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt())
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                } else if (event.y < -44 * density) {
                    uiHandler.removeCallbacks(longPressRun)
                    dismiss(onGone = onHide)
                    return true
                } else if (abs(event.rawX - downRawX) > touchSlop ||
                    abs(event.rawY - downRawY) > touchSlop
                ) {
                    uiHandler.removeCallbacks(longPressRun)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longPressRun)
                dragging = false
                longFired = false
                animatePress(0f)
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
        pulseAnim?.cancel()
        pressAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
