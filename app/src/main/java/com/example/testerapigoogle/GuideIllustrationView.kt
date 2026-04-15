package com.example.testerapigoogle

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * Animated illustration view for the photo guide onboarding.
 * Each scene loops like a short video clip — no external files needed.
 */
class GuideIllustrationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Scene {
        FULL_FRAME,   // phone steps back → panel fits → green brackets snap
        TOO_CLOSE,    // panel grows until cropped → arrows pulse
        GOOD_LIGHT,   // sun rays spin, light sweeps panel
        DARK,         // screen dims, "?" fades in/out
        STRAIGHT,     // crosshair draws to centre, guide lines extend
        GLARE         // glare blob drifts across panel
    }

    var scene = Scene.FULL_FRAME
        set(value) { field = value; invalidate() }

    // 0.0 → 1.0 driven by looping animator
    private var progress = 0f

    private val GREEN        = Color.parseColor("#3DCD58")
    private val RED          = Color.parseColor("#E53935")
    private val PANEL_BG     = Color.parseColor("#DDE3EA")
    private val PANEL_BORDER = Color.parseColor("#78909C")
    private val BREAKER_CLR  = Color.parseColor("#90A4AE")
    private val PHONE_BODY   = Color.parseColor("#2E3F4F")
    private val SCREEN_BG    = Color.parseColor("#ECEFF1")

    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration      = 3200
            repeatCount   = ValueAnimator.INFINITE
            repeatMode    = ValueAnimator.RESTART
            interpolator  = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    // ── Smooth easing helpers ─────────────────────────────────────────

    private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t
    private fun pingPong(t: Float) = if (t < 0.5f) t * 2 else (1 - t) * 2
    private fun pulse(t: Float, freq: Float = 1f) = (sin(t * freq * 2 * PI.toFloat()) + 1) / 2

    // ── Main draw dispatch ────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (scene) {
            Scene.FULL_FRAME -> drawFullFrame(canvas, w, h)
            Scene.TOO_CLOSE  -> drawTooClose(canvas, w, h)
            Scene.GOOD_LIGHT -> drawGoodLight(canvas, w, h)
            Scene.DARK       -> drawDark(canvas, w, h)
            Scene.STRAIGHT   -> drawStraight(canvas, w, h)
            Scene.GLARE      -> drawGlare(canvas, w, h)
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────

    private fun phoneRect(w: Float, h: Float, scale: Float = 1f): RectF {
        val pw = w * 0.50f * scale; val ph = h * 0.70f * scale
        val cx = w / 2; val cy = h / 2
        return RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
    }

    private fun screenRect(p: RectF) =
        RectF(p.left + 9, p.top + 22, p.right - 9, p.bottom - 22)

    private fun drawPhone(canvas: Canvas, p: RectF, screenColor: Int = SCREEN_BG) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = PHONE_BODY; paint.style = Paint.Style.FILL
        canvas.drawRoundRect(p, p.width() * 0.13f, p.width() * 0.13f, paint)
        paint.color = screenColor
        val s = screenRect(p)
        canvas.drawRect(s, paint)
        paint.color = Color.parseColor("#607D8B"); paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(p.centerX() - 16, p.bottom - 12, p.centerX() + 16, p.bottom - 12, paint)
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, alpha: Int = 255, cubicles: Int = 4) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun ac(c: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
        p.color = ac(PANEL_BG); p.style = Paint.Style.FILL
        canvas.drawRect(rect, p)
        p.color = ac(PANEL_BORDER); p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f
        canvas.drawRect(rect, p)
        val cw = rect.width() / cubicles
        for (i in 1 until cubicles) canvas.drawLine(rect.left + i * cw, rect.top, rect.left + i * cw, rect.bottom, p)
        p.color = ac(BREAKER_CLR); p.style = Paint.Style.FILL
        for (i in 0 until cubicles) {
            val cx = rect.left + i * cw + cw / 2
            val bw = cw * 0.55f; val bh = rect.height() * 0.38f
            canvas.drawRoundRect(RectF(cx - bw / 2, rect.centerY() - bh / 2, cx + bw / 2, rect.centerY() + bh / 2), 4f, 4f, p)
        }
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = GREEN; p.style = Paint.Style.FILL; canvas.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.28f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        path.moveTo(cx - r * 0.42f, cy); path.lineTo(cx - r * 0.08f, cy + r * 0.42f)
        path.lineTo(cx + r * 0.48f, cy - r * 0.32f)
        canvas.drawPath(path, p)
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = RED; p.style = Paint.Style.FILL; canvas.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.28f; p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - r * 0.42f, cy - r * 0.42f, cx + r * 0.42f, cy + r * 0.42f, p)
        canvas.drawLine(cx + r * 0.42f, cy - r * 0.42f, cx - r * 0.42f, cy + r * 0.42f, p)
    }

    // ── Scene: FULL_FRAME ─────────────────────────────────────────────
    // Phase 0-0.4: phone "steps back" (panel shrinks to fit)
    // Phase 0.4-0.7: green brackets snap in
    // Phase 0.7-1.0: hold, then loop
    private fun drawFullFrame(canvas: Canvas, w: Float, h: Float) {
        // Phone scale: starts at 1.15 (too close feel), shrinks to 1.0
        val phaseBack = (progress / 0.4f).coerceIn(0f, 1f)
        val phoneScale = 1.15f - easeInOut(phaseBack) * 0.15f
        val phone = phoneRect(w, h, phoneScale)
        drawPhone(canvas, phone)
        val screen = screenRect(phone)

        val mx = screen.width() * 0.09f; val my = screen.height() * 0.11f
        val panel = RectF(screen.left + mx, screen.top + my, screen.right - mx, screen.bottom - my)
        drawPanel(canvas, panel)

        // Green brackets appear after phase 0.4
        val bracketAlpha = ((progress - 0.4f) / 0.25f).coerceIn(0f, 1f)
        if (bracketAlpha > 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GREEN; style = Paint.Style.STROKE
                strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
                alpha = (bracketAlpha * 255).toInt()
            }
            val cs = 14f * bracketAlpha; val pad = 5f
            fun bracket(x1: Float, y1: Float, dx: Float, dy: Float) {
                canvas.drawLine(x1, y1, x1 + dx, y1, bp)
                canvas.drawLine(x1, y1, x1, y1 + dy, bp)
            }
            bracket(panel.left - pad, panel.top - pad, cs, cs)
            bracket(panel.right + pad, panel.top - pad, -cs, cs)
            bracket(panel.left - pad, panel.bottom + pad, cs, -cs)
            bracket(panel.right + pad, panel.bottom + pad, -cs, -cs)
        }
        if (progress > 0.65f) drawCheck(canvas, phone.right + 2, phone.top - 2, 20f)
    }

    // ── Scene: TOO_CLOSE ─────────────────────────────────────────────
    // Panel continuously grows beyond screen edges; red arrows pulse
    private fun drawTooClose(canvas: Canvas, w: Float, h: Float) {
        val phone = phoneRect(w, h)
        drawPhone(canvas, phone)
        val screen = screenRect(phone)

        // Overshoot oscillates 0..0.28 of screen width
        val overshoot = easeInOut(pingPong(progress)) * screen.width() * 0.28f
        val panel = RectF(screen.left - overshoot, screen.top - 8,
            screen.right + overshoot, screen.bottom + 8)

        canvas.save(); canvas.clipRect(screen)
        drawPanel(canvas, panel, cubicles = 5)
        canvas.restore()

        // Red cut lines
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED; style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(screen.left + 2, screen.top + 14, screen.left + 2, screen.bottom - 14, rp)
        canvas.drawLine(screen.right - 2, screen.top + 14, screen.right - 2, screen.bottom - 14, rp)

        // Pulsing outward arrows
        val arrowAlpha = (pulse(progress, 1.5f) * 255).toInt().coerceAtLeast(80)
        rp.style = Paint.Style.FILL; rp.alpha = arrowAlpha
        fun arrowTri(tip: Float, base: Float, y: Float) {
            val path = Path(); path.moveTo(tip, y); path.lineTo(base, y - 11); path.lineTo(base, y + 11); path.close()
            canvas.drawPath(path, rp)
        }
        arrowTri(screen.left - 18, screen.left - 4, screen.centerY())
        arrowTri(screen.right + 18, screen.right + 4, screen.centerY())

        drawCross(canvas, phone.right + 2, phone.top - 2, 20f)
    }

    // ── Scene: GOOD_LIGHT ─────────────────────────────────────────────
    // Sun rays rotate continuously; light beam sweeps across panel
    private fun drawGoodLight(canvas: Canvas, w: Float, h: Float) {
        val phone = phoneRect(w, h)

        // Rotating sun
        val sunCx = phone.centerX(); val sunCy = phone.top - 30f
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFC107"); style = Paint.Style.FILL }
        canvas.drawCircle(sunCx, sunCy, 14f, sp)
        sp.style = Paint.Style.STROKE; sp.strokeWidth = 2.5f; sp.strokeCap = Paint.Cap.ROUND
        val rotAngle = progress * 360f
        for (i in 0..7) {
            val a = Math.toRadians((i * 45.0) + rotAngle)
            canvas.drawLine(
                sunCx + cos(a).toFloat() * 18, sunCy + sin(a).toFloat() * 18,
                sunCx + cos(a).toFloat() * 27, sunCy + sin(a).toFloat() * 27, sp
            )
        }

        drawPhone(canvas, phone)
        val screen = screenRect(phone)
        val mx = screen.width() * 0.09f; val my = screen.height() * 0.11f
        val panel = RectF(screen.left + mx, screen.top + my, screen.right - mx, screen.bottom - my)
        drawPanel(canvas, panel)

        // Sweeping light beam across panel
        canvas.save(); canvas.clipRect(screen)
        val sweepX = panel.left - 30f + (panel.width() + 60f) * progress
        val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = LinearGradient(sweepX - 25, 0f, sweepX + 25, 0f,
            Color.argb(0, 255, 255, 200), Color.argb(70, 255, 255, 200),
            Shader.TileMode.CLAMP)
        // two-sided gradient
        val shader2 = LinearGradient(sweepX - 30, 0f, sweepX + 30, 0f,
            intArrayOf(Color.argb(0,255,240,150), Color.argb(80,255,240,150), Color.argb(0,255,240,150)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        beamPaint.shader = shader2
        canvas.drawRect(sweepX - 30, panel.top, sweepX + 30, panel.bottom, beamPaint)
        canvas.restore()

        drawCheck(canvas, phone.right + 2, phone.top - 2, 20f)
    }

    // ── Scene: DARK ───────────────────────────────────────────────────
    // Screen pulses between dim and very dark; "?" fades
    private fun drawDark(canvas: Canvas, w: Float, h: Float) {
        val phone = phoneRect(w, h)

        // Darkness oscillates
        val darkness = 0.10f + pingPong(progress) * 0.18f  // alpha of dark overlay
        val screenAlpha = (darkness * 255).toInt().coerceIn(180, 245)
        val screenColor = Color.argb(255, 28, 37, 51)
        drawPhone(canvas, phone, screenColor)

        val screen = screenRect(phone)
        canvas.save(); canvas.clipRect(screen)

        val mx = screen.width() * 0.09f; val my = screen.height() * 0.11f
        val panel = RectF(screen.left + mx, screen.top + my, screen.right - mx, screen.bottom - my)
        drawPanel(canvas, panel, alpha = 30)

        // Pulsing "?"
        val qAlpha = (pulse(progress, 0.8f) * 180).toInt() + 40
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(qAlpha, 255, 255, 255)
            textSize = 32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText("?", panel.centerX(), panel.centerY() + 12, tp)
        canvas.restore()

        // Moon with crescent
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#90A4AE"); style = Paint.Style.FILL }
        canvas.drawCircle(phone.centerX(), phone.top - 26f, 13f, mp)
        mp.color = Color.parseColor("#1C2533")
        canvas.drawCircle(phone.centerX() + 7, phone.top - 30f, 9f, mp)

        drawCross(canvas, phone.right + 2, phone.top - 2, 20f)
    }

    // ── Scene: STRAIGHT ───────────────────────────────────────────────
    // Crosshair draws itself in, guide lines extend from centre
    private fun drawStraight(canvas: Canvas, w: Float, h: Float) {
        val phone = phoneRect(w, h)
        drawPhone(canvas, phone)
        val screen = screenRect(phone)
        val mx = screen.width() * 0.09f; val my = screen.height() * 0.11f
        val panel = RectF(screen.left + mx, screen.top + my, screen.right - mx, screen.bottom - my)
        drawPanel(canvas, panel)

        // Guide lines grow from centre outward (0..0.6), then blink (0.6..1)
        val growT   = (progress / 0.55f).coerceIn(0f, 1f)
        val blinkA  = if (progress > 0.55f) (pulse(progress - 0.55f, 2f) * 0.6f + 0.4f) else 1f
        val lineAlpha = (blinkA * 180).toInt()

        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN; style = Paint.Style.STROKE; strokeWidth = 1.8f
            alpha = lineAlpha
        }
        val cx = screen.centerX(); val cy = screen.centerY()
        // Horizontal line grows left and right from centre
        canvas.drawLine(cx - (cx - screen.left - 4) * growT, cy, cx + (screen.right - 4 - cx) * growT, cy, gp)
        // Vertical line grows up and down from centre
        canvas.drawLine(cx, cy - (cy - screen.top - 4) * growT, cx, cy + (screen.bottom - 4 - cy) * growT, gp)

        // Cross centre
        gp.strokeWidth = 3.5f; gp.alpha = (blinkA * 240).toInt()
        val arm = 13f * growT
        canvas.drawLine(cx - arm, cy, cx + arm, cy, gp)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, gp)

        drawCheck(canvas, phone.right + 2, phone.top - 2, 20f)
    }

    // ── Scene: GLARE ─────────────────────────────────────────────────
    // Glare blob drifts slowly across panel; panel slightly skewed
    private fun drawGlare(canvas: Canvas, w: Float, h: Float) {
        val phone = phoneRect(w, h)
        drawPhone(canvas, phone)
        val screen = screenRect(phone)

        canvas.save(); canvas.clipRect(screen)

        // Skewed panel (perspective)
        val skew = 12f
        val top = screen.top + screen.height() * 0.10f
        val bot = screen.bottom - screen.height() * 0.10f
        val lft = screen.left + screen.width() * 0.08f
        val rgt = screen.right - screen.width() * 0.08f
        val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_BG; style = Paint.Style.FILL }
        val path = Path()
        path.moveTo(lft + skew, top); path.lineTo(rgt + skew / 2, top)
        path.lineTo(rgt - skew / 2, bot); path.lineTo(lft - skew, bot); path.close()
        canvas.drawPath(path, pp)
        pp.color = PANEL_BORDER; pp.style = Paint.Style.STROKE; pp.strokeWidth = 1.8f
        canvas.drawPath(path, pp)

        // Drifting glare blob (moves left→right and top→bot slowly)
        val gcx = screen.left + screen.width() * (0.3f + progress * 0.5f)
        val gcy = screen.top + screen.height() * (0.25f + sin(progress * PI.toFloat()).toFloat() * 0.15f)
        val glareRadius = 30f + pulse(progress, 1f) * 12f
        val glareAlpha = (160 + pulse(progress, 1.2f) * 60).toInt()
        val shader = RadialGradient(gcx, gcy, glareRadius,
            Color.argb(glareAlpha, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(gcx, gcy, glareRadius, gp)

        canvas.restore()

        // Angle indicator lines outside phone
        val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED; style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND; alpha = 180 }
        canvas.drawLine(phone.left - 10, phone.top + 10, phone.left - 26, phone.top + 32, ap)
        canvas.drawLine(phone.left - 10, phone.top + 10, phone.left + 12, phone.top + 10, ap)

        drawCross(canvas, phone.right + 2, phone.top - 2, 20f)
    }
}
