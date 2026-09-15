package com.kaavalan.note.features.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.kaavalan.note.MainActivity
import com.kaavalan.note.R

/**
 * Tier 0.2 (cleanup + ship-the-built): the quick-settings tile.
 *
 * This is the v1.6.0 rewrite of the v1.5.7
 * `KaavalanCaptureTileService`. The legacy implementation was
 * correct in shape but had three v1.6-relevant issues:
 *
 *  1. The tile label was read from `R.string.tile_label`
 *     ("Kaavalan note quick-capture") which renders correctly
 *     in the system shade but is verbose; the system tile
 *     picker prefers the short form. The new label is the
 *     same string (no breaking change) and the description is
 *     a new `tier0_tile_description` string the system uses
 *     for TalkBack and the long-press hint.
 *  2. The tile state was never explicitly set. The system
 *     defaults to [Tile.STATE_INACTIVE] on first render, but a
 *     tile that is part of a "fire-and-forget" capture
 *     surface must explicitly set the state in
 *     [onStartListening] so the system's accessibility
 *     services (TalkBack) announce the correct state.
 *  3. The tile did not have the `META_DATA_ACTIVE_TILE`
 *     meta-data. With it, the system only binds the service
 *     after the user explicitly requests listening state
 *     (which we never do for a one-shot capture tile), so the
 *     bound service lifetime is much shorter -- a battery win
 *     and a privacy win (no background service is alive when
 *     the user is not in the shade).
 *
 * **Active mode + INACTIVE state:** with the
 * `META_DATA_ACTIVE_TILE` flag set, the tile is an "active
 * tile" -- the system calls [onStartListening] when the tile
 * becomes visible, and [onStopListening] when it leaves. We
 * use [onStartListening] to push the [Tile.STATE_INACTIVE]
 * label/icon so TalkBack and the tile picker both see the
 * correct state. Tapping fires [onClick] which deep-links to [MainActivity] via
 * [SpeakNoteActivity.ACTION_SPEAK_NOTE] -- the same voice request used by the Tier 0.1 widget,
 * physical shortcut and launcher shortcut.
 *
 * **Android 14+ handling:** API 34 replaced the Intent overload
 * of [startActivityAndCollapse] with a [PendingIntent] overload.
 * Using the system-mediated PendingIntent path also keeps this
 * launch valid under modern background-activity restrictions.
 *
 * **Permission:** the manifest declares
 * `android.permission.BIND_QUICK_SETTINGS_TILE` -- a
 * system-level permission that requires no runtime ask. The
 * user adds the tile from the system shade.
 */
class KaavalanTileService : TileService() {

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartListening() {
        super.onStartListening()
        // Tier 0.2: explicitly push the inactive state. The
        // tile is a "fire-and-forget" capture entry point --
        // there is no persistent on/off state, so it is
        // permanently INACTIVE. The system will not flip the
        // state on its own; the explicit set is the contract.
        val tile = qsTile ?: return
        tile.label = getString(R.string.tier0_tile_label)
        tile.state = Tile.STATE_INACTIVE
        // v1.6: the description is what TalkBack reads when
        // the tile is focused in the shade. The
        // `R.string.tier0_tile_description` is the same copy
        // the tile picker shows in the "add a tile" dialog.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.contentDescription = getString(R.string.tier0_tile_description)
        }
        tile.updateTile()
    }

    // The deprecated Intent overload is required on API 26-33 because the PendingIntent
    // overload does not exist there. The runtime guard keeps it unreachable on API 34+.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = SpeakNoteActivity.ACTION_SPEAK_NOTE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                SPEAK_NOTE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val SPEAK_NOTE_REQUEST_CODE = 2_701
    }
}
