package com.fsck.k9.mailstore

import android.util.Base64
import android.util.Patterns
import androidx.javascriptengine.JavaScriptIsolate
import app.k9mail.legacy.di.DI.get
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.audriga.h2lj.model.StructuredData
import com.audriga.h2lj.model.StructuredSyntax
import com.audriga.h2lj.parser.StructuredDataExtractionUtils
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mail.internet.Viewable
import com.fsck.k9.sml.SMLUtil.css
import com.fsck.k9.sml.SMLUtil.getButtons
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId
import org.audriga.ld2h.ButtonDescription
import org.audriga.ld2h.MustacheRenderer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.kde.kitinerary.Kitinerary.Extractor
import org.mnode.ical4j.serializer.jsonld.EventJsonLdSerializer

object SMLMessageView {
    // todo document when we try to derive
    @JvmStatic
    fun shouldTryToDerive(message: Message): TryToDerive? {
        val subject = message.getSubject()
        if (subject.lowercase(Locale.getDefault()).contains("code")) {
            return TryToDerive.VERIFICATION_CODE
        }
        return null
    }

    fun addExtractedUrlButtons(urls: MutableList<String>, htmlString: String): String {
        val encodedUrls: MutableList<String?> = ArrayList<String?>(urls.size)
        for (url in urls) {
            // Substring to remove https:// prefix
            encodedUrls.add(
                Base64.encodeToString(url.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP + Base64.URL_SAFE),
            )
        }

        val button = "<a href=\"xloadcards://" + encodedUrls.joinToString(",") + "\">Load Cards</a><br><hr><br><br>"
        val htmlWithStringButtons = addLoadButtonsAfterUrls(htmlString)
        // todo this is not actually using the output of htmlProcessor.processForDisplay.
        //    also: the function that adds the buttons should proably work on the html tree instead of what it is currently doing
        return css() + button + htmlWithStringButtons
    }

    @Deprecated("Use composable markup card instead of html based card")
    @Throws(IOException::class)
    fun renderDataOrExtractedAndAddToHTML(
        unwrappedStructuredData: MutableList<JSONObject>, extracted: JSONObject?,
        sanitizedHtml: String, debugView: Boolean,
    ): String {
        val renderer = MustacheRenderer()
        val renderedHTMLs = ArrayList<String?>()
        val typeFilteredJsonLds = filterByType(unwrappedStructuredData)
        val renderFilteredJsonLds = renderer.filterRenderable(typeFilteredJsonLds)
        if (renderFilteredJsonLds.isEmpty()) {
            if (extracted == null) {
                return "<b>NO RENDERABLE STRUCTURED DATA FOUND</b><br>" + sanitizedHtml
            }
        } else if (renderFilteredJsonLds.size == 1) {
            val jsonObject = renderFilteredJsonLds.get(0)
            renderWithButtons(jsonObject, renderer, renderedHTMLs, debugView)
        } else {
            val tabbedCardHTML = renderTabbedWithButtons(renderFilteredJsonLds, renderer, debugView)
            renderedHTMLs.add(tabbedCardHTML)
        }
        if (extracted != null) {
            renderWithButtons(extracted, renderer, renderedHTMLs, debugView)
        }

        val result = renderedHTMLs.joinToString("\n")

        //                    /*
//                    // removed LD2H
//                    String snippet1 = "";
//
//                    String snippet2 = " ";
//                    */
//
//                    String url_story = "https://cdn.prod.www.spiegel.de/stories/117130/index.amp.html";
//                    String url_story2 = "https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html";
//                    String url_story3 = "http://www.audriga.eu/test/web-stories/amp_story.html";
//
//
//                    String linx = "<br>LINX: <a href=\"tel:124\">TEL</a>" +
//                        "<br><a href=\"file:blubb\">FILE</a>" +
//                        "<br><a href=\"xclipboard:12234567890\">Clipboard</a>" +
//                        "<br><a href=\"xmail:blupp\">xmail</a>" +
//                        "<br><a href=\"xalert:xblupp\">xalert</a>" +
//                        "<br><a href=\"xjs:xjs\">xjs</a>"
//                    + "<br><a href=\"xstory:xstory:https://www.broken.com\">x_broken</a>"
//                    + "<br><a href=\"xstory:#https://www.google.com\">x_google</a>"
//                    + "<br><a href=\"xstory:#" + url_story + "\">xstory</a>"
//                    + "<br><a href=\"xstory:#" + url_story2 + "\">xstory2</a>"
//                    + "<br><a href=\"xstory:#" + url_story3 + "\">xstory3</a>"
//                    + "<br><a href=\"xrequest://www.audriga.eu/test/web-stories/amp_story.html\">httprequest</a>"
//                    + "<br><a href=\"xreload://www.audriga.eu/test/data/68517fbf68ff3.json\">reload</a>"
//                    + "<br><a href=\"xbarcode:/abc\">barcodetest</a>"
//                    + "<br><hr><br><br>";
//
//
//                    sanitizedHtml = css  + "<br><br>SML:<br>" + result + "<br>XSML<br>" + linx + "<br>" + "<br><b>ACTUAL HTML MAIL BELOW</b><br>" + htmlProcessor.processForDisplay(htmlString);
        return css() + result + "<br><b>ACTUAL HTML MAIL BELOW</b><br>" + sanitizedHtml
    }

