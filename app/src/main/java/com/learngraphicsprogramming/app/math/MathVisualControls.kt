package com.learngraphicsprogramming.app.math

import com.learngraphicsprogramming.app.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/** Controls share typed answers and can freeze after success without discarding their state. */
object MathVisualControls {
    fun create(context: Context, spec: MathResponseSpec, accentColor: Int, onChange: () -> Unit, onDraftChange: (() -> Unit)? = null): MathResponseControl? = when (spec.type) {
        "math_segment_transport" -> MathSegmentTransportControl(context, spec, accentColor, onChange, onDraftChange ?: onChange)
        "math_rigid_angle_copy" -> rigidAngle(context, spec, accentColor, onChange)
        "math_coordinate_graph_builder", "math_geometry_builder" -> graph(context, spec, accentColor, onChange)
        "math_number_line" -> if (spec.config.optString("mode", "build_inequality") == "build_inequality") numberLine(context, spec, accentColor, onChange) else null
        "math_slider_interaction" -> sliders(context, spec, accentColor, onChange)
        else -> null
    }

    private fun rigidAngle(context: Context, response: MathResponseSpec, accent: Int, onChange: () -> Unit): MathResponseControl {
        val spec = RigidAngleCopySpec.parse(response.validator)
        val visual = MathRigidAngleCopyView(context, spec, accent)
        val root = column(context)
        response.config.optString("label").takeIf { it.isNotBlank() }?.let { root.addView(label(context, it)) }
        root.addView(label(context, context.getString(R.string.math_rigid_help)))
        root.addView(label(context, context.getString(R.string.math_rigid_legend)))
        val panel = object : LinearLayout(context), MathFullscreenGestureCapability {
            override val allowEdgeSwipeToDismiss = false
        }.apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf(R.string.math_rigid_move to MathRigidAngleCopyView.Mode.MOVE_COPY,
            R.string.math_rigid_rotate to MathRigidAngleCopyView.Mode.ROTATE_COPY, R.string.math_rigid_view to MathRigidAngleCopyView.Mode.VIEW)
        val buttons = modes.map { (text, mode) -> MathWidgetUi.button(context, context.getString(text), accent).apply {
            tag = "rigid_angle_mode_$mode"
            setOnClickListener {
                visual.mode = mode
                (0 until controls.childCount).forEach { index -> controls.getChildAt(index).isSelected = modes[index].second == mode }
            }
            isSelected = mode == visual.mode
            controls.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } }
        panel.addView(controls)
        val resetCopy = MathWidgetUi.button(context, context.getString(R.string.math_rigid_reset), accent).apply {
            tag = "rigid_angle_reset"
            setOnClickListener { visual.restorePlacement(spec.initial); onChange() }
        }
        panel.addView(resetCopy)
        panel.addView(visual, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(MathExpandableRepresentation.wrap(context, panel, accent, 1.1f))
        visual.onPlacementChanged = onChange
        return object : MathResponseControl {
            override val view: View = root
            override fun answer(): MathAnswer = MathAnswer.RigidAngle(visual.placement)
            override fun restore(answer: MathAnswer) { if (answer is MathAnswer.RigidAngle) visual.restorePlacement(answer.placement) }
            override fun setEditable(enabled: Boolean) { visual.editable = enabled; buttons.forEach { it.isEnabled = enabled }; resetCopy.isEnabled = enabled }
            override fun hasDraftChanges() = visual.placement != spec.initial
            override fun saveDraft(): JSONObject = MathAnswerJson.encode(answer())
            override fun restoreDraft(state: JSONObject) { MathAnswerJson.decode(state)?.let(::restore) }
        }
    }

    private fun graph(context: Context, spec: MathResponseSpec, accent: Int, onChange: () -> Unit): MathResponseControl {
        val config = JSONObject(spec.config.toString())
        if (spec.type == "math_geometry_builder" && !config.has("mode")) config.put("mode", "polygon")
        val layout = column(context)
        val graph = MathCartesianView(context, config, accent, true)
        layout.addView(MathExpandableRepresentation.wrap(context, graph, accent))
        layout.addView(label(context, context.getString(R.string.math_ui_edit_coordinates)))
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(fields)
        var synchronizing = false
        var editable = true
        val inputs = mutableListOf<Pair<EditText, EditText>>()
        fun sync(preserveIncomplete: Boolean = false) {
            synchronizing = true
            graph.points().forEachIndexed { index, point ->
                inputs.getOrNull(index)?.let { (x, y) ->
                    if (!preserveIncomplete || parse(x.text.toString()) != null) x.setText(MathCartesianView.formatCoordinate(point.x))
                    if (!preserveIncomplete || parse(y.text.toString()) != null) y.setText(MathCartesianView.formatCoordinate(point.y))
                }
            }
            synchronizing = false
        }
        fun buildFields() {
            val previousTextSize = inputs.firstOrNull()?.first?.textSize
            fields.removeAllViews()
            inputs.clear()
            graph.points().forEachIndexed { index, point ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                val pointPrefix = if (graph.points().size > 1) "${MathCartesianView.pointLabel(index)}: " else ""
                row.addView(label(context, "${pointPrefix}x ="))
                val x = numberInput(context, context.getString(R.string.math_ui_x_coordinate, index + 1), point.x, accent)
                MathKeyboardContext.configureVisual(x, config, spec.validator, "x")
                row.addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(label(context, " y ="))
                val y = numberInput(context, context.getString(R.string.math_ui_y_coordinate, index + 1), point.y, accent)
                MathKeyboardContext.configureVisual(y, config, spec.validator, "y")
                if (previousTextSize != null) {
                    x.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, previousTextSize)
                    y.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, previousTextSize)
                }
                row.addView(y, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                x.isEnabled = editable && graph.canMovePoint(index)
                y.isEnabled = editable && graph.canMovePoint(index)
                inputs += x to y
                fun update() {
                    if (synchronizing || !editable) return
                    val newX = parse(x.text.toString())
                    val newY = parse(y.text.toString())
                    // Keep the active input intact so that negative and decimal values can be typed.
                    if (newX != null && newY != null) {
                        synchronizing = true
                        graph.movePoint(index, newX, newY)
                        synchronizing = false
                    }
                    onChange()
                }
                x.addTextChangedListener(watcher(::update))
                y.addTextChangedListener(watcher(::update))
                val finish = View.OnFocusChangeListener { _, focused ->
                    if (!focused && !synchronizing && editable && inputs.all { (xField, yField) -> parse(xField.text.toString()) != null && parse(yField.text.toString()) != null }) {
                        sync()
                        onChange()
                    }
                }
                x.onFocusChangeListener = finish
                y.onFocusChangeListener = finish
                fields.addView(row)
            }
        }
        buildFields()
        val initialCoordinateText = inputs.map { (x, y) -> x.text.toString() to y.text.toString() }
        graph.onPointsChanged = { if (!synchronizing) { sync(preserveIncomplete = true); onChange() } }
        return object : MathResponseControl {
            override val view: View = layout
            override fun answer(): MathAnswer = MathAnswer.Graph(graph.points())
            override fun hasDraftChanges(): Boolean = inputs.map { (x, y) -> x.text.toString() to y.text.toString() } != initialCoordinateText
            override fun draftError(): String? = if (inputs.any { (x, y) -> parse(x.text.toString()) == null || parse(y.text.toString()) == null }) {
                context.getString(R.string.math_ui_valid_coordinates)
            } else null
            override fun saveDraft(): JSONObject? = if (hasDraftChanges()) JSONObject().put("coords", JSONArray().apply {
                inputs.forEach { (x, y) -> put(JSONObject().put("x", x.text.toString()).put("y", y.text.toString())) }
            }) else null
            override fun restoreDraft(state: JSONObject) {
                val coordinates = state.optJSONArray("coords") ?: return
                synchronizing = true
                try {
                    inputs.forEachIndexed { index, (x, y) ->
                        coordinates.optJSONObject(index)?.let { point ->
                            x.setText(point.optString("x", x.text.toString()))
                            y.setText(point.optString("y", y.text.toString()))
                        }
                    }
                } finally { synchronizing = false }
                onChange()
            }
            override fun restore(answer: MathAnswer) {
                if (answer is MathAnswer.Graph) {
                    val sameCount = answer.points.size == inputs.size
                    graph.setPoints(answer.points)
                    if (sameCount) sync() else buildFields()
                }
            }
            override fun setEditable(enabled: Boolean) {
                editable = enabled
                graph.editable = enabled
                inputs.forEachIndexed { index, (x, y) ->
                    x.isEnabled = enabled && graph.canMovePoint(index)
                    y.isEnabled = enabled && graph.canMovePoint(index)
                }
                if (!enabled) sync()
            }
        }
    }

