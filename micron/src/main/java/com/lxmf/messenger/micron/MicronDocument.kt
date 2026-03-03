package com.lxmf.messenger.micron

data class MicronDocument(
    val lines: List<MicronLine>,
    val pageBackground: MicronColor? = null,
    val pageForeground: MicronColor? = null,
    val cacheTime: Int? = null,
)

data class MicronLine(
    val elements: List<MicronElement>,
    val alignment: MicronAlignment = MicronAlignment.LEFT,
    val indentLevel: Int = 0,
    val isHeading: Boolean = false,
    val headingLevel: Int = 0,
)
