package com.kaavalan.note.features.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import com.kaavalan.note.ui.theme.KaavalanWidgetColors
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.kaavalan.note.R
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.CaptureEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * v1.9.11: the **Quick Note** home-screen widget — three size
 * classes with progressive disclosure.
 *
 * **v1.9.10 baseline.** v1.9.10 shipped a single-tap capture
 * button sized 2x2. The user (in the v1.9.10 final review)
 * asked for the larger sizes too: "Bigger widget sizes
 * (4x2 with count, 4x4 with recent-notes list) — additive
 * change, v1.9.11 fix them".
 *
 * **v1.9.11 design.**
 *
 *   | Size | Content rendered                                              |
 *   |------|---------------------------------------------------------------|
 *   | 2x2  | Header (icon + "Quick note" label) — the capture action      |
 *   | 4x2  | Header + today's capture count                               |
 *   | 4x4  | Header + count + 3 most recent capture titles (last-line)    |
 *
 *   The three sections are always rendered as a vertical
 *   stack; the launcher clips the lower sections when the
 *   widget is shorter. This avoids a runtime size-detection
 *   branch and keeps the composable shape stable.
 *
 * **Privacy.** Only the title (`rawText`) of each recent
 * capture is shown — never the full body, never metadata.
 * The titles are first-line-only (split on `\n` and take
 * the head). At 2x2 the recent list is not visible at all;
 * at 4x4 the user sees 3 short titles. The widget is
 * `widgetCategory=home_screen` only (no lock-screen surface).
 *
 * **Performance.** `provideGlance` reads from the local
 * SQLCipher DB through [EntryPointAccessors] — the same
 * Hilt-via-EntryPoint pattern the other Glance widgets use.
 * The three DAO calls (`countCapturesSince(startOfDay)`,
 * `recentCaptures(3)`) are cheap, indexed, and run on the
 * Glance coroutine. The widget is stateless (no
 * `GlanceStateDefinition`); the system re-renders on
 * `updatePeriodMillis` (30 min) and on explicit updates
 * from [QuickNoteActivity.save].
 *
 * **State.** Stateless, as in v1.9.10. v1.9.12 may add a
 * "Last saved: 2 min ago" subtitle that does need
 * per-widget state.
 */
class KaavalanQuickNoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        ).appDatabase()
        val todayStart = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val todayCount = runCatching {
            db.captureDao().countSince(
                Instant.ofEpochMilli(todayStart).toString(),
            )
        }.getOrDefault(0)
        val recent = runCatching {
            db.captureDao().recentText(limit = MAX_RECENT_VISIBLE)
        }.getOrDefault(emptyList())
        provideContent {
            GlanceTheme(colors = KaavalanWidgetColors) {
                QuickNoteWidgetBody(
                    todayCount = todayCount,
                    recent = recent,
                )
            }
        }
    }

    @Composable
    private fun QuickNoteWidgetBody(
        todayCount: Int,
        recent: List<CaptureEntity>,
    ) {
        // The click target is the whole card. The capture
        // button at the top is also a click target (redundant
        // with the card-level click — both fire the same
        // action). Tapping anywhere on the widget opens
        // QuickNoteActivity.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<QuickNoteActivity>()),
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
            ) {
                HeaderRow()
                CountRow(todayCount)
                if (recent.isNotEmpty()) {
                    RecentListRow(recent)
                }
            }
        }
    }

    @Composable
    private fun HeaderRow() {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_quick_note_widget),
                contentDescription = stringResource(
                    R.string.quick_note_widget_capture_desc,
                ),
                modifier = GlanceModifier.size(28.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = stringResource(R.string.quick_note_widget_label),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    @Composable
    private fun CountRow(todayCount: Int) {
        // The count line is shown when there's space. The
        // launcher clips it on 2x2 cells; on 4x2 and wider
        // it's visible below the header. The text is sized
        // small so it fits in a ~30dp vertical band.
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralizeToday(todayCount),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }

    @Composable
    private fun RecentListRow(recent: List<CaptureEntity>) {
        // The recent list is a small vertical list of titles.
        // The 4x4 cell has enough space to show 3 lines; the
        // launcher clips the rest. The titles are the first
        // line of `rawText` only — the rest is the user's
        // private body and must NOT show on the home screen.
        LazyColumn(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            items(recent) { capture ->
                RecentRow(
                    title = capture.rawText.orEmpty().lineSequence().firstOrNull()?.take(40) ?: "(untitled)",
                    ageMinutes = ageInMinutes(capture.createdAt),
                )
            }
        }
    }

    @Composable
    private fun RecentRow(title: String, ageMinutes: Long) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = relativeTime(ageMinutes),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }

    @Composable
    private fun stringResource(resId: Int): String {
        val ctx = LocalContext.current
        return ctx.getString(resId)
    }

    companion object {
        /** How many recent-capture titles to render. The widget's
         *  4x4 footprint can show 3 comfortable rows of text;
         *  4x2 shows 0; 2x2 shows 0. Three is the sweet spot. */
        const val MAX_RECENT_VISIBLE: Int = 3

        private fun pluralizeToday(count: Int): String = when (count) {
            0 -> "No notes today"
            1 -> "1 note today"
            else -> "$count notes today"
        }

        /**
         * Parse a stored `createdAt` string and return minutes
         * since the capture was made. Returns 0 on parse failure
         * (the title still shows; the time just says "0m").
         */
        private fun ageInMinutes(createdAt: String): Long {
            return runCatching {
                val instant = Instant.parse(createdAt)
                ChronoUnit.MINUTES.between(instant, Instant.now())
            }.getOrDefault(0L)
        }

        /** "5m", "2h", "yesterday", "1d" — for the relative-time
         *  badge on each recent row. */
        private fun relativeTime(minutes: Long): String = when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                LocalDate.now(ZoneId.systemDefault())
                    .minusDays(minutes / (60 * 24))
                    .format(formatter)
            }
        }
    }
}

/**
 * v1.9.11: Hilt entry point that exposes the [AppDatabase] to
 * the Glance widget. Same pattern as the existing
 * [KaavalanExtraWidgets.WidgetEntryPoint] but kept here so the
 * Quick Note widget doesn't depend on the gallery's
 * internals (the gallery may grow in v1.9.12+; the Quick
 * Note widget should remain standalone).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface QuickNoteWidgetEntryPoint {
    fun appDatabase(): AppDatabase
}

/**
 * Manifest-declared receiver. Standard Glance pattern.
 */
class KaavalanQuickNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KaavalanQuickNoteWidget()
}
