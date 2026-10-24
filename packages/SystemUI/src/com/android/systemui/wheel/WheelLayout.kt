/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.wheel

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.res.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import lineageos.providers.LineageSettings

/** Local to the shade, never the tile editor. Logical grid spans remain two per labelled tile. */
data class WheelLayout(
    val enabled: Boolean = false,
    val columns: Int = 1,
    val full: Boolean = false,
)

val LocalWheelLayout = staticCompositionLocalOf { WheelLayout() }

@Composable
fun rememberWheelLayout(full: Boolean): WheelLayout {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): WheelLayout =
        WheelLayout(
            enabled = WheelLayoutSettings.shade(context),
            columns =
                LineageSettings.Secure.getIntForUser(
                        resolver,
                        LineageSettings.Secure.WHEEL_BUTTON_COLUMNS,
                        1,
                        ActivityManager.getCurrentUser(),
                    )
                    .coerceIn(1, 2),
            full = full,
        )
    var layout by remember(context, full) { mutableStateOf(read()) }
    DisposableEffect(resolver, full) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    layout = read()
                }
            }
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.WHEEL_UI),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.WHEEL_SHADE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.WHEEL_BUTTON_COLUMNS),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        val userSwitch =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    layout = read()
                }
            }
        context.registerReceiver(userSwitch, IntentFilter(Intent.ACTION_USER_SWITCHED))
        // Register first, then read to avoid missing a setting change during composition.
        layout = read()
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(userSwitch)
        }
    }
    return layout
}

/**
 * Whether the wheel UI is on with the component's revised layout, as WheelLayoutSettings.component,
 * following the settings and the user.
 */
fun wheelLayoutEnabled(context: Context, component: String? = null): Flow<Boolean> =
    callbackFlow {
            val update = { trySend(WheelLayoutSettings.component(context, component)) }
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        update()
                    }
                }
            for (setting in listOfNotNull(LineageSettings.Secure.WHEEL_UI, component)) {
                context.contentResolver.registerContentObserver(
                    LineageSettings.Secure.getUriFor(setting),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
            }
            val userSwitch =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        update()
                    }
                }
            context.registerReceiver(userSwitch, IntentFilter(Intent.ACTION_USER_SWITCHED))
            update()
            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                context.unregisterReceiver(userSwitch)
            }
        }
        .distinctUntilChanged()
