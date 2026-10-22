package com.fsck.k9.sml

import android.net.Uri
import android.util.Base64
import android.util.Patterns
import java.nio.charset.StandardCharsets
import org.audriga.ld2h.ButtonDescription
import org.audriga.ld2h.HeadProvider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber.Forest.e

object SMLUtil {
    @JvmStatic
    fun css(): String {
        val css = HeadProvider.loadHead()
        return "<head>\n" + css + "\n</head>"
    }

    /**
     * Creates descriptions of buttons to be included in a rendered card.
     * @param jsonObject the cards schema
     * @return the button descriptions
     */
    @JvmStatic
    fun getButtons(jsonObject: JSONObject): MutableList<ButtonDescription?> {
        val buttons: MutableList<ButtonDescription?> = ArrayList<ButtonDescription?>()
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
                        buttons.add(ButtonDescription(null, "call", "tel:$phone"))
                    }
                }
                val phones: MutableList<Any?> = findAllRecursive(jsonObject, "phone")
                for (phone in phones) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (phone is String) {
                        buttons.add(ButtonDescription(null, "call", "tel:$phone"))
                    }
                }
            } catch (e: JSONException) {
                e(e, "Error trying to add phone button descriptions")
            }
            // try to get mail
            try {
                val mails: MutableList<Any?> = findAllRecursive(jsonObject, "email")
                for (email in mails) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (email is String) {
                        buttons.add(ButtonDescription(null, "mail", "mailto:$email"))
                    }
                }
            } catch (e: JSONException) {
                e(e, "Error trying to add phone button descriptions")
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
                            ButtonDescription(
                                null,
                                "assistant_direction",
                                "google.navigation:q=$latitude,$longitude"
                            )
                        )
                        buttons.add(ButtonDescription(null, "map", "geo:$latitude,$longitude"))
                    }
                }
            }
        } catch (e: JSONException) {
            e(e, "Error trying to add geo button descriptions")
        }
        if (shouldMakeSharableAsMail(type)) {
            val shareAsMailButtonDesc = getShareAsMailButtonDesc(jsonObject)
            buttons.add(shareAsMailButtonDesc)
        }
        val liveUri = jsonObject.optString("liveUri")
        if (!liveUri.isEmpty()) {
            val reloadButtonDesc = getReloadButtonDesc(liveUri)
            buttons.add(reloadButtonDesc)
        }

        //        buttons.add(new ButtonDescription("Call", "tel:124"));
