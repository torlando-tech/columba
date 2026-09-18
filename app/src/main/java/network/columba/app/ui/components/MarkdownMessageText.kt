package network.columba.app.ui.components

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.markdownAnimations
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import network.columba.app.R
import network.columba.app.ui.screens.toSafeBrowsableUri
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

private const val TAG = "MarkdownMessageText"

/**
 * Safely renders authenticated LXMF Markdown content inside a message bubble.
 *
 * Remote images are intentionally disabled. Link handling is restricted to the
 * same web and Reticulum schemes supported by plaintext message links.
 */
@Composable
fun MarkdownMessageText(
    markdown: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    fontScale: Float = 1.0f,
) {
    val context = LocalContext.current
    val textColor =
        if (isFromMe) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val safeUriHandler = remember(context) { SafeMarkdownUriHandler(context) }
    val remoteImagePlaceholder = stringResource(R.string.markdown_remote_image_not_loaded)
    val components =
        remember(remoteImagePlaceholder, textColor, bodyStyle, fontScale) {
            markdownComponents(
                codeFence = { model ->
                    SafeMarkdownCodeFence(
                        content = model.content,
                        node = model.node,
                        style = model.typography.code,
                        darkMode = textColor.luminance() > 0.5f,
                    )
                },
                image = {
                    Text(
                        text = remoteImagePlaceholder,
                        color = textColor,
                        style = bodyStyle.scaled(fontScale),
                    )
                },
                inlineImage = {
                    Text(
                        text = remoteImagePlaceholder,
                        color = textColor,
                        style = bodyStyle.scaled(fontScale),
                    )
                },
            )
        }

    CompositionLocalProvider(LocalUriHandler provides safeUriHandler) {
        Markdown(
            content = markdown,
            colors =
                markdownColor(
                    text = textColor,
                    codeBackground = textColor.copy(alpha = 0.1f),
                    inlineCodeBackground = textColor.copy(alpha = 0.1f),
                ),
            typography =
                markdownTypography(
                    h1 = MaterialTheme.typography.headlineSmall.scaled(fontScale),
                    h2 = MaterialTheme.typography.titleLarge.scaled(fontScale),
                    h3 = MaterialTheme.typography.titleMedium.scaled(fontScale),
                    h4 = MaterialTheme.typography.titleSmall.scaled(fontScale),
                    h5 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold).scaled(fontScale),
                    h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold).scaled(fontScale),
                    text = bodyStyle.scaled(fontScale),
                    paragraph = bodyStyle.scaled(fontScale),
                    ordered = bodyStyle.scaled(fontScale),
                    bullet = bodyStyle.scaled(fontScale),
                    list = bodyStyle.scaled(fontScale),
                    quote = MaterialTheme.typography.bodyMedium.scaled(fontScale),
                    code = MaterialTheme.typography.bodyMedium.scaled(fontScale),
                    inlineCode = MaterialTheme.typography.bodyMedium.scaled(fontScale),
                    textLink =
                        TextLinkStyles(
                            style =
                                bodyStyle
                                    .copy(
                                        color = linkColor,
                                        fontWeight = FontWeight.Bold,
                                        textDecoration = TextDecoration.Underline,
                                    ).scaled(fontScale)
                                    .toSpanStyle(),
                        ),
                ),
            imageTransformer = BlockedImageTransformer,
            components = components,
            animations = markdownAnimations(animateTextSize = { this }),
            error = { errorModifier ->
                Text(
                    text = markdown,
                    color = textColor,
                    style = bodyStyle.scaled(fontScale),
                    modifier = errorModifier,
                )
            },
            modifier =
                modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .testTag("markdown-message-content"),
        )
    }
}

@Composable
private fun SafeMarkdownCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle,
    darkMode: Boolean,
) {
    val language =
        remember(content, node) {
            node
                .findChildOfType(MarkdownTokenTypes.FENCE_LANG)
                ?.getTextInNode(content)
                ?.toString()
                ?.trim()
        }
    val supportedLanguage = remember(language) { language?.let(SyntaxLanguage::getByName) }
    if (supportedLanguage == null) {
        MarkdownCodeFence(content = content, node = node, style = style)
        return
    }

    val highlightsBuilder =
        remember(darkMode) {
            Highlights.Builder().theme(SyntaxThemes.default(darkMode = darkMode))
        }
    MarkdownHighlightedCodeFence(
        content = content,
        node = node,
        style = style,
        highlightsBuilder = highlightsBuilder,
    )
}

private fun androidx.compose.ui.text.TextStyle.scaled(scale: Float) = copy(fontSize = fontSize * scale)

private object BlockedImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ) = PlaceholderConfig(Size(200f, 24f))
}

private class SafeMarkdownUriHandler(
    private val context: Context,
) : UriHandler {
    override fun openUri(uri: String) {
        val safeUri = toSafeBrowsableUri(uri) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, safeUri))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open Markdown link: $safeUri", e)
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
