package com.learngraphicsprogramming.app.math

import com.learngraphicsprogramming.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Only complete, supported answers produce an evaluated correct/incorrect result. */
data class MathValidationResult(
    val correct: Boolean, val message: String, val evaluable: Boolean = true,
    val uiMessage: MathUiMessage? = null, val fieldId: String? = null
) {
    fun resolveMessage(context: android.content.Context): String = uiMessage?.resolve(context) ?: message
}

interface MathAnswerValidator {
    fun validate(spec: JSONObject, answer: MathAnswer): MathValidationResult
}

/**
 * Validators operate on typed answers. Register another validator without changing a renderer.
 * Numeric answers are rationally exact unless a tolerance is declared. Tolerance is absolute.
 * Legacy expression validators use exact bounded polynomials, not a CAS:
 * +, -, *, / by rational constants, integer powers 0..8, implicit multiplication and pi/π.
 * Expressions have at most one variable; equations have at most two and are compared after
 * moving both sides to the left, up to a nonzero rational factor. Functions, variable
 * denominators and general symbolic equivalence deliberately return an unsupported message.
 * The opt-in parametric_rational_expression validator has its own exact rational-function
 * contract with declared parameters, definitions and nonzero conditions; legacy limits stay intact.
 */
object MathValidators {
    private val registry = mutableMapOf<String, MathAnswerValidator>()

    init {
        register("numeric", ::numeric)
        register("tolerance", ::numeric)
        register("exact_fraction", ::fraction)
        register("equivalent_expression", ::expression)
        register("symbolic", ::expression)
        register("parametric_rational_expression", ::parametricExpression)
        register("text", ::literal)
        register("exact_text", ::literal)
        register("latex", ::latex)
        register("choice", ::choice)
        register("fields", ::fields)
        register("order", ::order)
        register("matching", ::matching)
        register("number_line", ::numberLine)
        register("sliders", ::sliders)
        register("coordinates", ::coordinates)
        register("geometry", ::geometry)
        register("rigid_angle_copy", ::rigidAngle)
        register("segment_transport", SegmentTransport::validate)
    }

    fun register(type: String, validator: MathAnswerValidator) {
        requireMath(type.isNotBlank()) { "El tipo de validador no puede estar vacío." }
        registry[type] = validator
    }

    private fun register(type: String, callback: (JSONObject, MathAnswer) -> MathValidationResult) {
        register(type, object : MathAnswerValidator {
            override fun validate(spec: JSONObject, answer: MathAnswer) = callback(spec, answer)
        })
    }

    fun validate(spec: JSONObject, answer: MathAnswer): MathValidationResult = try {
        val type = spec.optString("type")
        val validator = registry[type]
        if (validator == null) fail(MathUiMessage(R.string.math_ui_unsupported_validator, listOf(type)))
        else if (answer is MathAnswer.Text && answer.value.isBlank()) fail(MathUiMessage(R.string.math_ui_complete_answer))
        else validator.validate(spec, answer)
    } catch (exception: IllegalArgumentException) {
        fail((exception as? MathUiException)?.uiMessage ?: MathUiMessage(R.string.math_ui_validation_error))
    } catch (_: Exception) {
        fail(MathUiMessage(R.string.math_ui_invalid_validation))
    }

