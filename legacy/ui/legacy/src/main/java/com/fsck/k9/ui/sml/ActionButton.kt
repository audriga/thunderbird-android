package com.fsck.k9.ui.sml

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Patterns
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AssistantDirection
import androidx.compose.material.icons.automirrored.outlined.ForwardToInbox
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.graphics.vector.ImageVector
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.helper.MailTo
import com.fsck.k9.view.SMLWebViewClientExtensions
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import androidx.core.net.toUri
import coil3.toUri
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.GeneralSettingsManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ActionButtonUtil(
    private val onReload: ((JSONObject) -> Unit),
    private val clipboardManager: ClipboardManager,
    private val messageReference: MessageReference?,
    private val context: Context,
): KoinComponent {
    private val generalSettingsManager: GeneralSettingsManager by inject()
    private val logger: Logger by inject()
    /**
     * Creates descriptions of buttons to be included in a rendered card.
     * @param jsonObject the cards schema
     * @return the button descriptions
     */
    fun getButtons(jsonObject: JSONObject): MutableList<ActionButton?> {
        val buttons: MutableList<ActionButton?> = ArrayList()
        val type = jsonObject.optString("@type")
        val potentialActions = jsonObject.opt("potentialAction")
        if (potentialActions != null) {
            if (potentialActions is JSONObject) {
                handleExistingPotentialAction(potentialActions, buttons, type)
            } else if (potentialActions is JSONArray) {
                for (i in 0..<potentialActions.length()) {
                    val potentialAction = potentialActions.opt(i)
                    if (potentialAction is JSONObject) {
                        handleExistingPotentialAction(potentialAction, buttons, type)
                    }
                }
            }
        }
        val audioButtonDesc = getAudioContentButtonDesc(jsonObject)
        if (audioButtonDesc != null) {
            buttons.add(audioButtonDesc)
        }
        val videoButtonDesc = getVideoContentButtonDesc(jsonObject)
        if (videoButtonDesc != null) {
            buttons.add(videoButtonDesc)
        }
        val webUrlButtonDesc = getWebUrlButtonDesc(jsonObject)
        if (webUrlButtonDesc != null) {
            buttons.add(webUrlButtonDesc)
        }
        if (shouldMakeSharableAsFile(type)) {
            val shareAsFileButtonDesc = getShareAsFileButtonDesc(jsonObject, type)
            buttons.add(shareAsFileButtonDesc)
        }
        if (isEvent(type) || hasStartAndEndDate(jsonObject)) {
            val meetButtonDesc = getMeetButtonDesc(jsonObject)
            if (meetButtonDesc != null) {
                buttons.add(meetButtonDesc)
            }
            val eventButtonDesc = getEventButtonDescs(jsonObject)
            buttons.addAll(eventButtonDesc)
        }
        if (!isPoll(type)) {
            // try to get phone number
            try {
                val telephone: MutableList<Any?> = findAllRecursive(jsonObject, "telephone")
                for (phone in telephone) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (phone is String) {
                        buttons.add(ActionButton(null, Icons.Outlined.Phone, {invokeUri("tel:$phone".toUri())}))
                    }
                }
                val phones: MutableList<Any?> = findAllRecursive(jsonObject, "phone")
                for (phone in phones) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (phone is String) {
                        buttons.add(ActionButton(null, Icons.Outlined.Phone,{invokeUri("tel:$phone".toUri())}))
                    }
                }
            } catch (e: JSONException) {
                // todo logging
//                e(e, "Error trying to add phone button descriptions")
            }
            // try to get mail
            try {
                val mails: MutableList<Any?> = findAllRecursive(jsonObject, "email")
                for (email in mails) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (email is String) {
                        buttons.add(ActionButton(null, Icons.Outlined.Mail, { invokeUri("mailto:$email".toUri())}))
                    }
                }
            } catch (e: JSONException) {
                // todo logging
//                e(e, "Error trying to add phone button descriptions")
            }
        }

        // try to get Place/ Map
        try {
            val geos: MutableList<Any?> = findAllRecursive(jsonObject, "geo")
            for (geo in geos) {
                if (geo is JSONObject) {
                    val latitude = geo.opt("latitude")
                    val longitude = geo.opt("longitude")
                    // don't care if lat/ long is int, double or string, all of them should play nicely with string concatenation.
                    // likelyhood of being a JSONObject, or something unexpected is very low I would say
                    if (latitude != null && longitude != null) {
                        // todo the geo uris are hardcoded at the moment
                        buttons.add(
                            ActionButton(
                                null,
                                Icons.AutoMirrored.Outlined.AssistantDirection,
                                {invokeUri("google.navigation:q=$latitude,$longitude".toUri())},
                            ),
                        )
                        buttons.add(ActionButton(
                            null,
                            Icons.Outlined.Map,
                            {invokeUri("geo:$latitude,$longitude".toUri())}))
                    }
                }
            }
        } catch (e: JSONException) {
            // todo logging
//            e(e, "Error trying to add geo button descriptions")
        }
        if (shouldMakeSharableAsMail(type)) {
            val shareAsMailButtonDesc = getShareAsMailButtonDesc(jsonObject)
            buttons.add(shareAsMailButtonDesc)
        }
        val liveUri = jsonObject.optString("liveUri")
        if (liveUri.isNotEmpty()) {
            val reloadButtonDesc = getReloadButtonDesc(liveUri)
            buttons.add(reloadButtonDesc)
        }

        //        buttons.add(new ActionButton("Call", "tel:124"));