//        buttons.add(new ButtonDescription("Story", "xstory:#https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html"));
        return buttons
    }

    fun getAudioContentButtonDesc(jsonObject: JSONObject): ButtonDescription? {
        val mediaType = "audio"
        return getMediaContentButtonDesc(jsonObject, mediaType, "music_note")
    }

    fun getVideoContentButtonDesc(jsonObject: JSONObject): ButtonDescription? {
        val mediaType = "video"
        return getMediaContentButtonDesc(jsonObject, mediaType, "play_circle")
    }

    private fun getMediaContentButtonDesc(
        jsonObject: JSONObject,
        mediaType: String?,
        icon: String?
    ): ButtonDescription? {
        val mediaContentUrl = getMediaContentUrl(jsonObject, mediaType)
        if (mediaContentUrl == null || mediaContentUrl.isEmpty()) {
            return null
        }
        val encodedContentUrl =
            Base64.encodeToString(mediaContentUrl.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP + Base64.URL_SAFE)
        val uri = Uri.Builder()
            .scheme("xplaymedia")
            .authority(encodedContentUrl)
            .appendQueryParameter("mediaType", mediaType)
            .build()
        return ButtonDescription(null, icon, uri.toString())
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
                        e(e, "Error while iteration over audio or video jsonld element")
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
        if (!mediaContentUrl.isEmpty()) {
            return mediaContentUrl
        } else {
            val innerMedia = media.optJSONObject(mediaType)
            if (innerMedia != null) {
                mediaContentUrl = innerMedia.optString("contentUrl")
                if (!mediaContentUrl.isEmpty()) {
                    return mediaContentUrl
                }
            }
        }
        return null
    }

    fun getMeetButtonDesc(jsonObject: JSONObject): ButtonDescription? {
        val description = jsonObject.optString("description")
        if (!description.isEmpty()) {
            val urlHtmlMatcher = Patterns.WEB_URL.matcher(description)
            while (urlHtmlMatcher.find()) {
                val url = urlHtmlMatcher.group()
                if (url.contains("meet.google.com")) {
                    return ButtonDescription(null, "videocam", url)
                }
            }
        }
        return null
    }

    fun getEventButtonDescs(jsonObject: JSONObject): MutableList<ButtonDescription?> {
        val jsonBytes: ByteArray = jsonObject.toString().toByteArray(StandardCharsets.UTF_8)
        val encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE)
        val uri = Uri.Builder()
            .scheme("xshareascalendar")
            .authority(encodedJson)
            .build()
        val iTIPMethod = jsonObject.optString("iTIPMethod")
        val shareEventAsCalendar = ButtonDescription(null, "event", uri.toString())
        if (iTIPMethod.isEmpty()) {
            return mutableListOf<ButtonDescription?>(shareEventAsCalendar)
        } else if (iTIPMethod.lowercase() == "request") {
            return getIMITButtonDescriptions(encodedJson)
        } else {
            return mutableListOf<ButtonDescription?>(shareEventAsCalendar)
        }
    }

    private fun getIMITButtonDescriptions(encodedJson: String?): MutableList<ButtonDescription?> {
        val buttonDescs: MutableList<ButtonDescription?> = ArrayList<ButtonDescription?>(2)
        //        buttonDescs.add(shareEventAsCalendar);
        val acceptUri = Uri.Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("accept")
            .build()
        val declineUri = Uri.Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("decline")
            .build()
        val tentativeUri = Uri.Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("tentative")
            .build()
        buttonDescs.add(ButtonDescription("Accept", acceptUri.toString()))
        buttonDescs.add(ButtonDescription("Decline", declineUri.toString()))
        buttonDescs.add(ButtonDescription("Tentative", tentativeUri.toString()))
        return buttonDescs
    }

    fun getWebUrlButtonDesc(jsonObject: JSONObject): ButtonDescription? {
        val url = jsonObject.opt("url")
        var webUrlButtonDesc: ButtonDescription? = null
        if (url != null) {
            webUrlButtonDesc = ButtonDescription(null, "link", url.toString())
        }
        return webUrlButtonDesc
    }

    fun getReloadButtonDesc(liveUri: String?): ButtonDescription {
        val buttonUri = Uri.parse(liveUri).buildUpon()
            .scheme("xreload")
            .build()
        return ButtonDescription(null, "replay", buttonUri.toString())
    }

    fun getShareAsMailButtonDesc(jsonObject: JSONObject): ButtonDescription {
        val jsonBytes: ByteArray = jsonObject.toString().toByteArray(StandardCharsets.UTF_8)
        val encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE)
        val buttonUri = Uri.Builder()
            .scheme("xshareasmail")
            .authority(encodedJson)
            .build()
        return ButtonDescription(null, "forward_to_inbox", buttonUri.toString())
    }

    fun getShareAsFileButtonDesc(jsonObject: JSONObject, fallbackFileName: String): ButtonDescription {
        val jsonBytes: ByteArray = jsonObject.toString().toByteArray(StandardCharsets.UTF_8)
        val encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE)
        val fileName = jsonObject.optString("name", fallbackFileName) + ".json"
        val uri = Uri.Builder()
            .scheme("xshareasfile")
            .authority(encodedJson)
            .appendQueryParameter("fileName", fileName)
            .build()
        return ButtonDescription(null, "share", uri.toString())
    }

    private fun handleExistingPotentialAction(
        potentialAction: JSONObject,
        buttons: MutableList<ButtonDescription?>,
        mainSchemaType: String?
    ) {
        val actionType = potentialAction.optString("@type")
        when (actionType) {
            "CopyToClipboardAction" -> {
                val name = potentialAction.optString("name", "Copy to clipboard ")
                val description = potentialAction.optString("description")
                if (!description.isEmpty()) {
                    buttons.add(ButtonDescription(name, "content_paste", "xclipboard:" + description))
                }
            }

            "ConfirmAction" -> {
                val name = potentialAction.optString("name", "Confirm")
                val target = potentialAction.optString("target")
                if (!target.isEmpty()) {
                    buttons.add(ButtonDescription(name, target))
                }
            }

            "CancelAction" -> {
                val name = potentialAction.optString("name", "Deny")
                val target = potentialAction.optString("target")
                if (!target.isEmpty()) {
                    buttons.add(ButtonDescription(name, target))
                }
            }

            "ChooseAction", "UpdateAction" -> {
                if (isPoll(mainSchemaType)) {
                    val target = potentialAction.opt("target")
                    if (target is JSONArray) {
                        val targetArray = target
                        var i = 0
                        while (i < targetArray.length()) {
                            var targetMember: Any? = null
                            try {
                                targetMember = targetArray.get(i)
                            } catch (_: JSONException) {
                            }
                            addPollButtonFromSingleTarget(potentialAction, actionType, targetMember, buttons)
                            i++
                        }
                    } else {
                        addPollButtonFromSingleTarget(potentialAction, actionType, target, buttons)
                    }
                }
            }
        }
    }

    private fun addPollButtonFromSingleTarget(
        potentialAction: JSONObject,
        actionType: String,
        targetMember: Any?,
        buttons: MutableList<ButtonDescription?>
    ) {
        if (targetMember is String && targetMember.startsWith("http")) {
            val encodedTarget = Uri.encode(targetMember)
            // todo not tested yet
            if (actionType == "ChooseAction") {
                val submitButton =
                    ButtonDescription("Submit", null, "xsubmit:" + encodedTarget + "?action=submit&vote={{user_vote}}")
                buttons.add(submitButton)
            } else if (actionType == "UpdateAction") {
                val actionName = potentialAction.optString("name")
                when (actionName) {
                    "close" -> {
                        run {
                            val closeButton =
                                ButtonDescription("Close", null, "xsubmit:" + encodedTarget + "?action=close")
                            buttons.add(closeButton)
                        }
                        run {
                            val retractButton =
                                ButtonDescription("Retract", null, "xsubmit:" + encodedTarget + "?action=retract")
                            buttons.add(retractButton)
                        }
                    }

                    "retract" -> {
                        val retractButton =
                            ButtonDescription("Retract", null, "xsubmit:" + encodedTarget + "?action=retract")
                        buttons.add(retractButton)
                    }
                }
            }
        }
        //        else {
        // todo: Ignoring mailto in target for now
//        }
    }

    private fun shouldMakeSharableAsFile(type: String): Boolean {
        return type == "Recipe" || type.endsWith("Reservation")
    }

    private fun shouldMakeSharableAsMail(type: String?): Boolean {
        return true
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
        return (!startDate.isEmpty()) || (!endDate.isEmpty())
    }

    @JvmOverloads
    @Throws(JSONException::class)
    fun findAllRecursive(
        json: JSONObject,
        searchKey: String?,
        excluded: MutableList<String?> = mutableListOf<String?>()
    ): MutableList<Any?> {
        val collectedResults: MutableList<Any?> = ArrayList<Any?>()
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

    class ActionButton(string: String, string1: String) {

    }
}
