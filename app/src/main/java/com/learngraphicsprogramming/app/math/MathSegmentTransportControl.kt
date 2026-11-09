package com.learngraphicsprogramming.app.math

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.learngraphicsprogramming.app.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** The answer contains only constructed points. The camera and rigid tool have their own draft. */
internal class MathSegmentTransportControl(
    private val context: Context, response: MathResponseSpec, private val accent: Int,
    private val onAnswerChanged: () -> Unit, private val onDraftChanged: () -> Unit
) : MathResponseControl {
    private val spec = SegmentTransportSpec.parse(response.validator)
    private val canvas = MathSegmentTransportView(context, response.validator, accent)
    private val root = MathWidgetUi.column(context)
    private val pointSelector = Spinner(context)
    private val measureSelector = Spinner(context)
    private val modeSelector = Spinner(context)
    private var syncing = false
    private var measurements = emptyList<Pair<String, String>>()
    private val resetPoints: Button
    override val view: View get() = root

    init {
        response.config.optString("label").takeIf(String::isNotBlank)?.let { root.addView(MathWidgetUi.text(context, it)) }
        root.addView(MathWidgetUi.text(context, context.getString(R.string.math_segment_help)))
        root.addView(MathWidgetUi.text(context, context.getString(R.string.math_segment_legend)))
        val panel = object : LinearLayout(context), MathFullscreenGestureCapability, MathDirectManipulationSurface {
            override val allowEdgeSwipeToDismiss = false
        }.apply { orientation = LinearLayout.VERTICAL }
        panel.addView(canvas, LinearLayout.LayoutParams(-1, 0, 3f))
        val controls = MathWidgetUi.column(context)
        val scroller = ScrollView(context).apply { isFillViewport = false; addView(controls) }
        panel.addView(scroller, LinearLayout.LayoutParams(-1, 0, 2f))
        fun labeled(text: Int, selector: Spinner, tagName: String) {
            controls.addView(MathWidgetUi.text(context, context.getString(text)))
            selector.tag = tagName
            controls.addView(selector, LinearLayout.LayoutParams(-1, -2))
        }
        val modes = listOf("place", "move_tool", "rotate_tool", "view")
        modeSelector.adapter = adapter(listOf(R.string.math_segment_place, R.string.math_segment_move_tool,
            R.string.math_segment_rotate_tool, R.string.math_segment_view).map(context::getString))
        labeled(R.string.math_segment_surface, modeSelector, "segment_mode")
        modeSelector.onItemSelectedListener = selection { position ->
            if (!syncing) { canvas.setMode(modes[position]); onDraftChanged() }
        }
        labeled(R.string.math_segment_point, pointSelector, "segment_point")
        pointSelector.onItemSelectedListener = selection { position ->
            if (!syncing) { canvas.setActivePoint(position); onDraftChanged() }
        }
        labeled(R.string.math_segment_measure, measureSelector, "segment_measurement")
        fun button(label: Int, tagName: String, action: () -> Unit): Button =
            MathWidgetUi.button(context, context.getString(label), accent).apply {
                tag = tagName; setOnClickListener { action() }
                controls.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        button(R.string.math_segment_measure_action, "segment_take_length") {
            measurements.getOrNull(measureSelector.selectedItemPosition)?.let {
                canvas.gesture(JSONObject().put("type", "measure").put("id", it.first))
            }
        }
        val zoom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(zoom)
        for ((label, delta) in listOf(R.string.math_segment_zoom_in to 1.0, R.string.math_segment_zoom_out to -1.0)) {
            zoom.addView(MathWidgetUi.button(context, context.getString(label), accent).apply {
                tag = if (delta > 0) "segment_zoom_in" else "segment_zoom_out"
                setOnClickListener { canvas.zoom(delta) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button(R.string.math_segment_reset_view, "segment_reset_view") { canvas.gesture(JSONObject().put("type", "reset_view")) }
        button(R.string.math_segment_reset_tool, "segment_reset_tool") { canvas.gesture(JSONObject().put("type", "reset_tool")) }
        resetPoints = button(R.string.math_segment_reset_points, "segment_reset_points") {
            canvas.restoreAnswer(MathAnswer.Graph(spec.initial.map { MathPoint(it.x, it.y) }))
            sync(); onAnswerChanged()
        }
        root.addView(MathExpandableRepresentation.wrap(context, panel, accent, .65f))
        canvas.onChanged = { pointsChanged ->
            sync()
            if (pointsChanged) onAnswerChanged() else onDraftChanged()
        }
        sync()
    }

    private fun adapter(labels: List<String>) = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    private fun selection(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun sync() {
        syncing = true
        val amount = (canvas.answer().points.size + 1).coerceAtMost(spec.pointCount)
        if (pointSelector.adapter?.count != amount) pointSelector.adapter = adapter(spec.pointLabels.take(amount))
        pointSelector.setSelection(canvas.activePoint.coerceIn(0, amount - 1), false)
        modeSelector.setSelection(listOf("place", "move_tool", "rotate_tool", "view").indexOf(canvas.mode), false)
        val available = canvas.measurements()
        if (available != measurements) {
            val selected = measurements.getOrNull(measureSelector.selectedItemPosition)?.first
            measurements = available; measureSelector.adapter = adapter(available.map { it.second })
            measureSelector.setSelection(available.indexOfFirst { it.first == selected }.coerceAtLeast(0), false)
        }
        syncing = false
    }
    override fun answer(): MathAnswer = canvas.answer()
    override fun restore(answer: MathAnswer) { if (answer is MathAnswer.Graph) { canvas.restoreAnswer(answer); sync() } }
    override fun setEditable(enabled: Boolean) {
        canvas.editable = enabled; pointSelector.isEnabled = enabled; resetPoints.isEnabled = enabled
        if (!enabled && canvas.mode == "place") { canvas.setMode("view"); sync() }
    }
    override fun hasDraftChanges() = canvas.answer().points != spec.initial.map { MathPoint(it.x, it.y) }
    override fun saveDraft(): JSONObject = canvas.savedDraft()
    override fun restoreDraft(state: JSONObject) { canvas.restoreDraft(state); sync() }
}

/** No snapping and no calculated solution coordinates reach this surface. */
internal class MathSegmentTransportView(context: Context, private val config: JSONObject, private val accent: Int) :
    View(context), MathTextScaleAware, MathFullscreenGestureCapability {
    override val allowEdgeSwipeToDismiss = false
    var editable = true
    var onChanged: ((Boolean) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density.toDouble()
    private val background = context.getColor(R.color.lesson_canvas)
    private val toolColor = Color.rgb(255, 190, 95)
    private val constructedColor = Color.rgb(146, 225, 170)
    private var textScale = 1.0
    private var state: JSONObject? = null
    private var drawing: EuclideanDrawingSnapshot? = null
    private var envelope = JSONObject()
    private var last = EuclideanPosition(0.0, 0.0)
    private var dragging = false
    private var currentPoint = 0
    private var unavailable = false
    val mode: String get() = state?.optJSONObject("interactionDraft")?.optString("mode", "place") ?: "place"
    val activePoint: Int get() = state?.optJSONObject("interactionDraft")?.optInt("activePoint", 0) ?: 0

    init {
        id = View.generateViewId(); tag = "segment_canvas"; isSaveEnabled = true
        setBackgroundColor(background); contentDescription = context.getString(R.string.math_segment_surface)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refresh()
    }
    private fun measure(text: String, maximum: Double): EuclideanLabelMetrics {
        paint.textSize = (15 * resources.displayMetrics.scaledDensity * textScale).toFloat()
        val lines = mutableListOf<String>(); var start = 0
        while (start < text.length) {
            var end = start + Character.charCount(Character.codePointAt(text, start))
            while (end < text.length) {
                val next = end + Character.charCount(Character.codePointAt(text, end))
                if (paint.measureText(text, start, next) > maximum) break
                end = next
            }
            lines += text.substring(start, end); start = end
        }
        val metrics = paint.fontMetrics
        return EuclideanLabelMetrics(lines, lines.maxOfOrNull { paint.measureText(it).toDouble() } ?: 0.0,
            (metrics.descent - metrics.ascent + 2 * density), -metrics.ascent.toDouble())
    }
    private fun refresh(gesture: JSONObject? = null): Boolean = try {
        val request = JSONObject().put("config", config).put("width", max(1, width)).put("height", max(1, height))
            .put("unit", density).put("textSize", 15 * resources.displayMetrics.scaledDensity * textScale)
        state?.let { request.put("state", it) }; gesture?.let { request.put("gesture", it) }
        var nextDrawing: EuclideanDrawingSnapshot? = null
        val next = SegmentTransport.layout(request, { nextDrawing = it; JSONObject() }, ::measure)
        state = next.getJSONObject("state"); envelope = next; drawing = nextDrawing; unavailable = false
        invalidate(); true
    } catch (_: Exception) {
        unavailable = true; invalidate(); false
    }
    private fun setDraft(key: String, value: Any) {
        val previous = state?.let { JSONObject(it.toString()) }
        state?.getJSONObject("interactionDraft")?.put(key, value)
        if (!refresh()) state = previous
    }
    fun setMode(value: String) { require(value in setOf("place", "move_tool", "rotate_tool", "view")); setDraft("mode", value) }
    fun setActivePoint(value: Int) { setDraft("activePoint", value) }
    fun answer(): MathAnswer.Graph = MathAnswerJson.decode(savedDraft()) as MathAnswer.Graph
    fun drawingSnapshot(): EuclideanDrawingSnapshot? = drawing
    fun savedDraft(): JSONObject = state?.let { JSONObject(it.toString()) }
        ?: JSONObject().put("type", "graph").put("points", JSONArray())
    fun restoreAnswer(answer: MathAnswer.Graph) {
        val previous = state
        state = MathAnswerJson.encode(answer)
        if (!refresh()) state = previous
    }
    fun restoreDraft(value: JSONObject) {
        // A stale or damaged camera cannot overwrite the separately restored semantic answer.
        if (MathAnswerJson.decode(value) != answer()) return
        val previous = state; state = JSONObject(value.toString())
        if (!refresh()) state = previous
    }
    fun measurements(): List<Pair<String, String>> = envelope.optJSONArray("measurements")?.let { values ->
        (0 until values.length()).map { values.getJSONObject(it).let { it.getString("id") to it.getString("label") } }
    } ?: emptyList()
    fun gesture(value: JSONObject) {
        if (!editable && value.optString("type") in setOf("place", "move_point")) return
        val before = answer()
        if (refresh(value)) onChanged?.invoke(before != answer())
    }
    fun zoom(delta: Double) = gesture(JSONObject().put("type", "zoom").put("delta", delta)
        .put("current", JSONObject().put("x", width / 2.0).put("y", height / 2.0)))
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); refresh() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = drawing
        if (snapshot == null) { drawUnavailable(canvas); return }
        fun color(id: String) = when { id.startsWith("tool:") -> toolColor; id.startsWith("part:") || id.startsWith("point:") -> constructedColor; else -> accent }
        fun line(a: EuclideanPosition, b: EuclideanPosition) = canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (2.2 * density).toFloat(); paint.strokeCap = Paint.Cap.ROUND
        snapshot.paths.forEach { path ->
            paint.color = color(path.id); line(path.start, path.end)
            snapshot.arrows.filter { it.objectId == path.id }.forEach { line(it.tip, it.wingA); line(it.tip, it.wingB) }
        }
        paint.style = Paint.Style.FILL
        snapshot.points.forEach { point ->
            paint.color = background; canvas.drawCircle(point.center.x.toFloat(), point.center.y.toFloat(), (5.6 * density).toFloat(), paint)
            paint.color = color(point.id); canvas.drawCircle(point.center.x.toFloat(), point.center.y.toFloat(), (4 * density).toFloat(), paint)
        }
        paint.textSize = (15 * resources.displayMetrics.scaledDensity * textScale).toFloat()
        snapshot.labels.forEach { label ->
            val bounds = label.bounds; paint.color = background
            canvas.drawRoundRect(RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                (2 * density).toFloat(), (2 * density).toFloat(), paint)
            paint.color = color(label.ownerId)
            label.lines.forEachIndexed { index, text -> canvas.drawText(text, (bounds.left + 2 * density).toFloat(),
                (bounds.top + density + label.ascent + index * label.lineHeight).toFloat(), paint) }
        }
        if (unavailable) drawUnavailable(canvas)
    }
    private fun drawUnavailable(canvas: Canvas) {
        if (!unavailable) return
        paint.color = Color.WHITE; paint.style = Paint.Style.FILL
        val message = context.getString(R.string.math_segment_unavailable)
        val metrics = measure(message, (width - 16 * density).coerceAtLeast(1.0))
        metrics.lines.forEachIndexed { index, line -> canvas.drawText(line, (8 * density).toFloat(),
            (8 * density + metrics.ascent + index * metrics.lineHeight).toFloat(), paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1) { dragging = false; parent?.requestDisallowInterceptTouchEvent(false); return false }
        if (!editable && mode == "place") return false
        val position = EuclideanPosition(event.x.toDouble(), event.y.toDouble())
        fun pointer(point: EuclideanPosition) = JSONObject().put("x", point.x).put("y", point.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; last = position; currentPoint = activePoint
                if (mode == "place") {
                    val points = envelope.optJSONArray("constructedPoints") ?: JSONArray()
                    val nearby = (0 until points.length()).map { points.getJSONObject(it) }.minByOrNull {
                        val center = it.getJSONObject("center"); kotlin.math.hypot(center.getDouble("x") - position.x, center.getDouble("y") - position.y)
                    }
                    if (nearby != null) {
                        val center = nearby.getJSONObject("center")
                        if (kotlin.math.hypot(center.getDouble("x") - position.x, center.getDouble("y") - position.y) <= 24 * density)
                            currentPoint = nearby.getInt("index")
                    }
                    gesture(JSONObject().put("type", "place").put("index", currentPoint).put("current", pointer(position)))
                }
                parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val type = if (mode == "place") "move_point" else mode
                gesture(JSONObject().put("type", type).put("index", currentPoint)
                    .put("previous", pointer(last)).put("current", pointer(position)))
                last = position; return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun scaleMathText(factor: Float) { if (factor.isFinite() && factor > 0) { textScale *= factor; refresh() } }
    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also { it.source = savedDraft().toString() }
    override fun onRestoreInstanceState(value: Parcelable?) {
        if (value is SavedState) {
            super.onRestoreInstanceState(value.superState)
            try { MathAnswerJson.decode(JSONObject(value.source))?.let { if (it is MathAnswer.Graph) restoreAnswer(it) }; restoreDraft(JSONObject(value.source)) } catch (_: Exception) { }
        } else super.onRestoreInstanceState(value)
    }
    private class SavedState : BaseSavedState {
        var source = "{}"
        constructor(parent: Parcelable?) : super(parent)
        private constructor(parcel: Parcel) : super(parcel) { source = parcel.readString() ?: "{}" }
        override fun writeToParcel(out: Parcel, flags: Int) { super.writeToParcel(out, flags); out.writeString(source) }
        companion object { @JvmField val CREATOR = object : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel) = SavedState(source)
            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        } }
    }
}
