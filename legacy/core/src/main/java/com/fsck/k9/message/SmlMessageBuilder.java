package com.fsck.k9.message;


import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fsck.k9.CoreResourceProvider;
import com.fsck.k9.mail.BodyPart;

import net.thunderbird.core.android.account.Identity;
import net.thunderbird.core.android.account.QuoteStyle;
import com.fsck.k9.K9;
import app.k9mail.legacy.message.controller.MessageReference;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BoundaryGenerator;
import net.thunderbird.core.common.exception.MessagingException;
import com.fsck.k9.mail.internet.MessageIdGenerator;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMessageHelper;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.TextBody;
import com.fsck.k9.message.quote.InsertableHtmlContent;
import net.thunderbird.core.preference.GeneralSettingsManager;


public abstract class SmlMessageBuilder extends MessageBuilder {

    final private GeneralSettingsManager settingsManager;
    private String plainText;
    private String htmlText;
    // For the multipart variant, should we have a jsonld parameter and create the part here
    // or should we get an already created part?
    private BodyPart additionalAlternatePart;


    protected SmlMessageBuilder(MessageIdGenerator messageIdGenerator,
            BoundaryGenerator boundaryGenerator,
        CoreResourceProvider resourceProvider,
        GeneralSettingsManager settingsManager
    ) {
        super(messageIdGenerator, boundaryGenerator, resourceProvider, settingsManager);
        this.settingsManager = settingsManager;
    }

    /**
     * Build the message to be sent (or saved). If there is another message quoted in this one, it will be baked
     * into the message here.
     */
    @Override
    protected MimeMessage build() throws MessagingException {
        //FIXME: check arguments

        MimeMessage message = MimeMessage.create();

        buildHeader(message);
        buildBody(message);

        return message;
    }

    // todo: instead of a copy, extend the other message builder
    private void buildBody(MimeMessage message) throws MessagingException {
        // Build the body.

        TextBody bodyPlain;// = buildText(isDraft);

        final boolean hasAttachments = attachments != null && !attachments.isEmpty();

        // HTML message (with alternative text part)

        // This is the compiled MIME part for an HTML message.
        MimeMultipart composedMimeMessage = createMimeMultipart();
        composedMimeMessage.setSubType("alternative");
        // Let the receiver select either the text or the HTML part.
        bodyPlain = buildText(isDraft, SimpleMessageFormat.TEXT);
        composedMimeMessage.addBodyPart(MimeBodyPart.create(bodyPlain, "text/plain"));
        TextBody bodyHTML;
        if (htmlText != null && !htmlText.isEmpty()) {
            bodyHTML = buildText(isDraft, SimpleMessageFormat.HTML);
            MimeBodyPart htmlPart = MimeBodyPart.create(bodyHTML, "text/html");
            if (inlineAttachments != null && !inlineAttachments.isEmpty()) {
                MimeMultipart htmlPartWithInlineImages = new MimeMultipart("multipart/related",
                    boundaryGenerator.generateBoundary());
                htmlPartWithInlineImages.addBodyPart(htmlPart);
                addInlineAttachmentsToMessage(htmlPartWithInlineImages);
                composedMimeMessage.addBodyPart(MimeBodyPart.create(htmlPartWithInlineImages));
            } else {
                composedMimeMessage.addBodyPart(htmlPart);
            }
        } else {
            bodyHTML = null;
        }
        if (additionalAlternatePart != null) {
            composedMimeMessage.addBodyPart(additionalAlternatePart);
        }

        if (hasAttachments) {
            // If we're HTML and have attachments, we have a MimeMultipart container to hold the
            // whole message (mp here), of which one part is a MimeMultipart container
            // (composedMimeMessage) with the user's composed messages, and subsequent parts for
            // the attachments.
            MimeMultipart mp = createMimeMultipart();
            mp.addBodyPart(MimeBodyPart.create(composedMimeMessage));
            addAttachmentsToMessage(mp);
            MimeMessageHelper.setBody(message, mp);
        } else {
            // If no attachments, our multipart/alternative part is the only one we need.
            MimeMessageHelper.setBody(message, composedMimeMessage);
        }

        // If this is a draft, add metadata for thawing.
        if (isDraft) {
            // Add the identity to the message.
            if (bodyHTML != null) {
                message.addHeader(K9.IDENTITY_HEADER, buildIdentityHeader(bodyHTML, bodyPlain));
            } else {
                message.addHeader(K9.IDENTITY_HEADER, buildIdentityHeader(bodyPlain));
            }
        }
    }

