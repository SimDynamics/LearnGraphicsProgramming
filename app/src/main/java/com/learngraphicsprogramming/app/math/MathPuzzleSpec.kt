package com.learngraphicsprogramming.app.math

import org.json.JSONObject

/** Content describes a composition; neither topics nor pedagogical templates are engine types. */
data class MathResponseSpec(
    val id: String,
    val type: String,
    val config: JSONObject,
    val validator: JSONObject
)

data class MathPuzzleSpec(
    val prompt: String,
    val representations: List<JSONObject>,
    val tools: List<JSONObject>,
    val responses: List<MathResponseSpec>,
    val correctFeedback: String,
    val incorrectFeedback: String
)

object MathPuzzleTypes {
    val responseTypes: Set<String> = setOf(
        "math_multiple_choice_single", "math_multiple_choice_multi", "math_numeric_input",
        "math_multi_input", "math_expression_input", "math_structured_answer",
        "math_equation_fill_blanks", "math_ordering_drag", "math_number_line",
        "math_slider_interaction", "math_matching_drag", "math_coordinate_graph_builder",
        "math_geometry_builder", "math_rigid_angle_copy", "math_segment_transport"
    )

    fun isExercise(type: String): Boolean = type == "math_puzzle" || type in responseTypes
}

/** Version 1. A standalone response is normalized into the same composition as math_puzzle. */
object MathPuzzleParser {
    fun parse(block: JSONObject): MathPuzzleSpec {
        val type = requiredString(block, "type", "puzzle")
        require(MathPuzzleTypes.isExercise(type)) { "Tipo de puzzle matemático desconocido: $type" }
        for (key in listOf("schemaVersion", "version")) {
            require(!block.has(key) || (block.opt(key) as? Number)?.toDouble() == 1.0) {
                "Versión de puzzle matemático no soportada; se requiere schemaVersion: 1."
            }
        }
        val promptKey = if (block.has("prompt")) "prompt" else "question"
        val prompt = requiredString(block, promptKey, "puzzle")
        val representations = objects(block, "representations")
        val tools = objects(block, "tools")
        (representations + tools).forEachIndexed { index, value ->
            requiredString(value, "type", "representación/herramienta $index")
        }
        val rawResponses = if (type == "math_puzzle") {
            require(block.has("responses")) { "math_puzzle requiere responses." }
            objects(block, "responses")
        } else {
            listOf(JSONObject(block.toString()).put("id", "response"))
        }
        require(rawResponses.isNotEmpty()) { "El puzzle requiere al menos una respuesta." }
        val seenIds = mutableSetOf<String>()
        val responses = rawResponses.mapIndexed { index, raw ->
            val id = requiredString(raw, "id", "respuesta $index")
            require(seenIds.add(id)) { "ID de respuesta duplicado: $id" }
            val responseType = requiredString(raw, "type", "respuesta $id")
            require(responseType in MathPuzzleTypes.responseTypes) {
                "Tipo de respuesta matemática desconocido: $responseType"
            }
            val validator = raw.optJSONObject("validator")
                ?: throw IllegalArgumentException("La respuesta $id requiere validator como objeto.")
            MathValidators.validateSpec(validator)
            validateResponse(raw, responseType, validator, id)
            MathShortcutResolver.validateResponseConfiguration(raw, validator, responseType)
            MathResponseSpec(id, responseType, JSONObject(raw.toString()), JSONObject(validator.toString()))
        }
        val feedback = if (block.has("feedback")) {
            block.optJSONObject("feedback")
                ?: throw IllegalArgumentException("feedback debe ser un objeto.")
        } else null
        return MathPuzzleSpec(
            prompt, representations, tools, responses,
            feedback?.optString("correct").orEmpty(),
            feedback?.optString("incorrect").orEmpty()
        )
    }

    private fun requiredString(value: JSONObject, key: String, owner: String): String {
        val raw = value.opt(key)
        require(raw is String && raw.isNotBlank()) { "$owner requiere $key como texto no vacío." }
        return raw
    }

    private fun validatorFamily(type: String): String? = when (type) {
        "numeric", "tolerance", "exact_fraction", "equivalent_expression", "symbolic", "parametric_rational_expression", "text", "exact_text", "latex" -> "text"
        "choice", "fields", "order", "matching", "number_line", "sliders" -> type
        "coordinates", "geometry" -> "graph"
        "rigid_angle_copy" -> "rigid_angle"
        "segment_transport" -> "segment_transport"
        else -> null // Registered extensions define their own answer contract.
    }