    private fun unwrapStructuredData(data: MutableList<StructuredData>): MutableList<JSONObject> {
        val jsonObjects: MutableList<JSONObject> = ArrayList()
        for (structuredData in data) {
            val jsonObject = structuredData.json
            jsonObjects.add(jsonObject)
        }
        return jsonObjects
    }

    private fun filterByType(jsonObjects: MutableList<JSONObject>): MutableList<JSONObject> {
        // We know these types will most likely not render well, so don't render them (unless they are the only markup)
        // todo find a good place for this
        val typesToSkip = if (jsonObjects.size > 1) mutableListOf<String?>(
            "Organization",
            "NewsMediaOrganization",
            "WebSite",
            "BreadcrumbList",
            "WebPage",
        ) else null

        val filteredJsonLds: MutableList<JSONObject> = ArrayList<JSONObject>()
        for (jsonObject in jsonObjects) {
            val type = jsonObject.optString("@type")
            if (typesToSkip != null && typesToSkip.contains(type)) {
                continue
            }
            filteredJsonLds.add(jsonObject)
        }
        if (filteredJsonLds.isEmpty()) {
            // If filter would remove everything, return unfiltered list instead
            return jsonObjects
        }
        return filteredJsonLds
    }

    @Throws(IOException::class)
    private fun renderTabbedWithButtons(
        filteredJsonLds: MutableList<JSONObject>,
        renderer: MustacheRenderer,
        debugView: Boolean,
    ): String? {
        if (filteredJsonLds.isEmpty()) {
            // this shouldn't happen since this method should only be called if data has at least one element. But
            // just to be sure we add this check
            return null
        }
        val jsonLds = JSONArray()
        val buttonLists: MutableList<MutableList<ButtonDescription?>?> =
            ArrayList<MutableList<ButtonDescription?>?>(filteredJsonLds.size)
        for (jsonObject in filteredJsonLds) {
            val buttons = getButtons(jsonObject)
            buttonLists.add(buttons)
            jsonLds.put(jsonObject)
        }
        val showSourceButton = getShowSourceButton(jsonLds, debugView)
        var result = renderer.renderTabbed(jsonLds, buttonLists)
        if (showSourceButton != null) {
            result += showSourceButton
        }
        return result
    }

    @Throws(IOException::class)
    private fun renderWithButtons(
        jsonObject: JSONObject, renderer: MustacheRenderer,
        renderedHTMLs: ArrayList<String?>, debugView: Boolean,
    ) {
        val showSourceButton = getShowSourceButton(jsonObject, debugView)
        val buttons = getButtons(jsonObject)
        var result = renderer.render(jsonObject, buttons)
        if (showSourceButton != null) {
            result += showSourceButton
        }
        renderedHTMLs.add(result)
    }

    private fun getShowSourceButton(jsonArray: JSONArray, debugView: Boolean): String? {
        if (debugView) {
            try {
                val prettyJson = jsonArray.toString(2)
                return getShowSourceButton(prettyJson)
            } catch (_: JSONException) {
            }
        }
        return null
    }

    private fun getShowSourceButton(jsonObject: JSONObject, debugView: Boolean): String? {
        if (debugView) {
            try {
                val prettyJson = jsonObject.toString(2)
                return getShowSourceButton(prettyJson)
            } catch (_: JSONException) {
            }
        }
        return null
    }

    private fun getShowSourceButton(prettyJson: String): String {
        val encodedFullUrl = Base64.encodeToString(
            prettyJson.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP + Base64.URL_SAFE,
        )
        val jsonSourceUrl = "xshowsource://" + encodedFullUrl
        return "<button class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" onclick=\"window.open('" +
            jsonSourceUrl +
            "', '_blank');\"><span class=\"mdc-button__ripple\"></span><i class=\"material-icons mdc-button__icon\" aria-hidden=\"true\">data_object</i>Show source</span></button>"
    }

