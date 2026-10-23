package com.fsck.k9.ui.sml

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.sml.SMLUtil
import com.skydoves.landscapist.coil3.CoilImage
import com.skydoves.landscapist.ImageOptions
import org.json.JSONObject

/**
 * Card component for displaying schema.org structured data (JSON-LD) in the message view.
 *
 * Renders a Material3 card with:
 * - Image or icon based on @type
 * - Title (headline for NewsArticle, otherwise name)
 * - Subtitle (description)
 * - Action buttons from potentialAction
 *
 * @param initialJsonObject The schema.org JSON-LD object to display
 * @param onReload Callback when reload action is triggered, receives the new JSONObject
 * @param modifier The modifier to apply to the card
 */
@Composable
fun SMLCard(
    initialJsonObject: JSONObject,
    clipboardManager: ClipboardManager,
    messageReference: MessageReference?,
    context: Context,
    modifier: Modifier = Modifier,
    onReload: ((JSONObject) -> Unit)? = null,
) {
    val jsonObjectState = remember { mutableStateOf(initialJsonObject) }
    val jsonObject = jsonObjectState.value

    val schemaType = jsonObject.optString("@type", "")
    val title = getTitleFromMarkup(jsonObject, schemaType)
    val description = jsonObject.optString("description", "")
    val imageUrl = getImageUrlFromMarkup(jsonObject)
    val actionButtons = ActionButtonUtil(
        onReload = { newJsonObject ->
                    jsonObjectState.value = newJsonObject
                    onReload?.invoke(newJsonObject)
                },
        clipboardManager =  clipboardManager,
        messageReference = messageReference,
        context = context,
        ).getButtons(jsonObject)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with image/icon and text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Image or Icon
                if (!imageUrl.isNullOrEmpty()) {
                    CoilImage(
                        imageModel = { imageUrl },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .padding(end = 12.dp),
                    )
                } else {
                    // Placeholder icon based on @type
                    Icon(
                        imageVector = getIconForType(schemaType),
                        contentDescription = schemaType,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(end = 12.dp),
                    )
                }

                // Title and description
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (description.isNotEmpty()) {
                        Text(
                            text = description,
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Action buttons
            ActionButtons(
                actionButtons = actionButtons,
                onReload = { newJsonObject ->
                    jsonObjectState.value = newJsonObject
                    onReload?.invoke(newJsonObject)
                },
            )
        }
    }
}

@Composable
private fun ActionButtons(
    actionButtons: MutableList<ActionButton?>,
    onReload: (JSONObject) -> Unit = {},
) {
    if (actionButtons.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            items(actionButtons.size) { index ->
                StructuredDataActionButton(
                    actionButton = actionButtons[index]!!,
                    onReload = onReload,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StructuredDataActionButton(
    actionButton: ActionButton,
    modifier: Modifier = Modifier,
    onReload: (JSONObject) -> Unit = {},
) {
    Button(
        onClick = actionButton.onClick,
//        onClick = {
//            val url = actionButt?.url ?: return@Button
//            when {
//                url.startsWith("xreload://") -> {
//                    // Reload action: placeholder implementation
//                    // In real usage, this would fetch new JSON from a URL
//                    val newJson = JSONObject().apply {
//                        put("name", "${actionButt.label} - reloaded at ${System.currentTimeMillis()}")
//                        put("@type", "UpdatedContent")
//                    }
//                    onReload(newJson)
//                }
//                // Add more URL schemes here as needed
//                else -> {
//                    // Default action: could open URL, show dialog, etc.
//                    // TODO: Implement actual action handling
//                }
//            }
//        },
        modifier = modifier,
    ) {
        // todo just icon if label is null
        if (actionButton.icon != null) Icon(imageVector = actionButton.icon, contentDescription = null)
        if (actionButton.label != null ) Text(actionButton.label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Extract title based on schema type and available properties
 */
private fun getTitleFromMarkup(jsonObject: JSONObject, schemaType: String): String {
    return when {
        schemaType.contains("NewsArticle", ignoreCase = true) ||
        schemaType.contains("BlogPosting", ignoreCase = true) -> {
            jsonObject.optString("headline", jsonObject.optString("name", ""))
        }
        else -> jsonObject.optString("name", "")
    }
}

/**
 * Extract image URL from various schema.org image properties
 */
private fun getImageUrlFromMarkup(jsonObject: JSONObject): String? {
    // Try image property first
    val imageValue = jsonObject.opt("image")
    if (imageValue != null) {
        return when (imageValue) {
            is String -> imageValue
            is JSONObject -> {
                // ImageObject with url property
                imageValue.optString("url", null)
                    ?: imageValue.optString("contentUrl", null)
            }
            else -> null
        }
    }

    // Try thumbnailUrl
    val thumbnailUrl = jsonObject.opt("thumbnailUrl")
    if (thumbnailUrl != null) {
        return when (thumbnailUrl) {
            is String -> thumbnailUrl
            else -> null
        }
    }

    return null
}

/**
 * Map schema.org @type to an icon
 */
private fun getIconForType(schemaType: String): ImageVector {
    return when {
        schemaType.contains("NewsArticle", ignoreCase = true) ->
            Icons.Filled.Info
        schemaType.contains("Event", ignoreCase = true) ->
            Icons.Filled.Event
        schemaType.contains("Organization", ignoreCase = true) ->
            Icons.Filled.Store
        schemaType.contains("Person", ignoreCase = true) ->
            Icons.Filled.Person
        schemaType.contains("Product", ignoreCase = true) ->
            Icons.Filled.ShoppingCart
        schemaType.contains("Recipe", ignoreCase = true) ->
            Icons.Filled.RestaurantMenu
        else -> Icons.AutoMirrored.Filled.Notes
    }
}

class ActionButtonData(label: String?, icon: ImageVector? )
