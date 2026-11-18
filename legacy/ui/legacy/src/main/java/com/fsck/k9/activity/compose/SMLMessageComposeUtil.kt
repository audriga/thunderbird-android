package com.fsck.k9.activity.compose

import android.annotation.SuppressLint
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.audriga.h2lj.parser.StructuredDataExtractionUtils
import com.audriga.osmschemaorg.ConvertOsmToSchema
import com.audriga.osmschemaorg.OsmConversionException
import com.fsck.k9.activity.misc.Attachment
import com.fsck.k9.helper.MimeTypeUtil
import com.fsck.k9.ui.sml.SMLCards
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import net.thunderbird.components.core.logging.Logger
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.audriga.hetc.JsonLdDeserializer
import org.audriga.ld2h.MustacheRenderer
import org.json.JSONException
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "SMLMessageComposeUtil"
class SMLMessageComposeUtil(
    private val subjectView: EditText, private val attachmentPresenter: AttachmentPresenter,
    private val messageContentView: EditText,
//    private String smlHTMLEmail = null;
    private val messageContentViewSML: ComposeView, private val smlModeSwitch: MaterialSwitch,
): KoinComponent {
    private val logger: Logger by inject()

    // SML
    //    private String smlJsonLd = null;
    var smlPayload: List<JSONObject>? = null
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
            },
        )
    }

    @SuppressLint("SetTextI18n")
    fun initializePayloadFromIntent(intent: Intent) {
        if (intent.getBooleanExtra(IS_SML, false)) {
            val payload = intent.getStringExtra(SML_PAYLOAD)
            if (payload != null) {
                smlPayload = JsonLdDeserializer.deserialize(payload)
                val types = prepareSmlPayload(smlPayload!!)
                displayCardsAndUpdateSubject(smlPayload!!, types)
            } else {
                messageContentView.visibility = View.GONE
                if (subjectView.getText().isEmpty()) {
                    subjectView.setText("SML Mail")
                }
            }
        }
    }

    fun enrichTextToSmlIfUrl(text: CharSequence) {
        if (Patterns.WEB_URL.matcher(text).matches()) {
            // Input is exactl one url
            enrichSharedUrlToSml(text.toString())
        }
    }

    fun enrichSharedUrlToSml(url: String) {
        enrichSharedOsmPoi(url)
        var data = StructuredDataExtractionUtils.downloadParseAndRefineStructuredData(url, true, true)
        try {
            val ld2hRenderer = MustacheRenderer()
            data = ld2hRenderer.filterRenderable(data)
        } catch (_: IOException) {
        }
        if (data.isNotEmpty()) {
            updateAndDisplayPayload(data)
        }
        // else: No structured data found, todo: treat link as normal?
    }
    private fun enrichSharedOsmPoi(url: String) {
        if (url.toUri().authority?.contains("api.openstreetmap.org") == true) {
            val (pageSrc: String?, contentType: MediaType?, _: String?) = downloadUrl(url)
            if (contentType?.type.equals("application") && contentType?.subtype.equals("json") && pageSrc != null) {
                try {
                    val osmJson = Json.decodeFromString(KotlinxGenericMapSerializer, pageSrc)
                    try {
                        val osmSchema = ConvertOsmToSchema.convertOsmToJsonLd(osmJson, null)
                        val osmSchemaJson = JSONObject(osmSchema)
                        updateAndDisplayPayload(listOf(osmSchemaJson))
                    } catch (e: IllegalStateException) {
                        logger.error(TAG, e) { "OSM conversion failed" }
                    } catch (e: OsmConversionException) {
                        logger.error(TAG, e) { "OSM conversion failed" }
                    }
                } catch (_: SerializationException) {} catch (_: IllegalArgumentException) {}
            }
        }
    }


    fun displaySmlCards(content: @Composable () -> Unit) {
        messageContentView.visibility = View.GONE
        messageContentViewSML.visibility = View.VISIBLE
        messageContentViewSML.setContent(content)
        smlModeSwitch.visibility = View.VISIBLE
        smlModeSwitch.setChecked(true)
    }

    fun handleJsonLdAttachment(attachment: Attachment): Boolean {
        if (MimeTypeUtil.isSameMimeType(attachment.contentType, "application/json") || MimeTypeUtil.isSameMimeType(
                attachment.contentType, "application/json+ld",
            )
        ) {
            val filename = attachment.fileName ?: return false
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
                    val jsonLds: MutableList<JSONObject> = ArrayList(deserialized.size)
                    for (jsonObject in deserialized) {
                        if (isJsonLd(jsonObject)) {
                            jsonLds.add(jsonObject)
                        }
                    }
                    if (jsonLds.isNotEmpty()) {
                        val hadResult = updateAndDisplayPayload(jsonLds)
                        if (hadResult) {
                            attachmentPresenter.onClickRemoveAttachment(attachment.uri)
                            // todo: This function is called in updateAttachmentView, and after that function returns, the
                            //       attachment gets added in the attachments list in AttachmentPresenter.
                            //       so either need to change where this function is called, or add attachements on send
                            return true
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
        return false
    }

    private fun updateAndDisplayPayload(jsonLds: List<JSONObject>): Boolean {
        updatePayload(jsonLds)
        val types = prepareSmlPayload(smlPayload!!)
        val hadResult = displayCardsAndUpdateSubject(smlPayload!!, types)
        return hadResult
    }

    /**
     * Updates the current payload to contain the new jsonLds
     */
    private fun updatePayload(jsonLds: List<JSONObject>) {
        smlPayload = if (smlPayload == null) {
            jsonLds
        } else {
            // Instead of smlPayload.addAll create a new smlPayload list instance.
            listOf(*(smlPayload as List<JSONObject>).toTypedArray(), *jsonLds.toTypedArray())
        }
    }

    private fun displayCardsAndUpdateSubject(smlPayload: List<JSONObject>, types: List<String>): Boolean {
        if (types.isNotEmpty()) {
            displaySmlCards {
                SMLCards(
                    jsonLds = smlPayload,
                    interactive = false,
                    clipboardManager = null,
                    messageReference = null,
                    context = null,
                    onReload = null,
                    logger = logger,
                )
            }
            if (subjectView.getText().isEmpty() || subjectView.getText().toString().startsWith("Check out this ")) {
                val subject = "Check out this " + types.joinToString(", ")
                subjectView.setText(subject)
            }
            return true
        }
        return false
    }

    companion object {
        const val IS_SML: String = "isSML"
        const val SML_PAYLOAD: String = "sml_payload"

        fun isJsonLd(jsonObject: JSONObject): Boolean {
            try {
                val context = jsonObject.getString("@context")
                if (!(context.lowercase(Locale.getDefault())
                        .contains("schema") || context.lowercase(Locale.getDefault()).contains("sml"))
                ) {
                    return false
                }
                val type = jsonObject.getString("@type")
                return type.isNotEmpty()
            } catch (_: JSONException) {
                return false
            }
        }

        /**
         * Inlines images (if not already inlined) via H2LD, and then renders the given payload using LD2H.
         *
         * @param smlPayload schema to render. This is modified if inline is necessary and possible.
         * @return the resulting HTMLs of the LD2H render, together with the schema types.
         */
        private fun prepareSmlPayload(smlPayload: List<JSONObject>): List<String> {
            val types = ArrayList<String>(smlPayload.size)
            for (jsonObject in smlPayload) {
                // For freshly downloaded schema, the image has already been inlined.
                // But inlineImages is smart enough to detect the already inlined image.
                //
                // Since this modifies the actual jsonObject, and we iterate over call-by-reference smlPayload here,
                // this also modifies the smlPayload this function was called wit (which is what we want).
                StructuredDataExtractionUtils.inlineImages(jsonObject)
                val type = jsonObject.optString("@type")
                types.add(type)
            }
            return types
        }
    }
}
// todo unify this with download function in SMLWebViewExtension and move to some different class
private fun downloadUrl(url: String): Triple<String?, MediaType?, String?> {
    val client = OkHttpClient()
    var pageSrc: String? = null
    var contentType: MediaType? = null;
    var okErr: String? = null
    try {
        val request: Request = Request.Builder()
            .url(url)
            .build()
        client.newCall(request).execute().use { response ->
            response.body.contentType()
            pageSrc = response.body.string()
            contentType = response.body.contentType()
        }
    } catch (e: Exception) {
        okErr = e.message
    }
    return Triple(pageSrc, contentType, okErr)
}

object KotlinxGenericMapSerializer : KSerializer<Map<String, Any?>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GenericMap")

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
        val jsonObject = JsonObject(value.mapValues { it.value.toJsonElement()})
        val jsonObjectSerializer = encoder.serializersModule.serializer<JsonObject>()
        jsonObjectSerializer.serialize(encoder, jsonObject)
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("Can only deserialize Json content to generic Map")
        val root = jsonDecoder.decodeJsonElement()
        return if (root is JsonObject) root.toMap() else throw SerializationException("Cannot deserialize Json content to generic Map")
    }

    private fun Any?.toJsonElement(): JsonElement = when(this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> toJsonObject()
        is Iterable<*> -> toJsonArray()
        else -> throw SerializationException("Cannot serialize value type $this")
    }

    private fun Map<*,*>.toJsonObject(): JsonObject = JsonObject(this.entries.associate { it.key.toString() to it.value.toJsonElement() })

    private fun Iterable<*>.toJsonArray(): JsonArray = JsonArray(this.map { it.toJsonElement() })

    private fun JsonElement.toAnyNullableValue(): Any? = when (this) {
        is JsonPrimitive -> toScalarOrNull()
        is JsonObject -> toMap()
        is JsonArray -> toList()
    }

    private fun JsonObject.toMap(): Map<String, Any?> = entries.associate {
        when (val jsonElement = it.value) {
            is JsonPrimitive -> it.key to jsonElement.toScalarOrNull()
            is JsonObject -> it.key to jsonElement.toMap()
            is JsonArray -> it.key to jsonElement.toAnyNullableValueList()
        }
    }

    private fun JsonPrimitive.toScalarOrNull(): Any? = when {
        this is JsonNull -> null
        this.isString -> this.content
        else -> listOfNotNull(booleanOrNull, longOrNull, doubleOrNull).firstOrNull()
    }

    private fun JsonArray.toAnyNullableValueList(): List<Any?> = this.map {
        it.toAnyNullableValue()
    }
}
