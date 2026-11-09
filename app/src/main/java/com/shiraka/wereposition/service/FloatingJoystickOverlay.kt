package com.shiraka.wereposition.service

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.shiraka.wereposition.R
import com.shiraka.wereposition.utils.SettingsManager
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The touch wheel only stores input. One paced loop integrates motion without queued drag steps. */
internal class FloatingJoystickOverlay(
    private val context: Context,
    private val onMove: (Double, Float, Float, Double) -> Unit,
    private val onRelease: () -> Unit,
    private val onExitSimulation: () -> Unit
) : SensorEventListener {
    private val density = context.resources.displayMetrics.density
    private val settings = SettingsManager(context)
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val handler = Handler(Looper.getMainLooper())
    private val wheelSize = (128 * density).roundToInt()
    private val sliderWidth = (132 * density).roundToInt()
    private val sliderHeight = (36 * density).roundToInt()
    private val sliderGap = (8 * density).roundToInt()
    private val controlSize = (24 * density).roundToInt()
    private val controlInset = (8 * density).roundToInt()
    private val controlRaise = (9 * density).roundToInt()
    private var wheelEnd = (settings.joystickOverlayEndDp * density).roundToInt()
    private var wheelBottom = (settings.joystickOverlayBottomDp * density).roundToInt()
    private var wheelDialog: Dialog? = null
    private var sliderDialog: Dialog? = null
    private var exitDialog: Dialog? = null
    private var lockDialog: Dialog? = null
    private var lockView: ControlButtonView? = null
    private var overlayLocked = settings.joystickOverlayLocked
    private var wheelView: WheelView? = null
    private var sliderView: SpeedSliderView? = null
    private var speed = settings.joystickSpeedMs
    private var moving = false
    private var bearing = 0.0
    private var intensity = 0f
    private var deviceHeading: Double? = null
    private var lastTickNanos = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (wheelDialog == null) return
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsedSeconds = ((now - lastTickNanos).coerceIn(0L, 250_000_000L)) / 1_000_000_000.0
            lastTickNanos = now
            val limitedSpeed = speed.coerceIn(settings.joystickMinSpeedMs, settings.joystickMaxSpeedMs)
            if (limitedSpeed != speed) {
                speed = limitedSpeed
                sliderView?.invalidate()
            }
            if (moving && intensity > 0.01f) {
                val resolvedBearing = if (settings.joystickDirectionMode == "DEVICE") {
                    deviceHeading?.let { (bearing + it) % 360.0 }
                } else bearing
                if (resolvedBearing != null) onMove(resolvedBearing, intensity, speed, elapsedSeconds)
            }
            handler.postDelayed(this, 100L)
        }
    }

    fun show(): Boolean {
        if (wheelDialog != null) return true
        return runCatching {
            val wheel = WheelView(context)
            val slider = SpeedSliderView(context)
            clampOverlayPosition()
            val wheelWindow = createWindow(wheel, wheelSize, wheelSize, wheelEnd, wheelBottom, true)
            wheelDialog = wheelWindow
            wheelView = wheel
            sliderDialog = createWindow(slider, sliderWidth, sliderHeight,
                sliderEnd(), sliderBottom(), false)
            sliderView = slider
            exitDialog = createWindow(ControlButtonView(context, false) {
                onExitSimulation()
            }, controlSize, controlSize, exitEnd(), controlBottom(), true)
            val lock = ControlButtonView(context, true) {
                overlayLocked = !overlayLocked
                settings.joystickOverlayLocked = overlayLocked
                lockView?.invalidate()
                Toast.makeText(context, if (overlayLocked) R.string.joystick_overlay_locked
                    else R.string.joystick_overlay_unlocked, Toast.LENGTH_SHORT).show()
            }
            lockView = lock
            lockDialog = createWindow(lock, controlSize, controlSize,
                lockEnd(), controlBottom(), true)
            rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            lastTickNanos = SystemClock.elapsedRealtimeNanos()
            handler.post(tick)
            true
        }.getOrElse {
            hide()
            false
        }
    }

    fun hide() {
        handler.removeCallbacks(tick)
        sensors.unregisterListener(this)
        val wasMoving = moving
        moving = false
        intensity = 0f
        wheelDialog?.dismiss()
        sliderDialog?.dismiss()
        exitDialog?.dismiss()
        lockDialog?.dismiss()
        wheelDialog = null
        sliderDialog = null
        exitDialog = null
        lockDialog = null
        lockView = null
        wheelView = null
        sliderView = null
        if (wasMoving) onRelease()
    }

    private fun createWindow(
        content: View, width: Int, height: Int, marginEnd: Int, marginBottom: Int,
        circular: Boolean
    ): Dialog {
        val dialog = Dialog(context, R.style.FloatingJoystickDialog)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(false)
        val window = requireNotNull(dialog.window)
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.elevation = 0f
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = context.getColor(
            if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50
        )
        window.setBackgroundDrawable(GradientDrawable().apply {
            shape = if (circular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!circular) cornerRadius = height / 2f
            setColor((surface and 0x00ffffff) or 0xc8000000.toInt())
        })
        // Live cross-window blur repeatedly recomposes the app underneath both
        // moving overlay windows, including Maps' separate rendering surface.
        // Keep the floating controls translucent without that system blur pass.
        window.attributes = window.attributes.apply {
            this.width = width
            this.height = height
            gravity = Gravity.END or Gravity.BOTTOM
            x = marginEnd
            y = marginBottom
            dimAmount = 0f
        }
        dialog.show()
        return dialog
    }

    private fun sliderEnd(): Int = wheelEnd - (sliderWidth - wheelSize) / 2

    private fun sliderBottom(): Int = wheelBottom - sliderHeight - sliderGap

    private fun controlBottom(): Int = wheelBottom - (sliderGap + controlSize) / 2 + controlRaise

    private fun exitEnd(): Int = sliderEnd() + sliderWidth - controlSize / 2 - controlInset

    private fun lockEnd(): Int = sliderEnd() - controlSize / 2 + controlInset

    private fun clampOverlayPosition() {
        val metrics = windowManager.currentWindowMetrics
        val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val edge = (8 * density).roundToInt()
        val minEnd = safe.right + edge + (sliderWidth - wheelSize) / 2 + controlSize / 2
        val maxEnd = (metrics.bounds.width() - safe.left - edge -
            ((sliderWidth + wheelSize) / 2 + controlSize / 2))
            .coerceAtLeast(minEnd)
        wheelEnd = wheelEnd.coerceIn(minEnd, maxEnd)
        val minBottom = safe.bottom + sliderHeight + sliderGap + edge
        val maxBottom = (metrics.bounds.height() - safe.top - wheelSize - edge)
            .coerceAtLeast(minBottom)
        wheelBottom = wheelBottom.coerceIn(minBottom, maxBottom)
    }

    private fun moveOverlayBy(dx: Float, dy: Float) {
        wheelEnd -= dx.roundToInt()
        wheelBottom -= dy.roundToInt()
        clampOverlayPosition()
        wheelDialog?.window?.let { window ->
            window.attributes = window.attributes.apply { x = wheelEnd; y = wheelBottom }
        }
        sliderDialog?.window?.let { window ->
            window.attributes = window.attributes.apply { x = sliderEnd(); y = sliderBottom() }
        }
        exitDialog?.window?.let { window ->
            window.attributes = window.attributes.apply { x = exitEnd(); y = controlBottom() }
        }
        lockDialog?.window?.let { window ->
            window.attributes = window.attributes.apply { x = lockEnd(); y = controlBottom() }
        }
    }

    private fun saveOverlayPosition() {
        settings.setJoystickOverlayPosition(
            (wheelEnd / density).roundToInt(),
            (wheelBottom / density).roundToInt()
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val matrix = FloatArray(9)
        val adjusted = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val rotation = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, axisX, axisY, adjusted)
        val raw = (Math.toDegrees(SensorManager.getOrientation(adjusted, FloatArray(3))[0].toDouble()) +
            360.0) % 360.0
        deviceHeading = deviceHeading?.let { previous ->
            val shortestTurn = (raw - previous + 540.0) % 360.0 - 180.0
            (previous + shortestTurn * 0.25 + 360.0) % 360.0
        } ?: raw
        wheelView?.invalidate()
    }

    private inner class ControlButtonView(
        context: Context,
        private val isLock: Boolean,
        private val action: () -> Unit
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(android.R.color.system_accent1_600)
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            contentDescription = context.getString(if (isLock) R.string.joystick_lock_overlay
                else R.string.joystick_exit_simulation)
        }

        override fun onDraw(canvas: Canvas) {
            val c = controlSize / 2f
            if (!isLock) {
                canvas.drawLine(c - 5 * density, c - 5 * density,
                    c + 5 * density, c + 5 * density, paint)
                canvas.drawLine(c + 5 * density, c - 5 * density,
                    c - 5 * density, c + 5 * density, paint)
            } else {
                val left = c - 5 * density
                val right = c + 5 * density
                val shackleTop = if (overlayLocked) c - 7 * density else c - 9 * density
                canvas.drawRoundRect(left, c - density, right, c + 7 * density,
                    2 * density, 2 * density, paint)
                if (overlayLocked) {
                    canvas.drawArc(c - 4 * density, shackleTop, c + 4 * density,
                        c + 3 * density, 180f, 180f, false, paint)
                } else {
                    canvas.drawArc(c - 2 * density, shackleTop, c + 6 * density,
                        c + density, 180f, 180f, false, paint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> { performClick(); true }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }

        override fun performClick(): Boolean {
            super.performClick()
            action()
            return true
        }
    }

    private inner class WheelView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        private val primary = context.getColor(android.R.color.system_accent1_600)
        private val foreground = context.getColor(if (dark) android.R.color.white else android.R.color.black)
        private val center = wheelSize / 2f
        private val travel = 34 * density
        private var thumbX = 0f
        private var thumbY = 0f
        private var pointerId = -1
        private var overlayPointerId = -1
        private var lastRawX = 0f
        private var lastRawY = 0f

        override fun onDraw(canvas: Canvas) {
            paint.color = foreground
            paint.alpha = 75
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            canvas.drawCircle(center, center, 53 * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = primary
            paint.alpha = 255
            canvas.drawCircle(center + thumbX, center + thumbY, 19 * density, paint)
            paint.color = 0xffffffff.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 18 * density
            canvas.drawText("✥", center + thumbX, center + thumbY + 6 * density, paint)

            val markerRadius = 48 * density
            val heading = if (settings.joystickDirectionMode == "DEVICE") deviceHeading ?: 0.0 else 0.0
            listOf("N", "E", "S", "W").forEachIndexed { index, label ->
                val angle = Math.toRadians(index * 90.0 - heading)
                val markerX = center + sin(angle).toFloat() * markerRadius
                val markerY = center - cos(angle).toFloat() * markerRadius
                if (index == 0) {
                    paint.color = primary
                    canvas.drawCircle(markerX, markerY, 10 * density, paint)
                    paint.color = 0xffffffff.toInt()
                } else {
                    paint.color = foreground
                }
                paint.textSize = 11 * density
                canvas.drawText(label, markerX, markerY + 4 * density, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hypot(event.x - center, event.y - center) >= 41 * density) {
                        if (overlayLocked) return true
                        overlayPointerId = event.getPointerId(0)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        return true
                    }
                    pointerId = event.getPointerId(0)
                    lastTickNanos = SystemClock.elapsedRealtimeNanos()
                    updateThumb(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (overlayPointerId >= 0) {
                        val index = event.findPointerIndex(overlayPointerId)
                        if (index >= 0) {
                            val rawX = event.getRawX(index)
                            val rawY = event.getRawY(index)
                            moveOverlayBy(rawX - lastRawX, rawY - lastRawY)
                            lastRawX = rawX
                            lastRawY = rawY
                        }
                        return true
                    }
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) updateThumb(event.getX(index), event.getY(index))
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == overlayPointerId) {
                        overlayPointerId = -1
                        saveOverlayPosition()
                        return true
                    }
                    if (event.getPointerId(event.actionIndex) == pointerId) release()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (overlayPointerId >= 0) {
                        overlayPointerId = -1
                        saveOverlayPosition()
                        return true
                    }
                    release()
                    return true
                }
            }
            return true
        }

        private fun release() {
            if (pointerId == -1) return
            pointerId = -1
            moving = false
            intensity = 0f
            thumbX = 0f
            thumbY = 0f
            onRelease()
            invalidate()
        }

        private fun updateThumb(x: Float, y: Float) {
            val dx = x - center
            val dy = y - center
            val distance = hypot(dx, dy)
            val factor = if (distance > travel) travel / distance else 1f
            thumbX = dx * factor
            thumbY = dy * factor
            intensity = (hypot(thumbX, thumbY) / travel).coerceIn(0f, 1f)
            bearing = (Math.toDegrees(atan2(thumbX.toDouble(), -thumbY.toDouble())) + 360.0) % 360.0
            moving = true
            invalidate()
        }
    }

    private inner class SpeedSliderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val primary = context.getColor(android.R.color.system_accent1_600)
        private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        private val foreground = context.getColor(if (dark) android.R.color.white else android.R.color.black)
        private val left = 8 * density
        private val right = sliderWidth - left
        private val trackTop = 7 * density
        private val trackBottom = sliderHeight - trackTop
        private var pointerId = -1

        override fun onDraw(canvas: Canvas) {
            val min = settings.joystickMinSpeedMs
            val max = settings.joystickMaxSpeedMs
            val fraction = ((speed - min) / (max - min)).coerceIn(0f, 1f)
            val fillEnd = left + (right - left) * fraction
            val radius = (trackBottom - trackTop) / 2f
            paint.style = Paint.Style.FILL
            paint.color = foreground
            paint.alpha = 55
            canvas.drawRoundRect(left, trackTop, right, trackBottom, radius, radius, paint)
            paint.color = primary
            paint.alpha = 230
            if (fillEnd > left) {
                canvas.drawRoundRect(left, trackTop, fillEnd, trackBottom, radius, radius, paint)
            }
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.textSize = 10 * density
            val label = String.format(Locale.US, "%.1f m/s", speed)
            val baseline = sliderHeight / 2f - (paint.ascent() + paint.descent()) / 2f
            val filledClip = canvas.save()
            canvas.clipRect(left, trackTop, fillEnd, trackBottom)
            paint.color = 0xffffffff.toInt()
            canvas.drawText(label, sliderWidth / 2f, baseline, paint)
            canvas.restoreToCount(filledClip)
            val emptyClip = canvas.save()
            canvas.clipRect(fillEnd, trackTop, right, trackBottom)
            paint.color = foreground
            canvas.drawText(label, sliderWidth / 2f, baseline, paint)
            canvas.restoreToCount(emptyClip)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = event.getPointerId(0)
                    updateSpeed(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) updateSpeed(event.getX(index))
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == pointerId) pointerId = -1
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointerId = -1
                    return true
                }
            }
            return true
        }

        private fun updateSpeed(x: Float) {
            val min = settings.joystickMinSpeedMs
            val max = settings.joystickMaxSpeedMs
            val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
            speed = (((min + fraction * (max - min)) * 10f).roundToInt() / 10f)
                .coerceIn(min, max)
            settings.joystickSpeedMs = speed
            invalidate()
        }
    }
}
