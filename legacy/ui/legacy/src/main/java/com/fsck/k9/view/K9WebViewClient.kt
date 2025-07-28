package com.fsck.k9.view

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnClickListener
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Browser
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.legacy.account.Account
import app.k9mail.legacy.di.DI
import app.k9mail.legacy.message.controller.MessageReference
import com.audriga.jakarta.sml.h2lj.model.StructuredSyntax
import com.audriga.jakarta.sml.h2lj.parser.StructuredDataExtractionUtils
import com.fsck.k9.K9
import com.fsck.k9.Preferences
import com.fsck.k9.activity.MessageCompose
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.logging.Timber
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.MessagingException
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.mailstore.AttachmentResolver
import com.fsck.k9.message.MessageBuilder.Callback
import com.fsck.k9.message.SmlMessageUtil
import com.fsck.k9.provider.AttachmentTempFileProvider
import com.fsck.k9.ui.R
import com.fsck.k9.view.MessageWebView.OnPageFinishedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Date
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ComponentContainer
import net.fortuna.ical4j.model.PropertyContainer
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Url
import net.fortuna.ical4j.util.Uris
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Request.Builder
import okio.ByteString.Companion.encodeUtf8
import org.audriga.ld2h.ButtonDescription
import org.audriga.ld2h.JsonLdDeserializer
import org.audriga.ld2h.MustacheRenderer
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
//import java.awt.image.BufferedImage;
import androidx.core.graphics.createBitmap

// import android.R.style

/**
 * [WebViewClient] that intercepts requests for `cid:` URIs to load the respective body part.
 */
