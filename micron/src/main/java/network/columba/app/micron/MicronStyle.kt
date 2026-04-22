package network.columba.app.micron

data class MicronStyle(
    val foreground: MicronColor = MicronColor.Default,
    val background: MicronColor = MicronColor.Default,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

enum class MicronAlignment {
    LEFT,
    CENTER,
    RIGHT,
}
