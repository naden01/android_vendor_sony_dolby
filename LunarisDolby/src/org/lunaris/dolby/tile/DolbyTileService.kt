/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyRepository

class DolbyTileService : TileService() {

    // Created on demand and released in onDestroy: each instance owns a native
    // audio effect, and TileService instances are recreated frequently.
    private var repository: DolbyRepository? = null

    private fun repo(): DolbyRepository =
        repository ?: DolbyRepository(applicationContext).also { repository = it }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val r = repo()
        r.setDolbyEnabled(!r.getDolbyEnabled())
        updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            val enabled = repo().getDolbyEnabled()
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = getProfileName()
            updateTile()
        }
    }

    private fun getProfileName(): String {
        val profile = repo().getCurrentProfile()
        val profiles = resources.getStringArray(R.array.dolby_profile_entries)
        val profileValues = resources.getStringArray(R.array.dolby_profile_values)

        return try {
            val index = profileValues.indexOf(profile.toString())
            if (index != -1) profiles[index] else getString(R.string.dolby_unknown)
        } catch (e: Exception) {
            getString(R.string.dolby_unknown)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository?.close()
        repository = null
    }
}
