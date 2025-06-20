package com.fsck.k9.message

import com.fsck.k9.K9.isHideTimeZone
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.helper.toCrLf
import com.fsck.k9.mail.BodyPart
import com.fsck.k9.mail.internet.MimeMessage
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
        fun createSMLMessage(payload: List<JSONObject>, variant: SmlStandardVariant): MimeMessage {
            return createSMLMessage(payload, variant, null, null)
        }

        @JvmStatic
        fun createSMLMessage(
            payload: List<JSONObject>,
            variant: SmlStandardVariant,
            htmlFallback: String?,
            plainText: String?,
        ): MimeMessage {
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
            if (htmlFallback != null) {
                htmlFallbackToUse = htmlFallback
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
                val body = TextBody(encodedJson)
//                body.composedMessageLength = encodedJson.length
//                body.composedMessageOffset = 0
                dedicatedJsonMultipart = MimeBodyPart(body, "application/ld+json; charset=utf-8")
            } else {
                dedicatedJsonMultipart = null
            }
            val builder = SimpleSmlMessageBuilder.newInstance()
            // todo set from/ to cc bcc, subject
            builder
//                .setSubject(Utility.stripNewLines(subjectView.getText().toString()))
                .setSentDate(Date())
                .setHideTimeZone(isHideTimeZone)
//                .setRequestReadReceipt(requestReadReceipt) // todo?
//                .setIdentity(identity) // todo
                .setMessageFormat(SimpleMessageFormat.HTML)
                .setPlainText(plainTextToUse.toCrLf())
                .setHtmlText(htmlFallbackToUse.toCrLf())
            if (variant == SmlStandardVariant.DEDICATED_MULTIPART) {
                builder.setAdditionalAlternatePart(dedicatedJsonMultipart)
            }
            return builder.build()
        }
    }
}

enum class SmlStandardVariant {
    SML_IN_HTML,
    DEDICATED_MULTIPART,
}
