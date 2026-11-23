package com.nuvio.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioLiveTvItem
import com.nuvio.remote.connection.NuvioLiveTvScreen
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioTonightCard
import com.nuvio.remote.connection.NuvioTonightScreen
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.rememberRemoteBitmap
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * Live TV mirror floating Liquid Glass sheet (DESIGN-SPEC.md section 5.2.1 item 7).
 * Glass on outer container only; chips, channel list, and inner cards remain 100% opaque.
 */
@Composable
fun LiveTvOverlaySheet(
    screen: NuvioLiveTvScreen?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onLiveTvDo: (List<String>) -> Unit,
    onKey: (String) -> Unit,
) {
    BackHandler(enabled = visible && screen != null) { onDismiss() }
    Phase3SheetFrame(visible = visible && screen != null, onDismiss = onDismiss) {
        val current = screen ?: return@Phase3SheetFrame
        Phase3SheetHeader(
            title = l10n("LIVE TV MIRROR", "ESPELHO DO LIVE TV (LIVETV)"),
            subtitle = listOfNotNull(current.heading, current.sub).joinToString(" • "),
            onClose = onDismiss,
        )

        if (current.stages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current.stages.forEach { st ->
                    Phase3Chip(
                        label = st.label,
                        selected = st.name.equals(current.stage, ignoreCase = true),
                        onClick = { onLiveTvDo(listOf("stage", st.name)) },
                    )
                }
            }
        }

        if (current.items.isEmpty()) {
            Phase3EmptyBox(
                text = current.empty ?: l10n(
                    "No channels or programs listed in this view.",
                    "Nenhum canal ou programa listado neste estágio.",
                ),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    current.items,
                    key = { idx, item -> "${item.group}_${item.index}_$idx" },
                ) { _, item ->
                    LiveTvItemRow(
                        item = item,
                        onClick = { onLiveTvDo(listOf("item", item.group, item.index.toString())) },
                    )
                }
            }
        }

        LiveTvFooterRow(screen = current, onLiveTvDo = onLiveTvDo, onKey = onKey)
    }
}

@Composable
private fun LiveTvItemRow(item: NuvioLiveTvItem, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(
                1.dp,
                if (item.live) playerAccent.primary.copy(alpha = 0.45f) else scheme.contentBorder,
                RoundedCornerShape(14.dp),
            )
            .clickable {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = item.title,
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.live) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(scheme.statusError.copy(alpha = 0.16f))
                        .border(1.dp, scheme.statusError.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    NuvioText(
                        text = l10n("● LIVE", "● AO VIVO"),
                        style = NuvioTypography.KeyShortcutBadge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.statusError,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = item.sub ?: item.slot.ifBlank { l10n("Live channel", "Canal ao vivo") },
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.progress > 0) {
                NuvioText(
                    text = "${item.progress}%",
                    style = NuvioTypography.KeyShortcutBadge.copy(fontWeight = FontWeight.SemiBold),
                    color = playerAccent.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (item.progress > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(scheme.contentBorder),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.progress / 100f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (item.live) playerAccent.primary else scheme.statusOnline),
                )
            }
        }
    }
}

