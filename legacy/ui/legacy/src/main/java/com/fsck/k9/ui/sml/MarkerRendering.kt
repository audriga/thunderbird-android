package com.fsck.k9.ui.sml

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.time.Duration
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Size of the icons drawn on the map. */
private val MARKER_ICON_SIZE = 24.dp

@Composable
internal fun MarkerLayers(
    marker: MapMarker,
    highlight: Boolean,
    onSelect: () -> Unit,
) {
    val id = remember { UUID.randomUUID() }
    val data = remember(marker.latitude, marker.longitude) {
        GeoJsonData.Features(Feature(geometry = Point(Position(marker.longitude, marker.latitude)), properties = null))
    }
    val source = rememberGeoJsonSource(data = data)

    val instant = TransitionOptions(Duration.ZERO)
    // TODO themed colors
    val primaryColor = Color.Red
    if (highlight) {
        CircleLayer(
            id = "marker-halo-${id}",
            source = source,
            radius = const(10f.dp),
            color = const(primaryColor),
            opacity = const(0.12f),
            strokeColor = const(primaryColor),
            strokeWidth = const(1.5.dp),
            strokeOpacity = const(0.65f),
            radiusTransition = instant,
            opacityTransition = instant,
            strokeOpacityTransition = instant,
        )
    }

    SymbolLayer(
        id = "marker-$id",
        source = source,
        iconImage = image(rememberSchemaIconPainter(marker.schemaType, MARKER_ICON_SIZE)),
//        iconAllowOverlap = const(true),
        onClick = {
            onSelect()
            ClickResult.Consume
        }

//                        iconImage = image(rememberMarkerPainter())
    )
}

internal class MapMarker(val schemaType: String, val latitude: Double, val longitude: Double)
