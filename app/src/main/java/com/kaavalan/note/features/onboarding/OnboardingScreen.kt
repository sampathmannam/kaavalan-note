package com.kaavalan.note.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import kotlinx.coroutines.launch

/**
 * Tier 1.2 (v2.0) + v1.9.1 polish: the first-run onboarding
 * screen.
 *
 * 4 steps in a Compose `HorizontalPager`:
 *  1. Welcome — short hero + the K+dot icon.
 *  2. Privacy — "your data lives only here" + 3 plain lines.
 *  3. How Kaavalan note is different — the v1.9.0 "Kaavalan note is not a
 *     notes app" explainer (3 short bullet rows). The
 *     strings (`onboarding_screen_1_*` .. `onboarding_screen_4_*`)
 *     shipped in v1.9.0 but were never wired into the
 *     OnboardingScreen. v1.9.1 promotes the explainer from
 *     a settings-only view (intended for v1.9.0's
 *     `onboarding_title` / `onboarding_subtitle` doc screen)
 *     to the first-run flow. Three of the four v1.9.0
 *     strings map directly to a row in the new page; the
 *     fourth ("Free, forever") is folded into the page body.
 *  4. Add your first person — hero with a "Use sample data"
 *     toggle.
 *
 * The host ([com.kaavalan.note.MainScaffold]) reads the
 * `hasSeenOnboarding` flow in [KaavalanPreferences] and only
 * shows this screen on the first run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages = listOf(
        OnboardingPage.Welcome,
        OnboardingPage.Privacy,
        OnboardingPage.NotJustNotes,
        OnboardingPage.GetStarted,
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentPage(pagerState.currentPage)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            OnboardingPageContent(pages[page], loadSample = state.loadSample,
                onSampleToggled = viewModel::setSampleToggled)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            pages.indices.forEach { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (pagerState.currentPage > 0) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    } else {
                        viewModel.finish(onDone)
                    }
                },
                enabled = !state.working,
            ) {
                Text(
                    text = if (pagerState.currentPage > 0) {
                        stringResource(R.string.onboarding_back)
                    } else {
                        stringResource(R.string.onboarding_skip)
                    },
                )
            }
            Button(
                onClick = {
                    if (pagerState.currentPage < pages.lastIndex) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        viewModel.finish(onDone)
                    }
                },
                enabled = !state.working,
            ) {
                Text(
                    text = if (pagerState.currentPage < pages.lastIndex) {
                        stringResource(R.string.onboarding_next)
                    } else {
                        stringResource(R.string.onboarding_get_started)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private enum class OnboardingPage { Welcome, Privacy, NotJustNotes, GetStarted }

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    loadSample: Boolean,
    onSampleToggled: (Boolean) -> Unit,
) {
    when (page) {
        OnboardingPage.Welcome -> WelcomePage()
        OnboardingPage.Privacy -> PrivacyPage()
        OnboardingPage.NotJustNotes -> NotJustNotesPage()
        OnboardingPage.GetStarted -> GetStartedPage(
            loadSample = loadSample,
            onSampleToggled = onSampleToggled,
        )
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // v1.6.3: replaced the placeholder `Icons.Default.Lock`
        // with the new 05-shieldmark adaptive foreground. The
        // brand mark on the onboarding now matches the launcher
        // icon. The cream background of the shield is the
        // Kaavalan note brand surface; we render the shield on its
        // native background (no clip / circle) so the W
        // cutout is visible.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(
                id = com.kaavalan.note.R.drawable.ic_launcher_foreground,
            ),
            contentDescription = null,
            modifier = Modifier.size(120.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrivacyPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_privacy_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.onboarding_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PrivacyBullet(
            icon = Icons.Default.PhoneAndroid,
            text = stringResource(R.string.onboarding_privacy_line1),
        )
        PrivacyBullet(
            icon = Icons.Default.Lock,
            text = stringResource(R.string.onboarding_privacy_line2),
        )
        PrivacyBullet(
            icon = Icons.Default.Person,
            text = stringResource(R.string.onboarding_privacy_line3),
        )
    }
}

@Composable
private fun PrivacyBullet(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GetStartedPage(
    loadSample: Boolean,
    onSampleToggled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            text = stringResource(R.string.onboarding_start_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.onboarding_start_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // BUG FIX (found via adversarial QA audit): the row used
        // to rely on the Switch itself as the only tap target,
        // so tapping the label text -- the larger, more natural
        // target -- silently did nothing. Modifier.toggleable on the
        // Row makes the whole row (label included) a single tap
        // target, mirroring the "full-row-tappable" pattern
        // Settings' PrivacyRow uses. The Switch's own
        // onCheckedChange is set to null (not onSampleToggled) so
        // the Row's toggleable is the single source of truth for
        // both the tap handling and the TalkBack "switch" role
        // announcement -- keeping both wired would double-toggle on
        // a direct tap of the Switch thumb.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = loadSample,
                    onValueChange = onSampleToggled,
                    role = Role.Switch,
                ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onboarding_sample_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Switch(checked = loadSample, onCheckedChange = null)
        }
    }
}

/**
 * v1.9.1 (PROD-READINESS-P3-P0-#9 wiring): the "Kaavalan note is not
 * a notes app" explainer page. This is the v1.9.0
 * `onboarding_title` + `onboarding_subtitle` content rendered
 * inside the first-run pager rather than as a separate
 * settings doc. Three of the four v1.9.0 explainer rows
 * (local-only / person-first / deniable) become
 * `PrivacyBullet`-style rows; the fourth ("Free, forever")
 * is the page footer. The visible copy is the v1.9.0 string
 * verbatim — no rewrite, no padding, no multi-phase rollout.
 */
@Composable
private fun NotJustNotesPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        NotJustNotesBullet(
            icon = Icons.Default.PhoneAndroid,
            title = stringResource(R.string.onboarding_screen_1_title),
            body = stringResource(R.string.onboarding_screen_1_body),
        )
        NotJustNotesBullet(
            icon = Icons.Default.Person,
            title = stringResource(R.string.onboarding_screen_2_title),
            body = stringResource(R.string.onboarding_screen_2_body),
        )
        NotJustNotesBullet(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.onboarding_screen_3_title),
            body = stringResource(R.string.onboarding_screen_3_body),
        )
        NotJustNotesBullet(
            icon = Icons.Default.Favorite,
            title = stringResource(R.string.onboarding_screen_4_title),
            body = stringResource(R.string.onboarding_screen_4_body),
        )
    }
}

/**
 * v1.9.1: a title + body bullet variant for the
 * "NotJustNotes" page (the Privacy page uses a simpler
 * icon + single-line pattern). Same Surface-in-Circle icon
 * treatment so the two pages read as siblings.
 */
@Composable
private fun NotJustNotesBullet(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
