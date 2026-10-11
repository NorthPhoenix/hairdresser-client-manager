package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * The part of its font size a line of text keeps so as to be no wider than [available]: all of it
 * when the line fits, and never less than [least] of it. A line that does not fit loses 2% at the
 * least: glyphs are whole pixels wide, and a smaller step can leave the line as wide as it was.
 */
internal fun oneLineScale(width: Int, available: Float, least: Float): Float =
    if (width <= available) 1f else minOf(available / width, 0.98f).coerceAtLeast(least.coerceAtMost(1f))

/**
 * [style] in the largest font size, up to its own, in which every one of [texts] takes one line of
 * [width]. For short labels that stand side by side in equal places (the tabs, the weekdays of the
 * week strip): at a large font size they get smaller together, where each would otherwise break
 * inside the word or be cut in its own way. They do not get smaller than [smallest]: show them with
 * `maxLines = 1`, `softWrap = false` and an ellipsis for a text that does not fit even then.
 */
@Composable
fun fittedTextStyle(texts: List<String>, style: TextStyle, width: Dp, smallest: Dp = 8.dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    return remember(texts, style, width, smallest, density, measurer) {
        with(density) { measurer.fit(texts, style, width.toPx(), smallest.toSp()) }
    }
}

private fun TextMeasurer.fit(texts: List<String>, style: TextStyle, available: Float, smallest: TextUnit): TextStyle {
    if (!style.fontSize.isSp) return style
    var fitted = style

    // Measured again after every step: a font size does not make a line narrower by exactly its own share.
    repeat(8) {
        val widest = texts.maxOfOrNull { measure(it, fitted, softWrap = false, maxLines = 1).size.width } ?: 0
        val scale = oneLineScale(widest, available, least = smallest.value / fitted.fontSize.value)

        if (scale == 1f) return fitted
        fitted = fitted.copy(
            fontSize = fitted.fontSize * scale,
            lineHeight = if (fitted.lineHeight.isSpecified) fitted.lineHeight * scale else fitted.lineHeight,
        )
    }
    return fitted
}