    /** Fail malformed lesson content early, before an answer is requested. */
    fun validateSpec(spec: JSONObject) {
        val type = spec.opt("type")
        requireMath(type is String && type.isNotBlank()) { "validator requiere type como texto." }
        requireMath(type in registry) { "Tipo de validador desconocido: $type" }
        tolerance(spec)
        when (type) {
            "rigid_angle_copy" -> RigidAngleCopySpec.parse(spec)
            "segment_transport" -> SegmentTransportSpec.parse(spec)
            "numeric", "tolerance", "exact_fraction" -> number(spec, "expected")
            "equivalent_expression", "symbolic" -> {
                requireMath(spec.opt("expected") is String) { "$type requiere expected como texto." }
                PolynomialExpression.parse(MathExpressionSemantics.polynomialSource(spec.getString("expected")))
            }
            "parametric_rational_expression" -> parametricContract(spec)
            "text", "exact_text" -> requireMath(spec.opt("expected") is String) { "$type requiere expected como texto." }
            "latex" -> {
                requireMath(spec.opt("expected") is String) { "latex requiere expected como texto." }
                MathExpressionSemantics.canonical(spec.getString("expected"))
            }
            "choice", "order" -> stringList(requiredArray(spec, "expected"))
            "matching" -> stringMap(requiredObject(spec, "expected"))
            "fields", "sliders" -> {
                val values = requiredObject(spec, if (type == "fields") "fields" else "values")
                requireMath(values.length() > 0) { "$type requiere al menos un valor esperado." }
                values.keys().forEach { key ->
                    requireMath(key.isNotBlank()) { "Los IDs de $type no pueden estar vacíos." }
                    val validator = values.optJSONObject(key)
                        ?: throw IllegalArgumentException("$type.$key requiere un objeto validador.")
                    validateSpec(validator)
                    val childType = validator.getString("type")
                    val nonTextBuiltins = setOf("choice", "fields", "order", "matching", "number_line", "sliders", "coordinates", "geometry", "rigid_angle_copy", "segment_transport")
                    requireMath(childType !in nonTextBuiltins) { "$type.$key requiere un validador de entrada textual/numeral." }
                }
            }
            "number_line" -> {
                number(spec, if (spec.has("value")) "value" else "expected")
                requireMath(!spec.has("closed") || spec.opt("closed") is Boolean) {
                    "number_line.closed debe ser booleano."
                }
                requireMath(!spec.has("direction") || spec.optString("direction") in setOf("left", "right", "none")) {
                    "number_line.direction debe ser left, right o none."
                }
            }
            "coordinates" -> {
                val mode = spec.optString("mode", "point")
                requireMath(mode in setOf("point", "line", "polygon", "scatter", "parabola")) {
                    "Modo de coordenadas no soportado: $mode"
                }
                val points = points(requiredArray(spec, "expected"))
                requireMath(points.isNotEmpty()) { "coordinates.expected no puede estar vacío." }
                requireMath(when (mode) {
                    "point" -> points.size == 1
                    "line", "parabola" -> points.size == 2
                    "polygon" -> points.size >= 3
                    else -> true
                }) { "Cantidad de puntos incompatible con el modo $mode." }
                if (mode == "line") requireMath(distance(points[0], points[1]) > 1e-12) {
                    "Una recta esperada requiere dos puntos distintos."
                }
                if (mode == "parabola") parabolaCoefficient(points, 1e-12)
                if (mode == "polygon") requireMath(MathGeometry.isSimplePolygon(points)) {
                    "El polígono esperado debe ser simple y no degenerado."
                }
            }
            "geometry" -> {
                requireMath(number(spec, if (spec.has("expected")) "expected" else "area") > 0.0) {
                    "El área esperada debe ser positiva."
                }
                requireMath(!spec.has("shape") || spec.optString("shape") in setOf("polygon", "rectangle")) {
                    "Forma geométrica no soportada."
                }
            }
        }
    }

    private fun rigidAngle(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val placement = (answer as? MathAnswer.RigidAngle)?.placement ?: return wrongKind()
        val condition = RigidAngleCopySpec.parse(spec).evaluate(placement)
        val message = MathUiMessage(when (condition) {
            RigidAngleCondition.SATISFIED -> R.string.math_rigid_satisfied
            RigidAngleCondition.VERTEX -> R.string.math_rigid_review_vertex
            RigidAngleCondition.DIRECTION -> R.string.math_rigid_review_direction
            RigidAngleCondition.FIRST_SIDE -> R.string.math_rigid_review_first_side
            RigidAngleCondition.REGION -> R.string.math_rigid_review_region
            RigidAngleCondition.SECOND_SIDE -> R.string.math_rigid_review_second_side
            RigidAngleCondition.INCLUSION -> R.string.math_rigid_review_inclusion
        })
        return MathValidationResult(condition == RigidAngleCondition.SATISFIED, message.diagnostic, uiMessage = message)
    }

    private fun numeric(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        val value = Rational.parse(text) ?: return fail(MathUiMessage(R.string.math_ui_valid_number_fraction))
        if (!value.toDouble().isFinite()) return fail(MathUiMessage(R.string.math_ui_finite_number))
        val expected = Rational.parse(spec.get("expected").toString())
            ?: return fail(MathUiMessage(R.string.math_ui_invalid_expected_number))
        val tolerance = tolerance(spec, if (spec.optString("type") == "tolerance") 1e-6 else 0.0)
        return result(rationalNear(value, expected, tolerance))
    }

    private fun fraction(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        val value = Rational.parse(text) ?: return fail(MathUiMessage(R.string.math_ui_valid_fraction))
        val expected = Rational.parse(spec.get("expected").toString())
            ?: return fail(MathUiMessage(R.string.math_ui_invalid_expected_fraction))
        val tolerance = tolerance(spec, 0.0)
        return result(rationalNear(value, expected, tolerance))
    }

    private fun expression(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        if (text.isBlank()) return fail(MathUiMessage(R.string.math_ui_write_expression))
        val actual = PolynomialExpression.parse(MathExpressionSemantics.polynomialSource(text))
        val expected = PolynomialExpression.parse(MathExpressionSemantics.polynomialSource(spec.getString("expected")))
        return result(actual == expected)
    }