@Composable
private fun LiveTvFooterRow(screen: NuvioLiveTvScreen, onLiveTvDo: (List<String>) -> Unit, onKey: (String) -> Unit) {
    if (screen.buttons.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            screen.buttons.take(3).forEach { btn ->
                Phase3ActionPill(
                    label = btn.label,
                    primary = btn.accent,
                    onClick = { onLiveTvDo(listOf("item", btn.group, btn.index.toString())) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Phase3ActionPill(
                label = l10n("CH −", "Canal −"),
                primary = false,
                onClick = { onKey("PageDown") },
                modifier = Modifier.weight(1f),
            )
            Phase3ActionPill(
                label = l10n("Guide / EPG", "Guia / EPG"),
                primary = true,
                onClick = { onLiveTvDo(listOf("all")) },
                modifier = Modifier.weight(1.2f),
            )
            Phase3ActionPill(
                label = l10n("CH +", "Canal +"),
                primary = false,
                onClick = { onKey("PageUp") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Floating Liquid Glass sheet for "What to Watch Tonight" (`TONIGHT` — DESIGN-SPEC.md section 5.2.1 item 8).
 */
@Composable
fun TonightOverlaySheet(
    screen: NuvioTonightScreen?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onTonightDo: (action: String, number: Int?) -> Unit,
) {
    BackHandler(enabled = visible && screen != null) { onDismiss() }
    Phase3SheetFrame(visible = visible && screen != null, onDismiss = onDismiss) {
        val current = screen ?: return@Phase3SheetFrame
        val subtitle = when {
            current.total > 0 -> l10n(
                "Question ${current.number.coerceAtLeast(1)} of ${current.total}",
                "Pergunta ${current.number.coerceAtLeast(1)} de ${current.total}",
            )
            current.loading -> l10n("Computing recommendations in Nuvio…", "Calculando recomendações no Nuvio…")
            else -> l10n("Interactive curation on TV", "Curadoria interativa na TV")
        }
        Phase3SheetHeader(
            title = l10n("WHAT TO WATCH TONIGHT", "O QUE ASSISTIR HOJE (TONIGHT)"),
            subtitle = subtitle,
            onClose = onDismiss,
        )

        if (current.total > 0) {
            TonightProgressBar(current = current.number.coerceAtLeast(1), total = current.total)
        }

        if (!current.prompt.isNullOrBlank()) {
            NuvioText(
                text = current.prompt,
                style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                color = LocalNuvioColors.current.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TonightChoicesSection(screen = current, onTonightDo = onTonightDo)
        TonightCardsSection(cards = current.cards, onPick = { idx -> onTonightDo("pick", idx) })
        TonightFooterRow(screen = current, onTonightDo = onTonightDo)
    }
}

@Composable
private fun TonightProgressBar(current: Int, total: Int) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (step in 1..total.coerceAtMost(8)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (step <= current) playerAccent.primary else scheme.contentBorder),
            )
        }
    }
}

@Composable
private fun TonightChoicesSection(screen: NuvioTonightScreen, onTonightDo: (action: String, number: Int?) -> Unit) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)

    if (screen.answers.isNotEmpty()) {
        val rows = screen.answers.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rIdx, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEachIndexed { cIdx, answer ->
                        val absIndex = rIdx * 2 + cIdx
                        val isSelected = screen.selected == absIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) playerAccent.surfaceTint else scheme.contentCellSurface)
                                .border(
                                    1.dp,
                                    if (isSelected) playerAccent.primary else scheme.contentBorder,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { onTonightDo("answer", absIndex) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            NuvioText(
                                text = answer,
                                style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) playerAccent.primary else scheme.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (pair.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    } else if (!screen.leftLabel.isNullOrBlank() || !screen.rightLabel.isNullOrBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            screen.leftLabel?.let { lbl ->
                Phase3ActionPill(
                    label = lbl,
                    primary = false,
                    onClick = { onTonightDo("left", null) },
                    modifier = Modifier.weight(1f),
                )
            }
            screen.rightLabel?.let { lbl ->
                Phase3ActionPill(
                    label = lbl,
                    primary = true,
                    onClick = { onTonightDo("right", null) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TonightCardsSection(cards: List<NuvioTonightCard>, onPick: (Int) -> Unit) {
    if (cards.isEmpty()) return
    val scheme = LocalNuvioColors.current
    NuvioText(
        text = l10n("PICKS FOR TONIGHT", "SUGESTÕES PARA HOJE"),
        style = NuvioTypography.SectionMicroTitle,
        color = scheme.textMuted,
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(cards, key = { idx, c -> "${c.slot}_${c.name}_$idx" }) { idx, card ->
            TonightCardRow(card = card, onClick = { onPick(idx) })
        }
    }
}

@Composable
private fun TonightCardRow(card: NuvioTonightCard, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val poster = rememberRemoteBitmap(card.poster)
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(69.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.contentCardSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (poster != null) {
                Image(
                    bitmap = poster.asImageBitmap(),
                    contentDescription = card.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NuvioIcon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = playerAccent.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NuvioText(
                text = card.name,
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.meta?.let { meta ->
                NuvioText(
                    text = meta,
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.why?.let { why ->
                NuvioText(
                    text = "${l10n("Why", "Por quê")}: $why",
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TonightFooterRow(screen: NuvioTonightScreen, onTonightDo: (action: String, number: Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (screen.reshuffleLeft) {
            Phase3ActionPill(
                label = l10n("Shuffle", "Embaralhar"),
                primary = false,
                onClick = { onTonightDo("reshuffle", null) },
                modifier = Modifier.weight(1f),
            )
        }
        Phase3ActionPill(
            label = if (screen.cards.isNotEmpty()) {
                l10n("Pick on TV", "Escolher na TV")
            } else {
                l10n("Continue on TV", "Avançar na TV")
            },
            primary = true,
            onClick = { onTonightDo(if (screen.cards.isNotEmpty()) "pick" else "confirm", 0) },
            modifier = Modifier.weight(1.3f),
        )
    }
}

@Composable
private fun Phase3SheetFrame(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .nuvioGlass(
                        shape = RoundedCornerShape(24.dp),
                        variant = GlassVariant.REGULAR,
                        elevated = true,
                        accentTint = playerAccent.surfaceTint,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun Phase3SheetHeader(title: String, subtitle: String, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = title,
                style = NuvioTypography.SectionMicroTitle,
                color = accentForScreen(NuvioScreenState.PLAYER).primary,
                maxLines = 1,
            )
            NuvioText(
                text = subtitle,
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
                    if (hapticsEnabled) Haptics.tab(context)
                    onClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close", "Fechar"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Phase3Chip(label: String, selected: Boolean, onClick: () -> Unit) {
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
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (selected) scheme.pillActiveFg else scheme.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun Phase3ActionPill(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val fg = if (primary) scheme.pillActiveFg else scheme.textPrimary
    val bg = if (primary) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(bg)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Phase3EmptyBox(text: String) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioIcon(
            imageVector = Icons.Rounded.LiveTv,
            contentDescription = null,
            tint = scheme.textMuted,
            modifier = Modifier.size(18.dp),
        )
        NuvioText(text = text, style = NuvioTypography.KeyShortcutBadge, color = scheme.textSecondary)
    }
}