    @Throws(MessagingException::class, IOException::class)
    fun getStructuredDataFromParseableParts(parseableParts: ArrayList<Part>?, data: MutableList<StructuredData>) {
        if (parseableParts != null) {
            for (part in parseableParts) {
                if (MimeUtility.isSameMimeType(part.getMimeType(), "application/ld+json")) {
                    val json = readBodyToText(part)
                    val structuredData =
                        StructuredDataExtractionUtils.parseStructuredDataFromJsonStr(json)
                    if (structuredData != null) {
                        data.addAll(structuredData)
                    }
                }
            }
        }
    }

    @Throws(MessagingException::class, IOException::class)
    private fun readBodyToText(part: Part?): String? {
        var part = part
        var body = part!!.getBody()
        if (body == null) {
            // Part must have been lazy loaded. Downloading full part.
            if (part is LocalBodyPart) {
                val message = part.message
                val accountId = message.accountId
                val mc = get<MessagingController>(MessagingController::class.java)

                // It would probably be a bit cleaner to use a CompletableFuture but that is only available at API level 24
                val latch = CountDownLatch(1)
                val resultRef = AtomicReference<Part?>()
                //mc.loadMessageRemote(); this could potentially be used instead to download the entire message...
                mc.loadAttachment(
                    accountId, message, part,
                    object : SimpleMessagingListener() {
                        override fun loadAttachmentFinished(accountId: AccountId?, message: Message?, part: Part?) {
                            resultRef.set(part)
                            latch.countDown()
                            //                        super.loadAttachmentFinished(account, message, part);
                        }

                        override fun loadAttachmentFailed(
                            accountId: AccountId?,
                            message: Message?,
                            part: Part?,
                            reason: String?,
                        ) {
//                        Timber.e("Loading Part failed: %s", reason);
                            resultRef.set(part)
                            latch.countDown()
                            //                        super.loadAttachmentFailed(account, message, part, reason);
                        }
                    },
                )
                try {
                    latch.await()
                } catch (_: InterruptedException) {
//                    Timber.e(e, "Interrupted while trying to load part");
                }
                // Trying to use part after this (either via `part = resultRef.get();` or directly using the existing
                // reference) causes issues, because the body is a BinaryTempFileBody and the temp file is already gone.
                // Loading message from local storage instead works, but need to re-find sml part.
                val loadedMessage =
                    mc.loadMessage(accountId, message.folder.databaseId, message.uid)
                val messageBody = loadedMessage.body
                if (messageBody is MimeMultipart) {
                    part = null
                    val parts = messageBody.bodyParts
                    val viewableParts = ArrayList<Viewable?>()
                    val attachments = ArrayList<Part?>()
                    val parseableParts = ArrayList<Part>()

                    for (bp in parts) {
                        MessageExtractor.findViewablesAndAttachments(bp, viewableParts, attachments, parseableParts)
                    }
                    for (bp in parseableParts) {
                        if (MimeUtility.isSameMimeType(bp.getMimeType(), "application/ld+json")) {
                            part = bp
                            break
                        }
                    }
                    if (part == null) {
//                        Timber.e("Did not find application/ld+json part in message");
                        return null
                    }
                } else {
//                    Timber.e("Expected to get multipart after loading part, but got %s instead",
//                        messageBody.getClass());
                    return null
                }
                body = part.getBody()
            }
        }
        if (body == null) {
            return null
        }
        // Decoding body. Possible alternative (lower leve): use InputStream inputStream = MimeUtility.decodeBody(body)
        return MessageExtractor.getTextFromPart(part)
    }

