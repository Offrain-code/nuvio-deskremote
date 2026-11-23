package com.nuvio.remote.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioContinueItem
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioSearchResults
import com.nuvio.remote.connection.NuvioVideoLibrary
import com.nuvio.remote.connection.NuvioVideoTitle
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.rememberRemoteBitmap
import com.nuvio.remote.ui.components.rememberVoiceSearchLauncher
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import java.util.Locale

private data class WatchFilterSpec(
    val key: String,
    val labelEn: String,
    val labelPt: String,
)

private val WATCH_FILTERS = listOf(
    WatchFilterSpec("all", "All", "Todos"),
    WatchFilterSpec("movie", "Movies", "Filmes"),
    WatchFilterSpec("series", "Series", "Séries"),
    WatchFilterSpec("tv", "TV", "TV"),
    WatchFilterSpec("unwatched", "Unwatched", "Não assistidos"),
)

/**
 * Watch sub-tab inside Apps (DESIGN-SPEC.md section 5.5.1).
 * 16:9 cards and 2-column poster grids are 100% opaque (no glass).
 */
@Composable
fun AppsWatchTab(
    bridgeConnected: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    continueItems: List<NuvioContinueItem>,
    videoLibrary: NuvioVideoLibrary,
    searchResults: NuvioSearchResults,
    onRefresh: () -> Unit,
    onResume: (NuvioContinueItem) -> Unit,
    onOpenTitle: (NuvioVideoTitle) -> Unit,
    onOpenMenuForTitle: (NuvioVideoTitle) -> Unit,
    onOpenMenuForContinue: (NuvioContinueItem) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val trimmedQuery = searchQuery.trim()
    val searching = trimmedQuery.isNotEmpty()
    val filteredLibrary = remember(videoLibrary.items, selectedFilter) {
        videoLibrary.items.filter { item -> matchesWatchFilter(item, selectedFilter) }
    }
    val mergedSearchResults = remember(videoLibrary.items, searchResults.items, searchResults.query, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            val local = videoLibrary.items.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
            val remote = if (searchResults.query.equals(trimmedQuery, ignoreCase = true)) {
                searchResults.items
            } else {
                emptyList()
            }
            (local + remote).distinctBy { "${it.contentType}:${it.id}" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            WatchSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onSubmit = { onSubmitSearch(searchQuery) },
                onClear = {
                    onSearchQueryChange("")
                    onSubmitSearch("")
                },
                onRefresh = onRefresh,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WATCH_FILTERS.forEach { spec ->
                    CatalogFilterPill(
                        label = l10n(lang, spec.labelEn, spec.labelPt),
                        selected = selectedFilter == spec.key,
                        onClick = { onSelectFilter(spec.key) },
                    )
                }
            }
        }

        if (!bridgeConnected) {
            item {
                CatalogInfoBox(
                    text = l10n(
                        "Link Direct Nuvio Control in Settings to browse your library, " +
                            "continue watching, and search addons.",
                        "Vincule o Controle Direto do Nuvio em Ajustes para navegar pela sua biblioteca, " +
                            "continuar assistindo e buscar nos addons.",
                    ),
                )
            }
            return@LazyColumn
        }

        if (searching) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioText(
                        text = l10n("LIBRARY & ADDON SEARCH", "BUSCA NA BIBLIOTECA E ADDONS"),
                        style = NuvioTypography.SectionMicroTitle,
                        color = scheme.textMuted,
                    )
                    NuvioText(
                        text = if (searchResults.loading) {
                            l10n("Searching addons…", "Buscando nos addons…")
                        } else {
                            "${mergedSearchResults.size} ${l10n("found", "encontrados")}"
                        },
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textSecondary,
                    )
                }
            }
            if (mergedSearchResults.isEmpty()) {
                item {
                    CatalogInfoBox(
                        text = if (searchResults.loading || trimmedQuery.length >= 2) {
                            l10n(
                                "Searching Nuvio library and addons for \"$trimmedQuery\"…",
                                "Consultando biblioteca e addons do Nuvio para \"$trimmedQuery\"…",
                            )
                        } else {
                            l10n(
                                "Type at least 2 characters to search movies and series across addons.",
                                "Digite pelo menos 2 letras para buscar filmes e séries nos addons.",
                            )
                        },
                    )
                }
            } else {
                items(mergedSearchResults, key = { "${it.contentType}-${it.id}-${it.section}" }) { item ->
                    SearchResultRow(
                        item = item,
                        onClick = { onOpenTitle(item) },
                        onOpenMenu = { onOpenMenuForTitle(item) },
                    )
                }
            }
            return@LazyColumn
        }

        if (continueItems.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioText(
                        text = l10n("CONTINUE WATCHING", "CONTINUAR ASSISTINDO"),
                        style = NuvioTypography.SectionMicroTitle,
                        color = scheme.textMuted,
                    )
                    NuvioText(
                        text = l10n("Tap resumes on TV", "Toque retoma na TV"),
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textMuted,
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(continueItems, key = { "${it.parentId}-${it.videoId}" }) { item ->
                        ContinueWatchingCard(
                            item = item,
                            onClick = { onResume(item) },
                            onLongClick = { onOpenMenuForContinue(item) },
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n("YOUR LIBRARY", "SUA BIBLIOTECA"),
                    style = NuvioTypography.SectionMicroTitle,
                    color = scheme.textMuted,
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    NuvioText(
                        text = "${filteredLibrary.size} ${l10n("titles", "títulos")}",
                        style = NuvioTypography.KeyShortcutBadge,
                        color = accentForScreen(NuvioScreenState.PLAYER).primary,
                    )
                }
            }
        }

        if (filteredLibrary.isEmpty()) {
            item {
                CatalogInfoBox(
                    text = if (!videoLibrary.ready) {
                        l10n("Loading Nuvio library…", "Carregando biblioteca do Nuvio…")
                    } else {
                        l10n("No titles match this filter.", "Nenhum título corresponde a este filtro.")
                    },
                )
            }
        } else {
            val rows = filteredLibrary.chunked(2)
            items(rows.size) { rowIndex ->
                val pair = rows[rowIndex]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { item ->
                        LibraryPosterCard(
                            item = item,
                            onClick = { onOpenTitle(item) },
                            onOpenMenu = { onOpenMenuForTitle(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun matchesWatchFilter(item: NuvioVideoTitle, filter: String): Boolean {
    val type = item.contentType.lowercase(Locale.ROOT)
    return when (filter) {
        "movie" -> type == "movie"
        "series" -> type == "series"
        "tv" -> type == "tv" || type == "channel"
        "unwatched" -> !item.watched
        else -> true
    }
}

@Composable
private fun WatchSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val launchVoice = rememberVoiceSearchLauncher(
        onResult = { spoken ->
            onQueryChange(spoken)
            onSubmit()
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.Search,
                contentDescription = l10n("Search", "Buscar"),
                tint = scheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    NuvioText(
                        text = l10n("Search library & addons…", "Buscar biblioteca e addons…"),
                        style = NuvioTypography.BodyRegular,
                        color = scheme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(scheme.textPrimary),
                    textStyle = NuvioTypography.BodyRegular.copy(color = scheme.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        CatalogSquareIconButton(
            icon = Icons.Rounded.Mic,
            description = l10n("Voice search", "Busca por voz"),
            onClick = {
                if (hapticsEnabled) Haptics.action(context)
                launchVoice()
            },
        )
        CatalogSquareIconButton(
            icon = if (query.isNotEmpty()) Icons.Rounded.Close else Icons.Rounded.Refresh,
            description = if (query.isNotEmpty()) {
                l10n("Clear search", "Limpar busca")
            } else {
                l10n("Refresh library", "Atualizar biblioteca")
            },
            onClick = {
                if (hapticsEnabled) Haptics.action(context)
                if (query.isNotEmpty()) onClear() else onRefresh()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(item: NuvioContinueItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val lang = LocalAppLanguage.current
    val art = rememberRemoteBitmap(item.still ?: item.poster)
    val frac = (item.progressPermille / 1000f).coerceIn(0f, 1f)
    val subtitleText = listOfNotNull(
        item.episode?.takeIf { it.isNotBlank() },
        item.remainingMinutes?.let { l10n(lang, "${it}m left", "${it}m restantes") },
    ).joinToString(" • ")

    Column(
        modifier = Modifier
            .width(196.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onClick()
                },
                onLongClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onLongClick()
                },
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.contentCellSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                NuvioIcon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .fillMaxHeight()
                        .background(accentForScreen(NuvioScreenState.PLAYER).primary),
                )
            }
        }
        NuvioText(
            text = item.title,
            style = NuvioTypography.KeyLabelPrimary,
            color = scheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitleText.isNotEmpty()) {
            NuvioText(
                text = subtitleText,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPosterCard(
    item: NuvioVideoTitle,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val lang = LocalAppLanguage.current
    val art = rememberRemoteBitmap(item.poster ?: item.posterFallback)
    val isSeries = item.contentType.equals("series", ignoreCase = true)
    val meta = listOfNotNull(
        if (isSeries) l10n(lang, "Series", "Série") else l10n(lang, "Movie", "Filme"),
        item.year?.takeIf { it.isNotBlank() },
    ).joinToString(" • ")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onClick()
                },
                onLongClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onOpenMenu()
                },
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.contentCellSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                NuvioIcon(
                    imageVector = if (isSeries) Icons.Rounded.Tv else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (item.watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(scheme.statusOnline)
                        .border(1.5.dp, Color.Black.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = l10n("Watched", "Assistido"),
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioText(
                    text = item.name,
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                NuvioText(
                    text = meta,
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (hapticsEnabled) Haptics.action(context)
                        onOpenMenu()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = l10n("Action menu", "Menu de ações"),
                        tint = scheme.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(item: NuvioVideoTitle, onClick: () -> Unit, onOpenMenu: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val art = rememberRemoteBitmap(item.poster ?: item.posterFallback)
    val meta = listOfNotNull(
        if (item.contentType.equals("series", ignoreCase = true)) {
            l10n(lang, "Series", "Série")
        } else {
            l10n(lang, "Movie", "Filme")
        },
        item.year?.takeIf { it.isNotBlank() },
        item.section.takeIf { it.isNotBlank() },
    ).joinToString(" • ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.contentCellSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                NuvioIcon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = scheme.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = item.name,
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NuvioText(
                text = meta,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.size(48.dp).clickable(onClick = onOpenMenu),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = l10n("Actions", "Ações"),
                tint = scheme.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun CatalogFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val bg = if (selected) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, CircleShape)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(CircleShape)
                .then(bg)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun CatalogSquareIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NuvioIcon(
            imageVector = icon,
            contentDescription = description,
            tint = scheme.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun CatalogInfoBox(text: String) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        NuvioText(text = text, style = NuvioTypography.KeyShortcutBadge, color = scheme.textSecondary)
    }
}
