package network.columba.app.micron

sealed class MicronElement {
    data class Text(
        val content: String,
        val style: MicronStyle,
    ) : MicronElement()

    data class Link(
        val label: String,
        val destination: String,
        val fieldNames: List<String>,
        val style: MicronStyle,
    ) : MicronElement()

    data class Divider(
        val character: Char,
    ) : MicronElement()

    data class Field(
        val name: String,
        val defaultValue: String,
        val width: Int,
        val masked: Boolean,
        val style: MicronStyle,
    ) : MicronElement()

    data class Checkbox(
        val name: String,
        val value: String,
        val label: String,
        val prechecked: Boolean,
        val style: MicronStyle,
    ) : MicronElement()

    data class Radio(
        val name: String,
        val value: String,
        val label: String,
        val prechecked: Boolean,
        val style: MicronStyle,
    ) : MicronElement()

    data class Partial(
        val url: String,
        val refreshInterval: Int?,
        val fieldNames: List<String>,
        val partialId: String?,
    ) : MicronElement()

    /**
     * Inline image (upstream NomadNet `c0091db`): `` `(alt`w=<spec>`h=<spec>`a=<l|c|r>`:url) ``.
     * Block-level — always its own line. Size specs stay raw strings
     * (`"40"` cells, `"50%"` percent, `"n"` native) so layout policy lives
     * in the renderer, not the parser. [alt] doubles as the placeholder text
     * for renderers that can't (or haven't yet) fetched the image.
     */
    data class Image(
        val alt: String,
        val url: String,
        val width: String?,
        val height: String?,
        val align: String?,
    ) : MicronElement()

    data object LineBreak : MicronElement()
}