    private String buildIdentityHeader(TextBody bodyPlain) {
        return new IdentityHeaderBuilder()
            .setCursorPosition(cursorPosition)
            .setIdentity(identity)
            .setIdentityChanged(identityChanged)
            .setMessageFormat(messageFormat)
            .setMessageReference(messageReference)
            .setQuotedHtmlContent(quotedHtmlContent)
            .setQuoteStyle(quoteStyle)
            .setQuoteTextMode(quotedTextMode)
            .setSignature(signature)
            .setSignatureChanged(signatureChanged)
            .setBodyPlain(bodyPlain)
            .build();
    }

    private String buildIdentityHeader(TextBody body, TextBody bodyPlain) {
        return new IdentityHeaderBuilder()
                .setCursorPosition(cursorPosition)
                .setIdentity(identity)
                .setIdentityChanged(identityChanged)
                .setMessageFormat(messageFormat)
                .setMessageReference(messageReference)
                .setQuotedHtmlContent(quotedHtmlContent)
                .setQuoteStyle(quoteStyle)
                .setQuoteTextMode(quotedTextMode)
                .setSignature(signature)
                .setSignatureChanged(signatureChanged)
                .setBody(body)
                .setBodyPlain(bodyPlain)
                .build();
    }

    /**
     * Build the Body that will contain the text of the message. We'll decide where to
     * include it later. Draft messages are treated somewhat differently in that signatures are not
     * appended and HTML separators between composed text and quoted text are not added.
     * @param isDraft If we should build a message that will be saved as a draft (as opposed to sent).
     */
    private TextBody buildText(boolean isDraft) {
        return buildText(isDraft, messageFormat);
    }

    /**
     * Build the {@link Body} that will contain the text of the message.
     *
     * <p>
     * Draft messages are treated somewhat differently in that signatures are not appended and HTML
     * separators between composed text and quoted text are not added.
     * </p>
     *
     * @param isDraft
     *         If {@code true} we build a message that will be saved as a draft (as opposed to
     *         sent).
     * @param simpleMessageFormat
     *         Specifies what type of message to build ({@code text/plain} vs. {@code text/html}).
     *
     * @return {@link TextBody} instance that contains the entered text and possibly the quoted
     *         original message.
     */
    private TextBody buildText(boolean isDraft, SimpleMessageFormat simpleMessageFormat) {
        TextBodyBuilder textBodyBuilder;
        if (simpleMessageFormat == SimpleMessageFormat.TEXT) {
            textBodyBuilder = new TextBodyBuilder(plainText, settingsManager);
        } else {
            // don't care about quoted/ signature etc right now, and would need to edit TextBodyBuilder, to not break HTML,
            // if we wanted to use it as it has been.
            return new TextBody(htmlText);
        }

        /*
         * Find out if we need to include the original message as quoted text.
         *
         * We include the quoted text in the body if the user didn't choose to
         * hide it. We always include the quoted text when we're saving a draft.
         * That's so the user is able to "un-hide" the quoted text if (s)he
         * opens a saved draft.
         */
        boolean includeQuotedText = (isDraft || quotedTextMode == QuotedTextMode.SHOW);
        boolean isReplyAfterQuote = (quoteStyle == QuoteStyle.PREFIX && this.isReplyAfterQuote);

        textBodyBuilder.setIncludeQuotedText(false);
        if (includeQuotedText) {
            if (simpleMessageFormat == SimpleMessageFormat.HTML && quotedHtmlContent != null) {
                textBodyBuilder.setIncludeQuotedText(true);
                textBodyBuilder.setQuotedTextHtml(quotedHtmlContent);
                textBodyBuilder.setReplyAfterQuote(isReplyAfterQuote);
            }

            if (simpleMessageFormat == SimpleMessageFormat.TEXT && quotedText.length() > 0) {
                textBodyBuilder.setIncludeQuotedText(true);
                textBodyBuilder.setQuotedText(quotedText);
                textBodyBuilder.setReplyAfterQuote(isReplyAfterQuote);
            }
        }

        textBodyBuilder.setInsertSeparator(!isDraft);

        boolean useSignature = (!isDraft && identity.getSignatureUse());
        if (useSignature) {
            textBodyBuilder.setAppendSignature(true);
            textBodyBuilder.setSignature(signature);
            textBodyBuilder.setSignatureBeforeQuotedText(isSignatureBeforeQuotedText);
        } else {
            textBodyBuilder.setAppendSignature(false);
        }

        TextBody body;
        if (simpleMessageFormat == SimpleMessageFormat.HTML) {
            body = textBodyBuilder.buildTextHtml();
        } else {
            body = textBodyBuilder.buildTextPlain();
        }
        return body;
    }

