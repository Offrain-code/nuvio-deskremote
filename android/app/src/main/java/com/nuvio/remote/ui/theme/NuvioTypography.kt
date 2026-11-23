package com.nuvio.remote.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Unified geometric SansSerif typography hierarchy designed for AA/AAA legibility.
 */
object NuvioTypography {
    private val uiFamily = FontFamily.SansSerif

    val ScreenTitle = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.25).sp,
    )

    val DisplayKey = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    )

    val HudModeActive = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )

    val SectionMicroTitle = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
    )

    val KeyLabelPrimary = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )

    val KeyLabelCompact = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    )

    val KeyShortcutBadge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp,
    )

    val DockTabLabel = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )

    val BodyRegular = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}
