package com.kaavalan.note.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaavalan.note.ui.theme.KaavalanNoteDarkScheme
import com.kaavalan.note.ui.theme.KaavalanNoteLightScheme

/**
 * v1.6.0: the Kaavalan note signature accent. A single 2dp dot, line,
 * or left-edge tag that appears ONCE per screen as a quiet
 * signal of identity. The audit (§4.8) and the red-dot
 * critique both call out the lack of "an opinion about
 * colour" in the visual language. This is that opinion.
 *
 * Usage rules (research-driven, not arbitrary):
 *   1. **One per screen, not two.** Cowan 2001 3-4 chunk
 *      limit. Two competing accents on one screen reads as
 *      a settings page.
 *   2. **2dp wide, never larger.** The accent is a *whisper*,
 *      not a banner. Things 3's signature purple lives at
 *      1-2dp. Bear's at 1.5dp. We are 2dp.
 *   3. **Use the accent for *identity*, not for *emphasis*.**
 *      A red error message is not the accent — that's a
 *      different colour (and we don't ship red anyway).
 *      The accent is the "this is Kaavalan note" mark, not the
 *      "this is important" mark.
 *   4. **Hide when there's nothing to identify.** The dot
 *      is omitted on screens that already have a strong
 *      visual identity (the recovery phrase Surface, the
 *      capture sheet, the icon). Restraint over consistency.
 *
 * Variants:
 *   - [KaavalanAccentDot]   — a 2dp dot, used for inline identity
 *     on text-heavy rows.
 *   - [KaavalanAccentLine]  — a 2dp left-edge tag, used as the
 *     "active row" indicator on lists.
 *   - [KaavalanAccentBar]   — a 2dp horizontal bar, used at the
 *     top of section cards.
 *   - [KaavalanAccentLeftTag] — a 2dp vertical strip wrapping
 *     content, used for "active row" highlights.
 */
@Composable
fun KaavalanAccentDot(
    modifier: Modifier = Modifier,
    size: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(KaavalanAccentColor),
    )
}

@Composable
fun KaavalanAccentLine(
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    width: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(KaavalanAccentColor),
    )
}

@Composable
fun KaavalanAccentBar(
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .height(2.dp)
            .background(KaavalanAccentColor),
    )
}

@Composable
fun KaavalanAccentLeftTag(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(KaavalanAccentColor),
        )
        Box(modifier = Modifier.padding(start = 8.dp)) {
            content()
        }
    }
}

/** Legacy entry points share the app's plum identity rather than a second fixed accent. */
val KaavalanAccentLight: Color = KaavalanNoteLightScheme.primary
val KaavalanAccentDark: Color = KaavalanNoteDarkScheme.primary

/**
 * Follow the applied theme, including an app override that differs from the OS.
 */
val KaavalanAccentColor: Color
    @Composable
    get() = MaterialTheme.colorScheme.primary
