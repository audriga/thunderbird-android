package com.fsck.k9.message

//import com.fsck.k9.mail.internet.MimeMessage
import android.R.attr.mimeType
import com.fsck.k9.K9.isHideTimeZone
import com.fsck.k9.helper.toCrLf
import com.fsck.k9.mail.BodyPart
import com.fsck.k9.mail.internet.Headers.contentType
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.message.TextBodyBuilder.HTML_AND_BODY_END
import com.fsck.k9.message.TextBodyBuilder.HTML_AND_BODY_START
import java.io.IOException
import java.util.Date
import org.audriga.hetc.MustacheRenderer
import org.json.JSONException
import org.json.JSONObject

abstract class SmlMessageUtil {
    companion object {
        @JvmStatic
        fun createSMLMessageBuilder(payload: List<JSONObject>, variant: SmlStandardVariant): SmlMessageBuilder {
            return createSMLMessageBuilder(payload, variant, null, null, null)
        }

        @JvmStatic
        fun createSMLMessageBuilder(
            payload: List<JSONObject>,
            variant: SmlStandardVariant,
            htmlBody: String?,
            plainText: String?,
        ): SmlMessageBuilder {
            return createSMLMessageBuilder(payload, variant, htmlBody, plainText, null)
        }

        /***
         * Creates an SmlMessageBuilder, that has the corresponding text, html and if applicable alternate part set
         * with values corresponding to the payload.
         * @param payload the sml payload of the message
         * @param variant if the message should use the legacy sml in html variant, where the sml payload is in the head
         * of the html part, or the dedicated-multipart variant, in which the sml is contained in a separate part of the
         * multipart email.
         * @param htmlBody the html to use for the html part. If set, will be used as-is
         * If not set, will use hetc to create the body from payload.
         * @param plainText the plaintext to be set as the first part of the multipart email. Can currently not create the
         * plaintext from the payload.
         * @param builder if set, will set properties of this builder instead of creating a new one.
         */
        @JvmStatic
        fun createSMLMessageBuilder(
            payload: List<JSONObject>,
            variant: SmlStandardVariant,
            htmlBody: String?,
            plainText: String?,
            builder: SmlMessageBuilder?,
        ): SmlMessageBuilder {
            // If htmlFallback is not given, create it via HETC.
            val encodedJson: String
            val encodedJsonLds = payload.map { it.toString(2) }
            encodedJson = if (encodedJsonLds.size == 1) {
                encodedJsonLds.single()
            } else if (encodedJsonLds.size > 1) {
                "[" + encodedJsonLds.joinToString(",") + "]"
            } else {
                throw IllegalStateException("Payload must not be empty!")
            }
            val htmlFallbackToUse: String
            if (htmlBody != null) {
                htmlFallbackToUse = htmlBody
            } else {
                val hetcRenderer = MustacheRenderer()
                val renderedEmailHTMLs: ArrayList<String> = ArrayList(payload.size)
                for (json in payload) {
                    var hetcResult: String? = null
                    try {
                        hetcResult = hetcRenderer.render(json)
                    } catch (_: IOException) {
                    } catch (_: JSONException) {
                    }
                    if (hetcResult != null) {
                        renderedEmailHTMLs.add(hetcResult)
                    }
                }
                val joinedEmailHTMLRenderResults = renderedEmailHTMLs.joinToString("\n")
                if (variant == SmlStandardVariant.SML_IN_HTML) {
                    val smlScript = "<script type=\"application/ld+json\">$encodedJson</script>"
                    htmlFallbackToUse =
                        "<!DOCTYPE html><html>$smlScript</head><body>$joinedEmailHTMLRenderResults$HTML_AND_BODY_END"
                } else {
                    htmlFallbackToUse = "$HTML_AND_BODY_START$joinedEmailHTMLRenderResults$HTML_AND_BODY_END"
                }
            }
            // todo content based filling of plaintext
            val plainTextToUse = plainText ?: "This email contains SML content"

            val dedicatedJsonMultipart: BodyPart?
            if (variant == SmlStandardVariant.DEDICATED_MULTIPART) {
                val body = TextBody(encodedJson.replace("\r\n", "\n").replace("\n", "\r\n"))
//                body.composedMessageLength = encodedJson.length
//                body.composedMessageOffset = 0
                val contentType = contentType("application/ld+json", "utf-8", null)
                dedicatedJsonMultipart = MimeBodyPart.create(body, contentType)
                dedicatedJsonMultipart.setEncoding("8bit") // todo can't set TextBody to 7bit, could use BinaryMemoryBody to
                // freely set encoding to what we want. In that case it might also make sense to encode in base64, to avoid unwanted linebreaks
            } else {
                dedicatedJsonMultipart = null
            }

            val builderToUse: SmlMessageBuilder = builder ?: SimpleSmlMessageBuilder.newInstance()
            builderToUse
//                .setSubject(Utility.stripNewLines(subjectView.getText().toString()))
                .setSentDate(Date())
                .setHideTimeZone(isHideTimeZone)
//                .setRequestReadReceipt(requestReadReceipt) // todo?
//                .setIdentity(identity) // todo
                .setMessageFormat(SimpleMessageFormat.HTML)
                .setPlainText(plainTextToUse.toCrLf())
                .setHtmlText(htmlFallbackToUse.toCrLf())
            if (variant == SmlStandardVariant.DEDICATED_MULTIPART) {
                builderToUse.setAdditionalAlternatePart(dedicatedJsonMultipart)
            }
            return builderToUse
        }
    }
}

enum class SmlStandardVariant {
    SML_IN_HTML,
    DEDICATED_MULTIPART,
}
