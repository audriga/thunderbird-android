package com.fsck.k9.ui.sml

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import java.util.UUID
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@Composable
internal fun MarkerLayers(
    marker: MapMarker,
    onSelect: () -> Unit,
) {
    val uuid = UUID.randomUUID().toString()
    val id = "marker-$uuid"
    val source = GeoJsonSource(
        id = id,
        data = GeoJsonData.Features(Feature(geometry = Point(Position(marker.longitude, marker.latitude)), properties = null))
    )
    SymbolLayer(
        id = id,
        source = source,
        iconImage = image(rememberVectorPainter(marker.icon)),
        onClick = {
            onSelect()
            ClickResult.Consume
        }

//                        iconImage = image(rememberMarkerPainter())
    )
}

internal class MapMarker(val icon: ImageVector, val latitude: Double, val longitude: Double)