internal class K9WebViewClient(
    private val clipboardManager: ClipboardManager,
    private val attachmentResolver: AttachmentResolver?,
    private val onPageFinishedListener: OnPageFinishedListener?,
    private val messageReference: MessageReference?,
) : WebViewClient() {
    private var mimeBoundary: Int = 0

    @Deprecated("Deprecated in parent class")
    override fun shouldOverrideUrlLoading(webView: WebView, url: String): Boolean {
        return shouldOverrideUrlLoading(webView, Uri.parse(url))
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
        return shouldOverrideUrlLoading(webView, request.url)
    }

    private fun shouldOverrideUrlLoading(webView: WebView, uri: Uri): Boolean {
        return when (uri.scheme) {
            CID_SCHEME -> {
                false
            }
            XMAIL_SCHEME -> {
                xmail(webView.context, uri)
                true
            }
            XALERT_SCHEME -> {
                xalert(webView.context, "" + uri)
                true
            }
            XJS_SCHEME -> {
                xjs(webView, uri)
                true
            }
            XSTORY_SCHEME -> {
                xstory(webView.context, uri)
                true
            }
            XRELOAD_SCHEME -> {
                xreload(webView.context, uri)
                true
            }
            FILE_SCHEME -> {
                copyUrlToClipboard(webView.context, uri)
                true
            }
            XCLIPBOARD_SCHEME -> {
                copyToClipboard(webView.context, uri)
                true
            }
            MAILTO_SCHEME -> {
                mailTo(webView.context, uri)
                true
            }
            XREQUEST_SCHEME -> {
                xrequest(webView.context, uri)
                true
            }
            XLOADCARDS_SCHEME -> {
                xloadcards(webView.context, uri)
                true
            }
            XSHARE_AS_FILE_SCHEME -> {
                xshareAsFile(webView.context, uri)
                true
            }
            XSHARE_AS_CALENDAR_SCHEME -> {
                xshareAsCal(webView.context, uri)
                true
            }
            XSHARE_AS_MAIL -> {
                xshareAsMail(webView.context, uri)
                true
            }
            XBARCODE -> {
                openBarcode(webView.context, uri)
                true
            }
            XSHOW_SOURCE -> {
                xShowSource(webView.context, uri)
                true
            }
            else -> {
                openUrl(webView.context, uri)
                true
            }
        }
    }

    private fun mailTo(context: Context, uri: Uri) {
//                val actionQuery = uri.getQueryParameters("action") //for some reason this throws an exception
        val query = uri.query;
        if (query != null && query.contains("action")) {
            val schemaSpecific = uri.schemeSpecificPart
            val end = schemaSpecific.indexOf('?')
            val recipient = Uri.decode(schemaSpecific.substring(0, end))
            val requestAction = query.substring(query.indexOf('=') + 1, query.length)
            val smlPayload = SmlMessageUtil.getApproveDenyPayload(requestAction);
            if (smlPayload != null) {
                val mc = DI.get(MessagingController::class.java)
                val preferences = DI.get(Preferences::class.java)
                val account: Account? = preferences.defaultAccount
                if (account != null) {
                    val builder = SmlMessageUtil.createSMLMessageBuilder(
                        listOf(smlPayload),
                        SmlMessageUtil.getSmlVariantFromAccount(account),
                    )
                    val to = Address.parse(recipient) // todo error handling for this
                    builder.setTo(to.toList())
                    builder.setSubject(requestAction)
                    builder.setIdentity(account.identities.first()) // todo this also is not clean, but we want no interaction, so how would we pick the identity
                    builder.buildAsync(
                        object : Callback {
                            override fun onMessageBuildSuccess(message: MimeMessage?, isDraft: Boolean) {
                                mc.sendMessage(account, message, requestAction, null)
                                Toast.makeText(context, "Sent $requestAction", Toast.LENGTH_SHORT)
                                    .show()
                                //todo show success when done
                            }

                            override fun onMessageBuildCancel() {
    //                                    TODO("Not yet implemented")
                            }

                            override fun onMessageBuildException(exception: MessagingException?) {
    //                                    TODO("Not yet implemented")
                            }

                            override fun onMessageBuildReturnPendingIntent(
                                pendingIntent: PendingIntent?,
                                requestCode: Int,
                            ) {
    //                                    TODO("Not yet implemented")
                            }
                        },
                    )
                } else {
                    // todo show non-success
                }
            } else {
                // todo show non-success
            }

    ////                    val bundle = Bundle();
    ////                    bundle.putString("recipient", recipient)
    ////                    bundle.putString("action", requestAction)
    //                    val intent = Intent(webView.context, MessageCompose::class.java).apply {
    //                        action = MessageCompose.ACTION_COMPOSE_APPROVE
    //                        data = uri
    //                    }.putExtra("recipient", recipient).putExtra("requestAction", requestAction)
    ////                        .putExtra(MessageCompose.IS_SML, true)
    //                    webView.context.startActivity(intent)
        } else {
            openUrl(context, uri)
        }
    }

    private fun showToast(context: Context, message: String) {

        //Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_LONG).show()

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun xstory(context: Context, uri: Uri) {

        // https://www.geeksforgeeks.org/android-webview-in-kotlin/
        // https://stackoverflow.com/questions/5448841/what-do-setusewideviewport-and-setloadwithoverviewmode-precisely-do

        // https://stackoverflow.com/questions/47872078/how-to-load-an-url-inside-a-webview-using-android-kotlin
        // https://stackoverflow.com/questions/20333047/checking-internet-connection-in-webview

        var xwebView = WebView(context) // findViewById(R.id.webview)


//        xwebView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
//        xwebView.forceLayout()
//        xwebView.settings.javaScriptEnabled = true
//        xwebView.settings.mediaPlaybackRequiresUserGesture = false
//        xwebView.settings.useWideViewPort = true
//        xwebView.settings.loadWithOverviewMode = true
//        xwebView.settings.javaScriptCanOpenWindowsAutomatically = true;
//        xwebView.settings.setSupportMultipleWindows(true)
//        xwebView.layout(0,0,-1,-1)
//        xwebView.layoutMode = -1
        xwebView.setBackgroundColor(Color.RED);
        xwebView.setVisibility(View.VISIBLE);
        xwebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl("" + url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                showToast(context, "load: " + url)
                super.onPageStarted(view, url, favicon)
            }

            // https://stackoverflow.com/questions/18282892/android-webview-onpagefinished-called-twice
            //

            override fun onPageFinished(view: WebView?, url: String?) {
                showToast(
                    context,
                    "done: " + view?.progress + " / " + view?.contentHeight
                        + " / " + view?.title + " / " + url,
                );
                super.onPageFinished(view, url)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                val errorMessage = "got HTTP Error!"
                showToast(context, errorMessage)
                super.onReceivedHttpError(view, request, response)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val errorMessage = "got Error! $error"
                showToast(context, errorMessage)
                super.onReceivedError(view, request, error)
            }

            override fun onRenderProcessGone(view: WebView, detail:     RenderProcessGoneDetail): Boolean {

                val errorMessage = "onRenderProcessGone"
                showToast(context, errorMessage)

                return super.onRenderProcessGone(view, detail)
            }

            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {

                val errorMessage = "onOverride " + request.url
                showToast(context, errorMessage)
                return shouldOverrideUrlLoading(webView, request.url)
            }


            // https://stackoverflow.com/questions/8200945/how-to-get-html-content-from-a-webview

        }
        xwebView.settings.javaScriptEnabled = true
        xwebView.settings.domStorageEnabled = true
        xwebView.loadUrl("" + uri.fragment)

        // R.style.FullscreenDialogStyle
        // android.R.style.Theme_Black_NoTitleBar_Fullscreen
        var dialogAlert = MaterialAlertDialogBuilder(context)
          .setView(xwebView)
        //.setTitle("title")
        //.setMessage("msg: " + s)
        .setPositiveButton("Close", null)
        .setCancelable(false)
        .create()
        .apply {
            setCanceledOnTouchOutside(false)
            show()
        }

        // Make Fullscreen
        // https://stackoverflow.com/questions/2306503/how-to-make-an-alert-dialog-fill-90-of-screen-size
        dialogAlert.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)


    }

    private fun xreload(context: Context, uri: Uri) {
        val httpUri = uri.buildUpon().scheme("https").build()
        val (jsonSrc: String?, okErr: String?) = downloadPage(httpUri)



        if (jsonSrc != null) {
            val jsonLds = JsonLdDeserializer.deserialize(jsonSrc)
            val renderer: MustacheRenderer = MustacheRenderer()

            val renderedHTMLs: ArrayList<String> = ArrayList(jsonLds.size)
            for (jsonObject in jsonLds) {
//                val buttons: List<ButtonDescription> = getButtons(jsonObject)
                val result: String = renderer.render(jsonObject)
                renderedHTMLs.add(result)
            }


            showRenderedCardsPopup(context, renderedHTMLs)
        } else {
            showToast(context, "Got no content ($okErr)")
        }

    }

    private fun downloadPage(httpUri: Uri): Pair<String?, String?> {
        val client = OkHttpClient()
        var pageSrc: String? = null
        var okErr: String? = null
        try {
            val request: Request = Builder()
                .url(httpUri.toString()).build()
            client.newCall(request).execute().use { response ->
                if (response.body != null) {
                    pageSrc = response.body!!.string()
                    Timber.d("Got response from %s:\n%s", httpUri, pageSrc)
                }
            }
        } catch (e: java.lang.Exception) {
            okErr = e.message
            Timber.d(e, "Couldn't get: %s", httpUri)
        }
        return Pair(pageSrc, okErr)
    }

    private fun xjs(webView: WebView, uri: Uri) {

        webView.evaluateJavascript(
            "(function() { return 'this'; })();",
            object : ValueCallback<String?> {
                override fun onReceiveValue(value: String?) {
                    xalert(webView.context, "" + value);
                }
            },
        )

     }


    /*
    import android.view.LayoutInflater
    import android.view.View
    import com.google.android.material.dialog.MaterialAlertDialogBuilder
    import com.fsck.k9.ui.base.R as BaseR
    import app.k9mail.feature.settings.importing.R
    */

    private fun xalert(context: Context, s: String) {

        //https://developer.android.com/reference/com/google/android/material/dialog/MaterialAlertDialogBuilder
        // https://stackoverflow.com/questions/56098162/how-to-use-materialalertdialogbuilder-fine
        // https://medium.com/codex/optimized-androids-dialogs-management-1b899ecaedb6

        /*
        MaterialAlertDialogBuilder(this)
            .setMessage("This is a test of MaterialAlertDialogBuilder")
            .setPositiveButton("Ok", null)
            .show()
        */

        // R.style.FullscreenDialogStyle
        // android.R.style.Theme_Black_NoTitleBar_Fullscreen
        var dialogAlert = MaterialAlertDialogBuilder(context)
         // .setView(xwebView)
        .setTitle("title")
        .setMessage("msg: " + s)
        .setPositiveButton("Close", null)
        .setCancelable(false)
        .create()
        .apply {
            setCanceledOnTouchOutside(false)
            show()
        }

        /*
        var dialogView =  createView();


        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton(BaseR.string.okay_action, null)
            .setNegativeButton(BaseR.string.cancel_action, null)
            .create()
        */

    }



    private fun xmail(context: Context, uri: Uri) {

        val address = Address("max2@oldhoster.net")

        /*
        val message = MimeMessage().apply {
            setFrom(Address("from@example.com"))
            setHeader("To", "to@example.com")
            subject = "Test Message"
            setHeader("Date", "Wed, 28 Aug 2024 08:51:09 -0400")
        }

        val multipartBody = MimeMultipart("multipart/mixed", generateBoundary()).apply {
            addBodyPart(textBodyPart())
            addBodyPart(binaryBodyPart())
        }

        MimeMessageHelper.setBody(message, multipartBody)
        */

        // From AutoCryptMessageCreator.kt
        //val messageBody = MimeMultipart.newInstance()
        //messageBody.addBodyPart("Text")
        //messageBody.addBodyPart("Data")

        val textBodyPart = MimeBodyPart.create(TextBody("textBody"))
        val htmlBodyPart = MimeBodyPart.create(TextBody("htmlBody"))

        val messageBody = MimeMultipart("multipart/alternative", generateBoundary()).apply {
            addBodyPart(textBodyPart)
            addBodyPart(htmlBodyPart)
        }

        val message = MimeMessage.create()
        MimeMessageHelper.setBody(message, messageBody)

        val nowDate = Date()

        message.subject = "subjectText " + uri
        message.internalDate = nowDate
        message.addSentDate(nowDate, K9.isHideTimeZone)
        message.setFrom(address)
        message.setHeader("To", address.toEncodedString())

        val messagingController = DI.get(MessagingController::class.java)
        val preferences = DI.get(Preferences::class.java)
        val account = preferences.defaultAccount;
        messagingController.sendMessageBlocking(account, message);

        // val messageListRepository = DI.get<MessageListRepository>()
        // val context = DI.get(Context::class.java)

        //        MessagingController mc = DI.get(MessagingController.class);
        //        Preferences preferences = DI.get(Preferences.class);
        //        Account account = preferences.getDefaultAccount();
        //        mc.sendMessageBlocking(account, message);
    }

    private fun copyUrlToClipboard(context: Context, uri: Uri) {
        val label = context.getString(R.string.webview_contextmenu_link_clipboard_label)
        clipboardManager.setText(label, uri.toString())
    }
    private fun copyToClipboard(context: Context, uri: Uri) {
        val content = uri.schemeSpecificPart;
        val label = "Copied $content";
        clipboardManager.setText(label, content)
    }

    private fun xrequest(context: Context, uri: Uri) {
        val httpUri = uri.buildUpon().scheme("https").build()
        downloadPage(httpUri)
        return;
    }



    private fun xloadcards(context: Context, uri: Uri) {
        val  maxCards = 5;
        val encodedUrls = uri.schemeSpecificPart.split(",")
        val urls = encodedUrls.map {  String(Base64.decode(it, Base64.NO_WRAP + Base64.URL_SAFE)) }
        val ld2hRenderer = MustacheRenderer()
        val renderedDisplayHTMLs = ArrayList<String>()
        val typesToSkip = arrayOf("Organization", "NewsMediaOrganization", "WebSite", "BreadcrumbList", "WebPage")
        for (url in urls) {
            if (renderedDisplayHTMLs.size >= maxCards) {
                break
            }
            val (htmlSrc: String?, okErr: String?) = downloadPage(url.toUri())
            if (htmlSrc != null) {
                var data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.JSON_LD);
                if (data.isEmpty()) {
                    data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.MICRODATA);
                }
                if (data.isNotEmpty()) {
                    for (structuredData in data) {
                        if (renderedDisplayHTMLs.size >= maxCards) {
                            break
                        }
                        val jsonObject = structuredData.json
                        val type = jsonObject.optString("@type")
                        if (typesToSkip.contains(type)) {
                            continue
                        }
                        // Add button to share structured data as email
                        val jsonBytes = jsonObject.toString().encodeToByteArray()
                        val encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
                        val buttonUri = Uri.Builder()
                            .scheme("xshareasmail")
                            .authority(encodedJson)
                            .build()
                        val button = ButtonDescription(null, "forward_to_inbox", buttonUri.toString())
                        val ld2hRenderResult = ld2hRenderer.render(jsonObject, listOf(button))
                        if (ld2hRenderResult != null) {
                            renderedDisplayHTMLs.add(ld2hRenderResult);
                        }
                    }
                } else {
                    //showToast(context, "Could not load cards") // todo collect failed cards?
                }
            } else {
//            showToast(context, "Got no content ($okErr)")
            }
        }
        if (renderedDisplayHTMLs.isNotEmpty()) {
            showRenderedCardsPopup(context, renderedDisplayHTMLs)
        }