    @Throws(MessagingException::class, IOException::class, ParserException::class)
    fun getStructuredDataFromAttachments(
        parseableAttachments: HashMap<AttachmentViewInfo, String>?,
        headerMap: Map<String, String>?,
        contextDate: String?,
    ): Pair<List<JSONObject>, List<JSONObject>> {
        val extracted: MutableList<JSONObject> = mutableListOf()
        val kitiAllExtracted: MutableList<JSONObject> = mutableListOf()
        if (parseableAttachments != null) {
            for ((attachment, ext) in parseableAttachments) {
                val part = attachment.part ?: continue
                when (ext) {
                    "ics" -> {
                        //attachment.isContentAvailable();
                        // Check if ics is IMIP:
                        val contentTypeHeader = part.getContentType()
                        val itipMethod = MimeUtility.getHeaderParameter(contentTypeHeader, "method")
                        // could potentially get recipient via
                        // ((LocalBodyPart) part).message.getRecipients(RecipientType.TO)
                        val body = part.getBody()
                        val `is` = MimeUtility.decodeBody(body)

                        //                            BufferedReader r = new BufferedReader(new InputStreamReader(is));
//                            StringBuilder total = new StringBuilder();
//                            for (String line; (line = r.readLine()) != null; ) {
//                                total.append(line).append('\n');
//                            }
//                            String icsContent = total.toString();
                        val icalText: String?
                        val cal: Calendar
                        try {
                            val cbuilder = CalendarBuilder()
                            // Todo currently cbuilder.build always throws with NoSuchMethodError (something about ZoneRulesProvider)
                            cal = cbuilder.build(`is`)
                            icalText = cal.toString()
                        } catch (_: Throwable) {
                            continue
                        }
                        //                        System.out.println(cal.getUid());
                        val eventJsons: MutableList<StructuredData> = ArrayList()
                        for (event in cal.getComponents<CalendarComponent?>()) {
                            if (event is VEvent) {

                                //                                List<Attendee> attendees = event.getAttendees();
//                                Optional<Organizer> organizer = event.getOrganizer();
//                                Optional<Created> created = event.getCreated();
//                                Optional<LastModified> lastModified = event.getLastModified();
//                                Optional<DtStamp> dateTimeStamp = event.getDateTimeStamp();
//                                Optional<Sequence> sequence = event.getSequence();
//                                Optional<Status> status = event.getStatus();
//                                List<Property> rrules = event.getProperties(Property.RRULE);
                                val module = SimpleModule()
                                module.addSerializer<VEvent?>(
                                    VEvent::class.java,
                                    EventJsonLdSerializer(VEvent::class.java),
                                )
                                //                            module.addSerializer(VCard.class, new PersonJsonLdSerializer(VCard.class));
//                            module.addSerializer(VCard.class, new OrganizationJsonLdSerializer(VCard.class));
                                val mapper = ObjectMapper()
                                mapper.registerModule(module)
                                val serialized = mapper.writeValueAsString(event)
                                val structuredDatas =
                                    StructuredDataExtractionUtils.parseStructuredDataFromJsonStr(serialized)
                                // structuredData should only contain a single JSONObject I think
                                for (sd in structuredDatas) {
                                    try {
                                        sd.json.put("iTIPMethod", itipMethod)
                                        if (itipMethod != null && itipMethod.isNotEmpty()) {
                                            sd.json.put("originalICal", icalText)
                                        }
                                        //                                        List<String> attendeesText = new ArrayList<>(attendees.size());
//                                        for (Attendee attendee : attendees) {
//                                            attendeesText.add(attendee.toString().trim());
//                                        }
//                                        sd.getJson().put("attendee", new JSONArray(attendeesText));
//                                        if (VERSION.SDK_INT >= VERSION_CODES.N) {
//                                            if (organizer.isPresent()) {
//                                                sd.getJson().put("organizer", organizer.get().toString().trim());
//                                            }
//                                            if (created.isPresent()) {
//                                                sd.getJson().put("dateCreated", created.get().toString().trim());
//                                            }
//                                            if (lastModified.isPresent()) {
//                                                sd.getJson().put("dateModified", lastModified.get().toString().trim());
//                                            }
//                                            if (dateTimeStamp.isPresent()) {
//                                                sd.getJson().put("icalDTStamp", dateTimeStamp.get().toString().trim());
//                                            }
//                                            if (sequence.isPresent()) {
//                                                sd.getJson().put("icalSequence", sequence.get().toString().trim());
//                                            }
//                                            if (status.isPresent()) {
//                                                sd.getJson().put("icalStatus", status.get().toString().trim());
//                                            }
//                                            if (!rrules.isEmpty()) {
//                                                List<String> rrulesText = rrules.stream().map(rrule -> rrule.toString().trim()).collect(Collectors.toList());
//                                                sd.getJson().put("icalRRules", new JSONArray(rrulesText));
//                                            }
//                                        }
                                    } catch (_: JSONException) {
//                                        Timber.e(e, "Could not add iTIP things to json");
                                    }
                                }
                                eventJsons.addAll(structuredDatas)
                            }
                        }
                        extracted.addAll(unwrapStructuredData(eventJsons))
                    }

                    "vcard", "pkpass", "pdf" -> {
                        val body = part.getBody()
                        val bodyInputStream = MimeUtility.decodeBody(body)
                        val bytes = bodyInputStream.readBytes()
                        val itiExtracted =
                            Extractor().addData(bytes, attachment.filename, attachment.mimeType, headerMap)
                                .setContextDate(contextDate)
                                .setPostprocessing(true)
                                .extract()
                        for (i in 0..<itiExtracted.length()) {
                            val jsonObject: JSONObject? = itiExtracted.optJSONObject(i)
                            if (jsonObject != null) {
                                kitiAllExtracted.add(jsonObject)
                            }
                        }
                    }
                }
            }
        }
        return Pair(extracted, kitiAllExtracted)
    }