    private fun parametricContract(spec: JSONObject): ParametricRationalExpression.Contract {
        requireMath(!spec.has("parameterDomain") || spec.opt("parameterDomain") == "rational") {
            "parametric_rational_expression sólo admite parámetros racionales."
        }
        requireMath(tolerance(spec, 0.0) == 0.0) { "La comparación paramétrica debe ser exacta, sin tolerancia." }
        val parameters = stringList(requiredArray(spec, "parameters"))
        val assumptions = stringList(requiredArray(spec, "nonzero"))
        val rawDefinitions = requiredObject(spec, "definitions")
        val definitions = rawDefinitions.keys().asSequence().associateWith { key ->
            requireMath(rawDefinitions.opt(key) is String) { "La definición $key debe ser una expresión textual." }
            parametricSource(rawDefinitions.getString(key))
        }
        requireMath(spec.opt("expected") is String) { "El esperado paramétrico debe ser una expresión textual." }
        return ParametricRationalExpression.compile(parameters, definitions,
            assumptions.map(::parametricSource), parametricSource(spec.getString("expected")))
    }

    private fun parametricSource(source: String): String {
        if (source.length > ParametricRationalExpression.MAX_SOURCE_LENGTH)
            throw ParametricRationalExpression.ResourceLimitException("source length")
        return MathExpressionSemantics.polynomialSource(source)
    }

    private fun parametricExpression(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        // Invalid content is technical; only an answer's unsupported syntax gets retry guidance.
        val contract = parametricContract(spec)
        return try {
            when (contract.compare(parametricSource(text))) {
                ParametricRationalExpression.Comparison.CORRECT -> result(true)
                ParametricRationalExpression.Comparison.VALUE_MISMATCH -> {
                    val message = MathUiMessage(R.string.math_ui_parametric_value_mismatch)
                    MathValidationResult(false, message.diagnostic, uiMessage = message)
                }
                ParametricRationalExpression.Comparison.DOMAIN_MISMATCH -> {
                    val message = MathUiMessage(R.string.math_ui_parametric_domain_mismatch)
                    MathValidationResult(false, message.diagnostic, uiMessage = message)
                }
            }
        } catch (_: ParametricRationalExpression.ResourceLimitException) {
            fail(MathUiMessage(R.string.math_ui_parametric_resource_limit))
        } catch (_: ParametricRationalExpression.UnsupportedException) {
            fail(MathUiMessage(R.string.math_ui_parametric_unsupported))
        }
    }

    private fun literal(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        return result(text == spec.getString("expected"))
    }

    private fun latex(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val text = (answer as? MathAnswer.Text)?.value ?: return wrongKind()
        val actual = MathExpressionSemantics.canonical(text)
        val expected = MathExpressionSemantics.canonical(spec.getString("expected"))
        val actualTree = (MathLatexParser.parse(actual) as MathLatexParser.Result.Success).expression
        val expectedTree = (MathLatexParser.parse(expected) as MathLatexParser.Result.Success).expression
        // A puzzle may deliberately request a single sign. For ordinary formulas,
        // structural slots alone cannot detect an unfinished input such as x+.
        if (!operatorLiteral(expectedTree) && !completeOperands(actualTree)) return fail(MathUiMessage(R.string.math_ui_complete_operands))
        return result(actual == expected)
    }

    private fun operatorLiteral(expression: MathExpression.Sequence): Boolean = expression.children.singleOrNull().let {
        it is MathExpression.Operator || it is MathExpression.Relation ||
            it is MathExpression.Symbol && it.value in setOf("%", "!", "∪", "∩", "∈")
    }

    /** Checks missing operands in notation; this is not a new evaluator or parser. */
    private fun completeOperands(expression: MathExpression): Boolean {
        if (expression !is MathExpression.Sequence) return expression.childSlots().all { completeOperands(it.second) }
        var needsOperand = true
        for (child in expression.children) {
            val operator = when (child) {
                is MathExpression.Operator -> child.value
                is MathExpression.Relation -> "relation"
                is MathExpression.Symbol -> child.value.takeIf { it == "%" || it == "!" }
                else -> null
            }
            when (operator) {
                "+", "-", "−" -> needsOperand = true // Also allow unary signs.
                "*", "×", "·", "/", "÷", "^", "nCr", "nPr", "relation" -> {
                    if (needsOperand) return false
                    needsOperand = true
                }
                "%", "!" -> if (needsOperand) return false
                else -> {
                    // A postfix's superscript is attached to its punctuation leaf in
                    // LaTeX (5!^2), while the preceding value supplies its operand.
                    val postfix = (child as? MathExpression.Scripts)?.base?.children?.singleOrNull()
                    val postfixPower = postfix is MathExpression.Operator && postfix.value in setOf("%", "!") ||
                        postfix is MathExpression.Symbol && postfix.value in setOf("%", "!")
                    if (postfixPower) {
                        if (needsOperand) return false
                        val scripts = child as MathExpression.Scripts
                        if (listOfNotNull(scripts.upper, scripts.lower).any { !completeOperands(it) }) return false
                    } else {
                        if (!completeOperands(child)) return false
                        needsOperand = false
                    }
                }
            }
        }
        return !needsOperand
    }

