package com.learngraphicsprogramming.app.math

import org.json.JSONObject
import java.util.Locale
import com.learngraphicsprogramming.app.narration.NarrationLanguage

/** Describes only the static help that the existing native controls visibly display.
 * Keys resolve through the same localized UI resources; answers and current values are absent. */
internal object MathPuzzleUiInstructions {
    const val TILES = "math_matching_drag:tiles"
    const val TARGETS = "math_matching_drag:targets"
    const val EDIT_COORDINATES = "math_graph:edit_coordinates"
    const val POSITION = "math_number_line:position"
    const val INTERVAL_DIRECTION = "math_number_line:interval_direction"
    const val AUXILIARY_TOOL = "math_puzzle:auxiliary_tool"
    const val UNIT_TEMPLATE = "math_text:unit_template"
    const val SEGMENT_HELP = "math_segment_transport:help"
    const val SEGMENT_LEGEND = "math_segment_transport:legend"
    const val RIGID_ANGLE_HELP = "math_rigid_angle_copy:help"
    const val RIGID_ANGLE_LEGEND = "math_rigid_angle_copy:legend"

    /** The existing hole announcement follows the narration language, independently of the UI. */
    fun spokenBlank(language: NarrationLanguage): String = when (language) {
        NarrationLanguage.SPANISH -> "hueco"
        NarrationLanguage.FRENCH -> "case vide"
        else -> "blank"
    }

    /** TextControl displays this complete caption; field units are already literal labels. */
    fun textUnit(type: String, config: JSONObject, resolve: (String) -> String): String {
        if (type !in textResponseTypes || config.has("fields")) return ""
        val unit = config.optString("unit")
        if (unit.isBlank()) return ""
        val template = resolve(UNIT_TEMPLATE)
        return if (template.isBlank()) unit else String.format(Locale.ROOT, template, unit)
    }

    fun heading(type: String, config: JSONObject, resolve: (String) -> String): String =
        if (type in defaultHeadingTypes) config.optString("label", resolve(type))
        else config.optString("label", "")

    fun supplementary(type: String, resolve: (String) -> String): List<String> = when (type) {
        "math_segment_transport" -> listOf(resolve(SEGMENT_HELP), resolve(SEGMENT_LEGEND))
        "math_rigid_angle_copy" -> listOf(resolve(RIGID_ANGLE_HELP), resolve(RIGID_ANGLE_LEGEND))
        "math_coordinate_graph_builder", "math_geometry_builder" -> listOf(resolve(EDIT_COORDINATES))
        "math_number_line" -> listOf(resolve(POSITION), resolve(INTERVAL_DIRECTION))
        else -> emptyList()
    }

    /** The renderer uses this whitelist to bind these already visible TextViews to reading ink. */
    fun visible(type: String, config: JSONObject, resolve: (String) -> String): List<String> = buildList {
        add(heading(type, config, resolve))
        add(textUnit(type, config, resolve))
        addAll(supplementary(type, resolve))
        if (type == "math_matching_drag") {
            add(resolve(TILES))
            add(resolve(TARGETS))
        }
    }.filter(String::isNotBlank)

    private val defaultHeadingTypes = setOf("math_multiple_choice_single", "math_multiple_choice_multi",
        "math_ordering_drag", "math_matching_drag")
    private val textResponseTypes = setOf("math_numeric_input", "math_expression_input")
}
