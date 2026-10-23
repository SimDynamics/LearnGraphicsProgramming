package com.learngraphicsprogramming.app

import android.content.Context

data class NavigationState(

    val screen: String,

    val courseId: String?,

    val unitId: String?,

    val lessonId: String?,

    val routeId: String = AppearanceSettingsRepository.ROUTE_PROGRAMMING
)

object NavigationStateRepository {

    /** Scheduling observes navigation; it never owns the student's navigation or reading position. */
    private fun updateNarrationContext(context: Context) {
        val state = getState(context)
        val selected = state.screen != SCREEN_ROUTES
        com.learngraphicsprogramming.app.narration.NarrationAudioPlanner.enter(context,
            routeId = state.routeId.takeIf { selected },
            courseId = state.courseId.takeIf { selected },
            lessonId = state.lessonId.takeIf { selected },
            pageIndex = state.lessonId?.takeIf { selected }?.let { ProgressRepository.getCurrentPage(context, it) },
            unitId = state.unitId.takeIf { selected }
        )
    }

    const val SCREEN_ROUTES =
        "routes"

    const val SCREEN_COURSES =
        "courses"

    const val SCREEN_COURSE =
        "course"

    const val SCREEN_UNIT =
        "unit"

    const val SCREEN_LESSON =
        "lesson"

    private const val PREFERENCES_NAME =
        "navigation_state"

    private const val KEY_SCREEN =
        "last_screen"

    private const val KEY_ROUTE_ID = "last_route_id"

    private const val KEY_COURSE_ID =
        "last_course_id"

    private const val KEY_UNIT_ID =
        "last_unit_id"

    private const val KEY_LESSON_ID =
        "last_lesson_id"

    fun saveRoutes(
        context: Context
    ) {

        preferences(context)
            .edit()
            .putString(
                KEY_SCREEN,
                SCREEN_ROUTES
            )
            .remove(
                KEY_COURSE_ID
            )
            .remove(
                KEY_UNIT_ID
            )
            .remove(
                KEY_LESSON_ID
            )
            .remove(KEY_ROUTE_ID)
            .apply()
        updateNarrationContext(context)
    }

    fun saveCourses(
        context: Context,
        routeId: String = AppearanceSettingsRepository.ROUTE_PROGRAMMING
    ) {

        preferences(context)
            .edit()
            .putString(
                KEY_SCREEN,
                SCREEN_COURSES
            )
            .putString(KEY_ROUTE_ID, routeId)
            .remove(
                KEY_COURSE_ID
            )
            .remove(
                KEY_UNIT_ID
            )
            .remove(
                KEY_LESSON_ID
            )
            .apply()
        updateNarrationContext(context)
    }

    fun saveCourse(
        context: Context,
        courseId: String,
        routeId: String = AppearanceSettingsRepository.ROUTE_PROGRAMMING
    ) {

        preferences(context)
            .edit()
            .putString(
                KEY_SCREEN,
                SCREEN_COURSE
            )
            .putString(KEY_ROUTE_ID, routeId)
            .putString(
                KEY_COURSE_ID,
                courseId
            )
            .remove(
                KEY_UNIT_ID
            )
            .remove(
                KEY_LESSON_ID
            )
            .apply()
        updateNarrationContext(context)
    }

    fun saveUnit(
        context: Context,
        courseId: String,
        unitId: String,
        routeId: String = AppearanceSettingsRepository.ROUTE_PROGRAMMING
    ) {

        preferences(context)
            .edit()
            .putString(
                KEY_SCREEN,
                SCREEN_UNIT
            )
            .putString(KEY_ROUTE_ID, routeId)
            .putString(
                KEY_COURSE_ID,
                courseId
            )
            .putString(
                KEY_UNIT_ID,
                unitId
            )
            .remove(
                KEY_LESSON_ID
            )
            .apply()
        updateNarrationContext(context)
    }

    fun saveLesson(
        context: Context,
        lessonId: String,
        routeId: String = AppearanceSettingsRepository.ROUTE_PROGRAMMING
    ) {

        val parts =
            lessonId.split(
                "__",
                limit = 3
            )

        val editor =
            preferences(context)
                .edit()
                .putString(
                    KEY_SCREEN,
                    SCREEN_LESSON
                )
                .putString(KEY_ROUTE_ID, routeId)
                .putString(
                    KEY_LESSON_ID,
                    lessonId
                )

        /*
         * Nuestros IDs de lección tienen:
         *
         * curso__unidad__leccion
         *
         * Por tanto podemos reconstruir también
         * automáticamente sus padres.
         */
        if (
            parts.size == 3
        ) {

            val courseId =
                parts[0]

            val unitId =
                "${parts[0]}__${parts[1]}"

            editor.putString(
                KEY_COURSE_ID,
                courseId
            )

            editor.putString(
                KEY_UNIT_ID,
                unitId
            )
        } else {
            editor.remove(KEY_COURSE_ID).remove(KEY_UNIT_ID)
        }

        editor.apply()
        updateNarrationContext(context)
    }

    fun getState(
        context: Context
    ): NavigationState {

        val preferences =
            preferences(
                context
            )

        return NavigationState(

            screen =
                preferences.getString(
                    KEY_SCREEN,
                    SCREEN_ROUTES
                ) ?: SCREEN_ROUTES,

            courseId =
                preferences.getString(
                    KEY_COURSE_ID,
                    null
                ),

            unitId =
                preferences.getString(
                    KEY_UNIT_ID,
                    null
                ),

            lessonId =
                preferences.getString(
                    KEY_LESSON_ID,
                    null
                ),

            // Migrate older math state whose IDs already identify the route. Old Programming
            // states and the previous two-argument APIs retain their existing default.
            routeId = preferences.getString(KEY_ROUTE_ID, null)
                ?: if ((preferences.getString(KEY_COURSE_ID, null)
                        ?: preferences.getString(KEY_LESSON_ID, "")).orEmpty().startsWith("math_course_"))
                    AppearanceSettingsRepository.ROUTE_MATH_PHYSICS
                else AppearanceSettingsRepository.ROUTE_PROGRAMMING
        )
    }

    private fun preferences(
        context: Context
    ) =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
}