    @Throws(JSONException::class)
    fun maybeTryExtract(
        shouldTryToDerive: TryToDerive?, textString: String,
        htmlString: String,
    ): JSONObject? {
        val extracted: JSONObject?
        if (shouldTryToDerive != null) {
            extracted = tryExtract(shouldTryToDerive, textString, htmlString)
        } else {
            extracted = null
        }
        return extracted
    }

    @Throws(JSONException::class)
    fun tryExtract(tryToDerive: TryToDerive, text: String, html: String): JSONObject? {
        when (tryToDerive) {
            TryToDerive.VERIFICATION_CODE -> {
                //        Pattern c2cpattern = Pattern.compile("[0-9]{6}");
                val boldc2cpattern = Pattern.compile("<b>[0-9]{4,}</b>")
                //        Matcher textMatcher = c2cpattern.matcher(text);
                val htmlMatcher = boldc2cpattern.matcher(html)
                while (htmlMatcher.find()) {
//            String textMatch = textMatcher.group();
                    val htmlMatch = htmlMatcher.group()
                    val code = htmlMatch.substring(3, htmlMatch.length - 4)
                    if (text.contains(code)) {
                        return JSONObject()
                            .put("@context", "https://schema.org")
                            .put("@type", "EmailMessage")
                            .put("description", "Confirmation code: " + code)
                            .put(
                                "potentialAction",
                                JSONObject()
                                    .put("@type", "CopyToClipboardAction") // 📋
                                    .put("name", code)
                                    .put("description", code),
                            )
                    }
                }
            }
        }

        return null
    }

