package com.learngraphicsprogramming.app.narration

import android.content.Context
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.UiLanguageSettings
import com.learngraphicsprogramming.app.math.MathPuzzleUiInstructions

/**
 * Instructions displayed immediately below exercise headings.
 * Resolve them from exactly the same Android string resources as the UI,
 * including its independently configured interface language.
 */
internal object NarrationUiInstructions {
    fun forBlock(context: Context, blockType: String): String {
        if (blockType == "note") {
            // The native note is this formatted resource plus its authored body.
            // Read its existing heading without synthesizing another copy of the body.
            return UiLanguageSettings.localizedContext(context).getString(R.string.lesson_note, "")
        }
        val resource = when (blockType) {
            "order_code" -> R.string.exercise_order_instruction
            "drag_fill_code" -> R.string.exercise_fill_instruction
            "math_multiple_choice_single" -> R.string.math_ui_select_answer
            "math_multiple_choice_multi" -> R.string.math_ui_select_all_answers
            "math_ordering_drag" -> R.string.math_ui_ordering_help
            "math_matching_drag" -> R.string.math_ui_matching_help
            MathPuzzleUiInstructions.TILES -> R.string.math_ui_tiles
            MathPuzzleUiInstructions.TARGETS -> R.string.math_ui_targets
            MathPuzzleUiInstructions.EDIT_COORDINATES -> R.string.math_ui_edit_coordinates
            MathPuzzleUiInstructions.SEGMENT_HELP -> R.string.math_segment_help
            MathPuzzleUiInstructions.SEGMENT_LEGEND -> R.string.math_segment_legend
            MathPuzzleUiInstructions.RIGID_ANGLE_HELP -> R.string.math_rigid_help
            MathPuzzleUiInstructions.RIGID_ANGLE_LEGEND -> R.string.math_rigid_legend
            MathPuzzleUiInstructions.POSITION -> R.string.math_ui_position
            MathPuzzleUiInstructions.INTERVAL_DIRECTION -> R.string.math_ui_interval_direction
            MathPuzzleUiInstructions.AUXILIARY_TOOL -> R.string.math_ui_auxiliary_tool
            MathPuzzleUiInstructions.UNIT_TEMPLATE -> R.string.math_ui_unit
            else -> return ""
        }
        return UiLanguageSettings.localizedContext(context).getString(resource)
    }
}