//        buttons.add(new ActionButton("Story", "xstory:#https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html"));
        return buttons
    }

    fun getAudioContentButtonDesc(jsonObject: JSONObject): ActionButton? {
        val mediaType = "audio"
        return getMediaContentButtonDesc(jsonObject, mediaType, Icons.Outlined.MusicNote)
    }

    fun getVideoContentButtonDesc(jsonObject: JSONObject): ActionButton? {
        val mediaType = "video"
        return getMediaContentButtonDesc(jsonObject, mediaType, Icons.Outlined.PlayCircle)
    }

    private fun getMediaContentButtonDesc(
        jsonObject: JSONObject,
        mediaType: String?,
        icon: ImageVector?,
    ): ActionButton? {
        val mediaContentUrl = getMediaContentUrl(jsonObject, mediaType)
        if (mediaContentUrl.isNullOrEmpty()) {
            return null
        }
        return ActionButton(null, icon,{ SMLWebViewClientExtensions.playMedia(context, mediaType, mediaContentUrl.toUri())})
    }

    private fun getMediaContentUrl(jsonObject: JSONObject, mediaType: String?): String? {
        val media = jsonObject.opt(mediaType)
        if (media != null) {
            if (media is JSONArray) {
                for (i in 0..<media.length()) {
                    try {
                        val mediaElement = media.get(i)
                        if (mediaElement is JSONObject) {
                            return getPotentiallyNestedMediaContentUrl(mediaElement, mediaType)
                        }
                    } catch (e: JSONException) {
                        // todo logging
//                        e(e, "Error while iteration over audio or video jsonld element")
                    }
                }
            } else if (media is JSONObject) {
                return getPotentiallyNestedMediaContentUrl(media, mediaType)
            }
        }
        return null
    }

    /**
     * Gets contentUrl either directly from object, or from direct child, but does not recurse
     */
    private fun getPotentiallyNestedMediaContentUrl(media: JSONObject, mediaType: String?): String? {
        var mediaContentUrl = media.optString("contentUrl")
        if (mediaContentUrl.isNotEmpty()) {
            return mediaContentUrl
        } else {
            val innerMedia = media.optJSONObject(mediaType)
            if (innerMedia != null) {
                mediaContentUrl = innerMedia.optString("contentUrl")
                if (mediaContentUrl.isNotEmpty()) {
                    return mediaContentUrl
                }
            }
        }
        return null
    }

    fun getMeetButtonDesc(jsonObject: JSONObject): ActionButton? {
        val description = jsonObject.optString("description")
        if (description.isNotEmpty()) {
            val urlHtmlMatcher = Patterns.WEB_URL.matcher(description)
            while (urlHtmlMatcher.find()) {
                val url = urlHtmlMatcher.group()
                if (url.contains("meet.google.com")) {
                    return ActionButton(null, Icons.Filled.Videocam, {invokeUri(url.toUri())})
                }
            }
        }
        return null
    }

    fun getEventButtonDescs(jsonObject: JSONObject): MutableList<ActionButton?> {
        val iTIPMethod = jsonObject.optString("iTIPMethod")
        val shareEventAsCalendar = ActionButton(null, Icons.Outlined.Event, { SMLWebViewClientExtensions.shareAsCal(context, jsonObject)})
        return if (iTIPMethod.isEmpty()) {
            mutableListOf(shareEventAsCalendar)
        } else if (iTIPMethod.lowercase() == "request") {
            getIMITActionButtons(jsonObject)
        } else {
            mutableListOf(shareEventAsCalendar)
        }
    }

    private fun getIMITActionButtons(json: JSONObject): MutableList<ActionButton?> {
        val imipActionButtons: MutableList<ActionButton?> = ArrayList(3)
        imipActionButtons.add(ActionButton("Accept", null,{ SMLWebViewClientExtensions.imipInteract(json, SMLWebViewClientExtensions.Companion.IMIPAction.Accept, messageReference, generalSettingsManager, context)}))
        imipActionButtons.add(ActionButton("Decline", null, { SMLWebViewClientExtensions.imipInteract(json, SMLWebViewClientExtensions.Companion.IMIPAction.Accept, messageReference, generalSettingsManager, context)}))
        imipActionButtons.add(ActionButton("Tentative", null,{ SMLWebViewClientExtensions.imipInteract(json, SMLWebViewClientExtensions.Companion.IMIPAction.Accept, messageReference, generalSettingsManager, context)}))
        return imipActionButtons
    }

    fun getWebUrlButtonDesc(jsonObject: JSONObject): ActionButton? {
        val url = jsonObject.opt("url")
        var webUrlButtonDesc: ActionButton? = null
        if (url != null) {
            webUrlButtonDesc = ActionButton(null, Icons.Outlined.Link, {invokeUri(url.toString().toUri())})
        }
        return webUrlButtonDesc
    }

    fun getReloadButtonDesc(liveUri: String): ActionButton {
        // todo refresh action
        return ActionButton(null, Icons.Outlined.Replay, {
            val (jsonSrc: String?, okErr: String?) = SMLWebViewClientExtensions.downloadPage(liveUri.toUri())
            if (okErr != null) {
                showToast(context, "Http error ($okErr)") // todo use string resources
            } else if (jsonSrc == null) {
                showToast(context, "Got no content") // todo use string resources
            } else {
                val jsonObject = JSONObject(jsonSrc)
                onReload(jsonObject)
            }
        })
    }

    fun getShareAsMailButtonDesc(jsonObject: JSONObject): ActionButton {
        val jsonText = jsonObject.toString()
        return ActionButton(null, Icons.AutoMirrored.Outlined.ForwardToInbox, { SMLWebViewClientExtensions.shareAsSmlMail(context, messageReference, jsonText)})
    }

    fun getShareAsFileButtonDesc(jsonObject: JSONObject, fallbackFileName: String): ActionButton {
        val jsonBytes: ByteArray = jsonObject.toString().toByteArray(StandardCharsets.UTF_8)
        val encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE)
        val fileName = jsonObject.optString("name", fallbackFileName) + ".json"
        return ActionButton(
            null, Icons.Outlined.Share,
            {
                SMLWebViewClientExtensions.shareAsFile(context, fileName, jsonBytes)
            },
        )
    }

    private fun handleExistingPotentialAction(
        potentialAction: JSONObject,
        buttons: MutableList<ActionButton?>,
        mainSchemaType: String?,
    ) {
        when (val actionType = potentialAction.optString("@type")) {
            "CopyToClipboardAction" -> {
                val name = potentialAction.optString("name", "Copy to clipboard ")
                val description = potentialAction.optString("description")
                if (description.isNotEmpty()) {
                    buttons.add(
                        ActionButton(
                            label = name, icon = Icons.Outlined.ContentPaste,
                            onClick = {
                                clipboardManager.setText(
                                    description,
                                    description
                                ) // todo maybe set a summary text for the clipboard babel
                            },
                        ),
                    )
                }
            }

            "ConfirmAction" -> {
                val name = potentialAction.optString("name", "Confirm")
                val target = potentialAction.optString("target")
                if (target.isNotEmpty()) {
                    val uri = Uri.parse(target)
                    buttons.add(ActionButton(label = name, onClick =  { invokeUri(uri) }))
                }
            }

            "CancelAction" -> {
                val name = potentialAction.optString("name", "Deny")
                val target = potentialAction.optString("target")
                if (target.isNotEmpty()) {
                    val uri = Uri.parse(target)
                    buttons.add(ActionButton(label = name, onClick = { invokeUri(uri) }))
                }
            }

            // todo polls
//            "ChooseAction", "UpdateAction" -> {
//                if (isPoll(mainSchemaType)) {
//                    val target = potentialAction.opt("target")
//                    if (target is JSONArray) {
//                        var i = 0
//                        while (i < target.length()) {
//                            var targetMember: Any? = null
//                            try {
//                                targetMember = target.get(i)
//                            } catch (_: JSONException) {
//                            }
//                            addPollButtonFromSingleTarget(potentialAction, actionType, targetMember, buttons)
//                            i++
//                        }
//                    } else {
//                        addPollButtonFromSingleTarget(potentialAction, actionType, target, buttons)
//                    }
//                }
//            }
        }
    }

    private fun invokeUri(uri: Uri) {
        if (MailTo.isMailTo(uri)) {
            SMLWebViewClientExtensions.mailTo(context, uri, messageReference)
        } else {
            SMLWebViewClientExtensions.openUrl(context, uri)
        }
    }