    private fun choice(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val selected = (answer as? MathAnswer.Choice)?.selected ?: return wrongKind()
        if (selected.isEmpty() || selected.any { it.isBlank() }) return fail(MathUiMessage(R.string.math_ui_select_answer))
        return result(selected == stringList(spec.getJSONArray("expected")).toSet())
    }

    private fun fields(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val values = (answer as? MathAnswer.Fields)?.values ?: return wrongKind()
        val expected = spec.getJSONObject("fields")
        val keys = expected.keys().asSequence().toSet()
        if (values.keys != keys) return fail(MathUiMessage(R.string.math_ui_complete_fields))
        val results = keys.map { key ->
            key to validate(expected.getJSONObject(key), MathAnswer.Text(values.getValue(key)))
        }
        return combinedResults(results)
    }

    private fun order(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val ids = (answer as? MathAnswer.Order)?.ids ?: return wrongKind()
        val expected = stringList(spec.getJSONArray("expected"))
        if (ids.size != expected.size || ids.toSet() != expected.toSet()) return fail(MathUiMessage(R.string.math_ui_place_all_cards))
        return result(ids == expected)
    }

    private fun matching(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val pairs = (answer as? MathAnswer.Matching)?.pairs ?: return wrongKind()
        val expected = stringMap(spec.getJSONObject("expected"))
        if (pairs.keys != expected.keys || pairs.values.any { it.isBlank() } || pairs.values.toSet().size != pairs.size) {
            return fail(MathUiMessage(R.string.math_ui_match_all_tiles))
        }
        return result(pairs == expected)
    }

    private fun numberLine(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val value = answer as? MathAnswer.NumberLine ?: return wrongKind()
        if (!value.value.isFinite() || value.direction !in setOf("left", "right", "none")) return fail(MathUiMessage(R.string.math_ui_check_line))
        val expected = number(spec, if (spec.has("value")) "value" else "expected")
        return result(
            value.value.isFinite() && near(value.value, expected, tolerance(spec)) &&
                (!spec.has("closed") || value.closed == spec.getBoolean("closed")) &&
                (!spec.has("direction") || value.direction == spec.getString("direction"))
        )
    }

    private fun sliders(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val values = (answer as? MathAnswer.Sliders)?.values ?: return wrongKind()
        val expected = spec.getJSONObject("values")
        val keys = expected.keys().asSequence().toSet()
        if (values.keys != keys || values.values.any { !it.isFinite() }) return fail(MathUiMessage(R.string.math_ui_check_controls))
        val results = keys.map { key ->
            val itemValidator = JSONObject(expected.getJSONObject(key).toString())
            // Sliders compute Double values, so their internal roundoff is expected. Text
            // numeric inputs remain exact; a slider author can explicitly choose tolerance: 0.
            if (itemValidator.optString("type") == "numeric" && !itemValidator.has("tolerance")) itemValidator.put("tolerance", 1e-6)
            key to validate(itemValidator, MathAnswer.Text(values.getValue(key).toString()))
        }
        return combinedResults(results)
    }

    private fun coordinates(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val actual = (answer as? MathAnswer.Graph)?.points ?: return wrongKind()
        if (actual.any { !it.x.isFinite() || !it.y.isFinite() }) return fail(MathUiMessage(R.string.math_ui_finite_coordinates))
        val expected = points(spec.getJSONArray("expected"))
        if (actual.size < expected.size) return fail(MathUiMessage(R.string.math_ui_complete_points))
        val tolerance = tolerance(spec)
        val mode = spec.optString("mode", "point")
        val correct = when (mode) {
            "point" -> actual.size == 1 && expected.size == 1 && closePoint(actual[0], expected[0], tolerance)
            "line" -> {
                if (actual.size != 2 || expected.size != 2) false
                else {
                    val actualSpan = distance(actual[0], actual[1])
                    val span = distance(expected[0], expected[1])
                    requireMath(actualSpan.isFinite() && span.isFinite()) { MathUiMessage(R.string.math_ui_evaluate_finite_line) }
                    actualSpan > 1e-12 && span > 1e-12 && actual.all {
                        val offset = abs(cross(expected[0], expected[1], it)) / span
                        requireMath(offset.isFinite()) { MathUiMessage(R.string.math_ui_evaluate_finite_line) }
                        offset <= tolerance
                    }
                }
            }
            "scatter" -> unorderedPoints(actual, expected, tolerance)
            "polygon" -> {
                requireMath(MathGeometry.area(actual).isFinite() && MathGeometry.area(expected).isFinite()) { MathUiMessage(R.string.math_ui_evaluate_area) }
                cyclicPoints(actual, expected, tolerance)
            }
            "parabola" -> {
                if (actual.size != 2 || expected.size != 2 || !closePoint(actual[0], expected[0], tolerance)) false
                else {
                    val expectedA = parabolaCoefficient(expected, 1e-12)
                    val dx = actual[1].x - actual[0].x
                    val dy = actual[1].y - actual[0].y
                    requireMath(dx.isFinite() && dy.isFinite() && (dx * dx).isFinite()) { MathUiMessage(R.string.math_ui_evaluate_parabola) }
                    if (abs(dx) > 1e-12) requireMath((dy / (dx * dx)).isFinite()) { MathUiMessage(R.string.math_ui_evaluate_parabola) }
                    val actualA = try { parabolaCoefficient(actual, 1e-12) } catch (_: IllegalArgumentException) { null }
                    actualA != null && near(actualA, expectedA, tolerance)
                }
            }
            else -> return fail(MathUiMessage(R.string.math_ui_unsupported_graph_mode, listOf(mode)))
        }
        return result(correct)
    }