//        if (htmlSrc != null) {
//            var data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.JSON_LD);
//            if (data.isEmpty()) {
//                data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.MICRODATA);
//            }
//
//        } else {
//            showToast(context, "Got no content ($okErr)")
//        }
    }

    private fun showRenderedCardsPopup(
        context: Context,
        renderedDisplayHTMLs: List<String>,
    ) {
        val xwebView = WebView(context) // findViewById(R.id.webview)

        xwebView.setVisibility(View.VISIBLE);
        xwebView.settings.javaScriptEnabled = true
        xwebView.settings.domStorageEnabled = true
        xwebView.webViewClient = this

        val result = renderedDisplayHTMLs.joinToString("\n")
        val css = """<head>
            <link href="https://unpkg.com/material-components-web@latest/dist/material-components-web.min.css" rel="stylesheet">
            <script src="https://unpkg.com/material-components-web@latest/dist/material-components-web.min.js"></script>
            <link rel="stylesheet" href="https://fonts.googleapis.com/icon?family=Material+Icons">
            <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Roboto+Mono">
            <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,600,700">
    </head>""";
        val htmlToDisplay = "<!DOCTYPE html>$css<html><body>$result</body></html>"
        xwebView.loadDataWithBaseURL("about:blank", htmlToDisplay, "text/html", "utf-8", null)

        // R.style.FullscreenDialogStyle
        // android.R.style.Theme_Black_NoTitleBar_Fullscreen
        val dialogAlert = MaterialAlertDialogBuilder(context)
            .setView(xwebView)
            //.setTitle("title")
            //.setMessage("msg: " + s)
            .setPositiveButton("Close", null)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }

        // Make Fullscreen
        // https://stackoverflow.com/questions/2306503/how-to-make-an-alert-dialog-fill-90-of-screen-size
        dialogAlert.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun xshareAsFile(context: Context, uri: Uri) {
        val base64 = uri.authority
        val data: ByteArray = Base64.decode(base64, Base64.NO_WRAP + Base64.URL_SAFE)
//        val text = String(data, charset("UTF-8"))
        val fileName = uri.getQueryParameter("fileName")
        val applicationContext = context.applicationContext
        val directory = File(applicationContext.cacheDir, "temp")
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                Timber.e("Error creating directory: %s", directory.absolutePath)
                return
            }
        }
        val jsonFile = File(directory, fileName ?:"sml.json")
        jsonFile.writeBytes(data)

