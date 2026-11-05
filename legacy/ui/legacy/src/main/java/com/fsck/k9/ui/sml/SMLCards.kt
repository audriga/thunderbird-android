package com.fsck.k9.ui.sml

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.mailstore.MessageViewInfo
import org.json.JSONObject

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
    val jsonLds: List<JSONObject> =
        listOfNotNull(*structuredDataList.toTypedArray(), extracted, *itiExtractedList.toTypedArray()).distinct()
    if (jsonLds.isNotEmpty()) {
        Column {
            structuredDataList.forEach { jsonObject ->
                SMLCard(
                    initialJsonObject = jsonObject,
                    clipboardManager = clipboardManager,
                    messageReference = messageReference,
                    context = context,
                    modifier = modifier,
                    onReload = onReload,
                )
            }
        }
    }
}

fun cardsExist(
    messageViewInfo: MessageViewInfo
): Boolean {
    val structuredDataList = messageViewInfo.messageData?.structuredData.orEmpty()
    val extracted = messageViewInfo.messageData?.extracted
    val itiExtractedList = messageViewInfo.messageData?.itiExtracted.orEmpty()
    return  structuredDataList.isNotEmpty() || extracted != null || itiExtractedList.isNotEmpty()
}