    private fun geometry(spec: JSONObject, answer: MathAnswer): MathValidationResult {
        val points = (answer as? MathAnswer.Graph)?.points ?: return wrongKind()
        if (points.size < 3) return fail(MathUiMessage(R.string.math_ui_complete_vertices))
        if (points.size > 100 || points.any { !it.x.isFinite() || !it.y.isFinite() } || !MathGeometry.area(points).isFinite()) {
            return fail(MathUiMessage(R.string.math_ui_evaluate_finite_shape))
        }
        if (!MathGeometry.isSimplePolygon(points)) return result(false).copy(message = "invalid polygon", uiMessage = MathUiMessage(R.string.math_ui_simple_polygon))
        val expected = number(spec, if (spec.has("expected")) "expected" else "area")
        val tolerance = tolerance(spec)
        if (spec.optString("shape", "polygon") == "rectangle" && !MathGeometry.isRectangle(points, tolerance)) {
            return result(false).copy(message = "rectangle required", uiMessage = MathUiMessage(R.string.math_ui_rectangle_required))
        }
        return result(near(MathGeometry.area(points), expected, tolerance))
    }

    private fun unorderedPoints(actual: List<MathPoint>, expected: List<MathPoint>, tolerance: Double): Boolean {
        if (actual.size != expected.size) return false
        // Bipartite matching avoids a greedy ambiguity when two tolerance regions overlap.
        val matched = IntArray(expected.size) { -1 }
        fun match(index: Int, visited: BooleanArray): Boolean {
            for (target in expected.indices) {
                if (!visited[target] && closePoint(actual[index], expected[target], tolerance)) {
                    visited[target] = true
                    if (matched[target] == -1 || match(matched[target], visited)) {
                        matched[target] = index
                        return true
                    }
                }
            }
            return false
        }
        return actual.indices.all { match(it, BooleanArray(expected.size)) }
    }

    private fun cyclicPoints(actual: List<MathPoint>, expected: List<MathPoint>, tolerance: Double): Boolean {
        if (actual.size != expected.size || actual.size < 3 || !MathGeometry.isSimplePolygon(actual)) return false
        return expected.indices.any { start ->
            actual.indices.all { closePoint(actual[it], expected[(start + it) % expected.size], tolerance) } ||
                actual.indices.all { closePoint(actual[it], expected[(start - it + expected.size) % expected.size], tolerance) }
        }
    }

    private fun parabolaCoefficient(points: List<MathPoint>, epsilon: Double): Double {
        requireMath(points.size == 2) { MathUiMessage(R.string.math_ui_parabola_points) }
        val dx = points[1].x - points[0].x
        requireMath(abs(dx) > epsilon) { MathUiMessage(R.string.math_ui_parabola_x) }
        val a = (points[1].y - points[0].y) / (dx * dx)
        requireMath(a.isFinite() && abs(a) > epsilon) { MathUiMessage(R.string.math_ui_nondegenerate_parabola) }
        return a
    }

    private fun tolerance(spec: JSONObject, default: Double = 1e-6): Double {
        val value = if (spec.has("tolerance")) spec.optDouble("tolerance", Double.NaN) else default
        requireMath(value.isFinite() && value >= 0.0) { MathUiMessage(R.string.math_ui_valid_tolerance) }
        return value
    }

    private fun number(spec: JSONObject, key: String): Double {
        val value = Rational.parse(spec.opt(key)?.toString() ?: "")?.toDouble()
        requireMath(value != null && value.isFinite()) { "validator.$key requiere un número finito." }
        return value
    }

    private fun requiredArray(spec: JSONObject, key: String): JSONArray = spec.optJSONArray(key)
        ?: throw IllegalArgumentException("validator.$key requiere un arreglo.")

    private fun requiredObject(spec: JSONObject, key: String): JSONObject = spec.optJSONObject(key)
        ?: throw IllegalArgumentException("validator.$key requiere un objeto.")