//        val internalFileUri = DecryptedFileProvider.getUriForProvidedFile(context, jsonFile, null, null)
        val sharableUri = AttachmentTempFileProvider.getUriForFile(context, "${context.packageName}.tempfileprovider", jsonFile, "sml1.json")
//        val sharableUri = AttachmentTempFileProvider.createTempUriForContentUri(context, Uri.fromFile(jsonFile), "sml1.json")

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, sharableUri)
            type = "application/json+ld"

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val shareIntent = Intent.createChooser(sendIntent, "Share SML")
        startActivity(context, shareIntent, null)
        // todo the above contains some duplicate code from AttachmentTempFileProvider. It does not contain the cleanup code.
    }
    private fun xshareAsCal(context: Context, uri: Uri) {
        val base64 = uri.authority
        val data: ByteArray = Base64.decode(base64, Base64.NO_WRAP + Base64.URL_SAFE)
        val text = String(data, charset("UTF-8"))
        val json = JSONObject(text)
        val event = VEvent()
        val id = json.optString("@id")
        if (id.isNotEmpty()) {
            event.add<PropertyContainer>(Uid(id))
        }
        val type = json.optString("@type")
        val name = json.optString("name", type)
        if (name.isNotEmpty()) {
            event.add<PropertyContainer>(Summary(name))
        }
        val description = json.optString("description")
        if (description.isNotEmpty()) {
            event.add<PropertyContainer>(Description(description))
        }
        val url = json.opt("url")
        if (url is URI) {
            event.add<PropertyContainer>(Url(url))
        } else if (url is String && url.isNotEmpty()) {
            event.add<PropertyContainer>(Url(Uris.create(url)))
        }
        val startTime = json.optString("startTime")
        val startDate = json.optString("startDate", startTime)
        if (startDate.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (startDate.contains("+")) {
                        val dt = ZonedDateTime.parse(startDate)
                        event.add<PropertyContainer>(DtStart(dt))
                    } else {
                        event.add<PropertyContainer>(DtStart<LocalDateTime>(startDate))
                    }

                } catch (
                    e: DateTimeParseException,
                ) {
                    Timber.e("Error parsing start date: %s", e)

                }
            }
        }
        val endTime = json.optString("endTime")
        val endDate = json.optString("endDate", endTime)
        if (endDate.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (endDate.contains("+")) {
                        val dt = ZonedDateTime.parse(endDate)
                        event.add<PropertyContainer>(DtEnd(dt))
                    } else {
                        event.add<PropertyContainer>(DtEnd<LocalDateTime>(endDate))
                    }
                } catch (
                    e: DateTimeParseException,
                ) {
                    Timber.e("Error parsing end date: %s", e)
                }
            }
        }
        val location = json.optString("location")
        if (location.isNotEmpty()) {
            event.add<PropertyContainer>(Location(location))
        }

        val cal = Calendar().add<ComponentContainer<CalendarComponent>>(event)