    //    @Nullable
    //    static String tryExtractWhitelistedUrl(String text, String html) {
    //        List<String> whiteListedUrls = Arrays.asList("www.spiegel.de", "cooking.nytimes.com", "nl.nytimes.com");
    //        Matcher urlPlaintextMatcher = Patterns.WEB_URL.matcher(text);
    //        while (urlPlaintextMatcher.find()) {
    //            String url = urlPlaintextMatcher.group();
    //            for (String whiteListedUrl : whiteListedUrls) {
    //                if (url.contains(whiteListedUrl)) {
    //                    return  url;
    //                }
    //            }
    //        }
    //        Matcher urlHtmlMatcher = Patterns.WEB_URL.matcher(html);
    //        while (urlHtmlMatcher.find()) {
    //            String url = urlHtmlMatcher.group();
    //            for (String whiteListedUrl : whiteListedUrls) {
    //                if (url.contains(whiteListedUrl)) {
    //                    return  url;
    //                }
    //            }
    //        }
    //
    //        return null;
    //    }
    fun tryExtractAllWhitelistedUrls(text: String, html: String): MutableList<String> {
        val whiteListedUrls: MutableList<String> = mutableListOf("www.spiegel.de", "cooking.nytimes.com", "nl.nytimes.com/f/cooking")
        val urlPlaintextMatcher = Patterns.WEB_URL.matcher(text)
        val urls: MutableList<String> = ArrayList<String>()
        while (urlPlaintextMatcher.find()) {
            val url = urlPlaintextMatcher.group()
            for (whiteListedUrl in whiteListedUrls) {
                if (url.contains(whiteListedUrl)) {
                    val end = urlPlaintextMatcher.end()
                    var fullUrl = url
                    if (end < text.length) {
                        val afterUrl =
                            text.substring(end).split("[\r\n\t\u000c^\"<>]".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[0]
                        fullUrl = url + afterUrl
                    }
                    if (!urls.contains(fullUrl)) {
                        urls.add(fullUrl)
                    }
                }
            }
        }
        val urlHtmlMatcher = Patterns.WEB_URL.matcher(html)
        while (urlHtmlMatcher.find()) {
            val url = urlHtmlMatcher.group()
            for (whiteListedUrl in whiteListedUrls) {
                if (url.contains(whiteListedUrl)) {
                    val end = urlHtmlMatcher.end()
                    var fullUrl = url
                    if (end < html.length) {
                        val afterUrl =
                            html.substring(end).split("[\r\n\t\u000c^\"<>]".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[0]
                        fullUrl = url + afterUrl
                    }
                    if (!urls.contains(fullUrl)) {
                        urls.add(fullUrl)
                    }
                }
            }
        }

        return urls
    }

    fun addLoadButtonsAfterUrls(html: String): String {
        val whiteListedUrls: MutableList<String> = mutableListOf("www.spiegel.de", "cooking.nytimes.com", "nl.nytimes.com/f/cooking")
        val builder = StringBuilder()
        val urlHtmlMatcher = Patterns.WEB_URL.matcher(html)
        var originaHTMLIndex = 0
        while (urlHtmlMatcher.find()) {
            val url = urlHtmlMatcher.group()
            for (whiteListedUrl in whiteListedUrls) {
                if (url.contains(whiteListedUrl)) {
                    val end = urlHtmlMatcher.end()
                    if (end < html.length) {
                        val afterMatch = html.substring(end)
                        val afterUrlSplits =
                            afterMatch.split("[\r\n\t\u000c^\"<>]".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        val afterUrl = afterUrlSplits[0]
                        val fullUrl = url + afterUrl
                        val encodedFullUrl = Base64.encodeToString(
                            fullUrl.toByteArray(StandardCharsets.UTF_8),
                            Base64.NO_WRAP + Base64.URL_SAFE,
                        )
                        val loadCardUrl = "xloadcards://" + encodedFullUrl
                        if (originaHTMLIndex >= end) {
                            //todo something went wrong with the matching
                            return html
                        }
                        builder.append(html.substring(originaHTMLIndex, end))
                        val afterButtonSplits =
                            afterMatch.split("</a>".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        val insertPosition = end + afterButtonSplits[0].length + 4 //+4 to position after </a> tag
                        if (insertPosition >= html.length) {
                            //todo something went wrong with the matching
                            return html
                        } else if (end >= insertPosition) {
                            //todo something went wrong with the matching
                            return html
                        }
                        builder.append(html.substring(end, insertPosition))
                        val button =
                            "<a href=\"" + loadCardUrl + "\"><span class=\"material-icons\">web_asset</span></a>"
                        //                      button += "<a href=\"" + loadCardUrl + "\"><span class=\"material-symbols-outlined\">web_asset</span></a>";
                        builder.append(button)
                        originaHTMLIndex = insertPosition
                    }
                }
            }
        }
        builder.append(html.substring(originaHTMLIndex))
        return builder.toString()
    }

    @JvmStatic
    @Throws(JSONException::class, MessagingException::class, IOException::class, ParserException::class)
    fun getMessageData(
        text: String, rawHtml: String, parseableParts: ArrayList<Part>?,
        parseableAttachments: HashMap<AttachmentViewInfo, String>?, shouldTryToDerive: TryToDerive?,
        mailExtractorContext: MailExtractorContext?,
    ): MessageData {
        val extracted = maybeTryExtract(shouldTryToDerive, text, rawHtml)

        val headerMap: Map<String, String>?
        val contextDate: String?
        if (mailExtractorContext != null) {
            headerMap = mailExtractorContext.headerMap
            contextDate = mailExtractorContext.contextDate
        } else {
            headerMap = null
            contextDate = null
        }
        val kitiExtractArr: JSONArray = try {
            Extractor()
                .addText(text, "text/plain", headerMap)
                .addText(rawHtml, "text/html", headerMap)
                .setContextDate(contextDate)
                .setPostprocessing(true)
                .extract()
        } catch (_: Throwable) {
            JSONArray()
        }
        val kitiExtract = kitiExtractArr.toJSONObjectList()

        var data = StructuredDataExtractionUtils.parseStructuredDataPart(rawHtml, StructuredSyntax.JSON_LD)
        if (data.isEmpty()) {
            data = StructuredDataExtractionUtils.parseStructuredDataPart(rawHtml, StructuredSyntax.MICRODATA)
        }

        getStructuredDataFromParseableParts(parseableParts, data)

        val (attachmentExtracted, attachmentKitiExtracted) = getStructuredDataFromAttachments(parseableAttachments, headerMap, contextDate)

        return MessageData(unwrapStructuredData(data), listOfNotNull(extracted, *attachmentExtracted.toTypedArray()), listOf(*kitiExtract.toTypedArray(), *attachmentKitiExtracted.toTypedArray()))
    }

    @JvmStatic
    fun renderMarkupInMessageHTML(
        text: String, rawHtml: String, sanitizedHtml: String,
        messageData: MessageData?, javaScriptIsolateLazy: Lazy<JavaScriptIsolate?>?,
        mailExtractorContext: MailExtractorContext,
    ): String {
        if (messageData == null) return sanitizedHtml

        //            String s1 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n     <div class=\"mdc-card__media mdc-card__media--square demo-card__media\">\r\n        <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\"><!--!Font Awesome Free 6.6.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.--><path d=\"M336 352c97.2 0 176-78.8 176-176S433.2 0 336 0S160 78.8 160 176c0 18.7 2.9 36.8 8.3 53.7L7 391c-4.5 4.5-7 10.6-7 17l0 80c0 13.3 10.7 24 24 24l80 0c13.3 0 24-10.7 24-24l0-40 40 0c13.3 0 24-10.7 24-24l0-40 40 0c6.4 0 12.5-2.5 17-7l33.3-33.3c16.9 5.4 35 8.3 53.7 8.3zM376 96a40 40 0 1 1 0 80 40 40 0 1 1 0-80z\"/></svg>\r\n     </div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\">Your confirmation code: <strong>ABCDE123</strong> <span class=\"data_to_copy\"></span></p>\r\n        <!-- p class=\"ld-card__content\"><strong>Expires:</strong> </p -->\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"file:ABCDE123\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Copy</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//            String s2img = "data:image/jpeg;base64,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";
//
//            String s2 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n    <div class=\"mdc-card__media mdc-card__media--square demo-card__media\" style=\"background-image: url(\'" + s2img + "\'); width: 110px;\" alt=\"Picture showing Restaurant\"></div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\"><strong>Sticks N Sushiy</strong> <span class=\"data_to_copy\"></span></p>\r\n        <p class=\"ld-card__content\">Kingston, London<br>(Open till 10am)</p>\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"tel:\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Call</a>&nbsp;<a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"geo:51.4111047,-0.3876285,20883\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Nav</a>&nbsp;<a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"mailto:sushi@london.test\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Request table</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//            String s3 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n     <div class=\"mdc-card__media mdc-card__media--square demo-card__media\">\r\n<video controls height=\"100\" poster=\"https://is1-ssl.mzstatic.com/image/thumb/Video124/v4/5c/d0/fc/5cd0fc4a-714c-e24f-e287-1361bf9c69d6/GB1108700010.sca1.jpg/640x480mv.jpg\" src=\"https://video-ssl.itunes.apple.com/itunes-assets/Video124/v4/25/c0/a7/25c0a7ef-34a6-c36d-c921-ad3d90cec9d6/mzvf_18388793884790474945.640x480.h264lc.U.p.m4v\"></video>\r\n     </div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\"><strong>Rick Astley</strong> <span class=\"data_to_copy\"></span></p>\r\n        <!-- p class=\"ld-card__content\"><strong>Expires:</strong> </p -->\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"https://music.apple.com/us/music-video/never-gonna-give-you-up/1559900284\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Play</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//
//            String video = "<video controls height=\"150\" poster=\"https://is1-ssl.mzstatic.com/image/thumb/Video124/v4/5c/d0/fc/5cd0fc4a-714c-e24f-e287-1361bf9c69d6/GB1108700010.sca1.jpg/640x480mv.jpg\" src=\"https://video-ssl.itunes.apple.com/itunes-assets/Video124/v4/25/c0/a7/25c0a7ef-34a6-c36d-c921-ad3d90cec9d6/mzvf_18388793884790474945.640x480.h264lc.U.p.m4v\"></video>\r\n<br/><br/>\r\n<audio controls style=\"width: 200px; height: 20px;\" src=\"https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview126/v4/b0/d8/aa/b0d8aa8e-1a38-6287-508d-27693174249e/mzaf_13677213744690699536.plus.aac.ep.m4a\">boo</audio>";

//        JavaScriptIsolate jsIsolate = javaScriptIsolateLazy.getValue();
//        String code = "function sum(a, b) { let r = a + b; return r.toString(); }; sum(3, 4)";
//        String result = null;
//        try {
//            result = jsIsolate.evaluateJavaScriptAsync(code).get(5, TimeUnit.SECONDS);
//        } catch (ExecutionException | InterruptedException | TimeoutException e) {
//          return e.getMessage();
//        }
//        if (result != null) {
//            return result;
//        }
        var demoView = false
        val accountManager = get(LegacyAccountManager::class.java)
        val accountId = mailExtractorContext.accountId
        if (accountId != null) {
            val account = accountManager.findById(accountId)
            if (account != null) {
                demoView = account.demoView
            }
        }

        var displayHtml: String?
        try {
            if (messageData.structuredData.isEmpty() && messageData.extracted.isEmpty()) {
                if (demoView) {
//                if (account != null && account.getDemoView()) {
                    val urls = tryExtractAllWhitelistedUrls(text, sanitizedHtml)
                    displayHtml = if (urls.isNotEmpty()) {
                        addExtractedUrlButtons(urls, sanitizedHtml)
                    } else {
                       sanitizedHtml
//                        "<b>NO STRUCTURED DATA FOUND</b><br>$sanitizedHtml"
                    }
                } else {
                    displayHtml = sanitizedHtml
//                    displayHtml = "<b>NO STRUCTURED DATA FOUND</b><br>$sanitizedHtml"
                }
            } else {
                // Should not render all jsonLds at the top of the message, if we are in our "newsletter with a dozen recipes" case

                val inlineModifiedHtml =
                    addInlineButtonsToHtmlForEmbeddedStructuredData(rawHtml, messageData.structuredData)
                if (inlineModifiedHtml != null) {
                    // still showing newsletter
                    displayHtml = css() + inlineModifiedHtml
                } else {
                    displayHtml = sanitizedHtml
                    //Not showing ld2hj card anymore
//                    displayHtml = renderDataOrExtractedAndAddToHTML(messageData.structuredData, messageData.extracted, sanitizedHtml, debugView);
                }
            }
        } catch (_: Exception) {
//            Timber.e(e, "Encountered exception while trying to extract/ display markup from viewable");
            displayHtml = sanitizedHtml
        }
        return displayHtml
    }

    /**
     * This is for the newsletter with embedded structured data case.
     *
     * @return the modified html, if it was modified, to contain inline buttons to show the corresponding card, or null
     * if the html was not modified.
     */
    private fun addInlineButtonsToHtmlForEmbeddedStructuredData(
        rawHtml: String,
        data: MutableList<JSONObject>,
    ): String? {
        // Mail contains some structured data. This might be our "newsletter with data-id" case too.
        val idToJsonLd: MutableMap<String?, JSONObject?> = HashMap<String?, JSONObject?>(data.size)
        for (structuredDatum in data) {
            val id = structuredDatum.optString("@id")
            if (!id.isEmpty()) {
                idToJsonLd.put(id, structuredDatum)
            }
        }
        var didModifyHtml = false
        if (!idToJsonLd.isEmpty()) {
            val doc = Jsoup.parse(rawHtml)
            val linksWithId = doc.select("a[href][data-id]")
            if (!linksWithId.isEmpty()) {
                for (linkElement in linksWithId) {
                    val dataId = linkElement.attr("data-id")
                    if (idToJsonLd.containsKey(dataId)) {
                        val jsonObject = idToJsonLd.get(dataId)
                        if (jsonObject != null) {
                            val encodedJsonLd =
                                Base64.encodeToString(
                                    jsonObject.toString().toByteArray(StandardCharsets.UTF_8),
                                    Base64.NO_WRAP + Base64.URL_SAFE,
                                )
                            val popupCardUrl = "xpopupcard://" + encodedJsonLd
                            val button =
                                "<a href=\"" + popupCardUrl + "\"><span class=\"material-icons\">web_asset</span></a>"
                            linkElement.after(button)
                            didModifyHtml = true
                        }
                    }
                }
            }
            if (didModifyHtml) {
                // todo this is based on the rawHtml.. Should we run this through the sanitizer somehow?
                return doc.outerHtml()
            } else {
                return null
            }
        }
        return null
    }

    @JvmStatic
    fun getParseableAttachments(
        attachmentInfos: MutableList<AttachmentViewInfo>, mc: MessagingController,
    ): HashMap<AttachmentViewInfo?, String?> {
        val parseableAttachments = HashMap<AttachmentViewInfo?, String?>()
        for (attachmentViewInfo in attachmentInfos) {
            val filename = attachmentViewInfo.displayName
            if (filename != null && filename.lastIndexOf('.') != -1) {
                val extension = filename.substring(filename.lastIndexOf('.') + 1).lowercase()
                val extensionsOfParseable =
                    ArrayList<String?>(mutableListOf<String?>("pkpass", "vcard", "ics"))
                if (extensionsOfParseable.contains(extension)) {
                    if (!attachmentViewInfo.isContentAvailable) {
                        val localPart = attachmentViewInfo.part as LocalPart?
                        val accountId = localPart!!.getAccountId()

                        val message = localPart.getMessage()
                        // this copies parts of downloadAttachment, from AttachmentController
                        mc.loadAttachment(
                            accountId, message, attachmentViewInfo.part,
                            object : SimpleMessagingListener() {
                                override fun loadAttachmentFinished(
                                    accountId: AccountId?,
                                    message: Message?,
                                    part: Part?,
                                ) {
                                    attachmentViewInfo.setContentAvailable()
                                }

                                override fun loadAttachmentFailed(
                                    accountId: AccountId?, message: Message?, part: Part?,
                                    reason: String?,
                                ) {
                                    super.loadAttachmentFailed(accountId, message, part, reason)
                                }
                            },
                        )
                    }
                    // todo: Wait until all attachments have loaded(?)
                    parseableAttachments.put(attachmentViewInfo, extension)
                }
            }
        }
        return parseableAttachments
    }

    enum class TryToDerive {
        VERIFICATION_CODE
    }
}
