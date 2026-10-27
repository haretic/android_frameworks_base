/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.wheel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.res.R

/** The tag the wheel knows the tab header by, see WheelNavigator. */
const val WHEEL_SHADE_TABS_TAG = "wheel_shade_tabs"

/**
 * The wheel shade's tab header: Notifications, the shade with its quick panel, and Controls, its
 * full panel. One stop for the wheel; a press or a tap switches to the other tab, as a swipe across
 * the shade does. No stop while [shown] is false, as under the lock screen, where the collapsed
 * shade keeps it composed but out of sight.
 */
@Composable
fun WheelShadeTabs(
    controls: Boolean,
    onSwitch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shown: Boolean = true,
) {
    val notifications = stringResource(R.string.wheel_shade_tab_notifications)
    val controlsLabel = stringResource(R.string.wheel_shade_tab_controls)
    val current = if (controls) controlsLabel else notifications
    val other = if (controls) notifications else controlsLabel
    val switchLabel = stringResource(R.string.wheel_shade_tab_switch, other)
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.sysuiResTag(WHEEL_SHADE_TABS_TAG)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerHigh)
                .focusProperties { canFocus = shown }
                .thenIf(onSwitch != null) {
                    Modifier.clickable(onClickLabel = switchLabel) { onSwitch?.invoke() }
                }
                .clearAndSetSemantics {
                    role = Role.Tab
                    contentDescription = current
                    stateDescription = switchLabel
                }
                .padding(4.dp)
        ) {
            Tab(notifications, selected = !controls)
            Tab(controlsLabel, selected = controls)
        }
    }
}

@Composable
private fun RowScope.Tab(label: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .thenIf(selected) { Modifier.background(colors.primary) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.onPrimary else colors.onSurface,
            maxLines = 1,
        )
    }
}

