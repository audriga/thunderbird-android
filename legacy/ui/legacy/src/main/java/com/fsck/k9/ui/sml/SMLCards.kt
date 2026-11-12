package com.fsck.k9.ui.sml

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.sml.SMLUtil.findAllLocations
import kotlinx.coroutines.launch
import net.thunderbird.components.ui.bolt.resources.Res
import net.thunderbird.components.ui.bolt.resources.bolt_yatagarasu_logo
import net.thunderbird.components.ui.bolt.theme.BoltTheme.colors
import org.jetbrains.compose.resources.painterResource
import org.json.JSONObject
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SMLCards(
    messageViewInfo: MessageViewInfo,
    messageReference: MessageReference?,
    context: Context,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
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
    SMLCards(
        jsonLds = jsonLds,
        interactive = interactive,
        modifier = modifier,
        clipboardManager = clipboardManager,
        messageReference = messageReference,
        context = context,
        onReload = onReload
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SMLCards(
    jsonLds: List<JSONObject>,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    clipboardManager: ClipboardManager?,
    messageReference: MessageReference?,
    context: Context?,
    onReload: ((JSONObject) -> Unit)?,
) {
    if (jsonLds.isNotEmpty()) {
        val carouselState = rememberCarouselState { jsonLds.count() }
        val animationScope = rememberCoroutineScope()
        Column {
            BoxWithConstraints {
                HorizontalMultiBrowseCarousel(
                    state = carouselState,
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
                        interactive = interactive,
                        onReload = onReload,
                    )
                }
            }

            if (interactive) {
                val markersPerMarkup: Array<MutableList<MapMarker>> = Array(jsonLds.size) { mutableListOf() }
                // BoundingBox is bound by northeastern corner and southwestern corner
                // Bigger number in latitude is more north
                // Bigger number in longitude is more east
                // northeast is max,max southwest is min,min
                var northLat = 0.0
                var eastLong = 0.0
                var southLat = 0.0
                var westLong = 0.0
                var hasGeosVar = false
                for ((i, jsonObject) in jsonLds.withIndex()) {
                    findAllLocations(jsonObject) { latitude, longitude ->
                        if (hasGeosVar) {
                            // calculate new BoundingBox coordinates
                            northLat = maxOf(northLat, latitude)
                            eastLong = maxOf(eastLong, longitude)
                            southLat = minOf(southLat, latitude)
                            westLong = minOf(westLong, longitude)
                        } else {
                            // reached first geo, initialize BoundingBox coordinates with this one
                            northLat = latitude
                            eastLong = longitude
                            southLat = latitude
                            westLong = longitude
                        }
                        hasGeosVar = true
                        val schemaType = jsonObject.optString("@type", "")
                        markersPerMarkup[i].add(
                            MapMarker(
                                schemaType = schemaType,
                                latitude = latitude,
                                longitude = longitude,
                            ),
                        )
                    }
                }
                val hasGeos = hasGeosVar

                if (hasGeos) {
                    var showMap: Boolean by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            showMap = !showMap
                        },
                        modifier = modifier.padding(16.dp),
                    ) {
                        Text(if (!showMap) "Show Map" else "Hide Map")
                    }
                    if (showMap) {
                        val boundingBox = BoundingBox(
                            west = westLong,
                            south = southLat,
                            east = eastLong,
                            north = northLat,
                        )
                        val midLong = (westLong + eastLong) / 2.0
                        val midLat = (southLat + northLat) / 2.0
                        val baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
                        val mapState = rememberMapState(
                            baseStyle = baseStyle,
                            initialCameraPosition = CameraPosition(center = Position(midLong, midLat)),
                        ) {
                            for ((i, markers) in markersPerMarkup.withIndex()) {
                                val highlight = carouselState.currentItem == i
                                for (marker in markers) {
                                    MarkerLayers(marker, highlight) {
                                        animationScope.launch {
                                            carouselState.animateScrollToItem(i)
                                        }
                                    }
                                }
                            }
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
                        LaunchedEffect(mapState) {
                            mapState.animateCameraToBounds(
                                boundingBox = boundingBox,
                                fit = CameraFit(
                                    fitPadding = DpPadding(
                                        left = 32.dp,
                                        top = 32.dp,
                                        right = 32.dp,
                                        bottom = 32.dp,
                                    ),
                                ),
                            )
                        }
                    }
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