    private fun validateResponse(raw: JSONObject, type: String, validator: JSONObject, id: String) {
        val family = when (type) {
            "math_multiple_choice_single", "math_multiple_choice_multi" -> "choice"
            "math_numeric_input", "math_expression_input" -> if (raw.has("fields")) "fields" else "text"
            "math_multi_input", "math_structured_answer", "math_equation_fill_blanks" -> "fields"
            "math_ordering_drag" -> "order"
            "math_matching_drag" -> "matching"
            "math_number_line" -> "number_line"
            "math_slider_interaction" -> "sliders"
            "math_rigid_angle_copy" -> "rigid_angle"
            "math_segment_transport" -> "segment_transport"
            else -> "graph"
        }
        val validatorType = validator.getString("type")
        val actualFamily = validatorFamily(validatorType)
        require(actualFamily == null || family == actualFamily) {
            "La respuesta $id ($type) no es compatible con el validador $validatorType."
        }
        when (family) {
            "segment_transport" -> {
                require(validatorType == "segment_transport") { "El transporte requiere su validador geométrico." }
                SegmentTransportSpec.parse(validator)
            }
            "rigid_angle" -> {
                require(validatorType == "rigid_angle_copy") { "La copia rígida requiere su validador geométrico." }
                // One contract supplies both presentation and evaluation; there is no hidden second figure.
                RigidAngleCopySpec.parse(validator)
            }
            "choice" -> {
                val optionIds = ids(raw, "options", id)
                validateOptionReviews(raw, id)
                if (validatorType == "choice") {
                    val expected = validator.getJSONArray("expected")
                    val selected = (0 until expected.length()).map { expected.getString(it) }
                    require(selected.isNotEmpty() && selected.all { it in optionIds }) {
                        "Las opciones correctas de $id deben existir en options."
                    }
                    require(type != "math_multiple_choice_single" || selected.size == 1) {
                        "La selección única $id requiere exactamente una opción correcta."
                    }
                }
            }
            "fields" -> {
                val definitions = objects(raw, "fields")
                ids(raw, "fields", id)
                val editable = definitions.filter { it.optString("type", "numeric") != "fixed" }
                require(editable.isNotEmpty()) { "La respuesta $id requiere al menos un campo editable." }
                definitions.forEach { field ->
                    val fieldType = field.optString("type", "numeric")
                    require(fieldType in setOf("fixed", "numeric", "expression", "dropdown")) {
                        "Tipo de campo desconocido en $id: $fieldType"
                    }
                    if (fieldType == "fixed") require(field.opt("text") is String) { "El campo fijo de $id requiere text." }
                    if (fieldType == "dropdown") {
                        ids(field, "options", "$id.${field.getString("id")}")
                        validateOptionReviews(field, "$id.${field.getString("id")}")
                    }
                }
                if (validatorType == "fields") {
                    val expected = validator.getJSONObject("fields")
                    require(expected.keys().asSequence().toSet() == editable.map { it.getString("id") }.toSet()) {
                        "Los validadores de $id deben corresponder exactamente a los campos editables."
                    }
                    for (field in editable) {
                        val fieldId = field.getString("id")
                        val fieldValidator = expected.getJSONObject(fieldId)
                        val fieldValidatorFamily = validatorFamily(fieldValidator.getString("type"))
                        require(fieldValidatorFamily == null || fieldValidatorFamily == "text") {
                            "El validador de $id.$fieldId debe aceptar texto."
                        }
                        if (field.optString("type") == "dropdown" && fieldValidator.getString("type") in setOf("text", "exact_text")) {
                            require(fieldValidator.getString("expected") in ids(field, "options", "$id.$fieldId")) {
                                "La opción correcta de $id.$fieldId debe existir en options."
                            }
                        }
                    }
                }
            }
            "order" -> {
                val itemIds = ids(raw, "items", id)
                if (validatorType == "order") {
                    val expected = validator.getJSONArray("expected")
                    require((0 until expected.length()).map { expected.getString(it) }.toSet() == itemIds) {
                        "El orden esperado de $id debe contener cada tarjeta exactamente una vez."
                    }
                }
            }
            "matching" -> {
                val sources = ids(raw, "sources", id)
                val targets = ids(raw, "targets", id)
                if (validatorType == "matching") {
                    val expected = validator.getJSONObject("expected")
                    val keys = expected.keys().asSequence().toSet()
                    val values = keys.map { expected.getString(it) }
                    require(keys == sources && values.all { it in targets } && values.distinct().size == values.size) {
                        "Las relaciones de $id deben asignar cada ficha a un destino distinto que exista."
                    }
                }
            }
            "sliders" -> {
                val parameters = ids(raw, "parameters", id)
                if (validatorType == "sliders") require(validator.getJSONObject("values").keys().asSequence().toSet() == parameters) {
                    "Los validadores de $id deben corresponder a todos los parámetros."
                }
            }
            "graph" -> {
                val mode = raw.optString("mode", if (type == "math_geometry_builder") "polygon" else "point")
                require(mode in setOf("point", "line", "polygon", "scatter", "parabola")) { "Modo de gráfica desconocido en $id: $mode" }
                require(validatorType != "geometry" || mode == "polygon") { "El validador geométrico de $id requiere modo polygon." }
                require(validatorType != "coordinates" || mode == validator.optString("mode", "point")) {
                    "El modo de gráfica de $id debe coincidir con su validador."
                }
            }
        }
    }

    private fun validateOptionReviews(value: JSONObject, owner: String) {
        objects(value, "options").forEach { option ->
            if (option.has("reviewFeedback")) requiredString(option, "reviewFeedback", "opción de $owner")
        }
    }

    private fun ids(value: JSONObject, key: String, owner: String): Set<String> {
        val items = objects(value, key)
        require(items.isNotEmpty()) { "$owner requiere $key con al menos un elemento." }
        val ids = items.mapIndexed { index, item -> requiredString(item, "id", "$owner.$key[$index]") }
        require(ids.distinct().size == ids.size) { "IDs duplicados en $owner.$key." }
        return ids.toSet()
    }

    private fun objects(value: JSONObject, key: String): List<JSONObject> {
        if (!value.has(key)) return emptyList()
        val array = value.optJSONArray(key)
            ?: throw IllegalArgumentException("$key debe ser un arreglo de objetos.")
        return (0 until array.length()).map { index ->
            val obj = array.optJSONObject(index)
                ?: throw IllegalArgumentException("$key[$index] debe ser un objeto.")
            JSONObject(obj.toString())
        }
    }
}
