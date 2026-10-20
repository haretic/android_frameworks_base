/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.scene.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Pair
import android.view.DisplayCutout
import android.view.FocusFinder
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.updateMargins
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.res.R

/** A view that can serve as the root of the main SysUI window. */
open class WindowRootView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private lateinit var layoutInsetsController: LayoutInsetsController
    private var leftInset = 0
    private var rightInset = 0

    private var previousInsets: WindowInsets? = null
    private lateinit var windowRootViewKeyEventHandler: WindowRootViewKeyEventHandler

    fun setWindowRootViewKeyEventHandler(wrvkeh: WindowRootViewKeyEventHandler) {
        windowRootViewKeyEventHandler = wrvkeh
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (isRoot()) {
            ComposeInitializer.onAttachedToWindow(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (isRoot()) {
            ComposeInitializer.onDetachedFromWindow(this)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): FrameLayout.LayoutParams? {
        return LayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): FrameLayout.LayoutParams? {
        return LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets? {
        if (windowInsets == previousInsets) {
            return windowInsets
        }
        val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        if (fitsSystemWindows) {
            val paddingChanged = insets.top != paddingTop || insets.bottom != paddingBottom

            // Drop top inset, and pass through bottom inset.
            if (paddingChanged) {
                setPadding(0, 0, 0, 0)
            }
        } else {
            val changed =
                paddingLeft != 0 || paddingRight != 0 || paddingTop != 0 || paddingBottom != 0
            if (changed) {
                setPadding(0, 0, 0, 0)
            }
        }
        leftInset = 0
        rightInset = 0

        val displayCutout = rootWindowInsets.displayCutout
        val pairInsets: Pair<Int, Int> =
            layoutInsetsController.getinsets(windowInsets, displayCutout)
        leftInset = pairInsets.first
        rightInset = pairInsets.second
        applyMargins()
        return windowInsets.also { previousInsets = WindowInsets(it) }
    }

    fun setLayoutInsetsController(layoutInsetsController: LayoutInsetsController) {
        this.layoutInsetsController = layoutInsetsController
    }

    private fun applyMargins() {
        val count = childCount
        var hasChildMarginUpdated = false
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.layoutParams is LayoutParams) {
                val layoutParams = child.layoutParams as LayoutParams
                if (
                    !layoutParams.ignoreRightInset &&
                        (layoutParams.rightMargin != rightInset ||
                            layoutParams.leftMargin != leftInset)
                ) {
                    layoutParams.updateMargins(left = leftInset, right = rightInset)
                    hasChildMarginUpdated = true
                }
            }
        }
        if (hasChildMarginUpdated) {
            // Request layout at once after all children's margins has updated
            requestLayout()
        }
    }

    /**
     * Returns `true` if this view is the true root of the view-hierarchy; `false` otherwise.
     *
     * Please see the class-level documentation to understand why this is possible.
     */
    private fun isRoot(): Boolean {
        // TODO(b/283300105): remove this check once there's only one subclass of WindowRootView.
        return parent.let { it !is View || it.id == android.R.id.content }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        windowRootViewKeyEventHandler.collectKeyEvent(event)

        // Only here with INPUT_FEATURE_POWER_KEY_SELECTS (wheel UI, shade over an app): the
        // side button clicks the focused notification or tile, or first shows the focus
        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            if (
                event.action == KeyEvent.ACTION_UP &&
                    !event.isCanceled &&
                    (event.flags and KeyEvent.FLAG_LONG_PRESS) == 0
            ) {
                val focused = findFocus()
                if (focused != null && !isInTouchMode) {
                    focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    focused.performClick()
                } else {
                    FocusFinder.getInstance()
                        .findNextFocus(this, null, View.FOCUS_DOWN)
                        ?.requestFocusFromTouch()
                }
            }
            return true
        }

        // With the same feature the wheel starts at the top and goes round: past the last
        // item to the first
        if (
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
                ((layoutParams as? WindowManager.LayoutParams)?.inputFeatures ?: 0) and
                    WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS != 0
        ) {
            val focused = findFocus()
            if (isInTouchMode || focused == null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    FocusFinder.getInstance()
                        .findNextFocus(this, null, View.FOCUS_DOWN)
                        ?.requestFocusFromTouch()
                }
                return true
            }
            val direction =
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN
                else View.FOCUS_UP
            if (FocusFinder.getInstance().findNextFocus(this, focused, direction) == null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    FocusFinder.getInstance().findNextFocus(this, null, direction)?.let {
                        it.requestFocus()
                        it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                return true
            }
        }

        if (windowRootViewKeyEventHandler.interceptMediaKey(event)) {
            return true
        }

        if (super.dispatchKeyEvent(event)) {
            return true
        }

        return windowRootViewKeyEventHandler.dispatchKeyEvent(event)
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        return windowRootViewKeyEventHandler.dispatchKeyEventPreIme(event) ?: false
    }

    /** Controller responsible for calculating insets for the shade window. */
    interface LayoutInsetsController {

        /** Update the insets and calculate them accordingly. */
        fun getinsets(windowInsets: WindowInsets?, displayCutout: DisplayCutout?): Pair<Int, Int>
    }

    private class LayoutParams : FrameLayout.LayoutParams {
        var ignoreRightInset = false

        constructor(width: Int, height: Int) : super(width, height)

        @SuppressLint("CustomViewStyleable")
        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
            val obtainedAttributes =
                context.obtainStyledAttributes(attrs, R.styleable.StatusBarWindowView_Layout)
            ignoreRightInset =
                obtainedAttributes.getBoolean(
                    R.styleable.StatusBarWindowView_Layout_ignoreRightInset,
                    false,
                )
            obtainedAttributes.recycle()
        }
    }
}
