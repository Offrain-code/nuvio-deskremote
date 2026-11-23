package com.nuvio.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioMusicAlbum
import com.nuvio.remote.connection.NuvioMusicArtist
import com.nuvio.remote.connection.NuvioMusicLibrary
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.rememberRemoteBitmap
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen

/**
 * Music sub-tab inside Apps (DESIGN-SPEC.md section 5.5.2).
 * Saved albums (opaque 2-column grid) and Artists (quick Mix & Radio actions).
 */
@Composable
fun AppsMusicTab(
    bridgeConnected: Boolean,
    musicLibrary: NuvioMusicLibrary,
    onOpenAlbum: (NuvioMusicAlbum) -> Unit,
    onArtistAction: (artistId: String, action: String) -> Unit,
) {
    val scheme = LocalNuvioColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!bridgeConnected) {
            item {
                CatalogInfoBox(
                    text = l10n(
                        "Link Direct Nuvio Control in Settings to access your saved albums and artists.",
                        "Vincule o Controle Direto do Nuvio em Ajustes para acessar seus álbuns e artistas salvos.",
                    ),
                )
            }
            return@LazyColumn
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n("SAVED ALBUMS", "ÁLBUNS SALVOS"),
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
                        text = "${musicLibrary.albums.size} ${l10n("albums", "álbuns")}",
                        style = NuvioTypography.KeyShortcutBadge,
                        color = accentForScreen(NuvioScreenState.PLAYER).primary,
                    )
                }
            }
        }

        if (musicLibrary.albums.isEmpty()) {
            item {
                CatalogInfoBox(
                    text = if (!musicLibrary.ready) {
                        l10n("Loading Nuvio music library…", "Carregando biblioteca de música do Nuvio…")
                    } else {
                        l10n(
                            "No saved albums in your music library.",
                            "Nenhum álbum salvo na biblioteca de música.",
                        )
                    },
                )
            }
        } else {
            val rows = musicLibrary.albums.chunked(2)
            items(rows.size) { idx ->
                val pair = rows[idx]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { album ->
                        MusicAlbumCard(
                            album = album,
                            onClick = { onOpenAlbum(album) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (musicLibrary.artists.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioText(
                        text = l10n("ARTISTS • MIX & RADIO", "ARTISTAS • MIX E RÁDIO"),
                        style = NuvioTypography.SectionMicroTitle,
                        color = scheme.textMuted,
                    )
                    NuvioText(
                        text = l10n("Quick actions", "Ações rápidas"),
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textMuted,
                    )
                }
            }
            items(musicLibrary.artists, key = { it.id }) { artist ->
                MusicArtistRow(
                    artist = artist,
                    onMix = { onArtistAction(artist.id, "mix") },
                    onRadio = { onArtistAction(artist.id, "radio") },
                )
            }
        }
    }
}

@Composable
private fun MusicAlbumCard(album: NuvioMusicAlbum, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val art = rememberRemoteBitmap(album.cover)
    val meta = listOfNotNull(album.kind, album.year).joinToString(" • ")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(18.dp))
            .clickable {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.contentCellSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                NuvioIcon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (album.starred) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = l10n("Favorite", "Favorito"),
                        tint = scheme.statusError,
                        modifier = Modifier.size(11.dp),
                    )
                    NuvioText(
                        text = l10n("Favorite", "Favorito"),
                        style = NuvioTypography.KeyShortcutBadge,
                        color = Color.White,
                    )
                }
            }
        }
        NuvioText(
            text = album.title,
            style = NuvioTypography.KeyLabelCompact,
            color = scheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            NuvioText(
                text = meta,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MusicArtistRow(artist: NuvioMusicArtist, onMix: () -> Unit, onRadio: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val art = rememberRemoteBitmap(artist.photo)
    val initials = artist.name
        .split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "♪" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentForScreen(NuvioScreenState.PLAYER).surfaceTint),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                NuvioText(
                    text = initials,
                    style = NuvioTypography.KeyLabelCompact,
                    color = accentForScreen(NuvioScreenState.PLAYER).primary,
                )
            }
        }
        NuvioText(
            text = artist.name,
            style = NuvioTypography.KeyLabelPrimary,
            color = scheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ArtistActionPill(label = "Mix", icon = Icons.Rounded.Shuffle, onClick = onMix)
        ArtistActionPill(label = l10n("Radio", "Rádio"), icon = Icons.Rounded.Radio, onClick = onRadio)
    }
}

@Composable
private fun ArtistActionPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.textPrimary,
                modifier = Modifier.size(14.dp),
            )
            NuvioText(text = label, style = NuvioTypography.KeyShortcutBadge, color = scheme.textPrimary)
        }
    }
}
