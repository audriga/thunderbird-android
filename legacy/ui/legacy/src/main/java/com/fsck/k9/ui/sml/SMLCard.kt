package com.fsck.k9.ui.sml

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil3.CoilImage
import okhttp3.internal.toImmutableMap
import org.json.JSONArray
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
    // todo simplify by using `by` instead of `=`, see https://stackoverflow.com/questions/69326413/whats-difference-between-remember-and-by-remember
    val jsonObjectState = remember { mutableStateOf(initialJsonObject) }
    val jsonObject = jsonObjectState.value

    val schemaType = jsonObject.optString("@type", "")
    val title = getTitleFromMarkup(jsonObject, schemaType)
    val description = getDescriptionFromMarkup(jsonObject, schemaType)
    val imageUrl = extractImage(jsonObject)
    val actionButtons = ActionButtonUtil(
        onReload = { newJsonObject ->
                    jsonObjectState.value = newJsonObject
                    onReload?.invoke(newJsonObject)
                },
        clipboardManager =  clipboardManager,
        messageReference = messageReference,
        context = context,
        ).getButtons(jsonObject)
    if (schemaType.equals("SimplePoll")) {
        SimplePollCard(schemaType, title, imageUrl, jsonObject, jsonObjectState, actionButtons, modifier, onReload)
    } else {
        SimpleCard(schemaType, title, description, imageUrl, actionButtons, jsonObjectState, modifier, onReload)
    }
}

@Composable
private fun SimplePollCard(
    schemaType: String,
    title: String,
    imageUrl: String?,
    jsonObject: JSONObject,
    jsonObjectState: MutableState<JSONObject>,
    actionButtons: MutableList<ActionButton?>,
    modifier: Modifier,
    onReload: ((JSONObject) -> Unit)?,
) {
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
                // todo factor out to function
                // Image or Icon
                if (!imageUrl.isNullOrEmpty()) {
                    CoilImage(
                        imageModel = { imageUrl },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                        ),
                        // todo make image square, not rectangular
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

                // Title
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
            val votes = jsonObject.opt("votes")
            var totalVotes: Int
            var votesPerOption: Map<String, Int>
            if (votes is JSONArray) {
                totalVotes = votes.length()
                votesPerOption = getVotesPerOption(votes, totalVotes)
            } else {
                totalVotes = 0
                votesPerOption = mapOf()
            }

            val options = jsonObject.getJSONArray("options")// todo err checking
            val checkedBoxes = remember { List(options.length()) { false }.toMutableStateList() }
            for (i in 0..<options.length()) {
                val optionName = options.getJSONObject(i).optString("name", "Option $i")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checkedBoxes[i],
                        onCheckedChange = {
                            checkedBoxes[i] = it
                        },
                    )
                    Text(optionName)
                }
                val votesForOption = votesPerOption[optionName] ?: 0
                Column() {
                    LinearProgressIndicator(
                        progress = {
                            (if (totalVotes != 0) {
                                votesForOption.toFloat() / totalVotes
                            } else {
                                0.01f
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("$votesForOption Votes")
                }
            }

            actionButtons.add(
                0,
                ActionButton(
                    "Submit", null,
                    {
                        val foo = checkedBoxes

                        // todo
                    },
                ),
            )

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

private fun getVotesPerOption(votes: JSONArray, totalVotes: Int): Map<String, Int> {
    val votesPerOption: MutableMap<String, Int> = HashMap()
    for (i in 0..<totalVotes) {
        val vote = votes.get(i)
        if (vote is JSONObject
            && "ChooseAction" == vote.optString("@type") // todo to conform with spec changes potentially remove check for `@type`.
            ) {
            countVote(vote, votesPerOption)
        }
    }
    return votesPerOption.toImmutableMap()
}

private fun countVote(vote: JSONObject, votesPerOption: MutableMap<String, Int>) {
    val actionOption = vote.opt("actionOption")
    if (actionOption is JSONArray) {
        for (i in 0..<actionOption.length()) {
            val currentActionOption = actionOption.get(i)
            if (currentActionOption is String) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    votesPerOption.merge(currentActionOption, 1) { a: Int, b: Int -> Integer.sum(a, b) }
                } else {
                    votesPerOption[currentActionOption] = (votesPerOption[currentActionOption] ?: 0) + 1
                }
            }
        }
    } else if (actionOption is String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            votesPerOption.merge(actionOption, 1) { a: Int, b: Int -> Integer.sum(a, b) }
        } else {
            votesPerOption[actionOption] = (votesPerOption[actionOption] ?: 0) + 1
        }
    }
}

@Composable
private fun SimpleCard(
    schemaType: String,
    title: String,
    description: String,
    imageUrl: String?,
    actionButtons: MutableList<ActionButton?>,
    jsonObjectState: MutableState<JSONObject>,
    modifier: Modifier,
    onReload: ((JSONObject) -> Unit)?,
) {
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
                        // todo make image square, not rectangular
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
        schemaType.contains("ParcelDelivery") -> {
            jsonObject.optJSONObject("itemShipped")?.optString("name") ?: "Parcel"
        }
        else -> jsonObject.optString("name", "")
    }
}

private fun getDescriptionFromMarkup(jsonObject: JSONObject, schemaType: String): String {
    val descriptionProp = jsonObject.optString("description", "")
    if (descriptionProp.isNotEmpty()) {
        return descriptionProp
    }
    return when {
        schemaType.contains("ParcelDelivery") -> {
            // todo full text. Also what about i18n
            jsonObject.optString("expectedArrivalUntil") ?:""
        }
        else -> ""
    }
}

// todo only load image from web url depending on setting
private fun extractImage(jsonLd: JSONObject): String? {
    val thumbnailUrl = jsonLd.opt("thumbnailUrl")
    val thumbnail = jsonLd.opt("thumbnail")
    val image = jsonLd.opt("image")
    if (thumbnailUrl == null && thumbnail == null && image != null) {
        val imageUrl = imageFromNestedJson(image)
        return imageUrl
    } else {
        if (thumbnail != null) {
            val thumbnailExtracted = imageFromNestedJson(thumbnail)
            return thumbnailExtracted
        } else if (thumbnailUrl != null) {
            val thumbnailExtracted = imageFromNestedJson(thumbnailUrl)
            return thumbnailExtracted
        } else {
            return null
        }
    }
}

fun imageFromNestedJson(image: Any?): String? {
    when (image) {
        is JSONObject -> {
            val imageContentUrl = image.opt("contentUrl")
            if (imageContentUrl is String) {
                return imageContentUrl
            } else {
                val url = image.opt("url")
                return url as? String
            }
        }

        is JSONArray -> {
            when (val first = image.get(0)) {
                is JSONObject -> {
                    val imageContentUrl = first.opt("contentUrl")
                    if (imageContentUrl is String) {
                        return imageContentUrl
                    } else {
                        val url = first.opt("url")
                        return url as? String
                    }
                }

                is String -> {
                    return first
                }

                else -> {
                    return null
                }
            }
        }

        is String -> {
            return image
        }

        else -> {
            return null
        }
    }
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
