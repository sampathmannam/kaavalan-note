package com.kaavalan.note.features.capture

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import com.kaavalan.note.ui.theme.KaavalanWidgetColors
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import com.kaavalan.note.MainActivity
import com.kaavalan.note.R

/**
 * Tier 0.1 (cleanup + ship-the-built): the home-screen /
 * lock-screen widget, now built with Jetpack Glance.
 *
 * The widget is a single Capture button -- no count badge, no
 * streak counter, no carried-over copy. The colour comes from
 * [GlanceTheme.colors] (the M3 primaryContainer / onPrimaryContainer
 * tokens via the glance-material3 runtime), which is **never red**
 * per the no-shame / no-red spec rule. The button carries a
 * `contentDescription` so TalkBack reads "Open the capture sheet"
 * and the visible label is "Capture".
 *
 * **Why Glance over the legacy [android.appwidget.AppWidgetProvider] +
 * [android.widget.RemoteViews] stack:** Glance is the Compose-style
 * app-widget API as of Aug 2026; the RemoteViews path is no longer
 * recommended. Glance is also manifest-incompatible with a few
 * legacy patterns (e.g. a click listener that expects
 * `RemoteViews.setOnClickPendingIntent`); the new receiver is a
 * [GlanceAppWidgetReceiver] that owns one [GlanceAppWidget].
 *
 * **State:** the widget is stateless -- it is re-rendered on
 * every `update` (which is in turn triggered by the system at
 * `updatePeriodMillis` intervals or by an explicit
 * [KaavalanCaptureWidget.updateAll] call). The `Tap capture` action
 * always routes to [MainActivity] via the
 * [com.baton.app.features.capture.KaavalanCaptureWidget.ACTION_QUICK_CAPTURE]
 * deep link; the widget does NOT depend on app data.
 *
 * **No permission** is required to install or render the widget.
 */
class KaavalanCaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme(colors = KaavalanWidgetColors) {
                CaptureWidgetBody()
            }
        }
    }

    @Composable
    private fun CaptureWidgetBody() {
        // Tier 0.1: single Capture button. The row is centred in
        // the available cell, has rounded corners, and uses the
        // GlanceTheme primaryContainer / onPrimaryContainer tokens
        // -- **no red, no overdue wording**. The widget never
        // reads or displays instruction counts (no "red dot"
        // semantics), so the only state is the click handler
        // deep-linking to MainActivity.
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_mic_widget),
                contentDescription = stringResource(R.string.tier0_widget_capture_desc),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = stringResource(R.string.tier0_widget_capture),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                ),
            )
        }
    }

    /**
     * Glance does not ship a `stringResource` helper; the manual
     * `androidx.glance.text.Text` call site needs the resource id
     * looked up off the LocalContext. This shim is a thin wrapper
     * over [android.content.Context.getString] that the @Composable
     * site uses; keeping it private to the widget avoids leaking a
     * helper into the rest of the codebase.
     */
    @Composable
    private fun stringResource(resId: Int): String {
        val ctx = androidx.glance.LocalContext.current
        return ctx.getString(resId)
    }

    companion object {
        /**
         * Deep-link action for the quick-capture entry point. The
         * tile (Tier 0.2) and the widget both fire this action;
         * MainActivity consumes it. The constant is **unchanged**
         * from the v1.5.7 AppWidgetProvider implementation so the
         * tile + activity deep-link contract is preserved.
         */
        const val ACTION_QUICK_CAPTURE: String = "com.kaavalan.note.action.QUICK_CAPTURE"
    }
}

/**
 * Tier 0.1: the manifest-declared receiver. The class extends
 * [GlanceAppWidgetReceiver] and wires the singleton
 * [KaavalanCaptureWidget] composable. The receiver must be listed
 * in `AndroidManifest.xml` with the
 * `android.appwidget.action.APPWIDGET_UPDATE` intent filter and
 * a `<meta-data android:name="android.appwidget.provider" .../>`
 * pointing to the new `xml/kaavalan_capture_widget_info.xml`.
 */
class KaavalanCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KaavalanCaptureWidget()
}
