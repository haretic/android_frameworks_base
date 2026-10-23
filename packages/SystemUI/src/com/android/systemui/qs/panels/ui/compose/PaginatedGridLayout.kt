/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.padding
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.development.ui.compose.BuildNumber
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.dagger.PaginatedBaseLayoutType
import com.android.systemui.qs.panels.ui.compose.Dimensions.FooterHeight
import com.android.systemui.qs.panels.ui.compose.Dimensions.InterPageSpacing
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButton
import com.android.systemui.qs.panels.ui.viewmodel.PaginatedGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import com.android.systemui.res.R
import com.android.systemui.wheel.LocalWheelLayout
import javax.inject.Inject
import kotlinx.coroutines.launch

class PaginatedGridLayout
@Inject
constructor(
    private val viewModelFactory: PaginatedGridViewModel.Factory,
    @PaginatedBaseLayoutType private val delegateGridLayout: PaginatableGridLayout,
) : GridLayout by delegateGridLayout {
    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "PaginatedGridLayout-TileGrid") {
                viewModelFactory.create()
            }
        val delegateGridViewModel =
            rememberViewModel(traceName = "PaginatedGridLayout-TileGrid") {
                delegateGridLayout.viewModelFactory.create()
            }

        val wheel = LocalWheelLayout.current
        val rows =
            if (wheel.enabled) 2
            else integerResource(R.integer.quick_settings_paginated_grid_num_rows)
        val pages =
            remember(tiles, rows, wheel, *delegateGridViewModel.pageKeys) {
                if (wheel.enabled) tiles.chunked(rows * wheel.columns)
                else delegateGridViewModel.splitIntoPages(tiles, rows)
            }

        val pagerState = rememberPagerState(0) { pages.size }

        LaunchedEffect(pages.size) {
            if (pages.isNotEmpty() && pagerState.currentPage >= pages.size) {
                pagerState.scrollToPage(pages.lastIndex)
            }
        }

        LaunchedEffect(listening, pagerState) {
            snapshotFlow { listening() }
                .collect {
                    // Whenever we go from not listening to listening, we should be in the first
                    // page. If we did this when going from listening to not listening, opening
                    // edit mode in second page will cause it to go to first page during the
                    // transition.
                    if (listening()) {
                        pagerState.scrollToPage(0)
                    }
                }
        }

        // Used to track if this is currently in the first page or not, for animations
        LaunchedEffect(key1 = pagerState) {
            snapshotFlow { pagerState.currentPage == 0 }.collect { viewModel.inFirstPage = it }
        }

        Column(modifier) {
            val contentPaddingValue =
                if (pages.size > 1) {
                    InterPageSpacing
                } else {
                    0.dp
                }
            val contentPadding = PaddingValues(horizontal = contentPaddingValue)
            val nestedScrollConnection =
                remember(viewModel) {
                    object : NestedScrollConnection {
                        override suspend fun onPreFling(available: Velocity): Velocity {
                            viewModel.registerSideSwipeGesture()
                            return Velocity.Zero
                        }
                    }
                }

            /* Use negative padding equal with value equal to content padding. That way, each page
             * layout extends to the sides, but the content is as if there was no padding. That
             * way, the clipping bounds of the HorizontalPager extend beyond the tiles in each page.
             */
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier.sysuiResTag("qs_pager")
                        .padding(horizontal = { -contentPaddingValue.roundToPx() })
                        .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                pageSpacing = if (pages.size > 1) InterPageSpacing else 0.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
            ) {
                val page = pages[it]

                with(delegateGridLayout) {
                    TileGrid(
                        tiles = page,
                        modifier = Modifier,
                        listening = listening,
                        enableRevealEffect = false,
                    )
                }
            }
            FooterBar(
                buildNumberViewModelFactory = viewModel.buildNumberViewModelFactory,
                pagerState = pagerState,
                showArrowsInPager = viewModel.showArrowsInPagerDots,
                editButtonViewModelFactory = viewModel.editModeButtonViewModelFactory,
                isVisible = { listening() && layoutState.isIdle() },
            )
        }
    }
}

private object Dimensions {
    val FooterHeight = 48.dp
    val InterPageSpacing = 16.dp
}

@Composable
private fun FooterBar(
    buildNumberViewModelFactory: BuildNumberViewModel.Factory,
    pagerState: PagerState,
    showArrowsInPager: Boolean,
    editButtonViewModelFactory: EditModeButtonViewModel.Factory,
    isVisible: () -> Boolean = { true },
) {
    val editButtonViewModel =
        rememberViewModel(traceName = "PaginatedGridLayout-editButtonViewModel") {
            editButtonViewModelFactory.create()
        }

    val wheel = LocalWheelLayout.current
    if (wheel.enabled) {
        WheelPageControls(
            wheel.columns,
            pagerState,
            editButtonViewModel,
            buildNumberViewModelFactory,
        )
        return
    }

    // Use requiredHeight so it won't be squished if the view doesn't quite fit. As this is
    // expected to be inside a scrollable container, this should not be an issue.
    // Also, we construct the layout this way to do the following:
    // * PagerDots is centered in the row, taking as much space as it needs.
    // * On the start side, we place the BuildNumber, taking as much space as it needs, but
    //   constrained by the available space left over after PagerDots.
    // * On the end side, we place the edit mode button, with the same constraints as for
    //   BuildNumber (but it will usually fit, as it's just a square button).
    Row(
        modifier = Modifier.requiredHeight(FooterHeight).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f)) {
            BuildNumber(viewModelFactory = buildNumberViewModelFactory)
            Spacer(modifier = Modifier.weight(1f))
        }
        PagerDots(
            pagerState = pagerState,
            activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
            nonActiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
            modifier = Modifier.wrapContentWidth(),
            showArrows = showArrowsInPager,
            clickToCyclePages = !showArrowsInPager,
        )
        Row(Modifier.weight(1f)) {
            Spacer(modifier = Modifier.weight(1f))
            EditModeButton(viewModel = editButtonViewModel, isVisible = isVisible())
        }
    }
}

@Composable
private fun WheelPageControls(
    columns: Int,
    pagerState: PagerState,
    editButtonViewModel: EditModeButtonViewModel,
    buildNumberViewModelFactory: BuildNumberViewModel.Factory,
) {
    val scope = rememberCoroutineScope()
    val page: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = {
                if (pagerState.pageCount > 0)
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1) % pagerState.pageCount
                        )
                    }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).sysuiResTag("qs_page_button"),
        ) {
            Text(
                stringResource(
                    R.string.wheel_qs_page,
                    if (pagerState.pageCount == 0) 0 else pagerState.currentPage + 1,
                    pagerState.pageCount,
                )
            )
        }
    }
    val edit: @Composable () -> Unit = {
        if (editButtonViewModel.isEditButtonVisible) {
            FilledTonalButton(
                onClick = editButtonViewModel::onButtonClick,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).sysuiResTag("qs_edit_mode_button"),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = com.android.systemui.common.ui.icons.Edit,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.wheel_qs_edit))
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = spacedBy(8.dp)) {
        if (columns == 1) {
            page()
            edit()
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { page() }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { edit() }
            }
        }
        BuildNumber(viewModelFactory = buildNumberViewModelFactory)
    }
}
