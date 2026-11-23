package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.TvColorBar
import com.nuvio.remote.ui.components.rememberVoiceSearchLauncher
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass

private enum class DockMode {
    NAVIGATE,
    PLAYER,
    SEARCH,
}

private data class DockModuleItem(
    val label: String,
    val navAction: String,
    val icon: ImageVector,
)

/**
 * Nuvio Mode contextual Liquid Glass dock (single glass layer on outer container;
 * inner cells and buttons use opaque surfaces with >= 48dp touch targets).
 */
@Composable
fun DynamicContextDock(
    activeContext: NuvioScreenState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNav: (String) -> Unit,
    onKey: (String) -> Unit,
    onSendType: (String) -> Unit,
    onCloseSearchKeyboard: () -> Unit,
    textInputFocusTick: Long,
    nowPlaying: NuvioNowPlaying? = null,
    bridgeConnected: Boolean = false,
    onSeekTo: (Long) -> Unit = {},
    onSetVolume: (Int) -> Unit = {},
    onControl: (action: String, value: Int?) -> Unit = { _, _ -> },
    onOpenSheet: (PlayerSheetType) -> Unit = {},
    onOpenCinemaMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val accent = accentForScreen(activeContext)
    val dockMode = when (activeContext) {
        NuvioScreenState.PLAYER -> DockMode.PLAYER
        NuvioScreenState.SEARCH -> DockMode.SEARCH
        NuvioScreenState.HOME,
        NuvioScreenState.DETAIL,
        NuvioScreenState.UNKNOWN,
        -> DockMode.NAVIGATE
    }
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .nuvioGlass(
                shape = RoundedCornerShape(24.dp),
                variant = GlassVariant.REGULAR,
                elevated = true,
                accentTint = accent.surfaceTint,
            )
            .animateContentSize(animationSpec = NuvioMotion.smoothSpring(reducedMotion))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DockSheetHeader(
            dockMode = dockMode,
            nowPlaying = nowPlaying,
            dotColor = accent.primary,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onSkipIntro = {
                if (bridgeConnected) onControl("SKIP_INTRO", null) else onKey("Tab")
            },
            onNextEpisode = { onControl("NEXT_EPISODE", null) },
            onOpenLyrics = { onOpenSheet(PlayerSheetType.LYRICS) },
            onOpenSpeedTimer = { onOpenSheet(PlayerSheetType.SPEED_TIMER) },
            onOpenCinemaMode = onOpenCinemaMode,
        )

        AnimatedContent(
            targetState = Pair(dockMode, expanded),
            transitionSpec = {
                NuvioMotion.morphEnter(reducedMotion) togetherWith NuvioMotion.morphExit(reducedMotion)
            },
            label = "contextDockBody",
        ) { (mode, isExpanded) ->
            when (mode) {
                DockMode.NAVIGATE -> NavigateDockBody(
                    expanded = isExpanded,
                    onNav = onNav,
                    onKey = onKey,
                )
                DockMode.PLAYER -> PlayerNowPlayingDock(
                    nowPlaying = nowPlaying,
                    bridgeConnected = bridgeConnected,
                    expanded = isExpanded,
                    onKey = onKey,
                    onSeekTo = onSeekTo,
                    onSetVolume = onSetVolume,
                    onControl = onControl,
                    onOpenSheet = onOpenSheet,
                )
                DockMode.SEARCH -> SearchDockBody(
                    expanded = isExpanded,
                    searchText = searchText,
                    onSearchTextChange = { next ->
                        syncDockSearchText(
                            previous = searchText,
                            next = next,
                            onSendType = onSendType,
                            onKey = onKey,
                        )
                        searchText = next
                    },
                    onVoiceResult = { spoken ->
                        if (searchText.isNotEmpty()) {
                            repeat(searchText.length) { onKey("Backspace") }
                        }
                        onSendType(spoken)
                        searchText = spoken
                    },
                    onClearText = {
                        repeat(searchText.length.coerceAtLeast(1)) { onKey("Backspace") }
                        searchText = ""
                    },
                    onSubmitSearch = {
                        onKey("Enter")
                        searchText = ""
                    },
                    onCloseSearch = {
                        searchText = ""
                        onKey("Escape")
                        onCloseSearchKeyboard()
                    },
                    textInputFocusTick = textInputFocusTick,
                )
            }
        }
    }
}

