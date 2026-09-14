package com.kaavalan.note.features.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import com.kaavalan.note.ui.theme.KaavalanWidgetColors
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.kaavalan.note.data.local.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * v1.9.0 (PROD-READINESS-P3-P2-#3) +
 * v1.9.1 wiring: the "widget gallery" expansion. The v1.x
 * build had one widget (the Capture widget — single tap to
 * open the capture sheet). v1.9.0 adds two more:
 *
 *  - [KaavalanTodayWidget] — opens the Today screen. The widget
 *    surface shows the count of open instructions (the
 *    number is the same one the Today screen's "open"
 *    counter surfaces; the widget refreshes on
 *    `updatePeriodMillis` which is set to 30 minutes by
 *    the XML metadata).
 *  - [KaavalanDecayWidget] — opens the Today screen and scrolls
 *    to the Decay section. Shows the count of quiet contacts
 *    (people the user hasn't touched in 60+ days, i.e. the
 *    "Periodic" tier). The number is the same one the Decay
 *    section surfaces.
 *
 * **State (v1.9.1).** Both widgets are now data-bound. The
 * v1.9.0 release shipped them with a hard-coded "—" badge
 * (the documented trade-off was "show the widget shape, fill
 * in the data later"). v1.9.1 wires each `provideGlance` to
 * a one-shot Room count:
 *  - `InstructionDao.countOpen()` for the Today widget
 *  - `PersonDao.countQuietSince(thresholdMs)` for the Decay
 *    widget (threshold = now - 60 days, in epoch ms).
 *
 * Hilt is reached via [EntryPointAccessors.fromApplication]
 * (the standard pattern for Glance widgets, which run inside
 * the app process but outside the Compose navigation graph
 * where the regular `@HiltViewModel` injection works). The
 * one-shot DAO call is cheap (one indexed COUNT(*)) and
 * runs on the Glance coroutine; the result is rendered
 * synchronously inside the Composable.
 *
 * **Receivers.** Each widget has a [GlanceAppWidgetReceiver]
 * that the manifest registers. A single APK can ship
 * multiple receivers; they share the AppWidget icon assets
 * in `res/drawable/`.
 *
 * **No new permission** is required. The widget is a
 * small UI surface; the data it shows is in the app's
 * private SQLCipher DB.
 */
class KaavalanTodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        ).appDatabase()
        val openCount = runCatching { db.instructionDao().countOpen() }.getOrDefault(0)
        provideContent {
            GlanceTheme(colors = KaavalanWidgetColors) {
                TodayWidgetBody(openCount = openCount)
            }
        }
    }

    @Composable
    private fun TodayWidgetBody(openCount: Int) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.secondaryContainer)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = openCount.toString(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                    ),
                )
            }
        }
    }
}

class KaavalanTodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KaavalanTodayWidget()
}

class KaavalanDecayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        ).appDatabase()
        val thresholdMs = System.currentTimeMillis() -
            TimeUnit.DAYS.toMillis(QUIET_THRESHOLD_DAYS)
        val quietCount = runCatching {
            db.personDao().countQuietSince(thresholdMs)
        }.getOrDefault(0)
        provideContent {
            GlanceTheme(colors = KaavalanWidgetColors) {
                DecayWidgetBody(quietCount = quietCount)
            }
        }
    }

    @Composable
    private fun DecayWidgetBody(quietCount: Int) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.tertiaryContainer)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Quiet contacts",
                style = TextStyle(
                    color = GlanceTheme.colors.onTertiaryContainer,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = quietCount.toString(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
            }
        }
    }
}

class KaavalanDecayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KaavalanDecayWidget()
}

/**
 * v1.9.1: Hilt entry point for the Glance widgets. Glance
 * widgets run in the app process but outside the normal
 * Compose nav graph (no `@HiltViewModel` injection). The
 * standard pattern is `@EntryPoint` + `EntryPointAccessors
 * .fromApplication(...)` to pull a Singleton-scoped dep.
 * The widget uses the AppDatabase only (for one COUNT query
 * each); the higher-level repositories (RoomInstruction
 * Repository, RoomPersonRepository) are not needed here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun appDatabase(): AppDatabase
}

/**
 * v1.9.1: the quiet-contact threshold. Matches the v1.9.0
 * widget description string ("60+ days since last
 * interaction"). The Decay screen's user-togglable
 * filter (14/30/60/90) is a separate concern; the widget
 * intentionally uses a fixed 60d cutoff so the badge value
 * is stable across user filter changes.
 */
private const val QUIET_THRESHOLD_DAYS = 60L
