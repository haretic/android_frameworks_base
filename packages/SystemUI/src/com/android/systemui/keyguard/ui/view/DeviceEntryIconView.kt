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
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.view

import android.companion.virtualdevice.flags.Flags
import android.content.Context
import android.graphics.drawable.AnimatedStateListDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.StateSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.android.systemui.common.ui.view.TouchHandlingView
import com.android.systemui.log.TouchHandlingViewLogger
import com.android.systemui.res.R

class DeviceEntryIconView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttrs: Int = 0,
    logger: TouchHandlingViewLogger? = null,
) : FrameLayout(context, attrs, defStyleAttrs) {

    val touchHandlingView: TouchHandlingView =
        TouchHandlingView(
            context = context,
            attrs = attrs,
            longPressDuration = {
                if (Flags.viewconfigurationApis())
                    ViewConfiguration.get(context).longPressTimeoutMillis.toLong()
                else ViewConfiguration.getLongPressTimeout().toLong()
            },
            allowedTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop(),
            logger = logger,
        )
    val iconView: ImageView = ImageView(context, attrs).apply { id = R.id.device_entry_icon_fg }
    val bgView: ImageView = ImageView(context, attrs).apply { id = R.id.device_entry_icon_bg }
    val aodFpDrawable: LottieDrawable = LottieDrawable()
    var accessibilityHintType: AccessibilityHintType = AccessibilityHintType.NONE

    /** Whether the icon sits in the wheel UI's slot, a row with its label, see setWheelPill. */
    var wheelLayout = false
        private set

    /** Whether that row shows as the Unlock pill. */
    var wheelPill = false
        private set

    private var wheelRow: LinearLayout? = null
    private var wheelLabel: TextView? = null

    private var animatedIconDrawable: AnimatedStateListDrawable = AnimatedStateListDrawable()

    init {
        setupIconStates()
        setupIconTransitions()
        setupAccessibilityDelegate()

        // Ordering matters. From background to foreground we want:
        //     bgView, iconView, longpressHandlingView overlay
        addBgImageView()
        addIconImageView()
        addTouchHandlingView()
    }

    private fun setupAccessibilityDelegate() {
        accessibilityDelegate =
            object : AccessibilityDelegate() {
                private val accessibilityBouncerHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_bouncer),
                    )
                private val accessibilityEnterHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_enter_hint),
                    )

                override fun onInitializeAccessibilityNodeInfo(
                    v: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    when (accessibilityHintType) {
                        AccessibilityHintType.BOUNCER -> info.addAction(accessibilityBouncerHint)
                        AccessibilityHintType.ENTER -> info.addAction(accessibilityEnterHint)
                        AccessibilityHintType.NONE -> return
                    }
                }
            }
    }

    /**
     * Setups different icon states.
     * - All lottie views will require a LottieOnCompositionLoadedListener to update
     *   LottieProperties (like color) of the view.
     * - Drawable properties can be updated using ImageView properties like imageTintList.
     */
    private fun setupIconStates() {
        // Lockscreen States
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, false),
            context.getDrawable(R.drawable.ic_lock)!!,
            R.id.locked,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, false),
            context.getDrawable(R.drawable.ic_unlocked)!!,
            R.id.unlocked,
        )
        // FINGERPRINT
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, false),
            context.getDrawable(R.drawable.ic_fingerprint)!!,
            R.id.locked_fp,
        )

        // AOD states
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, true),
            context.getDrawable(R.drawable.ic_lock_aod)!!,
            R.id.locked_aod,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, true),
            context.getDrawable(R.drawable.ic_unlocked_aod)!!,
            R.id.unlocked_aod,
        )
        // FINGERPRINT
        LottieCompositionFactory.fromRawRes(mContext, R.raw.udfps_aod_fp).addListener { result ->
            aodFpDrawable.setComposition(result)
        }
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, true),
            aodFpDrawable,
            R.id.udfps_aod_fp,
        )

        // WILDCARD: should always be the last state added since any states will match with this
        // and therefore won't get matched with subsequent states.
        animatedIconDrawable.addState(
            StateSet.WILD_CARD,
            context.getDrawable(R.color.transparent)!!,
            R.id.no_icon,
        )
    }

    private fun setupIconTransitions() {
        // LockscreenFp <=> LockscreenUnlocked
        animatedIconDrawable.addTransition(
            R.id.locked_fp,
            R.id.unlocked,
            context.getDrawable(R.drawable.fp_to_unlock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked_fp,
            context.getDrawable(R.drawable.unlock_to_fp) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenLocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.locked_aod,
            R.id.locked,
            context.getDrawable(R.drawable.lock_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.lock_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenUnlocked <=> AodUnlocked
        animatedIconDrawable.addTransition(
            R.id.unlocked_aod,
            R.id.unlocked,
            context.getDrawable(R.drawable.unlocked_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.unlocked_aod,
            context.getDrawable(R.drawable.unlocked_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenLocked <=> LockscreenUnlocked
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.unlocked,
            context.getDrawable(R.drawable.lock_to_unlock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked,
            context.getDrawable(R.drawable.unlocked_to_locked) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenFingerprint => LockscreenLocked
        animatedIconDrawable.addTransition(
            R.id.locked_fp,
            R.id.locked,
            context.getDrawable(R.drawable.fp_to_locked) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenUnlocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.unlocked_to_aod_lock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
    }

    private fun addTouchHandlingView() {
        addView(touchHandlingView)
        val lp = touchHandlingView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        touchHandlingView.layoutParams = lp
    }

    private fun addIconImageView(index: Int = -1) {
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.setImageDrawable(animatedIconDrawable)
        val lp =
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.CENTER
        addView(iconView, index, lp)
    }

    private fun addBgImageView() {
        bgView.setImageDrawable(context.getDrawable(R.drawable.fingerprint_bg))
        addView(bgView)
        val lp = bgView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        bgView.layoutParams = lp
        bgView.alpha = 0f
    }

    /**
     * The wheel UI's Unlock pill: the icon and an Unlock label on a pill the side button presses,
     * in place of the bare icon. On AOD, where the pill would burn in, the icon alone keeps the
     * wheel's slot.
     */
    fun setWheelPill(wheel: Boolean, pill: Boolean) {
        wheelPill = wheel && pill
        if (wheel != wheelLayout) {
            wheelLayout = wheel
            removeView(iconView)
            if (wheel) {
                val row = wheelRow ?: createWheelRow().also { wheelRow = it }
                val iconSize = dp(24f)
                row.addView(iconView, 0, LinearLayout.LayoutParams(iconSize, iconSize))
                addView(
                    row,
                    indexOfChild(touchHandlingView),
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            } else {
                wheelRow?.let {
                    it.removeView(iconView)
                    removeView(it)
                }
                addIconImageView(indexOfChild(touchHandlingView))
            }
        }
        wheelLabel?.visibility = if (wheelPill) View.VISIBLE else View.GONE
        background =
            if (wheelPill) {
                GradientDrawable().apply {
                    cornerRadius = dp(WHEEL_PILL_RADIUS_DP).toFloat()
                    setColor(
                        context.getColor(
                            com.android.internal.R.color.materialColorSurfaceContainerHigh
                        )
                    )
                }
            } else {
                null
            }
    }

    private fun createWheelRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val label =
                TextView(context).apply {
                    setText(R.string.wheel_unlock)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(
                        context.getColor(com.android.internal.R.color.materialColorOnSurface)
                    )
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            addView(
                label,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { marginStart = dp(10f) },
            )
            wheelLabel = label
        }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
            .toInt()

    fun getIconState(icon: IconType, aod: Boolean): IntArray {
        val lockIconState = IntArray(2)
        when (icon) {
            IconType.LOCK -> lockIconState[0] = android.R.attr.state_first
            IconType.UNLOCK -> lockIconState[0] = android.R.attr.state_last
            IconType.FINGERPRINT -> lockIconState[0] = android.R.attr.state_middle
            IconType.NONE -> return StateSet.NOTHING
        }
        if (aod) {
            lockIconState[1] = android.R.attr.state_single
        } else {
            lockIconState[1] = -android.R.attr.state_single
        }
        return lockIconState
    }

    enum class IconType(val contentDescriptionResId: Int) {
        LOCK(R.string.accessibility_lock_icon),
        UNLOCK(R.string.accessibility_unlock_button),
        FINGERPRINT(R.string.accessibility_fingerprint_label),
        NONE(-1),
    }

    private companion object {
        const val WHEEL_PILL_RADIUS_DP = 24f
    }

    enum class AccessibilityHintType {
        NONE,
        BOUNCER,
        ENTER,
    }
}