@Composable
private fun DockSheetHeader(
    dockMode: DockMode,
    nowPlaying: NuvioNowPlaying?,
    dotColor: Color,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSkipIntro: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenSpeedTimer: () -> Unit,
    onOpenCinemaMode: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val dragThresholdPx = with(LocalDensity.current) { 26.dp.toPx() }
    val title = when (dockMode) {
        DockMode.NAVIGATE -> if (expanded) {
            l10n("NUVIO HUB", "CENTRAL DO NUVIO")
        } else {
            l10n("QUICK ACCESS", "ACESSO RÁPIDO")
        }
        DockMode.PLAYER -> if (nowPlaying?.isMusic == true) {
            l10n("NUVIO MUSIC", "MÚSICA NO NUVIO")
        } else {
            l10n("NUVIO PLAYER", "PLAYER NUVIO")
        }
        DockMode.SEARCH -> l10n("NUVIO SEARCH KEYBOARD & VOICE", "DIGITAÇÃO E VOZ NA BUSCA DO NUVIO")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .dockSheetVerticalDrag(
                expanded = expanded,
                dragThresholdPx = dragThresholdPx,
                onSwipeExpanded = { nextExpanded ->
                    if (hapticsEnabled) Haptics.tab(context)
                    onExpandedChange(nextExpanded)
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.tab(context)
                onExpandedChange(!expanded)
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(scheme.glassBorder),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (dockMode == DockMode.SEARCH) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
                val titleColor = if (dockMode == DockMode.SEARCH || dockMode == DockMode.PLAYER) {
                    dotColor
                } else {
                    scheme.textMuted
                }
                NuvioText(
                    text = title,
                    style = NuvioTypography.SectionMicroTitle,
                    color = titleColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            DockHeaderTrailing(
                dockMode = dockMode,
                nowPlaying = nowPlaying,
                expanded = expanded,
                onSkipIntro = onSkipIntro,
                onNextEpisode = onNextEpisode,
                onOpenLyrics = onOpenLyrics,
                onOpenSpeedTimer = onOpenSpeedTimer,
                onOpenCinemaMode = onOpenCinemaMode,
            )
        }
    }
}

private fun Modifier.dockSheetVerticalDrag(
    expanded: Boolean,
    dragThresholdPx: Float,
    onSwipeExpanded: (Boolean) -> Unit,
): Modifier = pointerInput(expanded, onSwipeExpanded, dragThresholdPx) {
    var dragAccum = 0f
    var triggered = false
    detectVerticalDragGestures(
        onDragStart = {
            dragAccum = 0f
            triggered = false
        },
        onDragEnd = { dragAccum = 0f },
        onDragCancel = { dragAccum = 0f },
    ) { change, dragAmount ->
        change.consume()
        if (triggered) return@detectVerticalDragGestures
        dragAccum += dragAmount
        val target = when {
            !expanded && dragAccum <= -dragThresholdPx -> true
            expanded && dragAccum >= dragThresholdPx -> false
            else -> null
        }
        if (target != null) {
            triggered = true
            onSwipeExpanded(target)
        }
    }
}

@Composable
private fun DockHeaderTrailing(
    dockMode: DockMode,
    nowPlaying: NuvioNowPlaying?,
    expanded: Boolean,
    onSkipIntro: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenSpeedTimer: () -> Unit,
    onOpenCinemaMode: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    if (dockMode == DockMode.PLAYER && expanded) {
        if (nowPlaying?.isMusic == true) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockHeaderActionPill(
                    label = l10n("Lyrics", "Letra"),
                    icon = Icons.AutoMirrored.Rounded.Subject,
                    onClick = onOpenLyrics,
                )
                DockHeaderActionPill(
                    label = "Cinema",
                    icon = Icons.Rounded.DarkMode,
                    onClick = onOpenCinemaMode,
                )
                val timerLabel = if (nowPlaying.sleepMinutes > 0) "${nowPlaying.sleepMinutes}m" else "Timer"
                DockHeaderActionPill(
                    label = timerLabel,
                    icon = Icons.Rounded.Timer,
                    onClick = onOpenSpeedTimer,
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockHeaderActionPill(
                    label = "Cinema",
                    icon = Icons.Rounded.DarkMode,
                    onClick = onOpenCinemaMode,
                )
                val skipText = nowPlaying?.skipLabel?.takeIf { it.isNotBlank() } ?: l10n("Skip intro", "Pular intro")
                if (nowPlaying == null || !nowPlaying.skipLabel.isNullOrBlank() || nowPlaying.canSkip) {
                    DockHeaderActionPill(
                        label = skipText,
                        icon = Icons.Rounded.SkipNext,
                        onClick = onSkipIntro,
                    )
                }
                if (nowPlaying?.canNextEpisode == true) {
                    DockHeaderActionPill(
                        label = l10n("Next ep.", "Próx. ep."),
                        icon = Icons.Rounded.SkipNext,
                        onClick = onNextEpisode,
                    )
                }
            }
        }
        return
    }
    val actionLabel = when {
        dockMode == DockMode.NAVIGATE && !expanded -> l10n("All", "Todos")
        expanded -> l10n("Collapse", "Recolher")
        else -> l10n("More", "Mais")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NuvioText(
            text = actionLabel,
            style = NuvioTypography.KeyLabelCompact,
            color = scheme.textPrimary,
            maxLines = 1,
            softWrap = false,
        )
        NuvioIcon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
            contentDescription = if (expanded) {
                l10n("Collapse panel", "Recolher painel")
            } else {
                l10n("Expand panel", "Expandir painel")
            },
            tint = scheme.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NavigateDockBody(expanded: Boolean, onNav: (String) -> Unit, onKey: (String) -> Unit) {
    val quickModules = listOf(
        DockModuleItem(l10n("Search", "Busca"), "search", Icons.Rounded.Search),
        DockModuleItem(l10n("Library", "Biblioteca"), "library", Icons.Rounded.VideoLibrary),
        DockModuleItem("YouTube", "youtube", Icons.Rounded.Slideshow),
        DockModuleItem("Live TV", "live", Icons.Rounded.LiveTv),
    )
    val primaryModules = listOf(
        DockModuleItem(l10n("Search", "Busca"), "search", Icons.Rounded.Search),
        DockModuleItem(l10n("Library", "Biblioteca"), "library", Icons.Rounded.VideoLibrary),
        DockModuleItem(l10n("Discover", "Descobrir"), "discover", Icons.Rounded.Explore),
        DockModuleItem("YouTube", "youtube", Icons.Rounded.Slideshow),
    )
    val secondaryModules = listOf(
        DockModuleItem(l10n("Music", "Música"), "music", Icons.AutoMirrored.Rounded.QueueMusic),
        DockModuleItem("Live TV", "live", Icons.Rounded.LiveTv),
        DockModuleItem(l10n("Games", "Jogos"), "game", Icons.Rounded.SportsEsports),
        DockModuleItem(l10n("Calendar", "Agenda"), "calendar", Icons.Rounded.CalendarMonth),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (item in quickModules) {
                    DockCircleTile(
                        label = item.label,
                        icon = item.icon,
                        onClick = { onNav(item.navAction) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in primaryModules) {
                    DockModuleTile(
                        label = item.label,
                        icon = item.icon,
                        onClick = { onNav(item.navAction) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in secondaryModules) {
                    DockModuleTile(
                        label = item.label,
                        icon = item.icon,
                        onClick = { onNav(item.navAction) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DockCellButton(
                label = l10n("Exit current screen", "Sair da tela atual"),
                icon = Icons.Rounded.Close,
                visualHeight = 38.dp,
                onClick = { onKey("Escape") },
                modifier = Modifier.fillMaxWidth(),
            )
            TvColorBar(onColor = { onKey(it.keyShortcut) })
        }
    }
}

@Composable
private fun SearchDockBody(
    expanded: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onVoiceResult: (String) -> Unit,
    onClearText: () -> Unit,
    onSubmitSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    textInputFocusTick: Long,
) {
    val scheme = LocalNuvioColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val launchVoice = rememberVoiceSearchLauncher(onResult = onVoiceResult)

    LaunchedEffect(textInputFocusTick) {
        if (textInputFocusTick > 0L) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchFieldBox(
                value = searchText,
                onValueChange = onSearchTextChange,
                focusRequester = focusRequester,
                onSubmit = onSubmitSearch,
                modifier = Modifier.weight(1f),
            )
            DockVoiceButton(onClick = launchVoice)
            if (expanded) {
                DockHeroPillButton(
                    label = l10n("Keyboard", "Teclado"),
                    icon = Icons.Rounded.Keyboard,
                    visualHeight = 40.dp,
                    onClick = {
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    },
                    modifier = Modifier.width(96.dp),
                )
            } else {
                DockHeroPillButton(
                    label = l10n("Search", "Buscar"),
                    icon = Icons.Rounded.Search,
                    visualHeight = 40.dp,
                    onClick = onSubmitSearch,
                    modifier = Modifier.width(86.dp),
                )
                DockIconOnlyButton(
                    icon = Icons.Rounded.Close,
                    description = l10n("Close search", "Fechar busca"),
                    tint = scheme.statusError,
                    onClick = onCloseSearch,
                )
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DockCellButton(
                    label = l10n("Clear", "Limpar"),
                    icon = Icons.AutoMirrored.Rounded.Backspace,
                    visualHeight = 38.dp,
                    onClick = onClearText,
                    modifier = Modifier.weight(1f),
                )
                DockCellButton(
                    label = l10n("Search", "Buscar"),
                    icon = Icons.Rounded.Search,
                    visualHeight = 38.dp,
                    onClick = onSubmitSearch,
                    modifier = Modifier.weight(1f),
                )
                DockCellButton(
                    label = l10n("Close", "Fechar"),
                    icon = Icons.Rounded.Close,
                    visualHeight = 38.dp,
                    onClick = {
                        keyboard?.hide()
                        onCloseSearch()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DockVoiceButton(onClick: () -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = l10n("Voice search", "Busca por voz"),
                tint = scheme.textPrimary,
                modifier = Modifier.size(16.dp),
            )
            NuvioText(
                text = l10n("Voice", "Voz"),
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SearchFieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current

    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                NuvioText(
                    text = l10n("Search in Nuvio…", "Digitar busca no Nuvio…"),
                    style = NuvioTypography.BodyRegular,
                    color = scheme.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(scheme.textPrimary),
                textStyle = NuvioTypography.BodyRegular.copy(color = scheme.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun DockCircleTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.94f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "dockCircleScale",
    )

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        NuvioText(
            text = label,
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DockModuleTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "dockModuleScale",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(13.dp))
                .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp))
                .padding(horizontal = 3.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.textPrimary,
                modifier = Modifier.size(16.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DockCellButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visualHeight: Dp = 42.dp,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "dockCellScale",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(visualHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(14.dp))
                .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.textPrimary,
                modifier = Modifier.size(17.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DockHeroPillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visualHeight: Dp = 44.dp,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "dockHeroScale",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.ok(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(visualHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.pillActiveFg,
                modifier = Modifier.size(18.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.pillActiveFg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DockHeaderActionPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.textPrimary,
                modifier = Modifier.size(15.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun DockIconOnlyButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

private fun syncDockSearchText(previous: String, next: String, onSendType: (String) -> Unit, onKey: (String) -> Unit) {
    when {
        next.length > previous.length && next.startsWith(previous) -> {
            onSendType(next.substring(previous.length))
        }
        next.length < previous.length && previous.startsWith(next) -> {
            repeat(previous.length - next.length) { onKey("Backspace") }
        }
        next != previous -> {
            repeat(previous.length) { onKey("Backspace") }
            if (next.isNotEmpty()) onSendType(next)
        }
    }
}