//        val module = SimpleModule()
//        module.addDeserializer(Calendar::class.java, JCalMapper(VEvent::class.java))
//        val mapper = ObjectMapper();
//        mapper.registerModule(module);
        val calText = cal.toString()

        val applicationContext = context.applicationContext
        val directory = File(applicationContext.cacheDir, "temp")
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                Timber.e("Error creating directory: %s", directory.absolutePath)
                return
            }
        }
        val jsonFile = File(directory, "${calText.encodeUtf8().sha1().hex()}.ical")
        jsonFile.writeText(calText)

//        val internalFileUri = DecryptedFileProvider.getUriForProvidedFile(context, jsonFile, null, null)
//        val sharableUri = AttachmentTempFileProvider.getUriForFile(context, "${context.packageName}.tempfileprovider", jsonFile, "sml1.json")
        val sharableUri = AttachmentTempFileProvider.createTempUriForContentUri(context, Uri.fromFile(jsonFile), "event.ical")
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(sharableUri, "text/calendar")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

//        val shareIntent = Intent.createChooser(sendIntent, "Share SML")
        startActivity(context, sendIntent, null)
        // todo the above contains some duplicate code from AttachmentTempFileProvider. It does not contain the cleanup code.
    }

    private fun xshareAsMail(context: Context, uri: Uri) {
        val base64 = uri.authority
        val data: ByteArray = Base64.decode(base64, Base64.NO_WRAP + Base64.URL_SAFE)
        val text = String(data)
        val defaultAccount = Preferences.getPreferences().defaultAccount
        if (defaultAccount == null) {
            FeatureLauncherActivity.launchSetupAccount(context);
        } else {
            val accountUuid = defaultAccount.uuid
            val i = Intent(context, MessageCompose::class.java)
            i.putExtra(MessageCompose.EXTRA_ACCOUNT, accountUuid)
            i.putExtra(MessageCompose.IS_SML, true)
            i.setAction(MessageCompose.ACTION_COMPOSE)
            i.putExtra(MessageCompose.SML_PAYLOAD, text)
            context.startActivity(i);
        }
    }

    private fun openUrl(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)

            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.d(e, "Couldn't open URL: %s", uri)
            Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_LONG).show()
        }
    }

    private fun openBarcode(context: Context, uri: Uri) {
        val bcbp = "M1TEST/HIDDEN E8OQ6FU FRARLGLH 4010 012C004D0001 35C>2180WM6012BLH 2922023642241060 LH *30600000K09"
        val bm = generateQRCodeImage(bcbp)
        val v : ImageView = ImageView(context)
        v.setImageBitmap(bm)
        val dialogAlert = MaterialAlertDialogBuilder(context)
            .setView(v)
            //.setTitle("title")
            //.setMessage("msg: " + s)
            .setPositiveButton("Close", null)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }

        // Make Fullscreen
        // https://stackoverflow.com/questions/2306503/how-to-make-an-alert-dialog-fill-90-of-screen-size
        dialogAlert.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun generateQRCodeImage(barcodeText: String): Bitmap {
        val barcodeWriter = MultiFormatWriter()
        val bitMatrix: BitMatrix = barcodeWriter.encode(barcodeText, BarcodeFormat.PDF_417, 600, 400)

        // Needs java.awt.image.BufferedImage
//        MatrixToImageWriter.toBufferedImage(bitMatrix)

//        val barcodeOutputStream = ByteArrayOutputStream();
//        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", barcodeOutputStream)

        val pixels = setBitmapPixels(bitMatrix)
        val bitmap = encodeBitmap(pixels, bitMatrix.width, bitMatrix.height)

        return bitmap;
    }



    private fun setBitmapPixels(bitMatrix: BitMatrix): IntArray {
        val pixels = IntArray(bitMatrix.width * bitMatrix.height)

        for (y in 0 until bitMatrix.height) {
            val offset = y * bitMatrix.width
            for (x in 0 until bitMatrix.width)
                pixels[offset + x] = if (bitMatrix.get(x , y)) Color.BLACK else Color.WHITE
        }
        return pixels
    }

    private fun encodeBitmap(pixels: IntArray, width: Int, height: Int) : Bitmap {
        val bitmap = createBitmap(width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }


    private fun xShowSource(context: Context, uri: Uri) {
        val encodedJsons = uri.schemeSpecificPart.split(",")
        val jsons = encodedJsons.map {  String(Base64.decode(it, Base64.NO_WRAP + Base64.URL_SAFE)) }
        val xwebView = WebView(context) // findViewById(R.id.webview)
        xwebView.visibility = View.VISIBLE;
        xwebView.webViewClient = this

        xwebView.loadDataWithBaseURL("about:blank", jsons[0], "application/json", "utf-8", null)

        val dialogAlert = MaterialAlertDialogBuilder(context)
            .setView(xwebView)
            .setPositiveButton("Copy to Clipboard"
            ) { dialog, which -> clipboardManager.setText("Copied jsonld", jsons[0]) }
            .setNegativeButton("Close", null)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
        dialogAlert.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url

        return if (uri.scheme == CID_SCHEME) {
            handleCidUri(uri, webView)
        } else {
            RESULT_DO_NOT_INTERCEPT
        }
    }

    private fun handleCidUri(uri: Uri, webView: WebView): WebResourceResponse {
        val attachmentUri = getAttachmentUriFromCidUri(uri) ?: return RESULT_DUMMY_RESPONSE

        val context = webView.context
        val contentResolver = context.contentResolver

        @Suppress("TooGenericExceptionCaught")
        return try {
            val mimeType = contentResolver.getType(attachmentUri)
            val inputStream = contentResolver.openInputStream(attachmentUri)

            WebResourceResponse(mimeType, null, inputStream).apply {
                addCacheControlHeader()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error while intercepting URI: %s", uri)
            RESULT_DUMMY_RESPONSE
        }
    }

    private fun getAttachmentUriFromCidUri(uri: Uri): Uri? {
        return uri.schemeSpecificPart
            ?.let { cid -> attachmentResolver?.getAttachmentUriForContentId(cid) }
    }

    private fun WebResourceResponse.addCacheControlHeader() {
        responseHeaders = mapOf("Cache-Control" to "no-store")
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)

        onPageFinishedListener?.onPageFinished()
    }

    private fun generateBoundary(): String {
        return "----Boundary${mimeBoundary++}"
    }

    companion object {
        private const val CID_SCHEME = "cid"
        private const val FILE_SCHEME = "file"
        private const val XMAIL_SCHEME = "xmail"
        private const val XALERT_SCHEME = "xalert"
        private const val XJS_SCHEME = "xjs"
        private const val XSTORY_SCHEME = "xstory"
        private const val XRELOAD_SCHEME = "xreload"
        private const val XCLIPBOARD_SCHEME = "xclipboard"
        private const val MAILTO_SCHEME = "mailto"
        private const val XREQUEST_SCHEME = "xrequest"
        private const val XLOADCARDS_SCHEME = "xloadcards"
        private const val XSHARE_AS_FILE_SCHEME = "xshareasfile"
        private const val XSHARE_AS_CALENDAR_SCHEME = "xshareascalendar"
        private const val XSHARE_AS_MAIL = "xshareasmail"
        private const val XBARCODE = "xbarcode"
        private const val XSHOW_SOURCE = "xshowsource"

        private val RESULT_DO_NOT_INTERCEPT: WebResourceResponse? = null
        private val RESULT_DUMMY_RESPONSE = WebResourceResponse(null, null, null)
    }
}
