package com.fsck.k9.ui.sml

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.mailstore.MessageViewInfo
import net.thunderbird.components.ui.bolt.resources.Res
import net.thunderbird.components.ui.bolt.resources.bolt_yatagarasu_logo
import net.thunderbird.components.ui.bolt.theme.BoltTheme.colors
import org.jetbrains.compose.resources.painterResource
import org.json.JSONObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SMLCards(
    messageViewInfo: MessageViewInfo,
    messageReference: MessageReference?,
    context: Context,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier,
    onReload: ((JSONObject) -> Unit)? = null,
) {
    val structuredDataList = messageViewInfo.messageData?.structuredData.orEmpty()
    val extracted = messageViewInfo.messageData?.extracted
    val itiExtractedList = messageViewInfo.messageData?.itiExtracted.orEmpty()
    // jsonld intentionally included by the sender vs jsonld derived (extracted) from non-json email text/html
    // might at some point be handled differently (e.g. user setting to only show cards for jsonld intentinally included by the sender)
    // Note that itiExtracted also includes jsonLd deliberately put into the mail if said jsonLd was a reservation of some kind.
    // For this reason duplicates are removed.
    val jsonLds: List<JSONObject> = remember {
        listOfNotNull(*structuredDataList.toTypedArray(), extracted, *itiExtractedList.toTypedArray()).distinct()
    }
    if (jsonLds.isNotEmpty()) {
        Column {
            BoxWithConstraints {
                HorizontalMultiBrowseCarousel(
                    state = rememberCarouselState { jsonLds.count() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(top = 16.dp, bottom = 16.dp),
                    preferredItemWidth = maxHeight.times(0.8f),
                    itemSpacing = 8.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) { i: Int ->
                    SMLCard(
                        initialJsonObject = jsonLds[i],
                        clipboardManager = clipboardManager,
                        messageReference = messageReference,
                        context = context,
                        modifier = modifier,
                        onReload = onReload,
                    )
                }
            }


            val a = androidx.compose.material.icons.Icons.Filled.RestaurantMenu
            val source = GeoJsonSource(
                id = "marker",
                data = GeoJsonData.Features(Feature(geometry = Point(Position(8.24, 50.07)), properties = null))
            )

                val baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
                val mapState = rememberMapState(
                    baseStyle = baseStyle,
                    initialCameraPosition = CameraPosition(center = Position(50.07, 8.24)),
                ) {
//                    SymbolLayer(id = "station-markers", source = baseStyle., iconImage = image("marker"))
                    SymbolLayer(
                        id = "marker",
                        source = source,
                        iconImage = image(rememberVectorPainter(a))
//                        iconImage = image(rememberMarkerPainter())
                    )
                }
                BoxWithConstraints {
                    MaplibreMap(
                        modifier = Modifier.heightIn(minHeight, maxWidth), // Make it square for now
                        state = mapState,
                    ) {
                        include(MapOverlay.Default)
                        // Can render (simple?) composables on a given map position
                    }
                }

        }
    }
}

fun cardsExist(
    messageViewInfo: MessageViewInfo,
): Boolean {
    val structuredDataList = messageViewInfo.messageData?.structuredData.orEmpty()
    val extracted = messageViewInfo.messageData?.extracted
    val itiExtractedList = messageViewInfo.messageData?.itiExtracted.orEmpty()
    return  structuredDataList.isNotEmpty() || extracted != null || itiExtractedList.isNotEmpty()
}



@Composable
internal fun rememberMarkerPainter(): Painter {
    val silhouette = painterResource(Res.drawable.bolt_yatagarasu_logo)
    val outline = MaterialTheme.colorScheme.surface
    val primary = colors.primary
    val onPrimary = colors.onPrimary
    return remember(silhouette, primary, onPrimary, outline) {
        object : Painter() {
            override val intrinsicSize = Size(40f, 48f)

            override fun DrawScope.onDraw() {
                with(silhouette) { draw(size, colorFilter = null) }
                // Scale the canvas so both draws request the same vector size. With inset, VectorPainter
                // rewrites its cached bitmap for the smaller draw before Android's recording canvas plays
                // back the outline draw, corrupting the outline. Scaling avoids that cache mutation.
                scale(scaleX = 0.89f, scaleY = 0.91f) {
                    with(silhouette) { draw(size, colorFilter = null) }
                }
                val center = Offset(size.width / 2f, size.height * 0.4f)
                drawCircle(onPrimary, size.width * 0.21f, center)
                drawCircle(primary, size.width * 0.085f, center)
            }
        }
    }
}
