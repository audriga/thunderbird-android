package com.fsck.k9.ui.sml

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fsck.k9.ui.R
import org.json.JSONArray

private val DEFAULT_ICON_SIZE = 48.dp
private const val ICON_MAPPING_ASSET = "osm.json"
private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/**
 * Maps schema.org types to Material Icons font codepoints, based on the shared osm.json.
 */
internal object SchemaIconCodepoints {
    @Volatile
    private var codepoints: Map<String, Int>? = null

    fun forType(context: Context, schemaType: String): Int? {
        return load(context)[schemaType.toSnakeCaseTypeName()]
    }

    private fun load(context: Context): Map<String, Int> {
        codepoints?.let { return it }
        return synchronized(this) {
            codepoints ?: parse(context).also { codepoints = it }
        }
    }

    private fun parse(context: Context): Map<String, Int> {
        val json = context.applicationContext.assets.open(ICON_MAPPING_ASSET).bufferedReader().use { it.readText() }
        val entries = JSONArray(json)
        val result = HashMap<String, Int>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val type = entry.optString("schemaorg", "")
            val codepoint = entry.optString("material_icon", "")
                .removePrefix("0x")
                .toIntOrNull(radix = 16)
            if (type.isNotEmpty() && codepoint != null) {
                result[type] = codepoint
            }
        }
        return result
    }

    private fun String.toSnakeCaseTypeName(): String {
        return substringAfterLast('/')
            .substringAfterLast(':')
            .replace(CAMEL_CASE_BOUNDARY, "_")
            .lowercase()
    }
}

private val MaterialIconsFontFamily = FontFamily(Font(R.font.material_icons))

/**
 * Draws a single Material Icons font glyph, scaled to fill and centered in the draw area.
 */
private class GlyphPainter(
    private val glyph: String,
    private val textMeasurer: TextMeasurer,
    override val intrinsicSize: Size,
) : Painter() {
    override fun DrawScope.onDraw() {
        val style = TextStyle(
            color = Color.Black,
            fontFamily = MaterialIconsFontFamily,
            fontSize = size.minDimension.toSp(),
        )
        val layout = textMeasurer.measure(glyph, style)
        val topLeft = Offset(
            x = (size.width - layout.size.width) / 2f,
            y = (size.height - layout.size.height) / 2f,
        )
        drawText(layout, topLeft = topLeft)
    }
}

/**
 * Scales [delegate] to [intrinsicSize], so that vector icons (24dp by default) match the glyph icons in size.
 */
private class SizedPainter(
    private val delegate: Painter,
    override val intrinsicSize: Size,
) : Painter() {
    override fun DrawScope.onDraw() {
        with(delegate) { draw(size) }
    }
}

/**
 * Returns a painter for the given schema.org type, using the osm.json mapping and falling back to [getIconForType].
 *
 * [size] is the intrinsic size of the painter; for map markers it determines the rendered image size.
 * The result is remembered per codepoint, so recompositions don't create (and re-register) new images.
 */
@Composable
internal fun rememberSchemaIconPainter(schemaType: String, size: Dp = DEFAULT_ICON_SIZE): Painter {
    val context = LocalContext.current
    val codepoint = remember(schemaType) { SchemaIconCodepoints.forType(context, schemaType) }
    val iconSizePx = with(LocalDensity.current) { size.toPx() }
    val iconSize = Size(iconSizePx, iconSizePx)

    if (codepoint == null) {
        val vectorPainter = rememberVectorPainter(getIconForType(schemaType))
        return remember(vectorPainter, iconSizePx) { SizedPainter(vectorPainter, iconSize) }
    }

    val textMeasurer = rememberTextMeasurer()
    return remember(codepoint, textMeasurer, iconSizePx) {
        GlyphPainter(
            glyph = String(Character.toChars(codepoint)),
            textMeasurer = textMeasurer,
            intrinsicSize = iconSize,
        )
    }
}