    private fun stringList(values: JSONArray): List<String> = (0 until values.length()).map {
        val value = values.get(it)
        requireMath(value is String && value.isNotBlank()) { "Los IDs esperados deben ser textos no vacíos." }
        value
    }.also { requireMath(it.distinct().size == it.size) { "Los IDs esperados no pueden repetirse." } }

    private fun stringMap(values: JSONObject): Map<String, String> = values.keys().asSequence().associateWith {
        val value = values.get(it)
        requireMath(value is String && it.isNotBlank() && value.isNotBlank()) { "Los pares esperados requieren IDs de texto." }
        value
    }

    private fun points(values: JSONArray): List<MathPoint> {
        requireMath(values.length() <= 100) { "Máximo 100 puntos por validador." }
        return (0 until values.length()).map {
            val point = values.optJSONObject(it) ?: throw IllegalArgumentException("Cada punto requiere x e y.")
            MathPoint(number(point, "x"), number(point, "y"))
        }
    }

    private fun near(a: Double, b: Double, tolerance: Double) = a.isFinite() && b.isFinite() && abs(a - b) <= tolerance
    private fun rationalNear(a: Rational, b: Rational, tolerance: Double): Boolean {
        if (tolerance == 0.0) return a == b
        val exactTolerance = Rational.parse(tolerance.toString()) ?: return false
        return (a - b).absolute() <= exactTolerance
    }
    private fun closePoint(a: MathPoint, b: MathPoint, tolerance: Double) = near(a.x, b.x, tolerance) && near(a.y, b.y, tolerance)
    private fun combinedResults(results: List<Pair<String, MathValidationResult>>): MathValidationResult {
        // An earlier wrong field must not hide another field's parser/internal error.
        val failure = results.firstOrNull { !it.second.evaluable } ?: results.firstOrNull { !it.second.correct }
        return failure?.let { (key, checked) -> checked.copy(message = "$key: ${checked.message}", fieldId = key) } ?: result(true)
    }
    private fun result(correct: Boolean): MathValidationResult {
        val message = MathUiMessage(if (correct) R.string.math_ui_correct else R.string.math_ui_incorrect)
        return MathValidationResult(correct, message.diagnostic, uiMessage = message)
    }
    private fun fail(message: MathUiMessage) = MathValidationResult(false, message.diagnostic, evaluable = false, uiMessage = message)
    private fun wrongKind() = fail(MathUiMessage(R.string.math_ui_wrong_answer_kind))
}

/** Pure polygon helpers; crossings and degeneracy are rejected before area can be accepted. */
object MathGeometry {
    fun area(points: List<MathPoint>): Double {
        if (points.size < 3) return 0.0
        // Subtract a local origin before the shoelace sum. Translating a small figure far
        // from (0,0) otherwise cancels large products and can lose its whole area.
        val origin = points.first()
        return abs((1 until points.lastIndex).sumOf { cross(origin, points[it], points[it + 1]) }) / 2.0
    }

