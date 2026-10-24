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
import android.app.StatusBarManager
import android.content.Context
import android.os.PowerManager
import android.util.AttributeSet
import android.util.Pair
import android.view.DisplayCutout
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.updateMargins
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.res.R
import com.android.systemui.wheel.WheelNavigator

/** A view that can serve as the root of the main SysUI window. */
open class WindowRootView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private lateinit var layoutInsetsController: LayoutInsetsController
    private var leftInset = 0
    private var rightInset = 0

    private var previousInsets: WindowInsets? = null

    /** The wheel's walk through the shade, see WheelNavigator. Up past the top closes it. */
    private var wheelNavigator: WheelNavigator? = null

    private fun wheelNavigator() =
        wheelNavigator
            ?: WheelNavigator(this, { it.id == R.id.split_shade_status_bar }) {
                    context.getSystemService(StatusBarManager::class.java)?.collapsePanels()
                }
                .also {
                    it.setLevels(wheelLevels)
                    wheelNavigator = it
                }

    /** Puts the wheel on the view, see WheelNavigator.focus. */
    fun focusWheelOn(view: View): Boolean = wheelSelects() && wheelNavigator().focus(view)

    /** The shade's levels for the wheel, see WheelNavigator.Levels. */
    var wheelLevels: WheelNavigator.Levels? = null
        set(value) {
            field = value
            wheelNavigator?.setLevels(value)
        }
    private lateinit var windowRootViewKeyEventHandler: WindowRootViewKeyEventHandler

    fun setWindowRootViewKeyEventHandler(wrvkeh: WindowRootViewKeyEventHandler) {
        windowRootViewKeyEventHandler = wrvkeh
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (isRoot()) {
            ComposeInitializer.onAttachedToWindow(this)
            // The wheel navigator draws the ring round its stops, the framework's would go round
            // the window's leftover focus
            viewRootImpl?.setWheelFocusRingHidden(true)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wheelNavigator?.reset()

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

        // With INPUT_FEATURE_POWER_KEY_SELECTS (wheel UI, shade over an app) the wheel walks the
        // shade's tiles, sliders and notifications and the side button presses them. The side
        // button only comes here with it.
        if (isRoot() && (event.keyCode == KeyEvent.KEYCODE_POWER || wheelSelects())) {
            if (wheelNavigator().dispatchKey(event)) {
                // The lock screen's window takes no user activity from input, so the wheel keeps
                // the screen on itself there
                if (wheelInputFeatures() and
                        WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY != 0) {
                    context
                        .getSystemService(PowerManager::class.java)
                        ?.userActivity(event.eventTime, PowerManager.USER_ACTIVITY_EVENT_BUTTON, 0)
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_POWER) {
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            wheelNavigator?.reset()
        }
    }

    private fun wheelSelects() =
        wheelInputFeatures() and WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS != 0

    private fun wheelInputFeatures() =
        (layoutParams as? WindowManager.LayoutParams)?.inputFeatures ?: 0

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
