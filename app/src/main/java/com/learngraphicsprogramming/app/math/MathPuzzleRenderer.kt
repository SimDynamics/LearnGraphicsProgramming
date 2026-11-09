package com.learngraphicsprogramming.app.math

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import com.learngraphicsprogramming.app.AppearanceUi
import com.learngraphicsprogramming.app.ExerciseAnswerIdentity
import com.learngraphicsprogramming.app.ProgressRepository
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.narration.NarrationUiInstructions
import org.json.JSONObject
import org.json.JSONArray

/** One host for any combination of representations, tools and independent responses. */
class MathPuzzleRenderer(
    private val context: Context,
    private val routeId: String,
    private val lessonId: String,
    private val pageIndex: Int,
    private val blockIndex: Int,
    block: JSONObject,
    private val attemptEvaluation: ((String, () -> List<MathValidationResult>) -> List<MathValidationResult>?)? = null,
    private val onTextReady: ((TextView, TextRole) -> Unit)? = null,
    private val onIncorrectAttempt: ((List<String>) -> Unit)? = null,
    private val onCompletionChanged: (Boolean) -> Unit
) {
    enum class TextRole { BODY, CORRECT_FEEDBACK, INCORRECT_FEEDBACK, ERROR_FEEDBACK, CLEARED }

    val view = MathWidgetUi.column(context)
    private val readableTexts = mutableListOf<TextView>()
    /** Passive authored text already included in the narration; never editable answers or feedback. */
    val narrationTextViews: List<TextView> get() = readableTexts.toList()
    /** Raw labels resolve their existing blank announcement when the active language is bound. */
    val narrationSpokenAnchors: List<Pair<View, String>> get() =
        responses.values.flatMap { it.narrationSpokenAnchors }
    private val accent = AppearanceSettingsRepository.getRouteAccentColor(context, routeId)
    val fullscreenButton = MathWidgetUi.icon(context, R.drawable.ic_code_fullscreen, context.getString(R.string.math_ui_fullscreen))
    private val checkButton = MathWidgetUi.button(context, context.getString(R.string.math_ui_check), accent).apply {
        tag = CHECK_ACTION_TAG
    }
    /** Enter delegates to the exact button used by tapping Comprobar. */
    val checkActionView: View get() = checkButton
    private val resetButton = MathWidgetUi.icon(context, R.drawable.ic_code_reset, context.getString(R.string.math_ui_restart), accent)
    private val feedback = MathWidgetUi.text(context, "")
    private val spec = MathPuzzleParser.parse(block)
    private val narrationSource = block
    private val fingerprint = MathPuzzleState.fingerprint(block)
    private val responses = linkedMapOf<String, MathResponseControl>()
    private val tools = linkedMapOf<String, MathResponseControl>()
    private val initialResponses = linkedMapOf<String, MathAnswer>()
    private val initialTools = linkedMapOf<String, MathAnswer>()
    private var initializing = true
    /** Existing source fields, projected through the same order that draws the native cards. */
    fun narrationSourceBlock(): JSONObject = MathNarrationPresentation.block(narrationSource,
        responses.mapNotNull { (id, control) ->
            (control.answer() as? MathAnswer.Order)?.let { id to it.ids }
        }.toMap())

    var isCorrect: Boolean = false
        private set

    init {
        // Identifies math cards for layout/tests. Gesture ownership belongs to interactive children.
        view.tag = INTERACTION_TAG
        view.background = MathWidgetUi.background(context)
        view.setPadding(MathWidgetUi.dp(context, 6), MathWidgetUi.dp(context, 8), MathWidgetUi.dp(context, 6), MathWidgetUi.dp(context, 12))
        view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = MathWidgetUi.dp(context, 24) }
        val toolbar = MathWidgetUi.row(context)
        toolbar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        toolbar.addView(fullscreenButton)
        toolbar.addView(resetButton)
        view.addView(toolbar)
        addReadableRepresentation(MathWidgetUi.text(context, spec.prompt, true))
        spec.representations.forEach { representation ->
            addReadableRepresentation(MathRepresentationRenderer.render(context, representation, accent), representationTexts(representation))
        }
        spec.tools.forEachIndexed { index, tool ->
            if (tool.optBoolean("interactive", false)) {
                val type = when (tool.getString("type")) {
                    "math_coordinate_graph" -> "math_coordinate_graph_builder"
                    "math_number_line" -> "math_number_line"
                    "math_slider_interaction" -> "math_slider_interaction"
                    else -> throw IllegalArgumentException("Herramienta interactiva desconocida: ${tool.getString("type")}")
                }
                val id = tool.optString("id", "tool_$index")
                require(id.isNotBlank() && id !in tools) { "ID de herramienta vacío o repetido: $id" }
                val config = tool.optJSONObject("config") ?: tool
                val control = MathVisualControls.create(context, MathResponseSpec(id, type, config, JSONObject()), accent, ::changed, ::save)
                    ?: throw IllegalArgumentException("Herramienta no disponible: $type")
                tools[id] = control
                initialTools[id] = control.answer()
                addReadableRepresentation(MathWidgetUi.text(context, tool.optString("label", context.getString(R.string.math_ui_auxiliary_tool))))
                view.addView(control.view)
                collectReadableText(control.view, MathPuzzleUiInstructions.supplementary(type) {
                    NarrationUiInstructions.forBlock(context, it)
                })
            } else addReadableRepresentation(MathRepresentationRenderer.render(context, tool, accent), representationTexts(tool))
        }
        spec.responses.forEach { response ->
            val control = MathInputControls.create(context, response, accent, ::changed)
                ?: MathDragControls.create(context, response, accent, ::changed)
                ?: MathVisualControls.create(context, response, accent, ::changed, ::save)
                ?: throw IllegalArgumentException("Interacción no disponible: ${response.type}")
            responses[response.id] = control
            initialResponses[response.id] = control.answer()
            view.addView(control.view)
            collectReadableText(control.view, responseTexts(response.config) + MathPuzzleUiInstructions.visible(
                response.type, response.config) { NarrationUiInstructions.forBlock(context, it) })
        }
        view.addView(checkButton.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        view.addView(feedback)
        MathInputFocusOrder.configure(view, responses.values.map { it.view }) {
            if (checkButton.isEnabled) checkButton.performClick()
        }

        val developmentAnswers =
            MathDevelopmentMode.answersFor(
                context,
                lessonId,
                pageIndex,
                block.optString("id")
            )

        val saved =
            if (developmentAnswers == null) {

                ProgressRepository
                    .getExerciseState(
                        context,
                        lessonId,
                        pageIndex,
                        blockIndex
                    )
                    ?.takeIf {
                        it.optString(
                            "contentFingerprint"
                        ) == fingerprint
                    }

            } else {
                null
            }

        val savedAnswers =
            saved?.optJSONObject(
                "responses"
            )

        responses.forEach { (id, control) ->
            savedAnswers
                ?.optJSONObject(id)
                ?.let {
                    MathAnswerJson.decode(it)
                }
                ?.let(control::restore)
        }

        val savedTools =
            saved?.optJSONObject(
                "tools"
            )

        tools.forEach { (id, control) ->
            savedTools
                ?.optJSONObject(id)
                ?.let {
                    MathAnswerJson.decode(it)
                }
                ?.let(control::restore)
        }

// Borradores normales.
        if (developmentAnswers == null) {

            val responseDrafts =
                saved?.optJSONObject(
                    "responseDrafts"
                )

            responses.forEach { (id, control) ->
                responseDrafts
                    ?.optJSONObject(id)
                    ?.let(control::restoreDraft)
            }

            val toolDrafts =
                saved?.optJSONObject(
                    "toolDrafts"
                )

            tools.forEach { (id, control) ->
                toolDrafts
                    ?.optJSONObject(id)
                    ?.let(control::restoreDraft)
            }
        }

// ------------------------------------------------------------
// MODO DE DESARROLLO
//
// Restauramos respuestas REALES en los controles.
// No hacemos simplemente isCorrect = true.
// ------------------------------------------------------------

        developmentAnswers?.forEach { (id, answer) ->
            responses[id]
                ?.restore(answer)
        }

        isCorrect =
            if (developmentAnswers != null) {

                spec.responses.all { response ->

                    val control =
                        responses.getValue(
                            response.id
                        )

                    developmentAnswers.containsKey(
                        response.id
                    ) &&
                            control.draftError() == null &&
                            MathValidators.validate(
                                response.validator,
                                control.answer()
                            ).correct
                }

            } else {

                saved
                    ?.optBoolean(
                        "correct",
                        false
                    ) == true &&
                        spec.responses.all { response ->

                            val control =
                                responses.getValue(
                                    response.id
                                )

                            savedAnswers
                                ?.has(
                                    response.id
                                ) == true &&
                                    control.draftError() == null &&
                                    MathValidators.validate(
                                        response.validator,
                                        control.answer()
                                    ).correct
                        }
            }

        if (isCorrect) {
            showCorrect()
        }
        initializing = false
        checkButton.setOnClickListener { check() }
        resetButton.setOnClickListener { reset() }
        AppearanceUi.applyRouteToViewTree(context, view, routeId)
        updateActionState()
        save()
    }

    private fun changed() {
        if (initializing || isCorrect) return
        MathTextRenderer.apply(feedback, "")
        onTextReady?.invoke(feedback, TextRole.CLEARED)
        updateActionState()
        save()
    }

    private fun check() {
        if (isCorrect || !hasResponseChanges()) return
        var submittedAnswers = emptyMap<String, MathAnswer>()
        val validations = try {
            // Capture one deliberate submission. Cursor, selection, IME metadata and
            // auxiliary tools are not part of an answer's identity.
            submittedAnswers = spec.responses.associate { response -> response.id to responses.getValue(response.id).answer() }
            val answers = submittedAnswers
            val draftErrors = spec.responses.associate { response -> response.id to responses.getValue(response.id).draftError() }
            val identity = ExerciseAnswerIdentity.of(JSONArray().apply {
                spec.responses.sortedBy { it.id }.forEach { response ->
                    put(JSONObject().put("id", response.id).put("answer", MathAnswerJson.encode(answers.getValue(response.id))))
                }
            })
            val evaluate = {
                spec.responses.map { response ->
                    draftErrors[response.id]?.let { MathValidationResult(false, it, evaluable = false) }
                        ?: MathValidators.validate(response.validator, answers.getValue(response.id))
                }
            }
            val checked = if (attemptEvaluation == null) evaluate() else attemptEvaluation.invoke(identity, evaluate) ?: return
            require(checked.size == spec.responses.size) { "La evaluación debe devolver un resultado por respuesta." }
            checked
        } catch (_: Exception) {
            spec.responses.map { MathValidationResult(false, "evaluation failed", evaluable = false, uiMessage = MathUiMessage(R.string.math_ui_evaluation_error)) }
        }
        val results = spec.responses.zip(validations)
        if (results.any { !it.second.evaluable }) {
            // A parser/resource failure is a retry, including when another field is wrong.
            // changed() already preserved the draft; do not record an incorrect submission.
            val details = results.mapIndexedNotNull { index, (response, checked) ->
                if (checked.evaluable) return@mapIndexedNotNull null
                val message = MathPuzzleFeedback.fieldMessage(response, checked.fieldId, checked.resolveMessage(context)) {
                    context.getString(R.string.math_ui_field, it)
                }
                "${responseLabel(response, index)}: $message"
            }
            MathTextRenderer.apply(feedback, details.joinToString("\n"))
            feedback.setTextColor(MathWidgetUi.muted)
            onTextReady?.invoke(feedback, TextRole.ERROR_FEEDBACK)
            return
        }
        if (results.all { it.second.correct }) {
            isCorrect = true
            showCorrect()
            updateActionState()
            save()
            onCompletionChanged(true)
        } else {
            val hasSelections = spec.responses.any(MathPuzzleFeedback::hasSelection)
            fun processMessage(result: MathValidationResult) = MathPuzzleFeedback.processMessage(result,
                context.getString(R.string.math_ui_review_process)) { result.resolveMessage(context) }
            val details = results.mapIndexedNotNull { index, (response, result) ->
                val message = if (MathPuzzleFeedback.hasSelection(response)) {
                    // Even a correct subset is not classified during an incorrect submission.
                    val reviews = MathPuzzleFeedback.selectedReviews(response, submittedAnswers.getValue(response.id),
                        context.getString(R.string.math_ui_review_selected_option)) {
                        context.getString(R.string.math_ui_field, it)
                    }
                    // A mixed response can retain its numeric/expression diagnostic without
                    // classifying either the chosen or the absent dropdown alternatives.
                    val fieldMessage = result.fieldId?.takeIf { !result.correct && !MathPuzzleFeedback.isDropdownField(response, it) }
                        ?.let { MathPuzzleFeedback.fieldMessage(response, it, processMessage(result)) { field ->
                            context.getString(R.string.math_ui_field, field)
                        } }
                    (reviews + listOfNotNull(fieldMessage)).joinToString("\n")
                } else {
                    if (result.correct) return@mapIndexedNotNull null
                    MathPuzzleFeedback.fieldMessage(response, result.fieldId, processMessage(result)) {
                        context.getString(R.string.math_ui_field, it)
                    }
                }
                if (message.isBlank()) null else "${responseLabel(response, index)}: $message"
            }
            // A legacy global explanation may discuss absent choices, including in a mixed puzzle.
            val heading = if (hasSelections) context.getString(R.string.math_ui_incorrect)
                else spec.incorrectFeedback.ifBlank { context.getString(R.string.math_ui_incorrect) }
            val guidance = MathPuzzleFeedback.incorrectGuidance(hasSelections, spec.incorrectFeedback, details,
                context.getString(R.string.math_ui_review_process))
            val visibleDetails = if (details.isEmpty() && (hasSelections || spec.incorrectFeedback.isBlank())) guidance else details
            val visibleFeedback = (listOf("✗ $heading") + visibleDetails).filter { it.isNotBlank() }.joinToString("\n")
            MathTextRenderer.apply(feedback, visibleFeedback)
            feedback.setTextColor(Color.rgb(240, 166, 166))
            onTextReady?.invoke(feedback, TextRole.INCORRECT_FEEDBACK)
            save()
            // Narrate exactly this visible review, including its verdict and heading.
            // Validators and absent alternatives remain outside the speech source.
            onIncorrectAttempt?.invoke(listOf(visibleFeedback))
        }
    }

    private fun responseLabel(response: MathResponseSpec, index: Int): String =
        MathPuzzleFeedback.responseLabel(response, context.getString(R.string.math_ui_response, index + 1))

    private fun showCorrect() {
        MathTextRenderer.apply(feedback, context.getString(R.string.math_ui_correct_heading) + spec.correctFeedback.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty())
        feedback.setTextColor(Color.rgb(180, 220, 190))
        onTextReady?.invoke(feedback, TextRole.CORRECT_FEEDBACK)
        checkButton.text = context.getString(R.string.math_ui_correct_button)
        checkButton.isEnabled = false
        responses.values.forEach { it.setEditable(false) }
        tools.values.forEach { it.setEditable(false) }
    }

    private fun reset() {
        val wasCorrect = isCorrect
        initializing = true
        isCorrect = false
        responses.forEach { (id, control) -> control.setEditable(true); control.restore(initialResponses.getValue(id)) }
        tools.forEach { (id, control) -> control.setEditable(true); control.restore(initialTools.getValue(id)) }
        checkButton.text = context.getString(R.string.math_ui_check)
        MathTextRenderer.apply(feedback, "")
        onTextReady?.invoke(feedback, TextRole.CLEARED)
        initializing = false
        updateActionState()
        save()
        if (wasCorrect) onCompletionChanged(false)
    }

    /** Compare complete typed answers, including non-text controls; undoing an edit also restores
     * the initial disabled state. Tool changes can be reset but do not enable answer checking. */
    private fun hasResponseChanges(): Boolean = responses.any { (id, control) ->
        control.answer() != initialResponses[id] || control.hasDraftChanges()
    }

    private fun updateActionState() {
        checkButton.isEnabled = !isCorrect && hasResponseChanges()
        resetButton.isEnabled = isCorrect || hasResponseChanges() || tools.any { (id, control) ->
            control.answer() != initialTools[id] || control.hasDraftChanges()
        }
    }

    private fun save() {
        if (initializing) return
        val state = JSONObject().put("schemaVersion", 1).put("contentFingerprint", fingerprint).put("correct", isCorrect)
        val answers = JSONObject()
        responses.forEach { (id, control) -> answers.put(id, MathAnswerJson.encode(control.answer())) }
        val auxiliary = JSONObject()
        tools.forEach { (id, control) -> auxiliary.put(id, MathAnswerJson.encode(control.answer())) }
        state.put("responses", answers).put("tools", auxiliary)
        val responseDrafts = JSONObject()
        responses.forEach { (id, control) -> control.saveDraft()?.let { responseDrafts.put(id, it) } }
        val toolDrafts = JSONObject()
        tools.forEach { (id, control) -> control.saveDraft()?.let { toolDrafts.put(id, it) } }
        if (responseDrafts.length() > 0) state.put("responseDrafts", responseDrafts)
        if (toolDrafts.length() > 0) state.put("toolDrafts", toolDrafts)
        ProgressRepository.saveExerciseState(context, routeId, lessonId, pageIndex, blockIndex, state)
    }

    private fun addReadableRepresentation(representation: View, sources: Collection<String>? = null) {
        view.addView(representation)
        collectReadableText(representation, sources)
    }

    private fun collectReadableText(representation: View, sources: Collection<String>?) {
        val displayedSources = sources?.filter(String::isNotBlank)?.flatMap { source ->
            listOf("text", "auto", "inline", "block").map { MathTextRenderer.render(source, it).toString() }
        }?.toSet()
        fun collect(candidate: View) {
            if (candidate.visibility != View.VISIBLE || candidate is android.widget.EditText ||
                candidate is android.widget.Button || candidate is android.widget.AdapterView<*> ||
                candidate is MathExpressionEditor || candidate is MathKeyboardView) return
            // A choice/drag card owns the gesture, while its rich body is ordinary passive text.
            // Descend those containers without ever registering their native controls or answers.
            if (candidate is TextView && !candidate.isClickable && !candidate.isLongClickable &&
                !candidate.text.isNullOrBlank() && (displayedSources == null || candidate.text.toString() in displayedSources) &&
                readableTexts.none { it === candidate }) {
                readableTexts += candidate
                onTextReady?.invoke(candidate, TextRole.BODY)
            }
            if (candidate is ViewGroup) {
                for (index in 0 until candidate.childCount) collect(candidate.getChildAt(index))
            }
        }
        collect(representation)
    }

    /** Whitelist the same authored, visible fields read by LessonNarrationText. This only matches
     * existing views: validator values, hints, current answers and generated control labels cannot
     * become narration targets merely because they are descendants of a response card. */
    private fun responseTexts(response: JSONObject): List<String> = buildList {
        add(response.optString("label"))
        val fields = MathWidgetUi.objects(response.optJSONArray("fields"))
        fields.forEach { field ->
            add(field.optString("label"))
            if (field.optString("type") == "fixed") add(field.optString("text"))
            add(field.optString("unit"))
        }
        MathWidgetUi.objects(response.optJSONArray("segments")).forEach { segment ->
            if (!segment.has("field")) add(segment.optString("text"))
        }
        if (response.optString("layout") == "table") {
            addAll(scalars(response.optJSONArray("headers")))
            val rows = response.optJSONArray("rows")
            if (rows != null) for (index in 0 until rows.length()) addAll(scalars(rows.optJSONArray(index)))
        }
        for (key in listOf("options", "items", "sources", "targets")) {
            MathWidgetUi.objects(response.optJSONArray(key)).forEach { addAll(richTexts(it)) }
        }
        MathWidgetUi.objects(response.optJSONArray("parameters")).forEach { add(it.optString("label")) }
        add(response.optString("unit"))
    }

    private fun richTexts(item: JSONObject): List<String> = buildList {
        val content = item.optJSONArray("content")
        if (content == null || content.length() == 0) add(item.optString("label", item.optString("text", item.optString("id"))))
        else for (index in 0 until content.length()) {
            val part = content.optJSONObject(index)
            if (part == null) addAll(scalars(JSONArray().put(content.opt(index))))
            else addAll(representationTexts(part))
        }
        add(item.optString("explanation"))
    }

    private fun representationTexts(representation: JSONObject): List<String> = buildList {
        val config = representation.optJSONObject("config") ?: representation
        when (representation.optString("type", "text")) {
            "text" -> add(config.optString("text", config.optString("value")))
            "math_formula" -> add(config.optString("latex", config.optString("text",
                config.optString("formula", config.optString("value")))))
            "math_table" -> {
                addAll(scalars(config.optJSONArray("headers")))
                val rows = config.optJSONArray("rows")
                if (rows != null) for (index in 0 until rows.length()) addAll(scalars(rows.optJSONArray(index)))
            }
            "rich_answer_option" -> {
                // This representation draws only its content, unlike a rich choice card.
                val content = config.optJSONArray("content")
                if (content != null) for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index)
                    if (part != null) addAll(representationTexts(part))
                    else addAll(scalars(JSONArray().put(content.opt(index))))
                }
            }
            "geometry_2d_diagram", "geometry_3d_diagram" -> {
                add(config.optString("label"))
                addAll(scalars(config.optJSONArray("labels"), limit = 3))
            }
            "venn_diagram" -> {
                for (key in listOf("universeLabel", "leftLabel", "leftOnly", "intersection", "rightLabel", "rightOnly", "outside")) {
                    add(config.optString(key))
                }
            }
            "math_coordinate_graph", "coordinate_graph", "math_number_line" -> {
                add(config.optString("label"))
                add(config.optString("description"))
            }
        }
    }

    private fun scalars(values: JSONArray?, limit: Int = Int.MAX_VALUE): List<String> = if (values == null) emptyList() else
        (0 until minOf(values.length(), limit)).mapNotNull { index ->
            when (val value = values.opt(index)) {
                is String, is Number -> value.toString()
                else -> null
            }
        }

    companion object {
        const val INTERACTION_TAG = "math_puzzle_interaction"
        const val CHECK_ACTION_TAG = "math_puzzle_check_action"
    }
}