    @Override
    public SmlMessageBuilder setSubject(String subject) {
        super.setSubject(subject);
        return this;
    }

    @Override
    public SmlMessageBuilder setSentDate(Date sentDate) {
        super.setSentDate(sentDate);
        return this;
    }

    @Override
    public SmlMessageBuilder setHideTimeZone(boolean hideTimeZone) {
        super.setHideTimeZone(hideTimeZone);
        return this;
    }

    @Override
    public SmlMessageBuilder setTo(List<Address> to) {
        super.setTo(to);
        return this;
    }

    @Override
    public SmlMessageBuilder setCc(List<Address> cc) {
        super.setCc(cc);
        return this;
    }

    @Override
    public SmlMessageBuilder setBcc(List<Address> bcc) {
        super.setBcc(bcc);
        return this;
    }

    @Override
    public SmlMessageBuilder setReplyTo(Address[] replyTo) {
        super.setReplyTo(replyTo);
        return this;
    }

    @Override
    public SmlMessageBuilder setInReplyTo(String inReplyTo) {
        super.setInReplyTo(inReplyTo);
        return this;
    }

    @Override
    public SmlMessageBuilder setReferences(String references) {
        super.setReferences(references);
        return this;
    }

    @Override
    public SmlMessageBuilder setRequestReadReceipt(boolean requestReadReceipt) {
        super.setRequestReadReceipt(requestReadReceipt);
        return this;
    }

    @Override
    public SmlMessageBuilder setIdentity(Identity identity) {
        this.identity = identity;
        return this;
    }

    @Override
    public SmlMessageBuilder setMessageFormat(SimpleMessageFormat messageFormat) {
        this.messageFormat = messageFormat;
        return this;
    }

    @Override
    public SmlMessageBuilder setText(String text) {
        this.plainText = text;
        return this;
    }
    public SmlMessageBuilder setPlainText(String plainText) {
        this.plainText = plainText;
        return this;
    }

    public SmlMessageBuilder setHtmlText(String htmlText) {
        this.htmlText = htmlText;
        return this;
    }

    public SmlMessageBuilder setAdditionalAlternatePart(BodyPart additionalAlternatePart) {
        this.additionalAlternatePart = additionalAlternatePart;
        return this;
    }

    @Override
    public SmlMessageBuilder setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
        return this;
    }

    @Override
    public SmlMessageBuilder setInlineAttachments(Map<String, Attachment> attachments) {
        this.inlineAttachments = attachments;
        return this;
    }

    @Override
    public SmlMessageBuilder setSignature(String signature) {
        this.signature = signature;
        return this;
    }

    @Override
    public SmlMessageBuilder setQuoteStyle(QuoteStyle quoteStyle) {
        this.quoteStyle = quoteStyle;
        return this;
    }

    @Override
    public SmlMessageBuilder setQuotedTextMode(QuotedTextMode quotedTextMode) {
        this.quotedTextMode = quotedTextMode;
        return this;
    }

    @Override
    public SmlMessageBuilder setQuotedText(String quotedText) {
        this.quotedText = quotedText;
        return this;
    }

    @Override
    public SmlMessageBuilder setQuotedHtmlContent(InsertableHtmlContent quotedHtmlContent) {
        this.quotedHtmlContent = quotedHtmlContent;
        return this;
    }

    @Override
    public SmlMessageBuilder setReplyAfterQuote(boolean isReplyAfterQuote) {
        this.isReplyAfterQuote = isReplyAfterQuote;
        return this;
    }

    @Override
    public SmlMessageBuilder setSignatureBeforeQuotedText(boolean isSignatureBeforeQuotedText) {
        this.isSignatureBeforeQuotedText = isSignatureBeforeQuotedText;
        return this;
    }

    @Override
    public SmlMessageBuilder setIdentityChanged(boolean identityChanged) {
        this.identityChanged = identityChanged;
        return this;
    }

    @Override
    public SmlMessageBuilder setSignatureChanged(boolean signatureChanged) {
        this.signatureChanged = signatureChanged;
        return this;
    }

    @Override
    public SmlMessageBuilder setCursorPosition(int cursorPosition) {
        this.cursorPosition = cursorPosition;
        return this;
    }

    @Override
    public SmlMessageBuilder setMessageReference(MessageReference messageReference) {
        this.messageReference = messageReference;
        return this;
    }

    @Override
    public SmlMessageBuilder setDraft(boolean isDraft) {
        this.isDraft = isDraft;
        return this;
    }

    @Override
    public SmlMessageBuilder setIsPgpInlineEnabled(boolean isPgpInlineEnabled) {
        super.setIsPgpInlineEnabled(isPgpInlineEnabled);
        return this;
    }

}
