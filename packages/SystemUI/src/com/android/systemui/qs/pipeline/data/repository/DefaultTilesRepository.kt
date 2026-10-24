package com.android.systemui.qs.pipeline.data.repository

import android.content.Context
import android.content.res.Resources
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.flags.QsSplitInternetTile
import com.android.systemui.qs.pipeline.data.model.AllowedTiles
import com.android.systemui.qs.pipeline.shared.InternetTileMigration.migrateInternetTile
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.wheel.WheelLayoutSettings
import javax.inject.Inject

interface DefaultTilesRepository {
    /**
     * Returns the list of default tiles for a user.
     *
     * @param isHeadlessSystemUser indicates if the user is the headless system user (HSU) and if
     *   the HSU default tiles should be returned
     */
    fun getDefaultTiles(isHeadlessSystemUser: Boolean): List<TileSpec>
}

@SysUISingleton
class DefaultTilesQSHostRepository
@Inject
constructor(
    @param:ShadeDisplayAware private val resources: Resources,
    private val hsuTilesRepository: HsuTilesRepository,
    @Application private val context: Context,
) : DefaultTilesRepository {
    private val defaultTiles: List<TileSpec>
        get() =
            (if (WheelLayoutSettings.shade(context)) {
                    listOf(
                        if (QsSplitInternetTile.isEnabled) "wifi" else "internet",
                        "bt",
                        "cast",
                        "dnd",
                        "airplane",
                        "rotation",
                        "battery",
                        "alarm",
                        "screenrecord",
                        "dark",
                        "location",
                        "usb_tether",
                    )
                } else QSHost.getDefaultSpecs(resources))
                .map(TileSpec::create)
                .filter { it != TileSpec.Invalid }

    override fun getDefaultTiles(isHeadlessSystemUser: Boolean): List<TileSpec> {
        return if (
                isHeadlessSystemUser &&
                    hsuTilesRepository.allowedTiles is AllowedTiles.SpecificTiles
            ) {
                hsuTilesRepository.allowedTiles.tiles
            } else {
                defaultTiles
            }
            .migrateInternetTile()
    }
}