// todo polls
//
//    private fun addPollButtonFromSingleTarget(
//        potentialAction: JSONObject,
//        actionType: String,
//        targetMember: Any?,
//        buttons: MutableList<ActionButton?>,
//    ) {
//        if (targetMember is String && targetMember.startsWith("http")) {
//            val encodedTarget = Uri.encode(targetMember)
//            // todo not tested yet
//            if (actionType == "ChooseAction") {
//                val submitButton =
//                    ActionButton("Submit", null, "xsubmit:$encodedTarget?action=submit&vote={{user_vote}}")
//                buttons.add(submitButton)
//            } else if (actionType == "UpdateAction") {
//                val actionName = potentialAction.optString("name")
//                when (actionName) {
//                    "close" -> {
//                        run {
//                            val closeButton =
//                                ActionButton("Close", null, "xsubmit:$encodedTarget?action=close")
//                            buttons.add(closeButton)
//                        }
//                        run {
//                            val retractButton =
//                                ActionButton("Retract", null, "xsubmit:$encodedTarget?action=retract")
//                            buttons.add(retractButton)
//                        }
//                    }
//
//                    "retract" -> {
//                        val retractButton =
//                            ActionButton("Retract", null, "xsubmit:$encodedTarget?action=retract")
//                        buttons.add(retractButton)
//                    }
//                }
//            }
//        }
//        //        else {
//        // todo: Ignoring mailto in target for now
////        }
//    }

    private fun shouldMakeSharableAsFile(type: String): Boolean {
        return type == "Recipe" || type.endsWith("Reservation")
    }

    private fun shouldMakeSharableAsMail(type: String?): Boolean {
        return !type.equals("Foo") // Always return true.
        // todo: long term wise should do some type based filtering
    }

    private fun isPoll(type: String?): Boolean {
        return type != null && type == "SimplePoll"
    }

    private fun isEvent(type: String): Boolean {
        return type.endsWith("Event")
    }

    private fun hasStartAndEndDate(jsonObject: JSONObject): Boolean {
        val startTime = jsonObject.optString("startTime")
        val startDate = jsonObject.optString("startDate", startTime)
        val endTime = jsonObject.optString("endTime")
        val endDate = jsonObject.optString("endDate", endTime)
        return (startDate.isNotEmpty()) || (endDate.isNotEmpty())
    }

    @JvmOverloads
    @Throws(JSONException::class)
    fun findAllRecursive(
        json: JSONObject,
        searchKey: String?,
        excluded: MutableList<String?> = mutableListOf(),
    ): MutableList<Any?> {
        val collectedResults: MutableList<Any?> = ArrayList()
        val it = json.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (excluded.contains(key)) {
                continue
            }
            val value = json.get(key)
            if (key == searchKey) {
                collectedResults.add(value)
            } else if (value is JSONObject) {
                val ret = findAllRecursive(value, searchKey, excluded)
                collectedResults.addAll(ret)
            } else if (value is JSONArray) {
                for (i in 0..<value.length()) {
                    val element = value.get(i)
                    if (element is JSONObject) {
                        val ret = findAllRecursive(element, searchKey, excluded)
                        collectedResults.addAll(ret)
                    }
                }
            }
        }
        return collectedResults
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

class ActionButton(
    val label: String?,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
){
}
