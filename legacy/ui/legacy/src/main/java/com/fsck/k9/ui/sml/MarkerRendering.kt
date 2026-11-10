package com.fsck.k9.ui.sml

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Size of the icons drawn on the map. */
private val MARKER_ICON_SIZE = 24.dp

@Composable
internal fun MarkerLayers(
    marker: MapMarker,
    onSelect: () -> Unit,
) {
    val id = remember { "marker-${UUID.randomUUID()}" }
    val data = remember(marker.latitude, marker.longitude) {
        GeoJsonData.Features(Feature(geometry = Point(Position(marker.longitude, marker.latitude)), properties = null))
    }
    val source = rememberGeoJsonSource(data = data)
    SymbolLayer(
        id = id,
        source = source,
        iconImage = image(rememberSchemaIconPainter(marker.schemaType, MARKER_ICON_SIZE)),
        onClick = {
            onSelect()
            ClickResult.Consume
        }

//                        iconImage = image(rememberMarkerPainter())
    )
}

internal class MapMarker(val schemaType: String, val latitude: Double, val longitude: Double)