    private fun numberLine(context: Context, spec: MathResponseSpec, accent: Int, onChange: () -> Unit): MathResponseControl {
        val config = JSONObject(spec.config.toString())
        if (!config.has("direction")) config.put("direction", "right")
        val layout = column(context)
        val line = MathNumberLineView(context, config, accent, true)
        layout.addView(MathExpandableRepresentation.wrap(context, line, accent, 2.4f))
        val position = numberInput(context, context.getString(R.string.math_ui_point_position), line.answer().value, accent)
        MathKeyboardContext.configureVisual(position, config, spec.validator)
        val initialPositionText = position.text.toString()
        layout.addView(label(context, context.getString(R.string.math_ui_position)))
        layout.addView(position)
        val closed = CheckBox(context).apply {
            text = context.getString(R.string.math_ui_closed_point)
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = line.answer().closed
        }
        layout.addView(closed)
        layout.addView(label(context, context.getString(R.string.math_ui_interval_direction)))
        val direction = Spinner(context)
        direction.contentDescription = context.getString(R.string.math_ui_interval_direction)
        direction.minimumHeight = dp(context, 48)
        direction.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        direction.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, listOf(context.getString(R.string.math_ui_left), context.getString(R.string.math_ui_right))) {
            private var selectedTextSize: Float? = null
            private var baseTextSize: Float? = null
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).also {
                (it as TextView).setTextColor(Color.WHITE)
                if (baseTextSize == null) baseTextSize = it.textSize
                selectedTextSize = (direction.selectedView as? TextView)?.textSize ?: (baseTextSize ?: it.textSize) * layout.mathTextScale
                it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, selectedTextSize ?: it.textSize)
                it.minimumHeight = dp(context, 48)
                it.gravity = android.view.Gravity.CENTER_VERTICAL
                it.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).also {
                it.setBackgroundColor(context.getColor(R.color.lesson_card))
                (it as TextView).setTextColor(Color.WHITE)
                selectedTextSize = (direction.selectedView as? TextView)?.textSize ?: (baseTextSize ?: it.textSize) * layout.mathTextScale
                it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, selectedTextSize ?: it.textSize)
                it.minimumHeight = dp(context, 48)
                it.gravity = android.view.Gravity.CENTER_VERTICAL
                it.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
            }
        }
        direction.setSelection(if (line.answer().direction == "left") 0 else 1)
        layout.addView(direction)
        var synchronizing = false
        fun sync(preserveIncomplete: Boolean = false) {
            synchronizing = true
            val answer = line.answer()
            if (!preserveIncomplete || parse(position.text.toString()) != null) position.setText(MathCartesianView.formatCoordinate(answer.value))
            closed.isChecked = answer.closed
            direction.setSelection(if (answer.direction == "left") 0 else 1)
            synchronizing = false
        }
        line.onValueChanged = { if (!synchronizing) { sync(preserveIncomplete = true); onChange() } }
        position.addTextChangedListener(watcher {
            if (!synchronizing && line.editable) {
                parse(position.text.toString())?.let {
                    synchronizing = true
                    line.setMarker(value = it)
                    synchronizing = false
                }
                onChange()
            }
        })
        position.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (!focused && !synchronizing && line.editable && parse(position.text.toString()) != null) { sync(); onChange() }
        }
        closed.setOnCheckedChangeListener { _, checked -> if (!synchronizing) line.setMarker(closed = checked) }
        direction.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!synchronizing) line.setMarker(direction = if (position == 0) "left" else "right")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        return object : MathResponseControl {
            override val view: View = layout
            override fun answer(): MathAnswer = line.answer()
            override fun hasDraftChanges(): Boolean = position.text.toString() != initialPositionText
            override fun draftError(): String? = if (parse(position.text.toString()) == null) context.getString(R.string.math_ui_valid_position) else null
            override fun saveDraft(): JSONObject? = if (hasDraftChanges()) JSONObject().put("position", position.text.toString()) else null
            override fun restoreDraft(state: JSONObject) {
                if (!state.has("position")) return
                synchronizing = true
                try { position.setText(state.optString("position", position.text.toString())) }
                finally { synchronizing = false }
                onChange()
            }
            override fun restore(answer: MathAnswer) {
                if (answer is MathAnswer.NumberLine) { line.restore(answer.value, answer.closed, answer.direction); sync() }
            }
            override fun setEditable(enabled: Boolean) {
                line.editable = enabled
                position.isEnabled = enabled
                closed.isEnabled = enabled
                direction.isEnabled = enabled
                if (!enabled) sync()
            }
        }
    }

    /** parameters:[{id,label,min,max,step,value}]; visual is rectangle or graph.
     * rectangle uses widthParameter/heightParameter/scaleParameter and baseWidth/baseHeight.
     * graph uses slopeParameter/interceptParameter and bounds. Topic knowledge stays in JSON.
     */
    private fun sliders(context: Context, spec: MathResponseSpec, accent: Int, onChange: () -> Unit): MathResponseControl {
        data class Parameter(val id: String, val name: String, val min: Double, val max: Double, val step: Double)
        val array = spec.config.optJSONArray("parameters")
            ?: throw IllegalArgumentException("El control de parámetros necesita parameters.")
        require(array.length() in 1..32) { "El control debe definir entre 1 y 32 parámetros." }
        val parameters = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Parámetro ${index + 1} inválido.")
            val min = item.optDouble("min", 0.0)
            val max = item.optDouble("max", 10.0)
            val rawStep = item.optDouble("step", 1.0)
            val value = item.optDouble("value", min)
            require(min.isFinite() && max.isFinite() && kotlin.math.abs(min) <= 1e6 && kotlin.math.abs(max) <= 1e6 && max > min) {
                "Rango inválido del parámetro ${index + 1}; usa min < max dentro de ±1000000."
            }
            require(rawStep.isFinite() && rawStep > 0 && rawStep <= max - min && ceil((max - min) / rawStep) <= 10000 && value.isFinite()) {
                "Paso o valor inválido del parámetro ${index + 1}; usa un máximo de 10000 pasos."
            }
            val id = item.optString("id", "parameter_$index")
            require(id.isNotBlank()) { "ID vacío del parámetro ${index + 1}." }
            Parameter(id, item.optString("label", context.getString(R.string.math_ui_parameter, index + 1)), min, max, rawStep)
        }
        require(parameters.map { it.id }.distinct().size == parameters.size) { "Los IDs de parámetros deben ser únicos." }
        val values = linkedMapOf<String, Double>()
        fun normalize(parameter: Parameter, value: Double): Double {
            val safe = if (value.isFinite()) value else parameter.min
            if (safe <= parameter.min) return parameter.min
            if (safe >= parameter.max) return parameter.max
            return (parameter.min + round((safe - parameter.min) / parameter.step) * parameter.step).coerceIn(parameter.min, parameter.max)
        }
        parameters.forEachIndexed { index, parameter -> values[parameter.id] = normalize(parameter, array.getJSONObject(index).optDouble("value", parameter.min)) }
        val layout = column(context)
        val visual = spec.config.optJSONObject("visual") ?: JSONObject()
        val graph = if (visual.optString("type") == "graph") MathCartesianView(context, JSONObject(visual.toString()).put("mode", "line").put("snap", 0), accent) else null
        val preview: View = graph ?: object : View(context), MathTextScaleAware {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var textScale = 1f
            override fun scaleMathText(factor: Float) {
                if (factor.isFinite() && factor > 0) { textScale *= factor; invalidate() }
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                fun value(key: String, defaultIndex: Int, fallback: Double): Double {
                    val defaultId = parameters.getOrNull(defaultIndex)?.id?.takeIf { it != visual.optString("scaleParameter") } ?: ""
                    return values[visual.optString(key, defaultId)] ?: fallback
                }
                val scale = values[visual.optString("scaleParameter")] ?: 1.0
                val widthValue = value("widthParameter", 0, MathCartesianView.finite(visual, "baseWidth", 3.0)) * scale
                val heightValue = value("heightParameter", 1, MathCartesianView.finite(visual, "baseHeight", 2.0)) * scale
                val scaleMaximum = parameters.find { it.id == visual.optString("scaleParameter") }?.max ?: 1.0
                val reference = max(1.0, (parameters.maxOfOrNull { max(kotlin.math.abs(it.min), kotlin.math.abs(it.max)) } ?: 10.0) * max(1.0, scaleMaximum))
                val unit = kotlin.math.min(width * .72, height * .66) / reference
                val w = kotlin.math.abs(widthValue) * unit
                val h = kotlin.math.abs(heightValue) * unit
                paint.color = (accent and 0x00ffffff) or 0x55000000
                paint.style = Paint.Style.FILL
                canvas.drawRect(((width - w) / 2).toFloat(), ((height - h) / 2).toFloat(), ((width + w) / 2).toFloat(), ((height + h) / 2).toFloat(), paint)
                paint.color = accent
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * resources.displayMetrics.density
                canvas.drawRect(((width - w) / 2).toFloat(), ((height - h) / 2).toFloat(), ((width + w) / 2).toFloat(), ((height + h) / 2).toFloat(), paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 15 * resources.displayMetrics.density * textScale
                canvas.drawText("${MathCartesianView.formatCoordinate(widthValue)} × ${MathCartesianView.formatCoordinate(heightValue)}", width / 2f, height - 14 * resources.displayMetrics.density, paint)
            }
        }
        preview.setBackgroundColor(context.getColor(R.color.lesson_canvas))
        layout.addView(MathExpandableRepresentation.wrap(context, preview, accent, if (graph != null) 1f else 1.2f))
        val seekBars = mutableMapOf<String, SeekBar>()
        val labels = mutableMapOf<String, TextView>()
        var synchronizing = false
        fun updatePreview() {
            parameters.forEach { p -> labels[p.id]?.let { MathTextRenderer.apply(it, "${p.name}: ${MathCartesianView.formatCoordinate(values[p.id] ?: p.min)}") } }
            if (graph != null) {
                val slope = values[visual.optString("slopeParameter", parameters.firstOrNull()?.id ?: "")] ?: 1.0
                val intercept = values[visual.optString("interceptParameter", parameters.getOrNull(1)?.id ?: "")] ?: 0.0
                graph.setPoints(listOf(MathPoint(0.0, intercept), MathPoint(1.0, intercept + slope)))
            }
            preview.contentDescription = context.getString(R.string.math_ui_parameter_preview, parameters.joinToString { "${it.name} ${MathCartesianView.formatCoordinate(values[it.id] ?: it.min)}" })
            preview.invalidate()
        }
        parameters.forEach { parameter ->
            val label = label(context, "")
            labels[parameter.id] = label
            layout.addView(label)
            val bar = SeekBar(context).apply {
                max = ceil((parameter.max - parameter.min) / parameter.step).toInt().coerceIn(1, 10000)
                progress = round(((values[parameter.id] ?: parameter.min) - parameter.min) / parameter.step).toInt().coerceIn(0, max)
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                contentDescription = parameter.name
                tag = "math_gesture_owner"
            }
            bar.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) view.parent?.requestDisallowInterceptTouchEvent(false)
                false
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (synchronizing || !fromUser) return
                    values[parameter.id] = normalize(parameter, parameter.min + parameter.step * progress)
                    updatePreview()
                    onChange()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            seekBars[parameter.id] = bar
            layout.addView(bar)
        }
        updatePreview()
        return object : MathResponseControl {
            override val view: View = layout
            override fun answer(): MathAnswer = MathAnswer.Sliders(values.toMap())
            override fun restore(answer: MathAnswer) {
                if (answer !is MathAnswer.Sliders) return
                synchronizing = true
                parameters.forEach { p ->
                    values[p.id] = normalize(p, answer.values[p.id] ?: values[p.id] ?: p.min)
                    seekBars[p.id]?.progress = round(((values[p.id] ?: p.min) - p.min) / p.step).toInt()
                }
                synchronizing = false
                updatePreview()
            }
            override fun setEditable(enabled: Boolean) { seekBars.values.forEach { it.isEnabled = enabled; it.tag = if (enabled) "math_gesture_owner" else null } }
        }
    }

    /** Keeps the lesson scale available to spinner rows that Android creates after initial layout. */
    private class VisualColumn(context: Context) : LinearLayout(context), MathTextScaleAware {
        var mathTextScale = 1f
            private set
        override fun scaleMathText(factor: Float) {
            if (factor.isFinite() && factor > 0) mathTextScale *= factor
        }
    }

    private fun column(context: Context): VisualColumn = VisualColumn(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        setBackgroundColor(context.getColor(R.color.lesson_card))
    }
    private fun label(context: Context, value: String): TextView = com.learngraphicsprogramming.app.narration.ReadingTextView(context).apply {
        MathTextRenderer.apply(this, value)
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
    }
    private fun numberInput(context: Context, description: String, value: Double, accent: Int): EditText = EditText(context).apply {
        // Ask Android for numeric keys while permitting locale separators from the shortcut bar.
        keyListener = android.text.method.TextKeyListener.getInstance()
        setSingleLine(true)
        setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        backgroundTintList = ColorStateList.valueOf(accent)
        contentDescription = description
        tag = "math_gesture_owner"
        setText(MathCartesianView.formatCoordinate(value))
        textSize = 18f
        minimumHeight = dp(context, 48)
    }
    private fun parse(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange() }
    }
    internal fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