    fun isSimplePolygon(points: List<MathPoint>): Boolean {
        if (points.size < 3 || points.size > 100 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        if (points.indices.any { distance(points[it], points[(it + 1) % points.size]) <= 1e-10 }) return false
        if (!area(points).isFinite() || area(points) <= 1e-10) return false
        for (i in points.indices) for (j in i + 1 until points.size) {
            if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
            if (intersects(points[i], points[(i + 1) % points.size], points[j], points[(j + 1) % points.size])) return false
        }
        // A reversal at adjacent collinear edges overlaps even though the edges share an endpoint.
        for (i in points.indices) {
            val before = points[(i - 1 + points.size) % points.size]
            val here = points[i]
            val after = points[(i + 1) % points.size]
            if (abs(cross(before, here, after)) <= 1e-10 &&
                (before.x - here.x) * (after.x - here.x) + (before.y - here.y) * (after.y - here.y) > 0) return false
        }
        return true
    }

    fun isRectangle(points: List<MathPoint>, tolerance: Double = 1e-6): Boolean {
        if (points.size != 4 || !isSimplePolygon(points)) return false
        return points.indices.all {
            val previous = points[(it + 3) % 4]
            val next = points[(it + 1) % 4]
            val here = points[it]
            val dot = (previous.x - here.x) * (next.x - here.x) + (previous.y - here.y) * (next.y - here.y)
            abs(dot) / (distance(previous, here) * distance(next, here)) <= max(tolerance, 1e-10)
        }
    }

    private fun intersects(a: MathPoint, b: MathPoint, c: MathPoint, d: MathPoint): Boolean {
        val first = cross(a, b, c)
        val second = cross(a, b, d)
        val third = cross(c, d, a)
        val fourth = cross(c, d, b)
        if (((first > 0 && second < 0) || (first < 0 && second > 0)) &&
            ((third > 0 && fourth < 0) || (third < 0 && fourth > 0))) return true
        fun onSegment(p: MathPoint, q: MathPoint, r: MathPoint, cross: Double): Boolean =
            abs(cross) <= 1e-10 && r.x >= minOf(p.x, q.x) - 1e-10 && r.x <= maxOf(p.x, q.x) + 1e-10 &&
                r.y >= minOf(p.y, q.y) - 1e-10 && r.y <= maxOf(p.y, q.y) + 1e-10
        return onSegment(a, b, c, first) || onSegment(a, b, d, second) ||
            onSegment(c, d, a, third) || onSegment(c, d, b, fourth)
    }
}

private fun cross(a: MathPoint, b: MathPoint, c: MathPoint): Double =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun distance(a: MathPoint, b: MathPoint) = hypot(a.x - b.x, a.y - b.y)

private data class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
    operator fun plus(other: Rational) = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    operator fun unaryMinus() = of(-numerator, denominator)
    operator fun minus(other: Rational) = this + -other
    operator fun times(other: Rational) = of(numerator * other.numerator, denominator * other.denominator)
    operator fun div(other: Rational) = of(numerator * other.denominator, denominator * other.numerator)
    fun toDouble() = BigDecimal(numerator).divide(BigDecimal(denominator), MathContext.DECIMAL64).toDouble()
    fun isZero() = numerator == BigInteger.ZERO
    fun absolute() = of(numerator.abs(), denominator)
    operator fun compareTo(other: Rational): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)

    companion object {
        val ZERO = of(BigInteger.ZERO, BigInteger.ONE)
        val ONE = of(BigInteger.ONE, BigInteger.ONE)
        fun of(numerator: BigInteger, denominator: BigInteger): Rational {
            requireMath(denominator != BigInteger.ZERO) { MathUiMessage(R.string.math_ui_nonzero_denominator) }
            val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
            val gcd = numerator.gcd(denominator)
            return Rational(numerator / gcd * sign, denominator / gcd * sign)
        }

        fun parse(raw: String): Rational? {
            return try {
            var text = raw.trim().replace('−', '-').replace(',', '.')
            if (text.length > 256 || text.isEmpty()) return null
            val percent = text.endsWith('%')
            if (percent) text = text.dropLast(1).trim()
            val parts = text.split('/')
            if (parts.size !in 1..2) return null
            fun decimal(value: String): Rational {
                val decimal = BigDecimal(value.trim())
                requireMath(abs(decimal.scale()) <= 1000 && decimal.precision() <= 256)
                return if (decimal.scale() >= 0) of(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()))
                    else of(decimal.unscaledValue() * BigInteger.TEN.pow(-decimal.scale()), BigInteger.ONE)
            }
            var value = decimal(parts[0])
            if (parts.size == 2) value /= decimal(parts[1])
            if (percent) value /= of(BigInteger.valueOf(100), BigInteger.ONE)
            value
            } catch (_: Exception) { null }
        }
    }
}

private data class Monomial(val powers: Map<String, Int>) {
    fun times(other: Monomial): Monomial {
        val merged = powers.toMutableMap()
        other.powers.forEach { (name, power) -> merged[name] = (merged[name] ?: 0) + power }
        requireMath(merged.values.sum() <= 16) { UNSUPPORTED_EXPRESSION }
        return Monomial(merged)
    }
    fun sortKey() = powers.toSortedMap().entries.joinToString(";") { "${it.key}:${it.value}" }
}

private data class Polynomial(val terms: Map<Monomial, Rational>) {
    operator fun plus(other: Polynomial): Polynomial {
        val sum = terms.toMutableMap()
        other.terms.forEach { (key, value) -> sum[key] = (sum[key] ?: Rational.ZERO) + value }
        return normalized(sum)
    }
    operator fun unaryMinus() = normalized(terms.mapValues { -it.value })
    operator fun minus(other: Polynomial) = this + -other
    operator fun times(other: Polynomial): Polynomial {
        var result = constant(Rational.ZERO)
        for ((a, av) in terms) for ((b, bv) in other.terms) result += normalized(mapOf(a.times(b) to av * bv))
        return result
    }
    fun divide(other: Polynomial): Polynomial {
        val divisor = other.terms[Monomial(emptyMap())]
        requireMath(other.terms.size == 1 && divisor != null && !divisor.isZero()) { UNSUPPORTED_EXPRESSION }
        return normalized(terms.mapValues { it.value / divisor })
    }
    fun pow(exponent: Int): Polynomial {
        requireMath(exponent in 0..8) { UNSUPPORTED_EXPRESSION }
        var value = constant(Rational.ONE)
        repeat(exponent) { value *= this }
        return value
    }
    fun equationNormalized(): Polynomial {
        val pivot = terms.entries.minByOrNull { it.key.sortKey() }?.value ?: return this
        return normalized(terms.mapValues { it.value / pivot })
    }
    fun variables(): Set<String> = terms.keys.flatMap { it.powers.keys }.filter { it != "pi" }.toSet()

