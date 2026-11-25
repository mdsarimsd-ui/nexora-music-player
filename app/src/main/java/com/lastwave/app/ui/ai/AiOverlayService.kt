package com.lastwave.app.ui.ai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lastwave.app.MainActivity

/**
 * A floating "Ask AI" orb over any screen, Google-Assistant style: drag to
 * move, tap to open the assistant sheet. Needs the "display over other apps"
 * permission, requested from the AI sheet.
 */
class AiOverlayService : Service() {

    companion object {
        @Volatile
        var running = false
            private set

        fun start(context: android.content.Context) {
            context.startService(Intent(context, AiOverlayService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, AiOverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var bubble: AiBubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = AiBubbleView(this)
        bubble = view
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 340
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params!!.x
                    startY = params!!.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params!!.x = startX + dx
                    params!!.y = startY + dy
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        AiEntryBus.requestOpen()
                        runCatching {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                    ),
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        added = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        if (added) runCatching { wm.removeView(bubble) }
        added = false
        bubble = null
        super.onDestroy()
    }
}

/** The orb itself: pulsing Nexora gradient with an AI label. */
private class AiBubbleView(context: android.content.Context) : View(context) {

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0x55FFFFFF
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 84f
        glow.shader = RadialGradient(
            cx, cy, r * 1.3f,
            intArrayOf(0x662B286A, 0x337C4DFF, 0x00FF5EA2),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * (1.15f + 0.07f * kotlin.math.sin(phase)), glow)
        core.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0xFF7C4DFF.toInt(), 0xFFFF5EA2.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 0.72f, core)
        sweep.shader = SweepGradient(
            cx, cy,
            intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, (phase / (2f * Math.PI.toFloat())).coerceIn(0f, 1f), 1f),
        )
        canvas.drawCircle(cx, cy, r * 0.72f + 7f, sweep)
        canvas.drawText("AI", cx, cy + 14f, label)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(260, 260)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
