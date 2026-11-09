package com.learngraphicsprogramming.app.math

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.learngraphicsprogramming.app.R

/** The same visual moves into the dialog, retaining its model, listeners and interaction.
 * The expand/exit controls have their own rows and never overlap the drawing surface.
 */
object MathExpandableRepresentation {
    fun wrap(context: Context, content: View, accentColor: Int, aspectRatio: Float = 1f): View =
        ExpandableView(context, content, accentColor,
            if (content is MathCartesianView) content.coordinateAspectRatio else aspectRatio)

    private class ExpandableView(
        context: Context,
        private val visual: View,
        accentColor: Int,
        aspectRatio: Float
    ) : LinearLayout(context), MathFullscreenGestureCapability {
        override val allowEdgeSwipeToDismiss: Boolean
            get() = MathFullscreenGesturePolicy.allowEdgeSwipeToDismiss(visual)
        private val visualRatio = aspectRatio.takeIf { it.isFinite() && it > 0 }?.coerceIn(.6f, 4f) ?: 1f
        private val holder = AspectFrame(context, visualRatio)
        private var expanded: ComponentDialog? = null

        init {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            val controls = LinearLayout(context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            controls.addView(icon(context, accentColor, context.getString(R.string.math_ui_expand_representation), "math_expand_representation").apply {
                setOnClickListener { expand(accentColor) }
            })
            addView(controls)
            addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            holder.addView(visual, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            /*
             * Toda representación expandible puede abrir
             * fullscreen con doble toque.
             *
             * Esto incluye:
             *
             * - gráficas estáticas
             * - gráficas dinámicas
             * - gráficas editables
             * - rectas numéricas
             * - demás representaciones
             *
             * El doble toque no habilita ningún swipe
             * de salida. Eso sigue siendo independiente.
             */
            if (visual !is MathDirectManipulationSurface) holder.onDoubleTap = {
                expand(accentColor)
            }
        }

        private fun expand(accentColor: Int) {
            if (expanded != null || !isAttachedToWindow) return
            val previousFocus = rootView.findFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
            val dialog = ComponentDialog(context, android.R.style.Theme_Material_NoActionBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            // ComponentDialog connects this dispatcher to Android's OnBackInvokedDispatcher
            // on API 33+, including system gesture navigation. No app-owned swipe is installed.
            dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dialog.dismiss()
            })
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            // Fullscreen is a black viewport; fit the same visual proportionally inside it.
            val expandedHolder = ContainFrame(context, visualRatio).apply { tag = "math_expanded_visual_container" }
            if (holder.onDoubleTap != null) expandedHolder.onDoubleTap = { dialog.dismiss() }
            column.addView(expandedHolder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            val exitRow = LinearLayout(context).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            val resetZoom = icon(context, accentColor, context.getString(R.string.math_ui_reset_zoom), "math_reset_representation_zoom").apply {
                setImageResource(R.drawable.ic_math_reset_zoom)
                visibility = View.GONE
                setOnClickListener { expandedHolder.resetZoom() }
            }
            expandedHolder.onZoomChanged = { enlarged -> resetZoom.visibility = if (enlarged) View.VISIBLE else View.GONE }
            exitRow.addView(resetZoom)
            exitRow.addView(View(context), LayoutParams(0, 1, 1f))
            exitRow.addView(icon(context, accentColor, context.getString(R.string.math_ui_close_representation), "math_exit_representation").apply {
                setOnClickListener { dialog.dismiss() }
            })
            column.addView(exitRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            // Detach only the visual: the inline holder keeps its measured size in the lesson.
            (visual.parent as? ViewGroup)?.removeView(visual)
            expandedHolder.addView(visual, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            expanded = dialog
            dialog.setContentView(column)
            dialog.setOnDismissListener {
                expandedHolder.resetZoom()
                (visual.parent as? ViewGroup)?.removeView(visual)
                holder.addView(visual, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                expanded = null
                if (previousFocus?.isAttachedToWindow == true && previousFocus.isEnabled) previousFocus.requestFocus()
            }
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
                window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, column).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
                    val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.mandatorySystemGestures())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(column)
            }
        }

        override fun onDetachedFromWindow() {
            expanded?.dismiss()
            super.onDetachedFromWindow()
        }
    }

    /** Like fit-center for an image: use the largest possible size without stretching either axis. */
    private class ContainFrame(context: Context, private val ratio: Float) : MathZoomViewport(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(1)
            val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(1)
            val contentWidth = kotlin.math.min(availableWidth.toFloat(), availableHeight * ratio).toInt().coerceAtLeast(1)
            val contentHeight = (contentWidth / ratio).toInt().coerceAtMost(availableHeight).coerceAtLeast(1)
            for (index in 0 until childCount) getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    /** Inline height follows the available width instead of a fixed dp rectangle. */
    private class AspectFrame(context: Context, ratio: Float) : MathDoubleTapFrame(context) {
        private val ratio = ratio.takeIf { it.isFinite() && it > 0 }?.coerceIn(.6f, 4f) ?: 1f
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (width > 0) {
                val wanted = (width / ratio).toInt().coerceAtLeast(1)
                val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
                    MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                    MeasureSpec.AT_MOST -> wanted.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
                    else -> wanted
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun icon(context: Context, accentColor: Int, description: String, marker: String): ImageButton =
        ImageButton(context).apply {
            setImageResource(if (marker == "math_exit_representation") android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_code_fullscreen)
            imageTintList = ColorStateList.valueOf(Color.rgb(190, 195, 205))
            background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00ffffff) or 0x33000000), ColorDrawable(Color.TRANSPARENT), null)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            contentDescription = description
            tag = marker
            layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))
        }

    private fun dp(context: Context, value: Int) = MathVisualControls.dp(context, value)
}