    companion object {
        fun constant(value: Rational) = normalized(mapOf(Monomial(emptyMap()) to value))
        fun variable(name: String) = Polynomial(mapOf(Monomial(mapOf(name to 1)) to Rational.ONE))
        private fun normalized(terms: Map<Monomial, Rational>): Polynomial {
            val cleaned = terms.filterValues { !it.isZero() }
            requireMath(cleaned.size <= 128) { UNSUPPORTED_EXPRESSION }
            return Polynomial(cleaned)
        }
    }
}

private val UNSUPPORTED_EXPRESSION = MathUiMessage(R.string.math_ui_unsupported_expression)

private data class PolynomialExpression(val polynomial: Polynomial, val equation: Boolean) {
    companion object {
        fun parse(raw: String): PolynomialExpression {
            requireMath(raw.isNotBlank() && raw.length <= 256) { UNSUPPORTED_EXPRESSION }
            val parts = raw.split('=')
            requireMath(parts.size in 1..2 && parts.all { it.isNotBlank() }) { UNSUPPORTED_EXPRESSION }
            val left = ExpressionParser(parts[0]).parse()
            if (parts.size == 1) {
                requireMath(left.variables().size <= 1) { UNSUPPORTED_EXPRESSION }
                return PolynomialExpression(left, false)
            }
            val difference = left - ExpressionParser(parts[1]).parse()
            requireMath(difference.variables().size <= 2) { UNSUPPORTED_EXPRESSION }
            return PolynomialExpression(difference.equationNormalized(), true)
        }
    }
}

/** Small recursive descent parser. No eval, reflection, scripting engine or numerical sampling. */
private class ExpressionParser(raw: String) {
    private val source = raw.replace('−', '-').replace('×', '*').replace('·', '*').replace('÷', '/').replace(',', '.')
    private var position = 0
    private var operations = 0

    fun parse(): Polynomial {
        val value = sum()
        skipSpaces()
        requireMath(position == source.length) { UNSUPPORTED_EXPRESSION }
        return value
    }

    private fun sum(): Polynomial {
        var value = product()
        while (true) {
            value = when {
                consume('+') -> value + product()
                consume('-') -> value - product()
                else -> return value
            }
        }
    }

    private fun product(): Polynomial {
        var value = unary()
        while (true) {
            value = when {
                consume('*') -> value * unary()
                consume('/') -> value.divide(unary())
                beginsAtom() -> value * unary()
                else -> return value
            }
            requireMath(++operations <= 256) { UNSUPPORTED_EXPRESSION }
        }
    }

    private fun unary(): Polynomial = when {
        consume('+') -> unary()
        consume('-') -> -unary()
        else -> power()
    }

    private fun power(): Polynomial {
        val value = atom()
        if (!consume('^')) return value
        skipSpaces()
        val start = position
        while (position < source.length && source[position].isDigit()) position++
        requireMath(position > start) { UNSUPPORTED_EXPRESSION }
        val exponent = source.substring(start, position).toIntOrNull()
        requireMath(exponent != null && exponent in 0..8) { UNSUPPORTED_EXPRESSION }
        return value.pow(exponent)
    }

    private fun atom(): Polynomial {
        skipSpaces()
        requireMath(position < source.length) { UNSUPPORTED_EXPRESSION }
        if (consume('(')) {
            val value = sum()
            requireMath(consume(')')) { UNSUPPORTED_EXPRESSION }
            return value
        }
        if (source[position].isDigit() || source[position] == '.') {
            val start = position
            while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
            val rational = Rational.parse(source.substring(start, position))
            requireMath(rational != null) { UNSUPPORTED_EXPRESSION }
            return Polynomial.constant(rational)
        }
        if (source[position] == 'π') { position++; return Polynomial.variable("pi") }
        if (source.startsWith("pi", position)) { position += 2; return Polynomial.variable("pi") }
        val letter = source[position]
        requireMath(letter in 'a'..'z' && letter !in setOf('e', 'i')) { UNSUPPORTED_EXPRESSION }
        position++
        // Allow xy as multiplication, but reject function-like identifiers such as sin/cos.
        requireMath(!source.substring(position - 1).startsWith("sin") &&
            !source.substring(position - 1).startsWith("cos") &&
            !source.substring(position - 1).startsWith("tan") &&
            !source.substring(position - 1).startsWith("sqrt") &&
            !source.substring(position - 1).startsWith("log")) { UNSUPPORTED_EXPRESSION }
        return Polynomial.variable(letter.toString())
    }

    private fun beginsAtom(): Boolean {
        skipSpaces()
        if (position >= source.length) return false
        val char = source[position]
        return char == '(' || char == '.' || char.isDigit() || char in 'a'..'z' || char == 'π'
    }

    private fun consume(char: Char): Boolean {
        skipSpaces()
        return if (position < source.length && source[position] == char) { position++; true } else false
    }

    private fun skipSpaces() { while (position < source.length && source[position].isWhitespace()) position++ }
}
