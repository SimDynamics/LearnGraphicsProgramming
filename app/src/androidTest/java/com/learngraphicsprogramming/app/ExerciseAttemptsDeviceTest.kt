package com.learngraphicsprogramming.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.learngraphicsprogramming.app.math.MathAnswer
import com.learngraphicsprogramming.app.math.MathRigidAngleCopyView
import com.learngraphicsprogramming.app.math.MathSegmentTransportView
import com.learngraphicsprogramming.app.math.EuclideanPosition
import com.learngraphicsprogramming.app.math.MathAnswerValidator
import com.learngraphicsprogramming.app.math.MathExpression
import com.learngraphicsprogramming.app.math.MathExpressionEditor
import com.learngraphicsprogramming.app.math.MathExpressionView
import com.learngraphicsprogramming.app.math.MathPuzzleRenderer
import com.learngraphicsprogramming.app.math.MathPuzzleTypes
import com.learngraphicsprogramming.app.math.MathValidationResult
import com.learngraphicsprogramming.app.math.MathValidators
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real LessonActivity check listeners with small, original in-memory lessons.
 * No course asset is changed. All preferences affected by the fixtures/tools are restored.
 */
@RunWith(AndroidJUnit4::class)
class ExerciseAttemptsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val snapshots = linkedMapOf<String, Map<String, *>>()

    @Before fun preserveUserState() {
        listOf(ATTEMPTS_PREFS, "learning_progress", "navigation_state", "scientific_calculator", "cpp_lesson_drafts").forEach { name ->
            snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the phone before instrumentation: isDeviceLocked=${keyguard.isDeviceLocked}, " +
            "isKeyguardLocked=${keyguard.isKeyguardLocked}", keyguard.isDeviceLocked)
        assertEquals("Preserve the current one-hour recovery policy", 3_600_000L, AttemptRepository.RECOVERY_MILLIS)
    }

    @After fun restoreUserState() {
        snapshots.forEach { (name, original) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported preference value for $name/$key: $value")
                }
            }
            assertTrue("Restore $name exactly", editor.commit())
        }
    }

    @Test fun programmingTextChecksChargeOnlyDistinctCompleteWrongAnswers() {
        for (type in listOf("fill_code", "output_prediction", "inline_fill_code")) {
            withFixture(PROGRAMMING, listOf(listOf(programmingBlock(type)))) { scenario ->
                scenario.onActivity { activity ->
                    checkButton(content(activity)).performClick()
                    assertReserve(PROGRAMMING, 8, "$type: a blank answer is incomplete")
                }
                assertAnswerCycle(scenario, PROGRAMMING, type) { activity, answer ->
                    descendants(content(activity)).filterIsInstance<EditText>().forEachIndexed { index, input ->
                        input.setText(if (answer == Answer.CORRECT) "correct${index + 1}" else answer.token)
                    }
                }
            }
        }
    }

    @Test fun programmingChoiceOrderAndDragChecksShareTheAttemptPolicy() {
        withFixture(PROGRAMMING, listOf(listOf(programmingBlock("multiple_choice")))) { scenario ->
            scenario.onActivity { activity ->
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 8, "An unselected choice is incomplete")
            }
            assertAnswerCycle(scenario, PROGRAMMING, "multiple_choice") { activity, answer ->
                descendants(content(activity)).filterIsInstance<RadioButton>()[answer.optionIndex].performClick()
            }
        }
        for (type in listOf("order_code", "drag_fill_code")) {
            withFixture(PROGRAMMING, listOf(listOf(programmingBlock(type)))) { scenario ->
                if (type == "drag_fill_code") {
                    scenario.onActivity { activity ->
                        token(content(activity), "Fragmento A").performClick()
                    }
                    settleCodeAnimation()
                    scenario.onActivity { activity ->
                        checkButton(content(activity)).performClick()
                        assertReserve(PROGRAMMING, 8, "One filled slot is still incomplete")
                    }
                }
                if (type == "drag_fill_code") {
                    for ((answer, remaining) in answerCycle(PROGRAMMING)) {
                        scenario.onActivity { resetButton(content(it)).performClick() }
                        for (index in answer.order) {
                            scenario.onActivity { token(content(it), "Fragmento ${('A'.code + index).toChar()}").performClick() }
                            // The real token enters its slot at the end of the animation; the next
                            // token must be tapped afterwards so it chooses the next empty slot.
                            settleCodeAnimation()
                        }
                        scenario.onActivity { assertCycleStep(it, PROGRAMMING, type, answer, remaining) }
                    }
                } else {
                    assertAnswerCycle(scenario, PROGRAMMING, type) { activity, answer ->
                        resetButton(content(activity)).performClick()
                        answer.order.forEach { index ->
                            token(content(activity), "Fragmento ${('A'.code + index).toChar()}").performClick()
                        }
                    }
                }
            }
        }
    }

    @Test fun everyMathResponseTypeUsesRealControlsAndChargesOncePerWrongAnswer() {
        assertTrue("Registered response types must be exercised", MathPuzzleTypes.responseTypes.isNotEmpty())
        for (type in MathPuzzleTypes.responseTypes) {
            withFixture(MATH, listOf(listOf(mathBlock(type)))) { scenario ->
                scenario.onActivity { activity ->
                    val card = mathCard(content(activity))
                    assertFalse("$type: unchanged controls do not enable checking", checkButton(card).isEnabled)
                    checkButton(card).performClick()
                    assertReserve(MATH, 12, "$type: rendering and an unchanged draft are free")
                }
                assertAnswerCycle(scenario, MATH, type) { activity, answer ->
                    setMathAnswer(mathCard(content(activity)), type, answer)
                }
            }
        }
        assertMultiChoiceSelectionOrderIdentity()
    }

    @Test fun compoundAndNestedFieldsMustBeEntirelyEvaluableBeforeSpendingOneAttempt() {
        for (block in listOf(compoundBlock(), mathBlock("math_multi_input"))) {
            withFixture(MATH, listOf(listOf(block))) { scenario ->
                scenario.onActivity { activity ->
                    val inputs = descendants(content(activity)).filterIsInstance<EditText>()
                    assertEquals(2, inputs.size)
                    inputs[0].setText("1") // A valid wrong first field cannot hide an invalid later one.
                    checkButton(content(activity)).performClick()
                    assertReserve(MATH, 12, "A later empty required field makes the whole card incomplete")
                    inputs[1].setText("1/0")
                    checkButton(content(activity)).performClick()
                    assertReserve(MATH, 12, "A later parser error makes the whole card non-evaluable")
                    inputs[1].setText("1")
                    val check = checkButton(content(activity))
                    check.performClick()
                    check.performClick()
                    assertReserve(MATH, 11, "Two wrong fields belong to one evaluated answer, including a repeated callback")
                    inputs[0].setText("3")
                    inputs[1].setText("4")
                    check.performClick()
                    assertReserve(MATH, 11, "Completing the whole card correctly is free")
                    assertCorrect(activity, 0, 0)
                }
            }
        }
    }

    @Test fun mathParserAndTechnicalValidationFailuresNeverConsumeReserve() {
        for ((type, malformed) in listOf("math_numeric_input" to "1/0", "math_expression_input" to "sin(")) {
            withFixture(MATH, listOf(listOf(mathBlock(type)))) { scenario ->
                scenario.onActivity { activity ->
                    val input = descendants(content(activity)).filterIsInstance<EditText>().single()
                    input.setText(malformed)
                    checkButton(content(activity)).performClick()
                    assertReserve(MATH, 12, "$type: malformed/unsupported input is feedback, not a wrong answer")
                    assertFalse(exerciseState(activity, 0, 0).optBoolean("correct"))
                    input.setText(if (type == "math_numeric_input") "1" else "x+1")
                    checkButton(content(activity)).performClick()
                    assertReserve(MATH, 11, "$type: a subsequent valid wrong answer still costs exactly one")
                }
            }
        }

        // A registered validator extension can fail at runtime even after its schema was accepted.
        // Restore the registry in finally; this fixture never changes production validators.
        val field = MathValidators::class.java.getDeclaredField("registry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val registry = field.get(MathValidators) as MutableMap<String, MathAnswerValidator>
        val type = "attempt_device_test_throwing_validator"
        val previous = registry[type]
        try {
            MathValidators.register(type, object : MathAnswerValidator {
                override fun validate(spec: JSONObject, answer: MathAnswer): MathValidationResult =
                    throw IllegalStateException("Deliberate test-only technical failure")
            })
            val block = mathBlock("math_numeric_input").put("validator", json("""{"type":"$type"}"""))
            withFixture(MATH, listOf(listOf(block))) { scenario ->
                scenario.onActivity { activity ->
                    descendants(content(activity)).filterIsInstance<EditText>().single().setText("1")
                    val check = checkButton(content(activity))
                    check.performClick()
                    check.performClick()
                    assertReserve(MATH, 12, "A validator exception and its repeated callback are free")
                    assertFalse(exerciseState(activity, 0, 0).optBoolean("correct"))
                }
            }
        } finally {
            if (previous == null) registry.remove(type) else registry[type] = previous
        }
    }

    private fun assertMultiChoiceSelectionOrderIdentity() {
        withFixture(MATH, listOf(listOf(mathBlock("math_multiple_choice_multi")))) { scenario ->
            scenario.onActivity { activity ->
                val card = mathCard(content(activity))
                val choices = descendants(card).filterIsInstance<CheckBox>()
                choices[0].performClick()
                choices[1].performClick()
                checkButton(card).performClick()
                assertReserve(MATH, 11, "First wrong choice set")
                choices[0].performClick()
                choices[1].performClick()
                choices[1].performClick()
                choices[0].performClick()
                checkButton(card).performClick()
                assertReserve(MATH, 11, "The same set selected in reverse order is the same answer")
            }
        }
    }

    @Test fun blockAndPageHistoriesStayIndependentAcrossResetAndPageReconstruction() {
        val first = programmingBlock("fill_code").put("question", "Primer ejercicio")
        val second = programmingBlock("fill_code").put("question", "Segundo ejercicio")
        withFixture(PROGRAMMING, listOf(listOf(first, second), listOf(first))) { scenario ->
            scenario.onActivity { activity ->
                var inputs = descendants(content(activity)).filterIsInstance<EditText>()
                inputs[0].setText("wrong-A")
                checkButton(content(activity), 0).performClick()
                inputs[1].setText("wrong-A")
                checkButton(content(activity), 1).performClick()
                assertReserve(PROGRAMMING, 6, "Identical text in two independent blocks costs one per block")
                inputs[0].setText("correct1")
                checkButton(content(activity), 0).performClick()
                assertCorrect(activity, 0, 0)
                resetButton(content(activity), 0).performClick()
                inputs = descendants(content(activity)).filterIsInstance<EditText>()
                assertEquals("Resetting one exercise preserves the other draft", "wrong-A", inputs[1].text.toString())
                assertFalse(exerciseState(activity, 0, 0).optBoolean("correct"))
                assertFalse(exerciseState(activity, 0, 1).optBoolean("correct"))
                inputs[0].setText("wrong-A")
                checkButton(content(activity), 0).performClick()
                checkButton(content(activity), 1).performClick()
                assertReserve(PROGRAMMING, 6, "Resetting a response cannot erase either block's penalty history")
                rebuildPage(activity)
                assertEquals(listOf("wrong-A", "wrong-A"), descendants(content(activity)).filterIsInstance<EditText>().map { it.text.toString() })
                checkButton(content(activity), 0).performClick()
                checkButton(content(activity), 1).performClick()
                assertReserve(PROGRAMMING, 6, "Reconstructing the page preserves both histories")
                showPage(activity, 1)
                descendants(content(activity)).filterIsInstance<EditText>().single().setText("wrong-A")
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 5, "The same block index on a different page has its own history")
                showPage(activity, 0)
                checkButton(content(activity), 0).performClick()
                checkButton(content(activity), 1).performClick()
                assertReserve(PROGRAMMING, 5, "Returning to cached views is free and retains their histories")
            }
        }
    }

    @Test fun zeroReserveBlocksWrongAndCorrectEvaluationOnBothRoutes() {
        for (route in listOf(PROGRAMMING, MATH)) {
            val block = if (route == PROGRAMMING) programmingBlock("multiple_choice") else mathBlock("math_numeric_input")
            withFixture(route, listOf(listOf(block)), reserve = 0) { scenario ->
                scenario.onActivity { activity ->
                    if (route == PROGRAMMING) {
                        val choices = descendants(content(activity)).filterIsInstance<RadioButton>()
                        choices[0].performClick()
                        checkButton(content(activity)).performClick()
                        choices[2].performClick()
                    } else {
                        val input = descendants(content(activity)).filterIsInstance<EditText>().single()
                        input.setText("1")
                        checkButton(content(activity)).performClick()
                        input.setText("3")
                    }
                    checkButton(content(activity)).performClick()
                    assertReserve(route, 0, "A zero reserve cannot evaluate or become negative")
                    assertFalse("Correct-looking input is not marked complete while evaluation is blocked",
                        ProgressRepository.getExerciseState(activity, lessonId(route), 0, 0)?.optBoolean("correct") == true)
                    assertEquals("The injected exercise stays incomplete while evaluation is blocked",
                        0, field(activity, "completedExercises"))
                }
            }
        }
    }

    @Test fun rotationAndRecreationKeepAllPreviouslyPenalizedAnswers() {
        val block = programmingBlock("multiple_choice")
        withFixture(PROGRAMMING, listOf(listOf(block))) { scenario ->
            scenario.onActivity { activity ->
                val choices = descendants(content(activity)).filterIsInstance<RadioButton>()
                choices[0].performClick()
                checkButton(content(activity)).performClick()
                choices[1].performClick()
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 6, "Two distinct wrong choices before rotation")
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await(scenario, "The lesson rotates using its own configuration handling") {
                it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            scenario.onActivity { activity ->
                descendants(content(activity)).filterIsInstance<RadioButton>()[0].performClick()
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 6, "Returning to the first answer after rotation is free")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                // The activity normally reloads authored content; reattach the exact same fixture.
                // Unlike a new test fixture, this must retain the restored attempt ledger.
                injectFixture(activity, PROGRAMMING, listOf(listOf(block)))
                val choices = descendants(content(activity)).filterIsInstance<RadioButton>()
                choices[1].performClick()
                checkButton(content(activity)).performClick()
                choices[0].performClick()
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 6, "Saved-instance state retains A and B, not just the last answer")
                choices[2].performClick()
                checkButton(content(activity)).performClick()
                assertReserve(PROGRAMMING, 6, "A correct answer after recreation is free")
                assertCorrect(activity, 0, 0)
            }
        }
    }

    @Test fun usingTheRealLessonCalculatorDoesNotSpendAttempts() {
        withFixture(MATH, listOf(listOf(mathBlock("math_numeric_input").put("calculator", true)))) { scenario ->
            scenario.onActivity { activity ->
                val answer = descendants(content(activity)).filterIsInstance<EditText>().single()
                answer.setText("1")
                activity.openCalculatorFor(answer)
            }
            val calculator = resumedCalculator()
            try {
                instrumentation.runOnMainSync {
                    val editor = calculator.findViewById<MathExpressionEditor>(R.id.calculator_input)
                    assertTrue(editor.setLatex("2+2"))
                    calculator.findViewById<Button>(R.id.calculator_evaluate).performClick()
                }
                awaitCalculator(calculator) {
                    it.findViewById<View>(R.id.calculator_result_host).visibility == View.VISIBLE &&
                        it.findViewById<Button>(R.id.calculator_evaluate).isEnabled
                }
                instrumentation.runOnMainSync {
                    val result = calculator.findViewById<MathExpressionView>(R.id.calculator_result).expression
                    assertEquals(4.0, (result.children.single() as MathExpression.Number).value.toDouble(), 0.0)
                    assertReserve(MATH, 12, "Real calculator evaluation is not exercise evaluation")
                }
            } finally {
                instrumentation.runOnMainSync { calculator.findViewById<Button>(R.id.calculator_back).performClick() }
                instrumentation.waitForIdleSync()
            }
            scenario.onActivity { activity ->
                assertEquals("1", descendants(content(activity)).filterIsInstance<EditText>().single().text.toString())
                assertReserve(MATH, 12, "Closing the calculator and returning to the lesson is free")
            }
        }
    }

    @Test fun actualCppCompilerDiagnosticsDoNotSpendAttempts() {
        val block = json("""{"type":"cpp_compiler","initialCode":"int main( { return 0; }"}""")
        withFixture(PROGRAMMING, listOf(listOf(block))) { scenario ->
            scenario.onActivity { activity -> button(content(activity), R.string.cpp_runtime_run).performClick() }
            await(scenario, "The offline Clang compile finishes with real diagnostics", timeout = 60_000L) { activity ->
                button(content(activity), R.string.cpp_runtime_run).isEnabled &&
                    descendants(content(activity)).filterIsInstance<TextView>().any {
                        matchesFormattedStatus(it, R.string.cpp_compilation_failed)
                    }
            }
            scenario.onActivity { activity ->
                assertTrue("Real Clang reports an error for invalid C++", descendants(content(activity)).filterIsInstance<TextView>()
                    .any { it.text.contains("error:") })
                assertEquals("The compiler never writes the attempt reserve", 8f, storedReserve(PROGRAMMING), 0f)
                assertEquals("A compiler block does not complete an evaluated exercise in the fixture",
                    0, field(activity, "completedExercises"))
            }
        }
    }

    private enum class Answer(val token: String, val optionIndex: Int, val order: List<Int>) {
        WRONG_A("wrong-A", 0, listOf(1, 0, 2)),
        WRONG_B("wrong-B", 1, listOf(2, 1, 0)),
        CORRECT("correct", 2, listOf(0, 1, 2))
    }

    private fun assertAnswerCycle(
        scenario: ActivityScenario<LessonActivity>,
        route: String,
        label: String,
        afterEdit: () -> Unit = {},
        edit: (LessonActivity, Answer) -> Unit
    ) {
        for ((answer, remaining) in answerCycle(route)) {
            scenario.onActivity { edit(it, answer) }
            afterEdit()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertCycleStep(activity, route, label, answer, remaining)
            }
        }
    }

    private fun answerCycle(route: String): List<Pair<Answer, Int>> {
        val maximum = if (route == MATH) 12 else 8
        return listOf(Answer.WRONG_A to maximum - 1, Answer.WRONG_B to maximum - 2,
            Answer.WRONG_A to maximum - 2, Answer.CORRECT to maximum - 2)
    }

    private fun assertCycleStep(activity: LessonActivity, route: String, label: String, answer: Answer, remaining: Int) {
        val check = checkButton(content(activity))
        assertTrue("$label: the edited answer enables the real check action", check.isEnabled)
        check.performClick()
        check.performClick() // Deliberately invoke the same listener again immediately.
        assertReserve(route, remaining, "$label: $answer and its repeated callback")
        if (answer == Answer.CORRECT) assertCorrect(activity, 0, 0)
        else assertFalse("$label: a wrong answer must remain correctable", exerciseState(activity, 0, 0).optBoolean("correct"))
    }

    private fun withFixture(
        route: String,
        pages: List<List<JSONObject>>,
        reserve: Int = if (route == MATH) 12 else 8,
        test: (ActivityScenario<LessonActivity>) -> Unit
    ) {
        seedReserve(route, reserve)
        ProgressRepository.resetLesson(context, route, lessonId(route))
        ActivityScenario.launch<LessonActivity>(lessonIntent(route)).use { scenario ->
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                injectFixture(it, route, pages)
            }
            instrumentation.waitForIdleSync()
            test(scenario)
        }
    }

    private fun injectFixture(activity: LessonActivity, route: String, pages: List<List<JSONObject>>) {
        val lesson = JSONObject().put("id", lessonId(route)).put("title", "Prueba aislada de intentos")
            .put("pages", JSONArray().apply {
                pages.forEach { blocks -> put(JSONObject().put("blocks", JSONArray().apply { blocks.forEach { put(JSONObject(it.toString())) } })) }
            })
        setField(activity, "lessonJson", lesson)
        setField(activity, "totalPages", pages.size)
        setField(activity, "currentPage", 0)
        listOf("pageViewCache", "pageRequiredExercises", "pageCompletedExercises").forEach { name -> mapField(activity, name).clear() }
        invoke(activity, "renderCurrentPage")
    }

    private fun rebuildPage(activity: LessonActivity) {
        val page = field(activity, "currentPage") as Int
        listOf("pageViewCache", "pageRequiredExercises", "pageCompletedExercises").forEach { mapField(activity, it).remove(page) }
        invoke(activity, "renderCurrentPage")
    }

    private fun showPage(activity: LessonActivity, page: Int) {
        invoke(activity, "saveCurrentPageState")
        setField(activity, "currentPage", page)
        invoke(activity, "renderCurrentPage")
    }

    private fun programmingBlock(type: String): JSONObject = when (type) {
        "fill_code", "output_prediction" -> json("""{"type":"$type","question":"$type: respuesta aislada","code":"// Código original para instrumentación","answers":["correct1"]}""")
        "inline_fill_code" -> json("""{"type":"inline_fill_code","question":"Completa ambos espacios","template":"[] + [];","answers":["correct1","correct2"]}""")
        "multiple_choice" -> json("""{"type":"multiple_choice","question":"Selecciona C","options":["A","B","C"],"correctIndex":2}""")
        "order_code" -> json("""{"type":"order_code","question":"Ordena A, B y C","fragments":["Fragmento A","Fragmento B","Fragmento C"],"correctOrder":[0,1,2]}""")
        "drag_fill_code" -> json("""{"type":"drag_fill_code","question":"Completa en orden A, B y C","template":"[] [] []","choices":["Fragmento A","Fragmento B","Fragmento C"],"correctOrder":[0,1,2]}""")
        else -> error("Unknown programming fixture $type")
    }

    private fun mathBlock(type: String): JSONObject {
        val block = JSONObject().put("type", type).put("prompt", "$type: respuesta aislada").put("label", "Respuesta")
        when (type) {
            "math_multiple_choice_single", "math_multiple_choice_multi" -> block
                .put("options", JSONArray("""[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}]"""))
                .put("validator", json("""{"type":"choice","expected":["c"]}"""))
            "math_numeric_input" -> block.put("validator", json("""{"type":"numeric","expected":"3"}"""))
            "math_expression_input" -> block.put("validator", json("""{"type":"equivalent_expression","expected":"x+3"}"""))
            "math_multi_input", "math_equation_fill_blanks" -> {
                block.put("fields", JSONArray("""[{"id":"a","type":"numeric","label":"Primero"},{"id":"b","type":"numeric","label":"Segundo"}]"""))
                    .put("validator", json("""{"type":"fields","fields":{"a":{"type":"numeric","expected":"3"},"b":{"type":"numeric","expected":"4"}}}"""))
                if (type == "math_equation_fill_blanks") block.put("segments", JSONArray("""[{"field":"a"},{"text":" + "},{"field":"b"}]"""))
            }
            "math_structured_answer" -> block
                .put("fields", JSONArray("""[{"id":"a","type":"dropdown","label":"Opción","options":[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}]},{"id":"b","type":"numeric","label":"Número"}]"""))
                .put("segments", JSONArray("""[{"field":"a"},{"text":" = "},{"field":"b"}]"""))
                .put("validator", json("""{"type":"fields","fields":{"a":{"type":"text","expected":"c"},"b":{"type":"numeric","expected":"4"}}}"""))
            "math_ordering_drag" -> block
                .put("items", JSONArray("""[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}]"""))
                .put("initialOrder", JSONArray("""["c","a","b"]"""))
                .put("validator", json("""{"type":"order","expected":["a","b","c"]}"""))
            "math_matching_drag" -> block
                .put("sources", JSONArray("""[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}]"""))
                .put("targets", JSONArray("""[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}]"""))
                .put("validator", json("""{"type":"matching","expected":{"a":"a","b":"b","c":"c"}}"""))
            "math_number_line" -> block.put("min", -5).put("max", 5).put("step", 1).put("value", 0)
                .put("closed", false).put("direction", "right")
                .put("validator", json("""{"type":"number_line","value":3,"closed":false,"direction":"right"}"""))
            "math_slider_interaction" -> block
                .put("parameters", JSONArray("""[{"id":"value","label":"Valor","min":0,"max":4,"step":1,"value":0}]"""))
                .put("validator", json("""{"type":"sliders","values":{"value":{"type":"numeric","expected":"3"}}}"""))
            "math_coordinate_graph_builder", "math_geometry_builder" -> {
                block.put("bounds", json("""{"xMin":-5,"xMax":5,"yMin":-5,"yMax":5}""")).put("snap", 1)
                if (type == "math_coordinate_graph_builder") block.put("mode", "point")
                    .put("points", JSONArray("""[{"x":0,"y":0,"draggable":true}]"""))
                    .put("validator", json("""{"type":"coordinates","mode":"point","expected":[{"x":3,"y":0}]}"""))
                else block.put("mode", "polygon")
                    .put("points", JSONArray("""[{"x":0,"y":0,"draggable":true},{"x":0,"y":1,"draggable":true},{"x":1,"y":0,"draggable":true}]"""))
                    .put("validator", json("""{"type":"geometry","expected":1.5,"shape":"polygon"}"""))
            }
            "math_rigid_angle_copy" -> {
                fun figure(vertex: String, first: String, second: String) = json("""{"vertex":"$vertex","sides":["first","second"],"region":"counterclockwise","diagram":{"points":[{"id":"$vertex","x":0,"y":0,"labels":["$vertex"]},{"id":"$first","x":4,"y":0,"labels":["$first"]},{"id":"$second","x":0,"y":3,"labels":["$second"]}],"objects":[{"id":"first","kind":"ray","through":["$vertex","$first"],"contains":["$vertex","$first"]},{"id":"second","kind":"ray","through":["$vertex","$second"],"contains":["$vertex","$second"]}]}}""")
                block.put("validator", JSONObject().put("type","rigid_angle_copy").put("reference",figure("O","A","B"))
                    .put("copy",figure("V","C","D")).put("initial",json("""{"x":0,"y":-1,"rotation":0}"""))
                    .put("relation","congruent").put("positionTolerance",.1).put("directionTolerance",.02))
            }
            "math_segment_transport" -> block.put("validator", json("""{"type":"segment_transport","mode":"chain","sources":[{"id":"ab","from":{"x":-3,"y":2,"label":"A"},"to":{"x":0,"y":2,"label":"B"}}],"target":{"origin":{"x":0,"y":0,"label":"M"},"direction":{"x":1,"y":0}},"steps":["ab"],"pointLabels":["N"],"positionTolerance":0.04,"lengthTolerance":0.04}"""))
            else -> error("Unknown math fixture $type")
        }
        return block
    }

    private fun compoundBlock() = json("""{"type":"math_puzzle","schemaVersion":1,"id":"attempt_compound_fixture","prompt":"Responde los dos campos","responses":[{"id":"a","type":"math_numeric_input","label":"Primero","validator":{"type":"numeric","expected":"3"}},{"id":"b","type":"math_numeric_input","label":"Segundo","validator":{"type":"numeric","expected":"4"}}]}""")

    private fun setMathAnswer(card: View, type: String, answer: Answer) {
        val number = when (answer) { Answer.WRONG_A -> "1"; Answer.WRONG_B -> "2"; Answer.CORRECT -> "3" }
        when (type) {
            "math_multiple_choice_single" -> descendants(card).filterIsInstance<RadioButton>()[answer.optionIndex].performClick()
            "math_multiple_choice_multi" -> descendants(card).filterIsInstance<CheckBox>().forEachIndexed { index, choice ->
                if (choice.isChecked != (index == answer.optionIndex)) choice.performClick()
            }
            "math_numeric_input", "math_number_line" -> descendants(card).filterIsInstance<EditText>().single().setText(number)
            "math_expression_input" -> descendants(card).filterIsInstance<EditText>().single().setText("x+$number")
            "math_multi_input", "math_equation_fill_blanks" -> descendants(card).filterIsInstance<EditText>().forEachIndexed { index, input ->
                input.setText(if (answer == Answer.CORRECT && index == 1) "4" else number)
            }
            "math_structured_answer" -> {
                descendants(card).filterIsInstance<Spinner>().single().setSelection(answer.optionIndex + 1)
                descendants(card).filterIsInstance<EditText>().single().setText(if (answer == Answer.CORRECT) "4" else number)
            }
            "math_ordering_drag" -> {
                resetButton(card).performClick()
                answer.order.forEach { index ->
                    val description = card.context.getString(R.string.math_ui_sort_card,
                        ('a'.code + index).toChar().toString(), ('A'.code + index).toChar().toString())
                    descendants(card).first { it.contentDescription?.toString() == description }.performClick()
                }
            }
            "math_matching_drag" -> {
                resetButton(card).performClick()
                answer.order.forEachIndexed { sourceIndex, targetIndex ->
                    val sourceDescription = card.context.getString(R.string.math_ui_select_tile, ('A'.code + sourceIndex).toChar().toString())
                    val targetDescription = card.context.getString(R.string.math_ui_target, ('A'.code + targetIndex).toChar().toString())
                    descendants(card).first { it.contentDescription?.toString() == sourceDescription }.performClick()
                    descendants(card).first { it.contentDescription?.toString() == targetDescription }.performClick()
                }
            }
            "math_slider_interaction" -> {
                val bar = descendants(card).filterIsInstance<SeekBar>().single()
                bar.keyProgressIncrement = 1
                val target = number.toInt()
                while (bar.progress != target) {
                    val key = if (bar.progress < target) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    assertTrue("The native slider key changes its value", bar.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
                }
            }
            "math_coordinate_graph_builder" -> {
                val inputs = descendants(card).filterIsInstance<EditText>()
                inputs[0].setText(number)
                inputs[1].setText("0")
            }
            "math_geometry_builder" -> {
                val x = when (answer) { Answer.WRONG_A -> "2"; Answer.WRONG_B -> "4"; Answer.CORRECT -> "3" }
                val values = listOf("0", "0", "0", "1", x, "0")
                descendants(card).filterIsInstance<EditText>().forEachIndexed { index, input -> input.setText(values[index]) }
            }
            "math_rigid_angle_copy" -> {
                val visual = descendants(card).filterIsInstance<MathRigidAngleCopyView>().single()
                visual.mode = MathRigidAngleCopyView.Mode.MOVE_COPY
                val targetX = when(answer) { Answer.WRONG_A -> 1.0; Answer.WRONG_B -> 2.0; Answer.CORRECT -> 0.0 }
                drag(visual, visual.project(EuclideanPosition(visual.placement.x,visual.placement.y)),
                    visual.project(EuclideanPosition(targetX,0.0)))
            }
            "math_segment_transport" -> {
                val visual = descendants(card).filterIsInstance<MathSegmentTransportView>().single()
                val drawing = requireNotNull(visual.drawingSnapshot())
                val origin = drawing.points.single { it.id=="target:origin" }.center
                val first = drawing.points.single { it.id=="source:ab:from" }.center
                val last = drawing.points.single { it.id=="source:ab:to" }.center
                val scale = kotlin.math.hypot(last.x-first.x,last.y-first.y)/3
                visual.setMode("place")
                val position = EuclideanPosition(origin.x+number.toDouble()*scale,origin.y)
                drag(visual,position,position)
            }
            else -> error("No Android control driver for $type")
        }
    }

    private fun drag(view: View, from: EuclideanPosition, to: EuclideanPosition) {
        val time=SystemClock.uptimeMillis()
        for((action,point) in listOf(MotionEvent.ACTION_DOWN to from,MotionEvent.ACTION_MOVE to to,MotionEvent.ACTION_UP to to)) {
            val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,point.x.toFloat(),point.y.toFloat(),0)
            try { assertTrue("The native geometry surface handles the gesture",view.onTouchEvent(event)) }
            finally { event.recycle() }
        }
    }

    private fun lessonIntent(route: String) = Intent(context, LessonActivity::class.java)
        .putExtra("routeId", route)
        .putExtra("courseId", if (route == MATH) "math_course_01" else "cpp_fundamentals")
        .putExtra("unitId", if (route == MATH) "arithmetic_fundamentals" else "intro_programming")
        .putExtra("lessonId", lessonId(route)).putExtra("lessonNumber", 1).putExtra("lessonTotal", 1)

    private fun lessonId(route: String) = if (route == MATH)
        "math_course_01__arithmetic_fundamentals__math_puzzle_sandbox"
    else "cpp_fundamentals__intro_programming__what_is_programming"

    private fun seedReserve(route: String, available: Int) {
        assertTrue(context.getSharedPreferences(ATTEMPTS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("unlimited", false).putFloat("reserve_$route", available.toFloat())
            .putLong("updated_at_$route", System.currentTimeMillis()).commit())
    }

    private fun storedReserve(route: String) = context.getSharedPreferences(ATTEMPTS_PREFS, Context.MODE_PRIVATE)
        .getFloat("reserve_$route", -1f)

    private fun assertReserve(route: String, expected: Int, reason: String) {
        val state = AttemptRepository.getState(context, route)
        assertEquals("$reason; reserve=${state.reserveUnits}, maximum=${state.maximum}", expected, state.available)
        // Recovery remains active: permit less than one recovered unit, never another penalty.
        assertTrue("$reason; stored reserve=${storedReserve(route)}", storedReserve(route) >= expected.toFloat() - 0.001f)
        assertTrue("$reason; stored reserve=${storedReserve(route)}", storedReserve(route) < expected + 1f)
    }

    private fun exerciseState(activity: LessonActivity, page: Int, block: Int): JSONObject =
        requireNotNull(ProgressRepository.getExerciseState(activity, field(activity, "currentLessonId") as String, page, block))

    private fun assertCorrect(activity: LessonActivity, page: Int, block: Int) {
        assertTrue("A correct evaluated answer is persisted and completes its exercise", exerciseState(activity, page, block).optBoolean("correct"))
    }

    private fun content(activity: LessonActivity): ViewGroup = activity.findViewById(R.id.lessonContent)
    private fun mathCard(root: View): View = descendants(root).single { it.tag == MathPuzzleRenderer.INTERACTION_TAG }
    private fun token(root: View, label: String): TextView = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == label }
    private fun checkButton(root: View, index: Int = 0): Button {
        val labels = listOf(R.string.exercise_check, R.string.exercise_correct,
            R.string.math_ui_check, R.string.math_ui_correct_button).map { root.context.getString(it) }
        return descendants(root).filterIsInstance<Button>()
            .filter { it.tag == MathPuzzleRenderer.CHECK_ACTION_TAG || it.text.toString() in labels }[index]
    }
    private fun resetButton(root: View, index: Int = 0): View {
        val descriptions = listOf(R.string.exercise_reset, R.string.exercise_reset_text,
            R.string.exercise_reset_code, R.string.math_ui_restart).map { root.context.getString(it) }
        return descendants(root).filter { it.contentDescription?.toString() in descriptions }[index]
    }
    private fun button(root: View, textResource: Int): Button {
        val candidates = descendants(root).filterIsInstance<Button>()
        val expected = root.context.getString(textResource)
        return requireNotNull(candidates.singleOrNull { it.text.toString() == expected }) {
            "Expected button '$expected'; actual buttons=${candidates.map { it.text.toString() }}; " +
                "locale=${root.resources.configuration.locales}"
        }
    }
    private fun matchesFormattedStatus(view: TextView, resource: Int): Boolean {
        val pattern = view.context.getText(resource).toString().split("%1\$d")
            .joinToString("[-+]?\\p{Nd}+") { Regex.escape(it) }
        return Regex(pattern).matches(view.text.toString())
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun field(activity: LessonActivity, name: String): Any? = LessonActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(activity: LessonActivity, name: String, value: Any) = LessonActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    @Suppress("UNCHECKED_CAST")
    private fun mapField(activity: LessonActivity, name: String) = field(activity, name) as MutableMap<Int, Any>
    private fun invoke(activity: LessonActivity, name: String) = LessonActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun json(value: String) = JSONObject(value)

    private fun settleCodeAnimation() {
        // drag_fill_code updates its saved slots/check availability after its 350ms animation.
        // Wait on the instrumentation thread, never inside onActivity/the UI thread.
        SystemClock.sleep(400L)
        instrumentation.waitForIdleSync()
    }

    private fun await(scenario: ActivityScenario<LessonActivity>, label: String, timeout: Long = 8_000L, predicate: (LessonActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            instrumentation.waitForIdleSync()
            var done = false
            scenario.onActivity { done = predicate(it) }
            if (done) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(label)
    }

    private fun resumedCalculator(): CalculatorActivity {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        do {
            instrumentation.waitForIdleSync()
            var calculator: CalculatorActivity? = null
            instrumentation.runOnMainSync {
                calculator = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<CalculatorActivity>().singleOrNull()
            }
            calculator?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The real lesson calculator did not resume")
    }

    private fun awaitCalculator(calculator: CalculatorActivity, predicate: (CalculatorActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        do {
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.runOnMainSync { done = predicate(calculator) }
            if (done) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("The real calculator did not produce its expected result")
    }

    companion object {
        private const val PROGRAMMING = "programming"
        private const val MATH = "math_physics"
        private const val ATTEMPTS_PREFS = "attempt_reserve_seconds_test"
    }
}

