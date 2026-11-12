package com.fsck.k9.activity.compose

import android.annotation.SuppressLint
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.EditText
import com.audriga.h2lj.parser.StructuredDataExtractionUtils
import com.fsck.k9.activity.misc.Attachment
import com.fsck.k9.helper.MimeTypeUtil
import com.fsck.k9.sml.SMLUtil.css
import com.fsck.k9.sml.SMLUtil.getButtons
import com.fsck.k9.view.MessageWebView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import kotlin.Boolean
import kotlin.CharSequence
import kotlin.Int
import kotlin.also
import org.audriga.hetc.JsonLdDeserializer
import org.audriga.ld2h.MustacheRenderer
import org.json.JSONException
import org.json.JSONObject

//import android.os.Bundle;
class SMLMessageComposeUtil(
    private val subjectView: EditText, private val attachmentPresenter: AttachmentPresenter,
    private val messageContentView: EditText,
//    private String smlHTMLEmail = null;
    private val messageContentViewSML: MessageWebView, private val smlModeSwitch: MaterialSwitch
) {
    // SML
    //    private String smlJsonLd = null;
    var smlPayload: MutableList<JSONObject>? = null
        private set

    init {
        this.messageContentView.addTextChangedListener(
            object : TextWatcher {
                private var insertedStartIndex = -1
                private var insertedEndIndex = -1

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s == null || count < 2) {
                        insertedStartIndex = -1
                    } else {
                        insertedStartIndex = start
                        insertedEndIndex = start + count
                    }
                }

                override fun afterTextChanged(s: Editable?) {
                    val insertedStartIndex = this.insertedStartIndex
                    val insertedEndIndex = this.insertedEndIndex
                    if (s != null && insertedStartIndex != -1) {
                        val insertedText = s.subSequence(insertedStartIndex, insertedEndIndex)
                        enrichTextToSmlIfUrl(insertedText)
                    }
                }
            }
        )
    }

    @SuppressLint("SetTextI18n")
    fun initializePayloadFromIntent(intent: Intent) {
        if (intent.getBooleanExtra(IS_SML, false)) {
            val payload = intent.getStringExtra(SML_PAYLOAD)
            if (payload != null) {
                smlPayload = JsonLdDeserializer.deserialize(payload)
                val ld2hResult: Ld2hResult = Companion.ld2hRenderSmlPayload(smlPayload!!)
                displayLd2hResultAndUpdateSubject(ld2hResult)
            } else {
                messageContentView.setVisibility(View.GONE)
                if (subjectView.getText().length == 0) {
                    subjectView.setText("SML Mail")
                }
                messageContentViewSML.setVisibility(View.VISIBLE)
                messageContentViewSML.displayHtmlContentWithInlineAttachments("<b>Testing</b>", null, null)
            }
        }
    }

    fun enrichTextToSmlIfUrl(text: CharSequence) {
        if (Patterns.WEB_URL.matcher(text).matches()) {
            // Input is exactl one url
            enrichSharedUrlToSml(text.toString())
        }
    }

    fun enrichSharedUrlToSml(url: String?) {
        var data = StructuredDataExtractionUtils.downloadParseAndRefineStructuredData(url, true, true)
        try {
            val ld2hRenderer = MustacheRenderer()
            data = ld2hRenderer.filterRenderable(data)
        } catch (ignored: IOException) {
        }
        if (!data.isEmpty()) {
            if (smlPayload == null) {
                smlPayload = data
            } else {
                smlPayload!!.addAll(data)
            }
            val ld2hResult: Ld2hResult = Companion.ld2hRenderSmlPayload(smlPayload!!)
            displayLd2hResultAndUpdateSubject(ld2hResult)
        }
        // else: No structured data found, todo: treat link as normal?
    }

    fun displayLd2hResult(ld2hRenderResult: String) {
        val htmlDisplay: String = addHeadToLd2hResult(ld2hRenderResult)
        messageContentView.setVisibility(View.GONE)
        messageContentViewSML.setVisibility(View.VISIBLE)
        messageContentViewSML.displayHtmlContentWithInlineAttachments(htmlDisplay, null, null)
        smlModeSwitch.setVisibility(View.VISIBLE)
        smlModeSwitch.setChecked(true)
    }

    //    public void setSmlCards(Function2<? super Composer, ? super Integer, Unit> content) {
    //        messageContentView.setVisibility(View.GONE);
    //        messageContentViewSML.setVisibility(View.VISIBLE);
    //        messageContentViewSML.setContent(content);
    //        messageContentViewSML.setVisibility(View.VISIBLE);
    //        smlModeSwitch.setVisibility(View.VISIBLE);
    //        smlModeSwitch.setChecked(true);
    //    }
    fun handleJsonLdAttachment(attachment: Attachment): Boolean {
        if (MimeTypeUtil.isSameMimeType(attachment.contentType, "application/json") || MimeTypeUtil.isSameMimeType(
                attachment.contentType, "application/json+ld"
            )
        ) {
            val filename = attachment.fileName
            if (filename == null) {
                return false
            }
            val fl = File(filename)
            try {
                BufferedReader(InputStreamReader(FileInputStream(fl))).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        sb.append(line).append("\n")
                    }
                    val ret = sb.toString()
                    // todo: popup to ask if they want to share as schema, or as attachment
                    val deserialized = JsonLdDeserializer.deserialize(ret)
                    val jsonLds: MutableList<JSONObject> = ArrayList<JSONObject>(deserialized.size)
                    for (jsonObject in deserialized) {
                        if (isJsonLd(jsonObject)) {
                            jsonLds.add(jsonObject)
                        }
                    }
                    if (!jsonLds.isEmpty()) {
                        if (smlPayload == null) {
                            smlPayload = ArrayList<JSONObject>(deserialized.size)
                        }
                        smlPayload!!.addAll(jsonLds)
                        val ld2hResult: Ld2hResult = Companion.ld2hRenderSmlPayload(smlPayload!!)
                        val hadResult = displayLd2hResultAndUpdateSubject(ld2hResult)
                        if (hadResult) {
                            attachmentPresenter.onClickRemoveAttachment(attachment.uri)
                            // todo: This function is called in updateAttachmentView, and after that function returns, the
                            //       attachment gets added in the attachments list in AttachmentPresenter.
                            //       so either need to change where this function is called, or add attachements on send
                            return true
                        }
                    }
                }
            } catch (ignored: IOException) {
            }
        }
        return false
    }

    private fun displayLd2hResultAndUpdateSubject(ld2hResult: Ld2hResult): Boolean {
        if (ld2hResult.renderedDisplayHTMLs.isNotEmpty()) {
            val joinedDisplayHTMLRenderResults = ld2hResult.renderedDisplayHTMLs.joinToString("\n")
            displayLd2hResult(joinedDisplayHTMLRenderResults)
            if (subjectView.getText().isEmpty() || subjectView.getText().toString().startsWith("Check out this ")) {
                val subject = "Check out this " + ld2hResult.types.joinToString(", ")
                subjectView.setText(subject)
            }
            return true
        }
        return false
    }

    internal class Ld2hResult(val renderedDisplayHTMLs: ArrayList<kotlin.String?>, val types: ArrayList<kotlin.String?>)
    companion object {
        const val IS_SML: kotlin.String = "isSML"
        const val SML_PAYLOAD: kotlin.String = "sml_payload"

        fun isJsonLd(jsonObject: JSONObject): Boolean {
            try {
                val context = jsonObject.getString("@context")
                if (!(context.lowercase(Locale.getDefault())
                        .contains("schema") || context.lowercase(Locale.getDefault()).contains("sml"))
                ) {
                    return false
                }
                val type = jsonObject.getString("@type")
                return !type.isEmpty()
            } catch (ignored: JSONException) {
                return false
            }
        }

        private fun addHeadToLd2hResult(ld2hRenderResult: kotlin.String): kotlin.String {
            return css() + ld2hRenderResult
        }

        /**
         * Inlines images (if not already inlined) via H2LD, and then renders the given payload using LD2H.
         *
         * @param smlPayload schema to render. This is modified if inline is necessary and possible.
         * @return the resulting HTMLs of the LD2H render, together with the schema types.
         */
        private fun ld2hRenderSmlPayload(smlPayload: MutableList<JSONObject>): Ld2hResult {
            var ld2hRenderer: MustacheRenderer? = null
            try {
                ld2hRenderer = MustacheRenderer()
            } catch (e: IOException) {
                //throw new RuntimeException(e);
            }
            val renderedDisplayHTMLs = ArrayList<kotlin.String?>(smlPayload.size)
            val types = ArrayList<kotlin.String?>(smlPayload.size)
            for (jsonObject in smlPayload) {
                // For freshly downloaded schema, the image has already been inlined.
                // But inlineImages is smart enough to detect the already inlined image.
                //
                // Since this modifies the actual jsonObject, and we iterate over call-by-reference smlPayload here,
                // this also modifies the smlPayload this function was called wit (which is what we want).
                StructuredDataExtractionUtils.inlineImages(jsonObject)
                val type = jsonObject.optString("@type")
                types.add(type)
                var ld2hRenderResult: kotlin.String? = null
                try {
                    val buttons = getButtons(jsonObject)
                    ld2hRenderResult = if (ld2hRenderer != null) ld2hRenderer.render(jsonObject, buttons) else null
                } catch (e: IOException) {
                    // todo handle
                }
                if (ld2hRenderResult != null) {
                    renderedDisplayHTMLs.add(ld2hRenderResult)
                }
            }
            return Ld2hResult(renderedDisplayHTMLs, types)
        }
    }
}
