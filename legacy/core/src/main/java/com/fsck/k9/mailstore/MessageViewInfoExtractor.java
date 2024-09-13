package com.fsck.k9.mailstore;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.fsck.k9.CoreResourceProvider;
import com.fsck.k9.crypto.MessageCryptoStructureDetector;
import com.fsck.k9.helper.ListUnsubscribeHelper;
import com.fsck.k9.helper.UnsubscribeUri;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.internet.MessageExtractor;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mail.internet.Viewable;
import com.fsck.k9.mailstore.CryptoResultAnnotation.CryptoError;
import com.fsck.k9.message.extractors.AttachmentInfoExtractor;
import com.fsck.k9.message.html.HtmlConverter;
import app.k9mail.html.cleaner.HtmlProcessor;
import org.openintents.openpgp.util.OpenPgpUtils;
import timber.log.Timber;
//import app.cash.barber.Barber;
//import com.github.mustachejava.DefaultMustacheFactory;
//import com.github.mustachejava.Mustache;
//import com.github.mustachejava.MustacheFactory;

import static com.fsck.k9.mail.internet.MimeUtility.getHeaderParameter;
import static com.fsck.k9.mail.internet.Viewable.Alternative;
import static com.fsck.k9.mail.internet.Viewable.Html;
import static com.fsck.k9.mail.internet.Viewable.MessageHeader;
import static com.fsck.k9.mail.internet.Viewable.Text;
import static com.fsck.k9.mail.internet.Viewable.Textual;

public class MessageViewInfoExtractor {
    private static final String TEXT_DIVIDER =
            "------------------------------------------------------------------------";
    private static final int TEXT_DIVIDER_LENGTH = TEXT_DIVIDER.length();
    private static final String FILENAME_PREFIX = "----- ";
    private static final int FILENAME_PREFIX_LENGTH = FILENAME_PREFIX.length();
    private static final String FILENAME_SUFFIX = " ";
    private static final int FILENAME_SUFFIX_LENGTH = FILENAME_SUFFIX.length();


    private final AttachmentInfoExtractor attachmentInfoExtractor;
    private final HtmlProcessor htmlProcessor;
    private final CoreResourceProvider resourceProvider;


    MessageViewInfoExtractor(AttachmentInfoExtractor attachmentInfoExtractor, HtmlProcessor htmlProcessor,
            CoreResourceProvider resourceProvider) {
        this.attachmentInfoExtractor = attachmentInfoExtractor;
        this.htmlProcessor = htmlProcessor;
        this.resourceProvider = resourceProvider;
    }

    @WorkerThread
    public MessageViewInfo extractMessageForView(Message message, @Nullable MessageCryptoAnnotations cryptoAnnotations,
            boolean openPgpProviderConfigured) throws MessagingException {
        ArrayList<Part> extraParts = new ArrayList<>();
        Part cryptoContentPart = MessageCryptoStructureDetector.findPrimaryEncryptedOrSignedPart(message, extraParts);

        if (cryptoContentPart == null) {
            if (cryptoAnnotations != null && !cryptoAnnotations.isEmpty()) {
                Timber.e("Got crypto message cryptoContentAnnotations but no crypto root part!");
            }
            MessageViewInfo messageViewInfo = extractSimpleMessageForView(message, message);
            return messageViewInfo.withSubject(message.getSubject(), false);
        }

        boolean isOpenPgpEncrypted = (MessageCryptoStructureDetector.isPartMultipartEncrypted(cryptoContentPart) &&
                        MessageCryptoStructureDetector.isMultipartEncryptedOpenPgpProtocol(cryptoContentPart)) ||
                        MessageCryptoStructureDetector.isPartPgpInlineEncrypted(cryptoContentPart);
        if (!openPgpProviderConfigured && isOpenPgpEncrypted) {
            CryptoResultAnnotation noProviderAnnotation = CryptoResultAnnotation.createErrorAnnotation(
                    CryptoError.OPENPGP_ENCRYPTED_NO_PROVIDER, null);
            return MessageViewInfo.createWithErrorState(message, false)
                    .withCryptoData(noProviderAnnotation, null, null);
        }

        MessageViewInfo messageViewInfo = getMessageContent(message, cryptoAnnotations, extraParts, cryptoContentPart);
        messageViewInfo = extractSubject(messageViewInfo);

        return messageViewInfo;
    }

    private MessageViewInfo extractSubject(MessageViewInfo messageViewInfo) {
        if (messageViewInfo.cryptoResultAnnotation != null && messageViewInfo.cryptoResultAnnotation.isEncrypted()) {
            String protectedSubject = extractProtectedSubject(messageViewInfo);
            if (protectedSubject != null) {
                return messageViewInfo.withSubject(protectedSubject, true);
            }
        }

        return messageViewInfo.withSubject(messageViewInfo.message.getSubject(), false);
    }

    @Nullable
    private String extractProtectedSubject(MessageViewInfo messageViewInfo) {
        String protectedHeadersParam = MimeUtility.getHeaderParameter(
                messageViewInfo.rootPart.getContentType(), "protected-headers");
        String[] protectedSubjectHeader = messageViewInfo.rootPart.getHeader("Subject");

        boolean hasProtectedSubject = "v1".equalsIgnoreCase(protectedHeadersParam) && protectedSubjectHeader.length > 0;
        if (hasProtectedSubject) {
            return MimeUtility.unfoldAndDecode(protectedSubjectHeader[0]);
        }

        return null;
    }

    private MessageViewInfo getMessageContent(Message message, @Nullable MessageCryptoAnnotations cryptoAnnotations,
            ArrayList<Part> extraParts, Part cryptoContentPart) throws MessagingException {
        CryptoResultAnnotation cryptoContentPartAnnotation =
                cryptoAnnotations != null ? cryptoAnnotations.get(cryptoContentPart) : null;
        if (cryptoContentPartAnnotation != null) {
            return extractCryptoMessageForView(message, extraParts, cryptoContentPart, cryptoContentPartAnnotation);
        }

        return extractSimpleMessageForView(message, message);
    }

    private MessageViewInfo extractCryptoMessageForView(Message message,
            ArrayList<Part> extraParts, Part cryptoContentPart, CryptoResultAnnotation cryptoContentPartAnnotation)
            throws MessagingException {
        if (cryptoContentPartAnnotation != null && cryptoContentPartAnnotation.hasReplacementData()) {
            cryptoContentPart = cryptoContentPartAnnotation.getReplacementData();
        }

        List<AttachmentViewInfo> extraAttachmentInfos = new ArrayList<>();
        ViewableExtractedText extraViewable = extractViewableAndAttachments(extraParts, extraAttachmentInfos);

        MessageViewInfo messageViewInfo = extractSimpleMessageForView(message, cryptoContentPart);
        return messageViewInfo.withCryptoData(cryptoContentPartAnnotation, extraViewable.text, extraAttachmentInfos);
    }

    private MessageViewInfo extractSimpleMessageForView(Message message, Part contentPart) throws MessagingException {
        List<AttachmentViewInfo> attachmentInfos = new ArrayList<>();
        ViewableExtractedText viewable = extractViewableAndAttachments(
                Collections.singletonList(contentPart), attachmentInfos);
        AttachmentResolver attachmentResolver = AttachmentResolver.createFromPart(contentPart);
        boolean isMessageIncomplete =
                !message.isSet(Flag.X_DOWNLOADED_FULL) || MessageExtractor.hasMissingParts(message);

        UnsubscribeUri preferredUnsubscribeUri = ListUnsubscribeHelper.INSTANCE.getPreferredListUnsubscribeUri(message);

        return MessageViewInfo.createWithExtractedContent(
                message, contentPart, isMessageIncomplete, viewable.html, attachmentInfos, attachmentResolver,
                preferredUnsubscribeUri);
    }

    private ViewableExtractedText extractViewableAndAttachments(List<Part> parts,
            List<AttachmentViewInfo> attachmentInfos) throws MessagingException {
        ArrayList<Viewable> viewableParts = new ArrayList<>();
        ArrayList<Part> attachments = new ArrayList<>();

        for (Part part : parts) {
            MessageExtractor.findViewablesAndAttachments(part, viewableParts, attachments);
        }

        attachmentInfos.addAll(attachmentInfoExtractor.extractAttachmentInfoForView(attachments));
        return extractTextFromViewables(viewableParts);
    }

    /**
     * Extract the viewable textual parts of a message and return the rest as attachments.
     *
     * @return A {@link ViewableExtractedText} instance containing the textual parts of the message as
     *         plain text and HTML, and a list of message parts considered attachments.
     *
     * @throws com.fsck.k9.mail.MessagingException
     *          In case of an error.
     */
    @VisibleForTesting
    ViewableExtractedText extractTextFromViewables(List<Viewable> viewables)
            throws MessagingException {
        try {
            // Collect all viewable parts

            /*
             * Convert the tree of viewable parts into text and HTML
             */

            // Used to suppress the divider for the first viewable part
            boolean hideDivider = true;

            StringBuilder text = new StringBuilder();
            StringBuilder html = new StringBuilder();

            for (Viewable viewable : viewables) {
                if (viewable instanceof Textual) {
                    // This is either a text/plain or text/html part. Fill the variables 'text' and
                    // 'html', converting between plain text and HTML as necessary.
                    text.append(buildText(viewable, !hideDivider));
                    html.append(buildHtml(viewable, !hideDivider));
                    hideDivider = false;
                } else if (viewable instanceof MessageHeader) {
                    MessageHeader header = (MessageHeader) viewable;
                    Part containerPart = header.getContainerPart();
                    Message innerMessage =  header.getMessage();

                    addTextDivider(text, containerPart, !hideDivider);
                    addMessageHeaderText(text, innerMessage);

                    addHtmlDivider(html, containerPart, !hideDivider);
                    addMessageHeaderHtml(html, innerMessage);

                    hideDivider = true;
                } else if (viewable instanceof Alternative) {
                    // Handle multipart/alternative contents
                    Alternative alternative = (Alternative) viewable;

                    /*
                     * We made sure at least one of text/plain or text/html is present when
                     * creating the Alternative object. If one part is not present we convert the
                     * other one to make sure 'text' and 'html' always contain the same text.
                     */
                    List<Viewable> textAlternative = alternative.getText().isEmpty() ?
                            alternative.getHtml() : alternative.getText();
                    List<Viewable> htmlAlternative = alternative.getHtml().isEmpty() ?
                            alternative.getText() : alternative.getHtml();

                    // Fill the 'text' variable
                    boolean divider = !hideDivider;
                    for (Viewable textViewable : textAlternative) {
                        text.append(buildText(textViewable, divider));
                        divider = true;
                    }

                    // Fill the 'html' variable
                    divider = !hideDivider;
                    for (Viewable htmlViewable : htmlAlternative) {
                        html.append(buildHtml(htmlViewable, divider));
                        divider = true;
                    }
                    hideDivider = false;
                }
            }

            String htmlString = html.toString();
            String sanitizedHtml = htmlProcessor.processForDisplay(htmlString);
            String[] split1 = htmlString.split("<script type=\"application/ld\\+json\">", 2);
            if (split1.length > 1) {
                String partContainingJson = split1[1];
                String[] split2 = partContainingJson.split("</script>", 2);
                if (split2.length > 0) {
                    String jsonld = split2[0];
//                    MustacheFactory mf = new DefaultMustacheFactory();
                    String css = "    <style>\n" +
                        "/* CSS rules covering most types */\n" +
                        "\n" +
                        "* {\n" +
                        "    box-sizing: border-box;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card {\n" +
                        "    /* maximal absoulute card width */\n" +
                        "    max-width: 600px;\n" +
                        "\n" +
                        "    /* round corners*/\n" +
                        "    border-radius: 0.75rem;\n" +
                        "\n" +
                        "    /* padding in the card*/\n" +
                        "    padding: 0.5rem;\n" +
                        "\n" +
                        "    font-family: \"Arial\";\n" +
                        "\n" +
                        "    background: #f1f1f3;\n" +
                        "    box-shadow: 0px 8px 16px 0px rgb(0 0 0 / 3%);\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__header {\n" +
                        "    margin-bottom: 3px;\n" +
                        "    border-bottom-width: 1px;\n" +
                        "    border-bottom-color: #dddddd;\n" +
                        "    border-bottom-style: solid;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "ul.ld-card__breadcrumb {\n" +
                        "    margin: 0;\n" +
                        "    padding: 0;\n" +
                        "\n" +
                        "    list-style: none;\n" +
                        "}\n" +
                        "\n" +
                        "/* Display list items side by side */\n" +
                        "ul.ld-card__breadcrumb li {\n" +
                        "    display: inline;\n" +
                        "    font-size: 14px;\n" +
                        "}\n" +
                        "\n" +
                        "/* Add a slash symbol (/) before/behind each list item */\n" +
                        "ul.ld-card__breadcrumb li+li:before {\n" +
                        "    padding: 8px;\n" +
                        "    color: black;\n" +
                        "    content: \"/\\00a0\";\n" +
                        "}\n" +
                        "\n" +
                        "/* Add a color to all links inside the list */\n" +
                        "ul.ld-card__breadcrumb li a {\n" +
                        "    color: #0275d8;\n" +
                        "    text-decoration: none;\n" +
                        "}\n" +
                        "\n" +
                        "/* Add a color on mouse-over */\n" +
                        "ul.ld-card__breadcrumb li a:hover {\n" +
                        "    color: #01447e;\n" +
                        "    text-decoration: underline;\n" +
                        "}\n" +
                        "\n" +
                        "/* Defines the horizontal row container in which the image and text column are placed =====*/\n" +
                        ".ld-card__row {\n" +
                        "    /*Layout Settings*/\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "\n" +
                        "    /* maximal absoulute card width */\n" +
                        "    max-width: 600px;\n" +
                        "\n" +
                        "    /* maximal absolute card height */\n" +
                        "    /*! max-height: 150px; */\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        ".ld-card__last-column {\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* initial/standard size of the text column (shrinkage still possible)*/\n" +
                        "    flex-basis: 90%;\n" +
                        "\n" +
                        "    /* minimum height , same as the picture box*/\n" +
                        "    min-height: 100px;\n" +
                        "    max-height: 100px;\n" +
                        "\n" +
                        "    /* this property is needed to make the truncating working for the child elements*/\n" +
                        "    min-width: 0;\n" +
                        "\n" +
                        "\n" +
                        "    width: -moz-available;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__first-column {\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to center */\n" +
                        "    justify-content: center;\n" +
                        "\n" +
                        "    min-height: 100px;\n" +
                        "    min-width: 100px;\n" +
                        "\n" +
                        "\n" +
                        "    /* in case of bigger elements in the box, cut off the sides*/\n" +
                        "    overflow: hidden;\n" +
                        "    flex-basis: 23%;\n" +
                        "    width: 90%;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__row img{\n" +
                        "    display: block;\n" +
                        "    max-width: 100px;\n" +
                        "    max-height: 100px;\n" +
                        "    min-width: 100px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__content {\n" +
                        "\n" +
                        "    /* Due to a paragraph class this properties are marked as important */\n" +
                        "\n" +
                        "    margin-top: 1px !important;\n" +
                        "    margin-bottom: 2px !important;\n" +
                        "    margin-left: 5% !important;\n" +
                        "    margin-right: 5% !important;\n" +
                        "    font-size: 0.86rem !important;\n" +
                        "    padding-bottom: 1px !important;\n" +
                        "\n" +
                        "\n" +
                        "    /* this is for truncating multiline texts*/\n" +
                        "\n" +
                        "\n" +
                        "    display: -webkit-box !important;\n" +
                        "    -webkit-box-orient: vertical !important;\n" +
                        "    -webkit-line-clamp: 4 !important;\n" +
                        "    overflow: hidden !important;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__title {\n" +
                        "    margin: 4px;\n" +
                        "    margin-left: 5%;\n" +
                        "\n" +
                        "\n" +
                        "    font-size: 1.1rem;\n" +
                        "    font-weight: bold;\n" +
                        "    min-height: 20%;\n" +
                        "\n" +
                        "    /* settings for truncating single line text */\n" +
                        "\n" +
                        "    max-width: 90%;\n" +
                        "    text-overflow: ellipsis;\n" +
                        "    white-space: nowrap;\n" +
                        "    overflow: hidden;\n" +
                        "}\n" +
                        "\n" +
                        "/* Only used in slim template for now */\n" +
                        ".ld-card__footnote {\n" +
                        "    padding-left: 15%;\n" +
                        "    color: #6e6b80;\n" +
                        "    font-size: 0.8rem;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__footer {\n" +
                        "    max-height: 50px;\n" +
                        "    margin-top: 3px;\n" +
                        "\n" +
                        "    border-top-width: 1px;\n" +
                        "    border-top-color: #dddddd;\n" +
                        "    border-top-style: solid;\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: wrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-end;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-end;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__action-button {\n" +
                        "    transition-duration: 0.4s;\n" +
                        "\n" +
                        "    background-color: #e8e3e3;\n" +
                        "    border: #cccccc;\n" +
                        "    border-width: 2px;\n" +
                        "    border-radius: 0.3rem;\n" +
                        "    color: black;\n" +
                        "    padding: 02px 8px;\n" +
                        "\n" +
                        "    margin-top: 3px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "    margin-right: 5px;\n" +
                        "    margin-left: 5px;\n" +
                        "\n" +
                        "    text-align: center;\n" +
                        "    text-decoration: none;\n" +
                        "    display: inline-block;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__action-button:hover {\n" +
                        "    background-color: #4CAF50;\n" +
                        "    color: #01447e;\n" +
                        "    text-decoration: underline;\n" +
                        "}\n" +
                        "\n" +
                        "/* =====  background image card  ========*/\n" +
                        "\n" +
                        ".ld-card_background-image{\n" +
                        "    /* maximal absoulute card width */\n" +
                        "    max-width: 600px;\n" +
                        "\n" +
                        "    /* round corners*/\n" +
                        "    border-radius: 0.75rem;\n" +
                        "\n" +
                        "    /* padding in the card*/\n" +
                        "    padding: 0.5rem;\n" +
                        "\n" +
                        "    font-family: \"Arial\";\n" +
                        "\n" +
                        "    background: #f1f1f3;\n" +
                        "    box-shadow: 0px 8px 16px 0px rgb(0 0 0 / 3%);\n" +
                        "\n" +
                        "\n" +
                        "    border-style: groove;\n" +
                        "    border-width: 2px;\n" +
                        "    background-position:top center;\n" +
                        "    background-size:cover;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__last-column_background-image{\n" +
                        "    /*\n" +
                        "    Actually these two are the only two additional attributes compared to normal text_column\n" +
                        "    BEGIN   ============================ BEGIN */\n" +
                        "\n" +
                        "    background: rgba(0, 0, 0, 0.5);\n" +
                        "    color: #f1f1f1;\n" +
                        "\n" +
                        "    /*\n" +
                        "    END     ============================ END */\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* initial/standard size of the text column (shrinkage still possible)*/\n" +
                        "    flex-basis: 90%;\n" +
                        "\n" +
                        "    /* minimum height , same as the picture box*/\n" +
                        "    min-height: 100px;\n" +
                        "    max-height: 100px;\n" +
                        "\n" +
                        "    /* this property is needed to make the truncating working for the child elements*/\n" +
                        "    min-width: 0;\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "/* ======  slim default card  ====== */\n" +
                        "\n" +
                        ".ld-card__last-column_slim {\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* initial/standard size of the text column (shrinkage still possible)*/\n" +
                        "    flex-basis: 90%;\n" +
                        "\n" +
                        "    /* minimum height , same as the picture box*/\n" +
                        "    min-height: 0px;\n" +
                        "    max-height: 100px;\n" +
                        "\n" +
                        "    /* this property is needed to make the truncating working for the child elements*/\n" +
                        "    min-width: 0;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "/* Responsive behavior */\n" +
                        "\n" +
                        "\n" +
                        "@media (max-width: 550px) {\n" +
                        "    .ld-card__row {\n" +
                        "      flex-direction: column;\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "/* ======  component promo cards  ========*/\n" +
                        "/* CSS rules specific to PromotionCard types */\n" +
                        "\n" +
                        ".ld-card__promotion-card {\n" +
                        "\n" +
                        "    /* round corners*/\n" +
                        "    border-radius: 0.75rem;\n" +
                        "\n" +
                        "    border-style: groove;\n" +
                        "    border-color: #d8d7e0;\n" +
                        "    border-width: 2px;\n" +
                        "\n" +
                        "    /* padding in the card*/\n" +
                        "    padding: 0.1rem;\n" +
                        "\n" +
                        "    font-family: \"Arial\";\n" +
                        "\n" +
                        "    background: #f1f1f3;\n" +
                        "    box-shadow: 0px 8px 16px 0px rgb(0 0 0 / 3%);\n" +
                        "\n" +
                        "    /*Layout Settings*/\n" +
                        "\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: center;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "\n" +
                        "    /* maximal absoulute card width\n" +
                        "\n" +
                        "    */\n" +
                        "    max-width: 100px;\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "    margin-left: 1% ;\n" +
                        "    margin-right: 1%;\n" +
                        "\n" +
                        "    /* maximal absolute card height\n" +
                        "\n" +
                        "    */\n" +
                        "    max-height: 200px;\n" +
                        "}\n" +
                        ".ld-card__img_promotion-card {\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "    border-radius: 0.75rem;\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to center */\n" +
                        "    justify-content: center;\n" +
                        "\n" +
                        "    min-height: 100px;\n" +
                        "    min-width: 100px;\n" +
                        "    max-width: 100px;\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "    /* in case of bigger elements in the box, cut off the sides*/\n" +
                        "    overflow: hidden;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__img_promotion-card img{\n" +
                        "\n" +
                        "    padding: 2px;\n" +
                        "    display: block;\n" +
                        "    max-width: 100px;\n" +
                        "    max-height: 100px;\n" +
                        "    min-width: 100px;\n" +
                        "    overflow: hidden;\n" +
                        "    font-size: 10px;\n" +
                        "    border-radius: 0.75rem;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-card-text {\n" +
                        "\n" +
                        "    width: 100%;\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-card-headline {\n" +
                        "    margin: 0;\n" +
                        "    margin-bottom: 5%;\n" +
                        "    margin-top: 5%;\n" +
                        "    margin-left: 10%;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-card-price {\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-card-previous-price {\n" +
                        "    text-decoration:line-through;\n" +
                        "    margin-right: 10%;\n" +
                        "    margin-left: 10%;\n" +
                        "}\n" +
                        "\n" +
                        "p.ld-card__promotion-card-previous-price {\n" +
                        "    font-size: 15px;\n" +
                        "    margin-top: 0px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-card-discount-price {\n" +
                        "    margin-left: 10%;\n" +
                        "}\n" +
                        "\n" +
                        "p.ld-card__promotion-card-discount-price {\n" +
                        "    font-size: 15px;\n" +
                        "    margin-top: 0px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__row_promotion-cards {\n" +
                        "    /*Layout Settings*/\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "\n" +
                        "    /* maximal absoulute card width */\n" +
                        "    max-width: 600px;\n" +
                        "\n" +
                        "    /* maximal absolute card height */\n" +
                        "    max-height: 150px;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "/* Defines the alternative horizontal row container which is used for the promotion tabs/cards */\n" +
                        ".ld-card__last-column_promotion-cards {\n" +
                        "    /*Layout Settings*/\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "\n" +
                        "    /* maximal absoulute card width */\n" +
                        "    max-width: 600px;\n" +
                        "\n" +
                        "    /* maximal absolute card height */\n" +
                        "    max-height: 150px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__mid-column_promotion-cards {\n" +
                        "    max-width: 150px;\n" +
                        "    min-height: 150px;\n" +
                        "\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: space-around;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: flex-start;\n" +
                        "\n" +
                        "    /* positioning in a html document*/\n" +
                        "    position: relative;\n" +
                        "\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__title_promotion-cards {\n" +
                        "    margin-top: 0px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__content--promotion-cards {\n" +
                        "    margin-top: 0px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__promotion-code--promotion-cards {\n" +
                        "    margin-top: 0px;\n" +
                        "    margin-bottom: 0px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__first-column_promotion-cards {\n" +
                        "\n" +
                        "    /* declaring the card class to a flex-contatiner */\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to center */\n" +
                        "    justify-content: center;\n" +
                        "\n" +
                        "    min-height: 100px;\n" +
                        "    min-width: 100px;\n" +
                        "    max-width: 100px;\n" +
                        "\n" +
                        "    /* in case of bigger elements in the box, cut off the sides*/\n" +
                        "    overflow: hidden;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "@media (max-width: 550px) {\n" +
                        "\n" +
                        "    .ld-card__row_promotion-cards{\n" +
                        "        flex-direction:column;\n" +
                        "        max-height: unset;\n" +
                        "        min-height: unset;\n" +
                        "    }\n" +
                        "\n" +
                        "    .ld-card__first-column_promotion-cards {\n" +
                        "        align-items: center;\n" +
                        "        max-width: unset;\n" +
                        "        width: 100%;\n" +
                        "        }\n" +
                        "\n" +
                        "    .ld-card__mid-column_promotion-cards {\n" +
                        "        max-width: unset;\n" +
                        "        align-items: flex-start;\n" +
                        "        align-items: center;\n" +
                        "        min-height: unset;\n" +
                        "        width: 100%;\n" +
                        "        }\n" +
                        "    .ld-card__last-column_promotion-cards {\n" +
                        "        justify-content: flex-start;\n" +
                        "        justify-content: center;\n" +
                        "        width: 100%;\n" +
                        "        }\n" +
                        "\n" +
                        "    .ld-card__first-column_promotion-cards {\n" +
                        "        max-width: unset;\n" +
                        "        width: 100%;\n" +
                        "        }\n" +
                        "\n" +
                        "    .ld-card__promotion-code--promotion-cards {\n" +
                        "        margin-bottom: 0px;\n" +
                        "        margin-bottom: 11px;\n" +
                        "        }\n" +
                        "}\n" +
                        "/* CSS rules specific to Reservation types */\n" +
                        "\n" +
                        ".ld-card__row_reservations{\n" +
                        "\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "/* Style the tab */\n" +
                        ".ld-card__header_reservations {\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    justify-content: flex-start;\n" +
                        "\n" +
                        "    overflow: hidden;\n" +
                        "\n" +
                        "    background-color: #f1f1f1;\n" +
                        "\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "/* Style the buttons that are used to open the tab content */\n" +
                        ".ld-card__header_reservations button {\n" +
                        "\n" +
                        "    font-size: 10px;\n" +
                        "    border-radius: 0.25rem;\n" +
                        "\n" +
                        "    background-color: inherit;\n" +
                        "    float: left;\n" +
                        "    border: none;\n" +
                        "    outline: none;\n" +
                        "    cursor: pointer;\n" +
                        "    padding: 4px 14px;\n" +
                        "    transition: 0.3s;\n" +
                        "    margin: 0;\n" +
                        "}\n" +
                        "\n" +
                        "/* Change background color of buttons on hover */\n" +
                        ".ld-card__header_reservations button:hover {\n" +
                        "    background-color: #ddd;\n" +
                        "}\n" +
                        "\n" +
                        "/* Create an active/current tablink class */\n" +
                        ".ld-card__header_reservations button.active {\n" +
                        "    background-color: #ccc;\n" +
                        "}\n" +
                        "\n" +
                        "/* Style the tab content */\n" +
                        ".ld-card__tab-row_reservations {\n" +
                        "    display: none;\n" +
                        "    padding: 3px 2px;\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: space-evenly;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* initial/standard size of the text column (shrinkage still possible)*/\n" +
                        "    flex-basis: 100%;\n" +
                        "\n" +
                        "    /* minimum height , same as the picture box*/\n" +
                        "    min-height: 100px;\n" +
                        "    max-height: 150px;\n" +
                        "\n" +
                        "    max-width: inherit;\n" +
                        "\n" +
                        "\n" +
                        "}\n" +
                        "/*   A specific row for flight reservations */\n" +
                        ".ld-card__row_flight-reservations {\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "\n" +
                        "    /* setting the alignment of the childs to vertical row layout*/\n" +
                        "    flex-direction: row;\n" +
                        "\n" +
                        "    /* the items in the container are able to wrap, works like a line break */\n" +
                        "    flex-wrap: nowrap;\n" +
                        "\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: space-evenly;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "\n" +
                        "    /* initial/standard size of the text column (shrinkage still possible)*/\n" +
                        "    flex-basis: 100%;\n" +
                        "\n" +
                        "    /* minimum height , same as the picture box*/\n" +
                        "    min-height: 100px;\n" +
                        "    max-height: 150px;\n" +
                        "\n" +
                        "    /* this property is needed to make the truncating working for the child elements*/\n" +
                        "    min-width: 0;\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__first-column_flight-reservations {\n" +
                        "    max-width: 180px;\n" +
                        "    overflow-wrap: break-word;\n" +
                        "    padding: 10px;\n" +
                        "    text-align: right;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__mid-column_flight-reservations {\n" +
                        "\n" +
                        "    display: flex;\n" +
                        "    flex-direction: column;\n" +
                        "\n" +
                        "    overflow-wrap: break-word;\n" +
                        "    padding: 10px;\n" +
                        "    /* align the items horizontally in the cointainer to left side (flex-start) */\n" +
                        "    justify-content: center;\n" +
                        "\n" +
                        "    /* align the items vertically in the center */\n" +
                        "    align-items: center;\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        ".ld-card__last-column_flight-reservations {\n" +
                        "    max-width: 180px;\n" +
                        "    overflow-wrap: break-word;\n" +
                        "    padding: 10px;\n" +
                        "}\n" +
                        "\n" +
                        ".ld-card__img_flight-reservations {\n" +
                        "    padding-bottom: 4px;\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "/* Responsiveness for mobile devices */\n" +
                        "\n" +
                        "@media (max-width: 550px) {\n" +
                        "\n" +
                        "    .ld-card__row_flight-reservations {\n" +
                        "        flex-direction: column;\n" +
                        "        max-height: unset;\n" +
                        "        align-items: unset;\n" +
                        "    }\n" +
                        "\n" +
                        "    .ld-card__first-column_flight-reservations {\n" +
                        "    \n" +
                        "    \n" +
                        "        max-width: unset;\n" +
                        "        width: -moz-available;\n" +
                        "        display: flex;\n" +
                        "\n" +
                        "        flex-flow: column;\n" +
                        "        align-items: center;\n" +
                        "        border-bottom: darkgray;\n" +
                        "        border-bottom-style: groove;\n" +
                        "        border-bottom-width: thin;\n" +
                        "        justify-content: space-around;\n" +
                        "        }\n" +
                        "\n" +
                        "    .ld-card__last-column_flight-reservations {\n" +
                        "        \n" +
                        "        display: flex;\n" +
                        "        max-width: unset;\n" +
                        "        width: -moz-available;\n" +
                        "        \n" +
                        "             \n" +
                        "        overflow-wrap: break-word;\n" +
                        "        padding: 10px;\n" +
                        "        text-align: right;\n" +
                        "        display: flex;\n" +
                        "        flex-flow: column;\n" +
                        "        align-items: center;\n" +
                        "        border-top: darkgray;\n" +
                        "        border-top-style: groove;\n" +
                        "        border-top-width: thin;\n" +
                        "        justify-content: space-around;\n" +
                        "        }\n" +
                        "\n" +
                        "    .ld-card__tab-row_flight-reservations {\n" +
                        "        \n" +
                        "        max-height: unset;\n" +
                        "        flex-direction: column;\n" +
                        "        }\n" +
                        "        \n" +
                        "    .ld-card__mid-column_flight-reservations {\n" +
                        "        display: flex;\n" +
                        "        flex-direction: row;\n" +
                        "        width: -moz-available;\n" +
                        "        justify-content: center;\n" +
                        "        }\n" +
                        "        \n" +
                        "      \n" +
                        "    .ld-card__img_flight-reservations {\n" +
                        "        font-size: 2em!important;\n" +
                        "        padding-bottom: 0;\n" +
                        "    }\n" +
                        "\n" +
                        "    .ld-card__img_flight-reservations-nextcloud {\n" +
                        "        width:39px!important;\n" +
                        "        height:39px!important;\n" +
                        "        margin:-4px;\n" +
                        "    }\n" +
                        "\n" +
                        "    .ld-card__tab-row_flight-reservations {\n" +
                        "        overflow: unset !important;\n" +
                        "        padding-left: 10px;\n" +
                        "    }\n" +
                        "}\n" +
                        "    </style>";
                    String snippet1 = "    <div id=\"output-target\"></div>\n" +
                        "    <script type=\"module\">\n" +
                        "        /*!\n" +
                        " * mustache.js - Logic-less {{mustache}} templates with JavaScript\n" +
                        " * http://github.com/janl/mustache.js\n" +
                        " */\n" +
                        "\n" +
                        "var objectToString = Object.prototype.toString;\n" +
                        "var isArray = Array.isArray || function isArrayPolyfill (object) {\n" +
                        "  return objectToString.call(object) === '[object Array]';\n" +
                        "};\n" +
                        "\n" +
                        "function isFunction (object) {\n" +
                        "  return typeof object === 'function';\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * More correct typeof string handling array\n" +
                        " * which normally returns typeof 'object'\n" +
                        " */\n" +
                        "function typeStr (obj) {\n" +
                        "  return isArray(obj) ? 'array' : typeof obj;\n" +
                        "}\n" +
                        "\n" +
                        "function escapeRegExp (string) {\n" +
                        "  return string.replace(/[\\-\\[\\]{}()*+?.,\\\\\\^$|#\\s]/g, '\\\\$&');\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Null safe way of checking whether or not an object,\n" +
                        " * including its prototype, has a given property\n" +
                        " */\n" +
                        "function hasProperty (obj, propName) {\n" +
                        "  return obj != null && typeof obj === 'object' && (propName in obj);\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Safe way of detecting whether or not the given thing is a primitive and\n" +
                        " * whether it has the given property\n" +
                        " */\n" +
                        "function primitiveHasOwnProperty (primitive, propName) {\n" +
                        "  return (\n" +
                        "    primitive != null\n" +
                        "    && typeof primitive !== 'object'\n" +
                        "    && primitive.hasOwnProperty\n" +
                        "    && primitive.hasOwnProperty(propName)\n" +
                        "  );\n" +
                        "}\n" +
                        "\n" +
                        "// Workaround for https://issues.apache.org/jira/browse/COUCHDB-577\n" +
                        "// See https://github.com/janl/mustache.js/issues/189\n" +
                        "var regExpTest = RegExp.prototype.test;\n" +
                        "function testRegExp (re, string) {\n" +
                        "  return regExpTest.call(re, string);\n" +
                        "}\n" +
                        "\n" +
                        "var nonSpaceRe = /\\S/;\n" +
                        "function isWhitespace (string) {\n" +
                        "  return !testRegExp(nonSpaceRe, string);\n" +
                        "}\n" +
                        "\n" +
                        "var entityMap = {\n" +
                        "  '&': '&amp;',\n" +
                        "  '<': '&lt;',\n" +
                        "  '>': '&gt;',\n" +
                        "  '\"': '&quot;',\n" +
                        "  \"'\": '&#39;',\n" +
                        "  '/': '&#x2F;',\n" +
                        "  '`': '&#x60;',\n" +
                        "  '=': '&#x3D;'\n" +
                        "};\n" +
                        "\n" +
                        "function escapeHtml (string) {\n" +
                        "  return String(string).replace(/[&<>\"'`=\\/]/g, function fromEntityMap (s) {\n" +
                        "    return entityMap[s];\n" +
                        "  });\n" +
                        "}\n" +
                        "\n" +
                        "var whiteRe = /\\s*/;\n" +
                        "var spaceRe = /\\s+/;\n" +
                        "var equalsRe = /\\s*=/;\n" +
                        "var curlyRe = /\\s*\\}/;\n" +
                        "var tagRe = /#|\\^|\\/|>|\\{|&|=|!/;\n" +
                        "\n" +
                        "/**\n" +
                        " * Breaks up the given `template` string into a tree of tokens. If the `tags`\n" +
                        " * argument is given here it must be an array with two string values: the\n" +
                        " * opening and closing tags used in the template (e.g. [ \"<%\", \"%>\" ]). Of\n" +
                        " * course, the default is to use mustaches (i.e. mustache.tags).\n" +
                        " *\n" +
                        " * A token is an array with at least 4 elements. The first element is the\n" +
                        " * mustache symbol that was used inside the tag, e.g. \"#\" or \"&\". If the tag\n" +
                        " * did not contain a symbol (i.e. {{myValue}}) this element is \"name\". For\n" +
                        " * all text that appears outside a symbol this element is \"text\".\n" +
                        " *\n" +
                        " * The second element of a token is its \"value\". For mustache tags this is\n" +
                        " * whatever else was inside the tag besides the opening symbol. For text tokens\n" +
                        " * this is the text itself.\n" +
                        " *\n" +
                        " * The third and fourth elements of the token are the start and end indices,\n" +
                        " * respectively, of the token in the original template.\n" +
                        " *\n" +
                        " * Tokens that are the root node of a subtree contain two more elements: 1) an\n" +
                        " * array of tokens in the subtree and 2) the index in the original template at\n" +
                        " * which the closing tag for that section begins.\n" +
                        " *\n" +
                        " * Tokens for partials also contain two more elements: 1) a string value of\n" +
                        " * indendation prior to that tag and 2) the index of that tag on that line -\n" +
                        " * eg a value of 2 indicates the partial is the third tag on this line.\n" +
                        " */\n" +
                        "function parseTemplate (template, tags) {\n" +
                        "  if (!template)\n" +
                        "    return [];\n" +
                        "  var lineHasNonSpace = false;\n" +
                        "  var sections = [];     // Stack to hold section tokens\n" +
                        "  var tokens = [];       // Buffer to hold the tokens\n" +
                        "  var spaces = [];       // Indices of whitespace tokens on the current line\n" +
                        "  var hasTag = false;    // Is there a {{tag}} on the current line?\n" +
                        "  var nonSpace = false;  // Is there a non-space char on the current line?\n" +
                        "  var indentation = '';  // Tracks indentation for tags that use it\n" +
                        "  var tagIndex = 0;      // Stores a count of number of tags encountered on a line\n" +
                        "\n" +
                        "  // Strips all whitespace tokens array for the current line\n" +
                        "  // if there was a {{#tag}} on it and otherwise only space.\n" +
                        "  function stripSpace () {\n" +
                        "    if (hasTag && !nonSpace) {\n" +
                        "      while (spaces.length)\n" +
                        "        delete tokens[spaces.pop()];\n" +
                        "    } else {\n" +
                        "      spaces = [];\n" +
                        "    }\n" +
                        "\n" +
                        "    hasTag = false;\n" +
                        "    nonSpace = false;\n" +
                        "  }\n" +
                        "\n" +
                        "  var openingTagRe, closingTagRe, closingCurlyRe;\n" +
                        "  function compileTags (tagsToCompile) {\n" +
                        "    if (typeof tagsToCompile === 'string')\n" +
                        "      tagsToCompile = tagsToCompile.split(spaceRe, 2);\n" +
                        "\n" +
                        "    if (!isArray(tagsToCompile) || tagsToCompile.length !== 2)\n" +
                        "      throw new Error('Invalid tags: ' + tagsToCompile);\n" +
                        "\n" +
                        "    openingTagRe = new RegExp(escapeRegExp(tagsToCompile[0]) + '\\\\s*');\n" +
                        "    closingTagRe = new RegExp('\\\\s*' + escapeRegExp(tagsToCompile[1]));\n" +
                        "    closingCurlyRe = new RegExp('\\\\s*' + escapeRegExp('}' + tagsToCompile[1]));\n" +
                        "  }\n" +
                        "\n" +
                        "  compileTags(tags || mustache.tags);\n" +
                        "\n" +
                        "  var scanner = new Scanner(template);\n" +
                        "\n" +
                        "  var start, type, value, chr, token, openSection;\n" +
                        "  while (!scanner.eos()) {\n" +
                        "    start = scanner.pos;\n" +
                        "\n" +
                        "    // Match any text between tags.\n" +
                        "    value = scanner.scanUntil(openingTagRe);\n" +
                        "\n" +
                        "    if (value) {\n" +
                        "      for (var i = 0, valueLength = value.length; i < valueLength; ++i) {\n" +
                        "        chr = value.charAt(i);\n" +
                        "\n" +
                        "        if (isWhitespace(chr)) {\n" +
                        "          spaces.push(tokens.length);\n" +
                        "          indentation += chr;\n" +
                        "        } else {\n" +
                        "          nonSpace = true;\n" +
                        "          lineHasNonSpace = true;\n" +
                        "          indentation += ' ';\n" +
                        "        }\n" +
                        "\n" +
                        "        tokens.push([ 'text', chr, start, start + 1 ]);\n" +
                        "        start += 1;\n" +
                        "\n" +
                        "        // Check for whitespace on the current line.\n" +
                        "        if (chr === '\\n') {\n" +
                        "          stripSpace();\n" +
                        "          indentation = '';\n" +
                        "          tagIndex = 0;\n" +
                        "          lineHasNonSpace = false;\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "\n" +
                        "    // Match the opening tag.\n" +
                        "    if (!scanner.scan(openingTagRe))\n" +
                        "      break;\n" +
                        "\n" +
                        "    hasTag = true;\n" +
                        "\n" +
                        "    // Get the tag type.\n" +
                        "    type = scanner.scan(tagRe) || 'name';\n" +
                        "    scanner.scan(whiteRe);\n" +
                        "\n" +
                        "    // Get the tag value.\n" +
                        "    if (type === '=') {\n" +
                        "      value = scanner.scanUntil(equalsRe);\n" +
                        "      scanner.scan(equalsRe);\n" +
                        "      scanner.scanUntil(closingTagRe);\n" +
                        "    } else if (type === '{') {\n" +
                        "      value = scanner.scanUntil(closingCurlyRe);\n" +
                        "      scanner.scan(curlyRe);\n" +
                        "      scanner.scanUntil(closingTagRe);\n" +
                        "      type = '&';\n" +
                        "    } else {\n" +
                        "      value = scanner.scanUntil(closingTagRe);\n" +
                        "    }\n" +
                        "\n" +
                        "    // Match the closing tag.\n" +
                        "    if (!scanner.scan(closingTagRe))\n" +
                        "      throw new Error('Unclosed tag at ' + scanner.pos);\n" +
                        "\n" +
                        "    if (type == '>') {\n" +
                        "      token = [ type, value, start, scanner.pos, indentation, tagIndex, lineHasNonSpace ];\n" +
                        "    } else {\n" +
                        "      token = [ type, value, start, scanner.pos ];\n" +
                        "    }\n" +
                        "    tagIndex++;\n" +
                        "    tokens.push(token);\n" +
                        "\n" +
                        "    if (type === '#' || type === '^') {\n" +
                        "      sections.push(token);\n" +
                        "    } else if (type === '/') {\n" +
                        "      // Check section nesting.\n" +
                        "      openSection = sections.pop();\n" +
                        "\n" +
                        "      if (!openSection)\n" +
                        "        throw new Error('Unopened section \"' + value + '\" at ' + start);\n" +
                        "\n" +
                        "      if (openSection[1] !== value)\n" +
                        "        throw new Error('Unclosed section \"' + openSection[1] + '\" at ' + start);\n" +
                        "    } else if (type === 'name' || type === '{' || type === '&') {\n" +
                        "      nonSpace = true;\n" +
                        "    } else if (type === '=') {\n" +
                        "      // Set the tags for the next time around.\n" +
                        "      compileTags(value);\n" +
                        "    }\n" +
                        "  }\n" +
                        "\n" +
                        "  stripSpace();\n" +
                        "\n" +
                        "  // Make sure there are no open sections when we're done.\n" +
                        "  openSection = sections.pop();\n" +
                        "\n" +
                        "  if (openSection)\n" +
                        "    throw new Error('Unclosed section \"' + openSection[1] + '\" at ' + scanner.pos);\n" +
                        "\n" +
                        "  return nestTokens(squashTokens(tokens));\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Combines the values of consecutive text tokens in the given `tokens` array\n" +
                        " * to a single token.\n" +
                        " */\n" +
                        "function squashTokens (tokens) {\n" +
                        "  var squashedTokens = [];\n" +
                        "\n" +
                        "  var token, lastToken;\n" +
                        "  for (var i = 0, numTokens = tokens.length; i < numTokens; ++i) {\n" +
                        "    token = tokens[i];\n" +
                        "\n" +
                        "    if (token) {\n" +
                        "      if (token[0] === 'text' && lastToken && lastToken[0] === 'text') {\n" +
                        "        lastToken[1] += token[1];\n" +
                        "        lastToken[3] = token[3];\n" +
                        "      } else {\n" +
                        "        squashedTokens.push(token);\n" +
                        "        lastToken = token;\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "\n" +
                        "  return squashedTokens;\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Forms the given array of `tokens` into a nested tree structure where\n" +
                        " * tokens that represent a section have two additional items: 1) an array of\n" +
                        " * all tokens that appear in that section and 2) the index in the original\n" +
                        " * template that represents the end of that section.\n" +
                        " */\n" +
                        "function nestTokens (tokens) {\n" +
                        "  var nestedTokens = [];\n" +
                        "  var collector = nestedTokens;\n" +
                        "  var sections = [];\n" +
                        "\n" +
                        "  var token, section;\n" +
                        "  for (var i = 0, numTokens = tokens.length; i < numTokens; ++i) {\n" +
                        "    token = tokens[i];\n" +
                        "\n" +
                        "    switch (token[0]) {\n" +
                        "      case '#':\n" +
                        "      case '^':\n" +
                        "        collector.push(token);\n" +
                        "        sections.push(token);\n" +
                        "        collector = token[4] = [];\n" +
                        "        break;\n" +
                        "      case '/':\n" +
                        "        section = sections.pop();\n" +
                        "        section[5] = token[2];\n" +
                        "        collector = sections.length > 0 ? sections[sections.length - 1][4] : nestedTokens;\n" +
                        "        break;\n" +
                        "      default:\n" +
                        "        collector.push(token);\n" +
                        "    }\n" +
                        "  }\n" +
                        "\n" +
                        "  return nestedTokens;\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * A simple string scanner that is used by the template parser to find\n" +
                        " * tokens in template strings.\n" +
                        " */\n" +
                        "function Scanner (string) {\n" +
                        "  this.string = string;\n" +
                        "  this.tail = string;\n" +
                        "  this.pos = 0;\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Returns `true` if the tail is empty (end of string).\n" +
                        " */\n" +
                        "Scanner.prototype.eos = function eos () {\n" +
                        "  return this.tail === '';\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Tries to match the given regular expression at the current position.\n" +
                        " * Returns the matched text if it can match, the empty string otherwise.\n" +
                        " */\n" +
                        "Scanner.prototype.scan = function scan (re) {\n" +
                        "  var match = this.tail.match(re);\n" +
                        "\n" +
                        "  if (!match || match.index !== 0)\n" +
                        "    return '';\n" +
                        "\n" +
                        "  var string = match[0];\n" +
                        "\n" +
                        "  this.tail = this.tail.substring(string.length);\n" +
                        "  this.pos += string.length;\n" +
                        "\n" +
                        "  return string;\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Skips all text until the given regular expression can be matched. Returns\n" +
                        " * the skipped string, which is the entire tail if no match can be made.\n" +
                        " */\n" +
                        "Scanner.prototype.scanUntil = function scanUntil (re) {\n" +
                        "  var index = this.tail.search(re), match;\n" +
                        "\n" +
                        "  switch (index) {\n" +
                        "    case -1:\n" +
                        "      match = this.tail;\n" +
                        "      this.tail = '';\n" +
                        "      break;\n" +
                        "    case 0:\n" +
                        "      match = '';\n" +
                        "      break;\n" +
                        "    default:\n" +
                        "      match = this.tail.substring(0, index);\n" +
                        "      this.tail = this.tail.substring(index);\n" +
                        "  }\n" +
                        "\n" +
                        "  this.pos += match.length;\n" +
                        "\n" +
                        "  return match;\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Represents a rendering context by wrapping a view object and\n" +
                        " * maintaining a reference to the parent context.\n" +
                        " */\n" +
                        "function Context (view, parentContext) {\n" +
                        "  this.view = view;\n" +
                        "  this.cache = { '.': this.view };\n" +
                        "  this.parent = parentContext;\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Creates a new context using the given view with this context\n" +
                        " * as the parent.\n" +
                        " */\n" +
                        "Context.prototype.push = function push (view) {\n" +
                        "  return new Context(view, this);\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Returns the value of the given name in this context, traversing\n" +
                        " * up the context hierarchy if the value is absent in this context's view.\n" +
                        " */\n" +
                        "Context.prototype.lookup = function lookup (name) {\n" +
                        "  var cache = this.cache;\n" +
                        "\n" +
                        "  var value;\n" +
                        "  if (cache.hasOwnProperty(name)) {\n" +
                        "    value = cache[name];\n" +
                        "  } else {\n" +
                        "    var context = this, intermediateValue, names, index, lookupHit = false;\n" +
                        "\n" +
                        "    while (context) {\n" +
                        "      if (name.indexOf('.') > 0) {\n" +
                        "        intermediateValue = context.view;\n" +
                        "        names = name.split('.');\n" +
                        "        index = 0;\n" +
                        "\n" +
                        "        /**\n" +
                        "         * Using the dot notion path in `name`, we descend through the\n" +
                        "         * nested objects.\n" +
                        "         *\n" +
                        "         * To be certain that the lookup has been successful, we have to\n" +
                        "         * check if the last object in the path actually has the property\n" +
                        "         * we are looking for. We store the result in `lookupHit`.\n" +
                        "         *\n" +
                        "         * This is specially necessary for when the value has been set to\n" +
                        "         * `undefined` and we want to avoid looking up parent contexts.\n" +
                        "         *\n" +
                        "         * In the case where dot notation is used, we consider the lookup\n" +
                        "         * to be successful even if the last \"object\" in the path is\n" +
                        "         * not actually an object but a primitive (e.g., a string, or an\n" +
                        "         * integer), because it is sometimes useful to access a property\n" +
                        "         * of an autoboxed primitive, such as the length of a string.\n" +
                        "         **/\n" +
                        "        while (intermediateValue != null && index < names.length) {\n" +
                        "          if (index === names.length - 1)\n" +
                        "            lookupHit = (\n" +
                        "              hasProperty(intermediateValue, names[index])\n" +
                        "              || primitiveHasOwnProperty(intermediateValue, names[index])\n" +
                        "            );\n" +
                        "\n" +
                        "          intermediateValue = intermediateValue[names[index++]];\n" +
                        "        }\n" +
                        "      } else {\n" +
                        "        intermediateValue = context.view[name];\n" +
                        "\n" +
                        "        /**\n" +
                        "         * Only checking against `hasProperty`, which always returns `false` if\n" +
                        "         * `context.view` is not an object. Deliberately omitting the check\n" +
                        "         * against `primitiveHasOwnProperty` if dot notation is not used.\n" +
                        "         *\n" +
                        "         * Consider this example:\n" +
                        "         * ```\n" +
                        "         * Mustache.render(\"The length of a football field is {{#length}}{{length}}{{/length}}.\", {length: \"100 yards\"})\n" +
                        "         * ```\n" +
                        "         *\n" +
                        "         * If we were to check also against `primitiveHasOwnProperty`, as we do\n" +
                        "         * in the dot notation case, then render call would return:\n" +
                        "         *\n" +
                        "         * \"The length of a football field is 9.\"\n" +
                        "         *\n" +
                        "         * rather than the expected:\n" +
                        "         *\n" +
                        "         * \"The length of a football field is 100 yards.\"\n" +
                        "         **/\n" +
                        "        lookupHit = hasProperty(context.view, name);\n" +
                        "      }\n" +
                        "\n" +
                        "      if (lookupHit) {\n" +
                        "        value = intermediateValue;\n" +
                        "        break;\n" +
                        "      }\n" +
                        "\n" +
                        "      context = context.parent;\n" +
                        "    }\n" +
                        "\n" +
                        "    cache[name] = value;\n" +
                        "  }\n" +
                        "\n" +
                        "  if (isFunction(value))\n" +
                        "    value = value.call(this.view);\n" +
                        "\n" +
                        "  return value;\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * A Writer knows how to take a stream of tokens and render them to a\n" +
                        " * string, given a context. It also maintains a cache of templates to\n" +
                        " * avoid the need to parse the same template twice.\n" +
                        " */\n" +
                        "function Writer () {\n" +
                        "  this.templateCache = {\n" +
                        "    _cache: {},\n" +
                        "    set: function set (key, value) {\n" +
                        "      this._cache[key] = value;\n" +
                        "    },\n" +
                        "    get: function get (key) {\n" +
                        "      return this._cache[key];\n" +
                        "    },\n" +
                        "    clear: function clear () {\n" +
                        "      this._cache = {};\n" +
                        "    }\n" +
                        "  };\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Clears all cached templates in this writer.\n" +
                        " */\n" +
                        "Writer.prototype.clearCache = function clearCache () {\n" +
                        "  if (typeof this.templateCache !== 'undefined') {\n" +
                        "    this.templateCache.clear();\n" +
                        "  }\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Parses and caches the given `template` according to the given `tags` or\n" +
                        " * `mustache.tags` if `tags` is omitted,  and returns the array of tokens\n" +
                        " * that is generated from the parse.\n" +
                        " */\n" +
                        "Writer.prototype.parse = function parse (template, tags) {\n" +
                        "  var cache = this.templateCache;\n" +
                        "  var cacheKey = template + ':' + (tags || mustache.tags).join(':');\n" +
                        "  var isCacheEnabled = typeof cache !== 'undefined';\n" +
                        "  var tokens = isCacheEnabled ? cache.get(cacheKey) : undefined;\n" +
                        "\n" +
                        "  if (tokens == undefined) {\n" +
                        "    tokens = parseTemplate(template, tags);\n" +
                        "    isCacheEnabled && cache.set(cacheKey, tokens);\n" +
                        "  }\n" +
                        "  return tokens;\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * High-level method that is used to render the given `template` with\n" +
                        " * the given `view`.\n" +
                        " *\n" +
                        " * The optional `partials` argument may be an object that contains the\n" +
                        " * names and templates of partials that are used in the template. It may\n" +
                        " * also be a function that is used to load partial templates on the fly\n" +
                        " * that takes a single argument: the name of the partial.\n" +
                        " *\n" +
                        " * If the optional `config` argument is given here, then it should be an\n" +
                        " * object with a `tags` attribute or an `escape` attribute or both.\n" +
                        " * If an array is passed, then it will be interpreted the same way as\n" +
                        " * a `tags` attribute on a `config` object.\n" +
                        " *\n" +
                        " * The `tags` attribute of a `config` object must be an array with two\n" +
                        " * string values: the opening and closing tags used in the template (e.g.\n" +
                        " * [ \"<%\", \"%>\" ]). The default is to mustache.tags.\n" +
                        " *\n" +
                        " * The `escape` attribute of a `config` object must be a function which\n" +
                        " * accepts a string as input and outputs a safely escaped string.\n" +
                        " * If an `escape` function is not provided, then an HTML-safe string\n" +
                        " * escaping function is used as the default.\n" +
                        " */\n" +
                        "Writer.prototype.render = function render (template, view, partials, config) {\n" +
                        "  var tags = this.getConfigTags(config);\n" +
                        "  var tokens = this.parse(template, tags);\n" +
                        "  var context = (view instanceof Context) ? view : new Context(view, undefined);\n" +
                        "  return this.renderTokens(tokens, context, partials, template, config);\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Low-level method that renders the given array of `tokens` using\n" +
                        " * the given `context` and `partials`.\n" +
                        " *\n" +
                        " * Note: The `originalTemplate` is only ever used to extract the portion\n" +
                        " * of the original template that was contained in a higher-order section.\n" +
                        " * If the template doesn't use higher-order sections, this argument may\n" +
                        " * be omitted.\n" +
                        " */\n" +
                        "Writer.prototype.renderTokens = function renderTokens (tokens, context, partials, originalTemplate, config) {\n" +
                        "  var buffer = '';\n" +
                        "\n" +
                        "  var token, symbol, value;\n" +
                        "  for (var i = 0, numTokens = tokens.length; i < numTokens; ++i) {\n" +
                        "    value = undefined;\n" +
                        "    token = tokens[i];\n" +
                        "    symbol = token[0];\n" +
                        "\n" +
                        "    if (symbol === '#') value = this.renderSection(token, context, partials, originalTemplate, config);\n" +
                        "    else if (symbol === '^') value = this.renderInverted(token, context, partials, originalTemplate, config);\n" +
                        "    else if (symbol === '>') value = this.renderPartial(token, context, partials, config);\n" +
                        "    else if (symbol === '&') value = this.unescapedValue(token, context);\n" +
                        "    else if (symbol === 'name') value = this.escapedValue(token, context, config);\n" +
                        "    else if (symbol === 'text') value = this.rawValue(token);\n" +
                        "\n" +
                        "    if (value !== undefined)\n" +
                        "      buffer += value;\n" +
                        "  }\n" +
                        "\n" +
                        "  return buffer;\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.renderSection = function renderSection (token, context, partials, originalTemplate, config) {\n" +
                        "  var self = this;\n" +
                        "  var buffer = '';\n" +
                        "  var value = context.lookup(token[1]);\n" +
                        "\n" +
                        "  // This function is used to render an arbitrary template\n" +
                        "  // in the current context by higher-order sections.\n" +
                        "  function subRender (template) {\n" +
                        "    return self.render(template, context, partials, config);\n" +
                        "  }\n" +
                        "\n" +
                        "  if (!value) return;\n" +
                        "\n" +
                        "  if (isArray(value)) {\n" +
                        "    for (var j = 0, valueLength = value.length; j < valueLength; ++j) {\n" +
                        "      buffer += this.renderTokens(token[4], context.push(value[j]), partials, originalTemplate, config);\n" +
                        "    }\n" +
                        "  } else if (typeof value === 'object' || typeof value === 'string' || typeof value === 'number') {\n" +
                        "    buffer += this.renderTokens(token[4], context.push(value), partials, originalTemplate, config);\n" +
                        "  } else if (isFunction(value)) {\n" +
                        "    if (typeof originalTemplate !== 'string')\n" +
                        "      throw new Error('Cannot use higher-order sections without the original template');\n" +
                        "\n" +
                        "    // Extract the portion of the original template that the section contains.\n" +
                        "    value = value.call(context.view, originalTemplate.slice(token[3], token[5]), subRender);\n" +
                        "\n" +
                        "    if (value != null)\n" +
                        "      buffer += value;\n" +
                        "  } else {\n" +
                        "    buffer += this.renderTokens(token[4], context, partials, originalTemplate, config);\n" +
                        "  }\n" +
                        "  return buffer;\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.renderInverted = function renderInverted (token, context, partials, originalTemplate, config) {\n" +
                        "  var value = context.lookup(token[1]);\n" +
                        "\n" +
                        "  // Use JavaScript's definition of falsy. Include empty arrays.\n" +
                        "  // See https://github.com/janl/mustache.js/issues/186\n" +
                        "  if (!value || (isArray(value) && value.length === 0))\n" +
                        "    return this.renderTokens(token[4], context, partials, originalTemplate, config);\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.indentPartial = function indentPartial (partial, indentation, lineHasNonSpace) {\n" +
                        "  var filteredIndentation = indentation.replace(/[^ \\t]/g, '');\n" +
                        "  var partialByNl = partial.split('\\n');\n" +
                        "  for (var i = 0; i < partialByNl.length; i++) {\n" +
                        "    if (partialByNl[i].length && (i > 0 || !lineHasNonSpace)) {\n" +
                        "      partialByNl[i] = filteredIndentation + partialByNl[i];\n" +
                        "    }\n" +
                        "  }\n" +
                        "  return partialByNl.join('\\n');\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.renderPartial = function renderPartial (token, context, partials, config) {\n" +
                        "  if (!partials) return;\n" +
                        "  var tags = this.getConfigTags(config);\n" +
                        "\n" +
                        "  var value = isFunction(partials) ? partials(token[1]) : partials[token[1]];\n" +
                        "  if (value != null) {\n" +
                        "    var lineHasNonSpace = token[6];\n" +
                        "    var tagIndex = token[5];\n" +
                        "    var indentation = token[4];\n" +
                        "    var indentedValue = value;\n" +
                        "    if (tagIndex == 0 && indentation) {\n" +
                        "      indentedValue = this.indentPartial(value, indentation, lineHasNonSpace);\n" +
                        "    }\n" +
                        "    var tokens = this.parse(indentedValue, tags);\n" +
                        "    return this.renderTokens(tokens, context, partials, indentedValue, config);\n" +
                        "  }\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.unescapedValue = function unescapedValue (token, context) {\n" +
                        "  var value = context.lookup(token[1]);\n" +
                        "  if (value != null)\n" +
                        "    return value;\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.escapedValue = function escapedValue (token, context, config) {\n" +
                        "  var escape = this.getConfigEscape(config) || mustache.escape;\n" +
                        "  var value = context.lookup(token[1]);\n" +
                        "  if (value != null)\n" +
                        "    return (typeof value === 'number' && escape === mustache.escape) ? String(value) : escape(value);\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.rawValue = function rawValue (token) {\n" +
                        "  return token[1];\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.getConfigTags = function getConfigTags (config) {\n" +
                        "  if (isArray(config)) {\n" +
                        "    return config;\n" +
                        "  }\n" +
                        "  else if (config && typeof config === 'object') {\n" +
                        "    return config.tags;\n" +
                        "  }\n" +
                        "  else {\n" +
                        "    return undefined;\n" +
                        "  }\n" +
                        "};\n" +
                        "\n" +
                        "Writer.prototype.getConfigEscape = function getConfigEscape (config) {\n" +
                        "  if (config && typeof config === 'object' && !isArray(config)) {\n" +
                        "    return config.escape;\n" +
                        "  }\n" +
                        "  else {\n" +
                        "    return undefined;\n" +
                        "  }\n" +
                        "};\n" +
                        "\n" +
                        "var mustache = {\n" +
                        "  name: 'mustache.js',\n" +
                        "  version: '4.2.0',\n" +
                        "  tags: [ '{{', '}}' ],\n" +
                        "  clearCache: undefined,\n" +
                        "  escape: undefined,\n" +
                        "  parse: undefined,\n" +
                        "  render: undefined,\n" +
                        "  Scanner: undefined,\n" +
                        "  Context: undefined,\n" +
                        "  Writer: undefined,\n" +
                        "  /**\n" +
                        "   * Allows a user to override the default caching strategy, by providing an\n" +
                        "   * object with set, get and clear methods. This can also be used to disable\n" +
                        "   * the cache by setting it to the literal `undefined`.\n" +
                        "   */\n" +
                        "  set templateCache (cache) {\n" +
                        "    defaultWriter.templateCache = cache;\n" +
                        "  },\n" +
                        "  /**\n" +
                        "   * Gets the default or overridden caching object from the default writer.\n" +
                        "   */\n" +
                        "  get templateCache () {\n" +
                        "    return defaultWriter.templateCache;\n" +
                        "  }\n" +
                        "};\n" +
                        "\n" +
                        "// All high-level mustache.* functions use this writer.\n" +
                        "var defaultWriter = new Writer();\n" +
                        "\n" +
                        "/**\n" +
                        " * Clears all cached templates in the default writer.\n" +
                        " */\n" +
                        "mustache.clearCache = function clearCache () {\n" +
                        "  return defaultWriter.clearCache();\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Parses and caches the given template in the default writer and returns the\n" +
                        " * array of tokens it contains. Doing this ahead of time avoids the need to\n" +
                        " * parse templates on the fly as they are rendered.\n" +
                        " */\n" +
                        "mustache.parse = function parse (template, tags) {\n" +
                        "  return defaultWriter.parse(template, tags);\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Renders the `template` with the given `view`, `partials`, and `config`\n" +
                        " * using the default writer.\n" +
                        " */\n" +
                        "mustache.render = function render (template, view, partials, config) {\n" +
                        "  if (typeof template !== 'string') {\n" +
                        "    throw new TypeError('Invalid template! Template should be a \"string\" ' +\n" +
                        "                        'but \"' + typeStr(template) + '\" was given as the first ' +\n" +
                        "                        'argument for mustache#render(template, view, partials)');\n" +
                        "  }\n" +
                        "\n" +
                        "  return defaultWriter.render(template, view, partials, config);\n" +
                        "};\n" +
                        "\n" +
                        "// Export the escaping function so that the user may override it.\n" +
                        "// See https://github.com/janl/mustache.js/issues/244\n" +
                        "mustache.escape = escapeHtml;\n" +
                        "\n" +
                        "// Export these mainly for testing, but also for advanced usage.\n" +
                        "mustache.Scanner = Scanner;\n" +
                        "mustache.Context = Context;\n" +
                        "mustache.Writer = Writer;\n" +
                        "\n" +
                        "// The purpose of this module is to determine the main schema type of the given input\n" +
                        "// This main schema type is needed to identify its corresponding template\n" +
                        "\n" +
                        "function findObjectWithKey(jsonLd, key){\n" +
                        "    if(key in jsonLd){\n" +
                        "        return jsonLd;\n" +
                        "    }\n";
                        String snippet2 = "    else if(Array.isArray(jsonLd)){\n" +
                        "        for (const jsonLdObject of jsonLd) {\n" +
                        "            if(key in jsonLdObject){\n" +
                        "                return jsonLdObject\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Transform each array property of a JSON object into its first element.\n" +
                        " * This is because each JSON-LD property may be an array or not.\n" +
                        " * @param object jsonObject - original JSON object\n" +
                        " * @return object JSON object with no more arrays as values\n" +
                        " */\n" +
                        "function transformArrayProperties(jsonObject) {\n" +
                        "    for (const key in jsonObject) {\n" +
                        "        if (Array.isArray(jsonObject[key])) {\n" +
                        "            jsonObject[key] = jsonObject[key][0];\n" +
                        "        }\n" +
                        "    }\n" +
                        "    return jsonObject;\n" +
                        "}\n" +
                        "\n" +
                        "function getMainEntity(jsonLd){\n" +
                        "    if(Array.isArray(jsonLd))\n" +
                        "    {\n" +
                        "        let possibleMainEntity = findObjectWithKey(jsonLd,\"mainEntityOfPage\");\n" +
                        "        if(possibleMainEntity != null)\n" +
                        "        {\n" +
                        "            return possibleMainEntity;\n" +
                        "        }\n" +
                        "        else return jsonLd[0];\n" +
                        "    }\n" +
                        "    else if(jsonLd[\"@graph\"] != null\n" +
                        "        && jsonLd[\"@graph\"] !== undefined\n" +
                        "        && Array.isArray(jsonLd[\"@graph\"]))\n" +
                        "    {\n" +
                        "        let possibleGraph = jsonLd[\"@graph\"];\n" +
                        "        let possibleMainEntity = getMainEntity(possibleGraph);\n" +
                        "\n" +
                        "        return possibleMainEntity;\n" +
                        "    }\n" +
                        "    else return jsonLd;\n" +
                        "}\n" +
                        "\n" +
                        "// This function extracts a \"usable\" image from the different cases of a json-ld\n" +
                        "//  into the thumbnail or thumbnailUrl property\n" +
                        "\n" +
                        "function extractImage(json_object){\n" +
                        "\n" +
                        "    // first check for the thumbnailUrl property\n" +
                        "    if(json_object[\"thumbnailUrl\"] !== undefined)\n" +
                        "    {\n" +
                        "        // case single item, no array\n" +
                        "        if(!Array.isArray(json_object[\"thumbnailUrl\"]) &&\n" +
                        "            typeof json_object[\"thumbnailUrl\"] === 'string')\n" +
                        "        {\n" +
                        "            return json_object;\n" +
                        "        }\n" +
                        "        // case array\n" +
                        "        else\n" +
                        "        {\n" +
                        "            // take the first element\n" +
                        "            if(json_object[\"thumbnailUrl\"][0] !== undefined && typeof json_object[\"thumbnailUrl\"][0] === 'string')\n" +
                        "            {\n" +
                        "                json_object[\"thumbnailUrl\"] = json_object[\"thumbnailUrl\"][0];\n" +
                        "                return json_object;\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "    // second check for the thumbnail property\n" +
                        "    if(json_object[\"thumbnail\"] !== undefined)\n" +
                        "    {\n" +
                        "        // case single item, no array\n" +
                        "        if(!Array.isArray(json_object[\"thumbnail\"]) &&\n" +
                        "            typeof json_object[\"thumbnail\"] === 'string')\n" +
                        "        {\n" +
                        "            return json_object;\n" +
                        "        }\n" +
                        "        // case array\n" +
                        "        else\n" +
                        "        {\n" +
                        "            // take the first element\n" +
                        "            if(json_object[\"thumbnail\"][0] !== undefined && typeof json_object[\"thumbnail\"][0] === 'string')\n" +
                        "            {\n" +
                        "                json_object[\"thumbnail\"] = json_object[\"thumbnail\"][0];\n" +
                        "                return json_object;\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "    // second check for the image property\n" +
                        "    if(json_object[\"image\"] !== undefined)\n" +
                        "    {\n" +
                        "        // case url value directly behind the image key\n" +
                        "        if(typeof json_object[\"image\"] === \"string\")\n" +
                        "        {\n" +
                        "\n" +
                        "            json_object[\"thumbnailUrl\"] = json_object[\"image\"];\n" +
                        "            return json_object;\n" +
                        "\n" +
                        "        }\n" +
                        "        // case array\n" +
                        "        else if(Array.isArray(json_object[\"image\"]))\n" +
                        "        {\n" +
                        "            // case array of string \"urls\"\n" +
                        "            if(json_object[\"image\"][0] !== undefined &&\n" +
                        "                    typeof json_object[\"image\"][0] === 'string')\n" +
                        "            {\n" +
                        "\n" +
                        "                json_object[\"thumbnailUrl\"] = json_object[\"image\"][0];\n" +
                        "                return json_object;\n" +
                        "\n" +
                        "            }\n" +
                        "            // case array of \"imageObjects\"\n" +
                        "            // take first element for now\n" +
                        "            if(json_object[\"image\"][0] !== undefined &&\n" +
                        "                    typeof json_object[\"image\"][0] === 'object')\n" +
                        "            {\n" +
                        "                if(json_object[\"image\"][0][\"url\"] !== undefined &&\n" +
                        "                    typeof json_object[\"image\"][0][\"url\"] === 'string')\n" +
                        "                {\n" +
                        "                    // take first element for now\n" +
                        "                    json_object[\"thumbnailUrl\"] = json_object[\"image\"][0][\"url\"];\n" +
                        "                    return json_object;\n" +
                        "                }\n" +
                        "            }\n" +
                        "\n" +
                        "        }\n" +
                        "        // case \"object\" - caution an array is also of type object! So check that before\n" +
                        "        else if(typeof json_object[\"image\"] === 'object')\n" +
                        "        {\n" +
                        "            if(json_object[\"image\"][\"url\"] !== undefined &&\n" +
                        "                    typeof json_object[\"image\"][\"url\"] === 'string')\n" +
                        "            {\n" +
                        "\n" +
                        "                json_object[\"thumbnailUrl\"] = json_object[\"image\"][\"url\"];\n" +
                        "                return json_object;\n" +
                        "\n" +
                        "            }\n" +
                        "             // case base64\n" +
                        "            else if(json_object[\"image\"][\"contentUrl\"] !== undefined &&\n" +
                        "                    typeof json_object[\"image\"][\"contentUrl\"] === 'string')\n" +
                        "            {\n" +
                        "                json_object[\"thumbnail\"] = json_object[\"image\"][\"contentUrl\"];\n" +
                        "                return json_object;\n" +
                        "            }\n" +
                        "        }\n" +
                        "\n" +
                        "    }\n" +
                        "    // If nothing happened return json_object\n" +
                        "    return json_object;\n" +
                        "}\n" +
                        "\n" +
                        "// This function creates an \"artificial\" view Action object if there is none given.\n" +
                        "// To do so it takes the URL of a possible given mainEntityOfPage and creates a view Action\n" +
                        "// Object with that.\n" +
                        "\n" +
                        "function createPotentialViewAction(json_object){\n" +
                        "    if(json_object[\"@type\"] !== undefined &&\n" +
                        "            json_object[\"potentialAction\"] === undefined)\n" +
                        "    {\n" +
                        "        // TODO validate what happens if json_object[\"mainEntityOfPage\"] is not set\n" +
                        "        let possibleMainEntityOfPage = json_object[\"mainEntityOfPage\"];\n" +
                        "        if(possibleMainEntityOfPage !== undefined && possibleMainEntityOfPage !== null)\n" +
                        "        {\n" +
                        "            let urlOfMainPage;\n" +
                        "            if(typeof possibleMainEntityOfPage === 'string')\n" +
                        "            {\n" +
                        "                urlOfMainPage = possibleMainEntityOfPage;\n" +
                        "            }\n" +
                        "            if(possibleMainEntityOfPage[\"@id\"] !== undefined &&\n" +
                        "                        typeof possibleMainEntityOfPage[\"@id\"] === 'string')\n" +
                        "            {\n" +
                        "                urlOfMainPage = possibleMainEntityOfPage[\"@id\"];\n" +
                        "            }\n" +
                        "            if(typeof urlOfMainPage === 'string' &&\n" +
                        "                        urlOfMainPage !== undefined &&\n" +
                        "                        urlOfMainPage !== null)\n" +
                        "            {\n" +
                        "                let viewActionObject =\n" +
                        "                {\n" +
                        "                    \"@type\": \"ViewAction\",\n" +
                        "                    \"target\": urlOfMainPage\n" +
                        "                };\n" +
                        "                json_object[\"potentialAction\"] = viewActionObject;\n" +
                        "            }\n" +
                        "            return json_object\n" +
                        "        }\n" +
                        "        else\n" +
                        "        {\n" +
                        "            return json_object;\n" +
                        "        }\n" +
                        "    }\n" +
                        "    else\n" +
                        "    {\n" +
                        "        return json_object;\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +
                        "var headerIconTemplate$4 = \"<i class='fa-solid fa-{{iconName}} fa-1x'></i>\";\n" +
                        "var imageIconTemplate$4 = \"<i class='fa-solid fa-{{iconName}} fa-5x'></i>\";\n" +
                        "var transportIconTemplate$4 = \"<i class='fa-solid fa-{{iconName}} fa-3x ld-card__img_flight-reservations'></i>\";\n" +
                        "var iconMap$3 = {\n" +
                        "\tArticle: \"comment\",\n" +
                        "\tAudioObject: \"music\",\n" +
                        "\tBook: \"book\",\n" +
                        "\tBusinessEvent: \"business-time\",\n" +
                        "\tBusReservation: \"bus\",\n" +
                        "\tCity: \"city\",\n" +
                        "\tCreativeWork: \"paintbrush\",\n" +
                        "\tEmailMessage: \"envelope\",\n" +
                        "\tEvent: \"calendar\",\n" +
                        "\tEventReservation: \"calendar\",\n" +
                        "\tFoodEstablishmentReservation: \"utensils\",\n" +
                        "\tFlightReservation: \"plane\",\n" +
                        "\tInvoice: \"file-invoice-dollar\",\n" +
                        "\tMovie: \"video\",\n" +
                        "\tMusicAlbum: \"music\",\n" +
                        "\tMusicGroup: \"music\",\n" +
                        "\tMusicRecording: \"music\",\n" +
                        "\tNewsArticle: \"newspaper\",\n" +
                        "\tNewsMediaOrganization: \"newspaper\",\n" +
                        "\tOrder: \"truck\",\n" +
                        "\tParcelDelivery: \"truck\",\n" +
                        "\tPerson: \"person\",\n" +
                        "\tPlace: \"location-dot\",\n" +
                        "\tPodcastSeries: \"podcast\",\n" +
                        "\tProduct: \"cart-shopping\",\n" +
                        "\tProfilePage: \"user\",\n" +
                        "\tRecipe: \"utensils\",\n" +
                        "\tRentalCarReservation: \"car-side\",\n" +
                        "\tSoftwareApplication: \"code\",\n" +
                        "\tTVSeries: \"tv\",\n" +
                        "\tTrainReservation: \"train\",\n" +
                        "\tWebSite: \"globe\",\n" +
                        "\tWebpage: \"globe\",\n" +
                        "\t\"https://ld2h/Default\": \"image\",\n" +
                        "\t\"https://ld2h/EventReservations\": \"calendar\",\n" +
                        "\t\"https://ld2h/FlightReservations\": \"plane\",\n" +
                        "\t\"https://ld2h/TrainReservations\": \"train\",\n" +
                        "\t\"https://ld2h/BusReservations\": \"bus\"\n" +
                        "};\n" +
                        "var fontAwesome = {\n" +
                        "\theaderIconTemplate: headerIconTemplate$4,\n" +
                        "\timageIconTemplate: imageIconTemplate$4,\n" +
                        "\ttransportIconTemplate: transportIconTemplate$4,\n" +
                        "\ticonMap: iconMap$3\n" +
                        "};\n" +
                        "\n" +
                        "var headerIconTemplate$3 = \"<span style='vertical-align: sub;font-size: 20px;' class='material-icons'>{{iconName}}</span>\";\n" +
                        "var imageIconTemplate$3 = \"<span style='vertical-align: middle;font-size: 100px;' class='material-icons'>{{iconName}}</span>\";\n" +
                        "var transportIconTemplate$3 = \"<span style='vertical-align: middle;font-size: 60px;' class='material-icons ld-card__img_flight-reservations'>{{iconName}}</span>\";\n" +
                        "var iconMap$2 = {\n" +
                        "\tArticle: \"news\",\n" +
                        "\tBusReservation: \"directions_bus\",\n" +
                        "\tEmailMessage: \"mail\",\n" +
                        "\tFoodEstablishmentReservation: \"restaurant\",\n" +
                        "\tFlightReservation: \"flight\",\n" +
                        "\tMovie: \"videocam\",\n" +
                        "\tMusicRecording: \"music_note\",\n" +
                        "\tNewsArticle: \"newspaper\",\n" +
                        "\tTVSeries: \"live_tv\",\n" +
                        "\tTrainReservation: \"train\",\n" +
                        "\t\"https://ld2h/Default\": \"add_photo_alternate\",\n" +
                        "\t\"https://ld2h/FlightReservations\": \"flight\",\n" +
                        "\t\"https://ld2h/TrainReservations\": \"train\",\n" +
                        "\t\"https://ld2h/BusReservations\": \"directions_bus\"\n" +
                        "};\n" +
                        "var materiaDesignIcons = {\n" +
                        "\theaderIconTemplate: headerIconTemplate$3,\n" +
                        "\timageIconTemplate: imageIconTemplate$3,\n" +
                        "\ttransportIconTemplate: transportIconTemplate$3,\n" +
                        "\ticonMap: iconMap$2\n" +
                        "};\n" +
                        "\n" +
                        "var headerIconTemplate$2 = \"<span style='vertical-align: sub;font-size: 20px;' class='material-symbols-outlined'>{{iconName}}</span>\";\n" +
                        "var imageIconTemplate$2 = \"<span style='vertical-align: middle;font-size: 100px;' class='material-symbols-outlined'>{{iconName}}</span>\";\n" +
                        "var transportIconTemplate$2 = \"<span style='vertical-align: middle;font-size: 60px;' class='material-icons ld-card__img_flight-reservations'>{{iconName}}</span>\";\n" +
                        "var iconMap$1 = {\n" +
                        "\tArticle: \"news\",\n" +
                        "\tBusReservation: \"directions_bus\",\n" +
                        "\tEmailMessage: \"mail\",\n" +
                        "\tFoodEstablishmentReservation: \"restaurant\",\n" +
                        "\tFlightReservation: \"flight\",\n" +
                        "\tMovie: \"videocam\",\n" +
                        "\tMusicRecording: \"music_note\",\n" +
                        "\tNewsArticle: \"newspaper\",\n" +
                        "\tTVSeries: \"live_tv\",\n" +
                        "\tTrainReservation: \"train\",\n" +
                        "\t\"https://ld2h/Default\": \"add_photo_alternate\",\n" +
                        "\t\"https://ld2h/FlightReservations\": \"flight\",\n" +
                        "\t\"https://ld2h/TrainReservations\": \"train\",\n" +
                        "\t\"https://ld2h/BusReservations\": \"directions_bus\"\n" +
                        "};\n" +
                        "var materialDesignSymbols = {\n" +
                        "\theaderIconTemplate: headerIconTemplate$2,\n" +
                        "\timageIconTemplate: imageIconTemplate$2,\n" +
                        "\ttransportIconTemplate: transportIconTemplate$2,\n" +
                        "\ticonMap: iconMap$1\n" +
                        "};\n" +
                        "\n" +
                        "var headerIconTemplate$1 = \"<svg class='material-design-icon__svg' style='width:20px;height:20px;margin-top:-3px;margin-bottom: -4px;' viewBox='0 0 24 24'> <path d='{{iconName}}'> </path> </svg>\";\n" +
                        "var imageIconTemplate$1 = \"<svg class='material-design-icon__svg' style='width:98px;height:98px;' viewBox='0 0 24 24'> <path d='{{iconName}}'> </path> </svg>\";\n" +
                        "var transportIconTemplate$1 = \"<svg class='material-design-icon__svg ld-card__img_flight-reservations-nextcloud' style='width:48px;height:48px;' viewBox='0 0 24 24'> <path d='{{iconName}}'> </path> </svg>\";\n" +
                        "var iconMap = {\n" +
                        "\tArticle: \"M20 5L20 19L4 19L4 5H20M20 3H4C2.89 3 2 3.89 2 5V19C2 20.11 2.89 21 4 21H20C21.11 21 22 20.11 22 19V5C22 3.89 21.11 3 20 3M18 15H6V17H18V15M10 7H6V13H10V7M12 9H18V7H12V9M18 11H12V13H18V11Z\",\n" +
                        "\tBusReservation: \"M18,11H6V6H18M16.5,17A1.5,1.5 0 0,1 15,15.5A1.5,1.5 0 0,1 16.5,14A1.5,1.5 0 0,1 18,15.5A1.5,1.5 0 0,1 16.5,17M7.5,17A1.5,1.5 0 0,1 6,15.5A1.5,1.5 0 0,1 7.5,14A1.5,1.5 0 0,1 9,15.5A1.5,1.5 0 0,1 7.5,17M4,16C4,16.88 4.39,17.67 5,18.22V20A1,1 0 0,0 6,21H7A1,1 0 0,0 8,20V19H16V20A1,1 0 0,0 17,21H18A1,1 0 0,0 19,20V18.22C19.61,17.67 20,16.88 20,16V6C20,2.5 16.42,2 12,2C7.58,2 4,2.5 4,6V16Z\",\n" +
                        "\tEmailMessage: \"M20,8L12,13L4,8V6L12,11L20,6M20,4H4C2.89,4 2,4.89 2,6V18A2,2 0 0,0 4,20H20A2,2 0 0,0 22,18V6C22,4.89 21.1,4 20,4Z\",\n" +
                        "\tFlightReservation: \"M20.56 3.91C21.15 4.5 21.15 5.45 20.56 6.03L16.67 9.92L18.79 19.11L17.38 20.53L13.5 13.1L9.6 17L9.96 19.47L8.89 20.53L7.13 17.35L3.94 15.58L5 14.5L7.5 14.87L11.37 11L3.94 7.09L5.36 5.68L14.55 7.8L18.44 3.91C19 3.33 20 3.33 20.56 3.91Z\",\n" +
                        "\tFoodEstablishmentReservation: \"M3,3A1,1 0 0,0 2,4V8L2,9.5C2,11.19 3.03,12.63 4.5,13.22V19.5A1.5,1.5 0 0,0 6,21A1.5,1.5 0 0,0 7.5,19.5V13.22C8.97,12.63 10,11.19 10,9.5V8L10,4A1,1 0 0,0 9,3A1,1 0 0,0 8,4V8A0.5,0.5 0 0,1 7.5,8.5A0.5,0.5 0 0,1 7,8V4A1,1 0 0,0 6,3A1,1 0 0,0 5,4V8A0.5,0.5 0 0,1 4.5,8.5A0.5,0.5 0 0,1 4,8V4A1,1 0 0,0 3,3M19.88,3C19.75,3 19.62,3.09 19.5,3.16L16,5.25V9H12V11H13L14,21H20L21,11H22V9H18V6.34L20.5,4.84C21,4.56 21.13,4 20.84,3.5C20.63,3.14 20.26,2.95 19.88,3Z\",\n" +
                        "\tMovie: \"M14.75 7.46L12 3.93L13.97 3.54L16.71 7.07L14.75 7.46M21.62 6.1L20.84 2.18L16.91 2.96L19.65 6.5L21.62 6.1M4.16 5.5L3.18 5.69C2.1 5.91 1.4 6.96 1.61 8.04L2 10L6.9 9.03L4.16 5.5M11.81 8.05L9.07 4.5L7.1 4.91L9.85 8.44L11.81 8.05M2 10V20C2 21.11 2.9 22 4 22H13.81C13.3 21.12 13 20.1 13 19C13 15.69 15.69 13 19 13C20.1 13 21.12 13.3 22 13.81V10H2M17 22L22 19L17 16V22Z\",\n" +
                        "\tMusicRecording: \"M21,3V15.5A3.5,3.5 0 0,1 17.5,19A3.5,3.5 0 0,1 14,15.5A3.5,3.5 0 0,1 17.5,12C18.04,12 18.55,12.12 19,12.34V6.47L9,8.6V17.5A3.5,3.5 0 0,1 5.5,21A3.5,3.5 0 0,1 2,17.5A3.5,3.5 0 0,1 5.5,14C6.04,14 6.55,14.12 7,14.34V6L21,3Z\",\n" +
                        "\tNewsArticle: \"M20 5L20 19L4 19L4 5H20M20 3H4C2.89 3 2 3.89 2 5V19C2 20.11 2.89 21 4 21H20C21.11 21 22 20.11 22 19V5C22 3.89 21.11 3 20 3M18 15H6V17H18V15M10 7H6V13H10V7M12 9H18V7H12V9M18 11H12V13H18V11Z\",\n" +
                        "\tTVSeries: \"M9 20C9 17.44 10.87 12.42 13.86 7.25C14.29 6.5 15.08 6 16 6C17.12 6 18 6.88 18 8V20H20V8C20 5.8 18.2 4 16 4C14.34 4 12.9 4.92 12.13 6.25C10.56 8.96 9.61 11.15 9 13.03V6.5C9 5.13 7.87 4 6.5 4C5.13 4 4 5.13 4 6.5C4 7.87 5.13 9 6.5 9C6.67 9 6.84 9 7 8.95V20M6.5 6C6.79 6 7 6.21 7 6.5C7 6.79 6.79 7 6.5 7C6.21 7 6 6.79 6 6.5C6 6.21 6.21 6 6.5 6Z\",\n" +
                        "\tTrainReservation: \"M12,2C8,2 4,2.5 4,6V15.5A3.5,3.5 0 0,0 7.5,19L6,20.5V21H8.23L10.23,19H14L16,21H18V20.5L16.5,19A3.5,3.5 0 0,0 20,15.5V6C20,2.5 16.42,2 12,2M7.5,17A1.5,1.5 0 0,1 6,15.5A1.5,1.5 0 0,1 7.5,14A1.5,1.5 0 0,1 9,15.5A1.5,1.5 0 0,1 7.5,17M11,10H6V6H11V10M13,10V6H18V10H13M16.5,17A1.5,1.5 0 0,1 15,15.5A1.5,1.5 0 0,1 16.5,14A1.5,1.5 0 0,1 18,15.5A1.5,1.5 0 0,1 16.5,17Z\",\n" +
                        "\t\"https://ld2h/BusReservations\": \"M18,11H6V6H18M16.5,17A1.5,1.5 0 0,1 15,15.5A1.5,1.5 0 0,1 16.5,14A1.5,1.5 0 0,1 18,15.5A1.5,1.5 0 0,1 16.5,17M7.5,17A1.5,1.5 0 0,1 6,15.5A1.5,1.5 0 0,1 7.5,14A1.5,1.5 0 0,1 9,15.5A1.5,1.5 0 0,1 7.5,17M4,16C4,16.88 4.39,17.67 5,18.22V20A1,1 0 0,0 6,21H7A1,1 0 0,0 8,20V19H16V20A1,1 0 0,0 17,21H18A1,1 0 0,0 19,20V18.22C19.61,17.67 20,16.88 20,16V6C20,2.5 16.42,2 12,2C7.58,2 4,2.5 4,6V16Z\",\n" +
                        "\t\"https://ld2h/Default\": \"M4,4H7L9,2H15L17,4H20A2,2 0 0,1 22,6V18A2,2 0 0,1 20,20H4A2,2 0 0,1 2,18V6A2,2 0 0,1 4,4M12,7A5,5 0 0,0 7,12A5,5 0 0,0 12,17A5,5 0 0,0 17,12A5,5 0 0,0 12,7M12,9A3,3 0 0,1 15,12A3,3 0 0,1 12,15A3,3 0 0,1 9,12A3,3 0 0,1 12,9Z\",\n" +
                        "\t\"https://ld2h/FlightReservations\": \"M20.56 3.91C21.15 4.5 21.15 5.45 20.56 6.03L16.67 9.92L18.79 19.11L17.38 20.53L13.5 13.1L9.6 17L9.96 19.47L8.89 20.53L7.13 17.35L3.94 15.58L5 14.5L7.5 14.87L11.37 11L3.94 7.09L5.36 5.68L14.55 7.8L18.44 3.91C19 3.33 20 3.33 20.56 3.91Z\",\n" +
                        "\t\"https://ld2h/TrainReservations\": \"M12,2C8,2 4,2.5 4,6V15.5A3.5,3.5 0 0,0 7.5,19L6,20.5V21H8.23L10.23,19H14L16,21H18V20.5L16.5,19A3.5,3.5 0 0,0 20,15.5V6C20,2.5 16.42,2 12,2M7.5,17A1.5,1.5 0 0,1 6,15.5A1.5,1.5 0 0,1 7.5,14A1.5,1.5 0 0,1 9,15.5A1.5,1.5 0 0,1 7.5,17M11,10H6V6H11V10M13,10V6H18V10H13M16.5,17A1.5,1.5 0 0,1 15,15.5A1.5,1.5 0 0,1 16.5,14A1.5,1.5 0 0,1 18,15.5A1.5,1.5 0 0,1 16.5,17Z\"\n" +
                        "};\n" +
                        "var materialDesignRawSvg = {\n" +
                        "\theaderIconTemplate: headerIconTemplate$1,\n" +
                        "\timageIconTemplate: imageIconTemplate$1,\n" +
                        "\ttransportIconTemplate: transportIconTemplate$1,\n" +
                        "\ticonMap: iconMap\n" +
                        "};\n" +
                        "\n" +
                        "// Mapping the fallback icon to schema type\n" +
                        "const typeToIconMap = new Map();\n" +
                        "var headerIconTemplate;\n" +
                        "var imageIconTemplate;\n" +
                        "var transportIconTemplate;\n" +
                        "\n" +
                        "// Available icons\n" +
                        "const icons = {\n" +
                        "  FontAwesome: \"FontAwesome\",\n" +
                        "  MaterialDesignIcons: \"MaterialDesignIcons\",\n" +
                        "  MaterialDesignSymbols: \"MaterialDesignSymbols\",\n" +
                        "  MaterialDesignRawSvg: \"MaterialDesignRawSvg\"\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        " * Set icons to use. Currently available:\n" +
                        " *  * FontAwesome\n" +
                        " *  * MaterialDesignIcons\n" +
                        " *  * MaterialDesignSymbols\n" +
                        " *\n" +
                        " * @param string fontName - Sets the font name if set to one of above's strings.\n" +
                        " */\n" +
                        "function setIcons(fontName){\n" +
                        "    var icon;\n" +
                        "    if (fontName == icons.FontAwesome) {\n" +
                        "        icon = fontAwesome;\n" +
                        "    } else if (fontName == icons.MaterialDesignIcons) {\n" +
                        "        icon = materiaDesignIcons;\n" +
                        "    } else if (fontName == icons.MaterialDesignSymbols) {\n" +
                        "        icon = materialDesignSymbols;\n" +
                        "    } else if (fontName == icons.MaterialDesignRawSvg) {\n" +
                        "        icon = materialDesignRawSvg;\n" +
                        "    }\n" +
                        "    else {\n" +
                        "      console.error(fontName + ' is unsupported!');\n" +
                        "    }\n" +
                        "    Object.entries(icon[\"iconMap\"]).forEach(element => {\n" +
                        "        typeToIconMap.set(element[0],element[1]);\n" +
                        "    });\n" +
                        "    headerIconTemplate = icon[\"headerIconTemplate\"];\n" +
                        "    imageIconTemplate = icon[\"imageIconTemplate\"];\n" +
                        "    transportIconTemplate = icon[\"transportIconTemplate\"];\n" +
                        "}\n" +
                        "\n" +
                        "var cardDefaultFluent = \"<fluent-card class=\\\"ld-card\\\">\\n    <div class=\\\"ld-card__header\\\">\\n        <!--  Experimental breadcrumblist support:   -->\\n        \\n        <!-- {{^ itemListElement}} {{@type}} {{> headerIconTemplate}} {{/ itemListElement}} -->\\n        <!-- <ul class=\\\"ld-card__breadcrumb\\\"> {{# itemListElement}} <li><a href= {{item}} >{{name}}</a></li> {{/ itemListElement}}\\n        </ul> -->\\n\\n        <!-- No breadcrumblist:  -->\\n        {{#thumbnailUrl}}{{> headerIconTemplate}}{{/thumbnailUrl}}\\n        {{#thumbnail}}{{> headerIconTemplate}}{{/thumbnail}}\\n        \\n    </div>\\n    <div class=\\\"ld-card__row\\\">\\n        <div class=\\\"ld-card__first-column\\\">\\n            <!-- First try the thumbnailUrl property which stores urls-->\\n            {{#thumbnailUrl}}<img src=\\\"{{&thumbnailUrl}}\\\" alt=\\\"Picture showing {{@type}}\\\">{{/thumbnailUrl}}\\n            <!-- Then try the thumbnail property which stores base64 pictures-->\\n            {{#thumbnail}}<img src=\\\"{{&thumbnail}}\\\" alt=\\\"Picture showing {{@type}}\\\">{{/thumbnail}}\\n            <!-- If both of them dont exist, render the i frame with the iconName -->\\n            {{^thumbnailUrl}} {{^thumbnail}} {{> imageIconTemplate}}{{/thumbnail}}{{/thumbnailUrl}}\\n        </div>\\n        <div class=\\\"ld-card__last-column\\\">\\n            <!--This part is filled with subtemplates-->\\n        {{#ld2hSubtemplateContent}}\\n            {{{ld2hSubtemplateContent}}}\\n        {{/ld2hSubtemplateContent}}\\n        </div>\\n    </div>\\n        <div class=\\\"ld-card__footer\\\">\\n            {{#potentialAction}}<fluent-button class=\\\"ld-card__action-button--fui\\\" appearance=\\\"accent\\\" onclick=\\\"window.open('{{target}}', '_blank');\\\">{{@type}}</fluent-button>{{/potentialAction}}\\n        </div>\\n\\n        \\n\\n</fluent-card>\\n    \\n\";\n" +
                        "\n" +
                        "var cardDefault = \"<div class=\\\"ld-card\\\">\\n    <div class=\\\"ld-card__header\\\">\\n        <!--  Experimental breadcrumblist support:   -->\\n        \\n        <!-- {{^ itemListElement}} {{@type}} {{> headerIconTemplate}} {{/ itemListElement}} -->\\n        <!-- <ul class=\\\"ld-card__breadcrumb\\\"> {{# itemListElement}} <li><a href= {{item}} >{{name}}</a></li> {{/ itemListElement}}\\n        </ul> -->\\n\\n        <!-- No breadcrumblist:  -->\\n        {{#thumbnailUrl}}{{> headerIconTemplate}}{{/thumbnailUrl}}\\n        {{#thumbnail}}{{> headerIconTemplate}}{{/thumbnail}}\\n        \\n    </div>\\n    <div class=\\\"ld-card__row\\\">\\n        <div class=\\\"ld-card__first-column\\\">\\n            <!-- First try the thumbnailUrl property which stores urls-->\\n            {{#thumbnailUrl}}<img src=\\\"{{&thumbnailUrl}}\\\" alt=\\\"Picture showing {{@type}}\\\">{{/thumbnailUrl}}\\n            <!-- Then try the thumbnail property which stores base64 pictures-->\\n            {{#thumbnail}}<img src=\\\"{{&thumbnail}}\\\" alt=\\\"Picture showing {{@type}}\\\">{{/thumbnail}}\\n            <!-- If both of them dont exist, render the i frame with the iconName -->\\n            {{^thumbnailUrl}} {{^thumbnail}} {{> imageIconTemplate}}{{/thumbnail}}{{/thumbnailUrl}}\\n        </div>\\n        <div class=\\\"ld-card__last-column\\\">\\n            <!--This part is filled with subtemplates-->\\n        {{#ld2hSubtemplateContent}}\\n            {{{ld2hSubtemplateContent}}}\\n        {{/ld2hSubtemplateContent}}\\n        </div>\\n    </div>\\n        <div class=\\\"ld-card__footer\\\">\\n            {{#potentialAction}}<button type=\\\"button\\\" class=\\\"ld-card__action-button\\\" onclick=\\\"window.open('{{target}}', '_blank');\\\">{{@type}}</button>{{/potentialAction}}\\n        </div>\\n</div>\\n    \\n\";\n" +
                        "\n" +
                        "var subDefault = \"<h1 class=\\\"ld-card__title\\\">{{name}}</h1>\\n\\n<p class=\\\"ld-card__content\\\">{{{description}}}</p>\\n\";\n" +
                        "\n" +
                        "var cardPromotionCards = \"\\n<div class=\\\"ld-card\\\">\\n<div class=\\\"ld-card__header\\\">\\n    {{^breadcrumbList.itemListElement}} {{@type}}   {{#iconName}}<i class=\\\"fa-solid fa-{{iconName}} fa-1x\\\"></i>{{/iconName}}  {{/breadcrumbList.itemListElement}}\\n    <ul class=\\\"ld-card__breadcrumb\\\">\\n        {{#breadcrumbList.itemListElement}}<li><a href=\\\"{{item.id}}\\\">{{item.name}}</a></li>{{/breadcrumbList.itemListElement}}\\n    </ul>\\n\\n\\n</div>\\n\\n<div class=\\\"ld-card__row_promotion-cards\\\">\\n    <div class=\\\"ld-card__first-column_promotion-cards\\\">\\n\\n        <img src=\\\"{{logo}}\\\">\\n\\n\\n    </div>\\n\\n    <div class=\\\"ld-card__mid-column_promotion-cards\\\">\\n\\n        <h4 class=\\\"ld-card__title--promotion-cards\\\">{{subjectLine}}</h4>\\n        <p class=\\\"ld-card__content--promotion-cards\\\">{{description}}</p>\\n        <p class=\\\"ld-card__promotion-code--promotion-cards\\\">Code {{discountCode}}</p>\\n\\n    </div>\\n\\n    <div class=\\\"ld-card__last-column_promotion-cards\\\">\\n\\n        {{#promotionCards}}\\n        {{{.}}}\\n        {{/promotionCards}}\\n\\n    </div>\\n\\n\\n\\n\\n</div>\\n    <div class=\\\"ld-card__footer\\\">\\n        {{#potentialAction}}<button type=\\\"button\\\" class=\\\"ld-card__action-button\\\" onclick=\\\"window.open('{{target}}', '_blank');\\\">{{@type}}</button>{{/potentialAction}}\\n\\n    </div>\\n\\n\\n</div>\\n\\n\";\n" +
                        "\n" +
                        "var cardPromotionCardsFluent = \"\\n<fluent-card class=\\\"ld-card\\\">\\n<div class=\\\"ld-card__header\\\">\\n    {{^breadcrumbList.itemListElement}} {{@type}}   {{#iconName}}<i class=\\\"fa-solid fa-{{iconName}} fa-1x\\\"></i>{{/iconName}}  {{/breadcrumbList.itemListElement}}\\n    <ul class=\\\"ld-card__breadcrumb\\\">\\n        {{#breadcrumbList.itemListElement}}<li><a href=\\\"{{item.id}}\\\">{{item.name}}</a></li>{{/breadcrumbList.itemListElement}}\\n    </ul>\\n\\n\\n</div>\\n\\n<div class=\\\"ld-card__row_promotion-cards\\\">\\n    <div class=\\\"ld-card__first-column_promotion-cards\\\">\\n\\n        <img src=\\\"{{logo}}\\\">\\n\\n\\n    </div>\\n\\n    <div class=\\\"ld-card__mid-column_promotion-cards\\\">\\n\\n        <h4 class=\\\"ld-card__title--promotion-cards\\\">{{subjectLine}}</h4>\\n        <p class=\\\"ld-card__content--promotion-cards\\\">{{description}}</p>\\n        <p class=\\\"ld-card__promotion-code--promotion-cards\\\">Code {{discountCode}}</p>\\n\\n    </div>\\n\\n    <div class=\\\"ld-card__last-column_promotion-cards\\\">\\n\\n        {{#promotionCards}}\\n        {{{.}}}\\n        {{/promotionCards}}\\n\\n    </div>\\n\\n\\n\\n</div>\\n    <div class=\\\"ld-card__footer\\\">\\n        {{#potentialAction}}<fluent-button class=\\\"ld-card__action-button--fui\\\" appearance=\\\"accent\\\" onclick=\\\"window.open('{{target}}', '_blank');\\\">{{@type}}</fluent-button>{{/potentialAction}}\\n\\n    </div>\\n\\n\\n</fluent-card>\\n\\n\";\n" +
                        "\n" +
                        "var cardReservations = \"<div class=\\\"ld-card\\\">\\n    <div class=\\\"ld-card__header_reservations\\\">\\n        \\n        <!-- Tab links -->\\n        {{#.}}\\n            {{#ld2hIsArray}}\\n            <button type=\\\"button\\\" class=\\\"tablinks {{ld2hTabBarId}} {{#ld2hIsFirst}}active{{/ld2hIsFirst}}{{^ld2hIsFirst}}{{/ld2hIsFirst}}\\\" onclick='openCard(event, \\\"{{ld2hTabId}}\\\", \\\"{{ld2hTabBarId}}\\\");'>{{underName.name}} {{#reservationFor.departureAirport.iataCode}}{{reservationFor.departureAirport.iataCode}}-{{reservationFor.arrivalAirport.iataCode}}{{/reservationFor.departureAirport.iataCode}}{{#reservationFor.trainNumber}}{{reservationFor.trainNumber}}{{/reservationFor.trainNumber}}{{^reservationFor.departureAirport.iataCode}}{{^reservationFor.trainNumber}}{{ld2hElementNumber}}{{/reservationFor.trainNumber}}{{/reservationFor.departureAirport.iataCode}}</button>\\n            {{/ld2hIsArray}}\\n        {{/.}}\\n    </div>\\n    <div class=\\\"ld-card__row_reservations ld-card__row_flight-reservations\\\">\\n        {{^ld2hSubtemplateContent}}\\n            {{> tabContent}}\\n        {{/ld2hSubtemplateContent}}\\n        {{#ld2hSubtemplateContent}}\\n            {{{ld2hSubtemplateContent}}}\\n        {{/ld2hSubtemplateContent}}\\n    </div>\\n</div>\\n\";\n" +
                        "\n" +
                        "var cardReservationsFluent = \"<fluent-card class=\\\"ld-card\\\">\\n    <div class=\\\"ld-card__header_reservations\\\">\\n        \\n        <!-- Tab links -->\\n        <div class=\\\"tab\\\">\\n        {{#.}}\\n            {{#ld2hIsArray}}\\n            <button type=\\\"button\\\" class=\\\"tablinks {{ld2hTabBarId}} {{#ld2hIsFirst}}active{{/ld2hIsFirst}}{{^ld2hIsFirst}}{{/ld2hIsFirst}}\\\" onclick='openCard(event, \\\"{{ld2hTabId}}\\\", \\\"{{ld2hTabBarId}}\\\");'>{{underName.name}} {{#reservationFor.departureAirport.iataCode}}{{reservationFor.departureAirport.iataCode}}-{{reservationFor.arrivalAirport.iataCode}}{{/reservationFor.departureAirport.iataCode}}{{#reservationFor.trainNumber}}{{reservationFor.trainNumber}}{{/reservationFor.trainNumber}}{{^reservationFor.departureAirport.iataCode}}{{^reservationFor.trainNumber}}{{ld2hElementNumber}}{{/reservationFor.trainNumber}}{{/reservationFor.departureAirport.iataCode}}</button>\\n            {{/ld2hIsArray}}\\n        {{/.}}\\n        </div>\\n    </div>\\n    <div class=\\\"ld-card__row_reservations ld-card__row_flight-reservations\\\">\\n        {{^ld2hSubtemplateContent}}\\n            {{> tabContent}}\\n        {{/ld2hSubtemplateContent}}\\n        {{#ld2hSubtemplateContent}}\\n            {{{ld2hSubtemplateContent}}}\\n        {{/ld2hSubtemplateContent}}\\n    </div>\\n</fluent-card>\\n\";\n" +
                        "\n" +
                        "var subArticle = \"<h1 class=\\\"ld-card__title\\\">{{headline}}</h1>\\n{{#articleBody}}\\n    <p class=\\\"ld-card__content\\\">{{articleBody}}</p>\\n{{/articleBody}}\\n\\n{{^articleBody}}\\n    <p class=\\\"ld-card__content\\\">{{description}}</p>\\n{{/articleBody}}\\n\\n\";\n" +
                        "\n" +
                        "var subEmailMessage = \"<p class=\\\"ld-card__content\\\"><strong>Confirmation code:</strong> <span class=\\\"data_to_copy\\\">{{potentialAction.description}}</span>\\n\\n</p>\\n<p class=\\\"ld-card__content\\\"><strong>Expires:</strong> {{expires}}\\n\\n</p>\\n\";\n" +
                        "\n" +
                        "var subFoodEstablishmentReservation = \"<h1 class=\\\"ld-card__title\\\">{{reservationFor.name}}</h1>\\n<p class=\\\"ld-card__content\\\">\\n    <span>{{reservationFor.name}}</span>\\n    <span>{{reservationFor.address.streetAddress}}</span>\\n    <span>{{reservationFor.address.addressLocality}}</span>\\n    <span>{{reservationFor.address.addressRegion}}</span>\\n    <span>{{reservationFor.address.postalCode}}</span>\\n    <span>{{reservationFor.address.addressCountry}}</span>\\n\\n    <br>\\n    <span>{{reservationNumber}}</span>\\n    <span>{{underName.name}}</span>\\n    <span>{{startTime}}</span>\\n</p>\\n\";\n" +
                        "\n" +
                        "var subNewsArticle = \"<h1 class=\\\"ld-card__title\\\"> {{headline}}</h1>\\n{{#articleBody}}\\n    <p class=\\\"ld-card__content\\\">{{articleBody}}</p>\\n{{/articleBody}}\\n\\n{{^articleBody}}\\n    <p class=\\\"ld-card__content\\\">{{description}}</p>\\n{{/articleBody}}\\n\\n\";\n" +
                        "\n" +
                        "var subParcelDelivery = \"<h1 class=\\\"ld-card__title\\\">{{partOfOrder.merchant.name}}\\n</h1>\\n<p class=\\\"ld-card__content\\\">\\n    <span>{{partOfOrder.@type}}</span>\\n    <span>{{partOfOrder.orderNumber}}</span>\\n    <span>{{itemShipped.description}}</span>\\n\\n    <br>\\n\\n    <span>{{pickupTime}}</span>\\n    <span>{{deliveryAddress.name}}</span>\\n    <span>{{deliveryAddress.streetAddress}}</span>\\n    <span>{{deliveryAddress.addressLocality}}</span>\\n    <span>{{deliveryAddress.addressRegion}}</span>\\n    <span>{{deliveryAddress.postalCode}}</span>\\n    <span>{{deliveryAddress.addressCountry}}</span>\\n\\n    <br>\\n\\n    <span>{{expectedArrivalFrom}} - </span>\\n    <span>{{expectedArrivalUntil}}</span>\\n    \\n</p>\\n\\n\";\n" +
                        "\n" +
                        "var subPlace = \"<h1 class=\\\"ld-card__title\\\">{{name}}\\n</h1>\\n\\n<p class=\\\"ld-card__content\\\">{{address}}</p>\\n<p class=\\\"ld-card__content\\\">\\n    <span>{{geo.latitude}}</span>\\n    <span>{{geo.longitude}}</span>\\n</p>\\n\\n\";\n" +
                        "\n" +
                        "var subPromotionCards = \"<div class=\\\"ld-card__promotion-card\\\">\\n\\n    <div class=\\\"ld-card__img_promotion-card\\\">\\n        <img src=\\\"{{image}}\\\" alt=\\\"picture\\\">\\n    </div>\\n\\n    <div class=\\\"ld-card__promotion-card-text\\\">\\n        <h4 class=\\\"ld-card__promotion-card-headline\\\">{{headline}}</h4>\\n        <div class=\\\"ld-card__promotion-card-price\\\">\\n            <div class=\\\"ld-card__promotion-card-previous-price\\\"><p class=\\\"ld-card__promotion-card-previous-price\\\">{{oldPrice}}</p></div>\\n            <div class=\\\"ld-card__promotion-card-discount-price\\\"><p class=\\\"ld-card__promotion-card-discount-price\\\">{{newPrice}}</p></div>\\n        </div>\\n    </div>\\n    \\n</div>\\n\";\n" +
                        "\n" +
                        "var subRentalCarReservation = \"<h1 class=\\\"ld-card__title\\\">{{reservationFor.rentalCompany.name}}</h1>\\n<p class=\\\"ld-card__content\\\">\\n    <span>{{reservationFor.name}}</span>\\n    <span>{{reservationFor.brand.name}}</span>\\n    <span>{{reservationFor.model}}</span>\\n    <span>{{reservationNumber}}</span>\\n    <span>{{underName.name}}</span>\\n\\n    <br>\\n    \\n    <span>{{pickupTime}}</span>\\n    <span>{{pickupLocation.name}}</span>\\n    <span>{{pickupLocation.address.streetAddress}}</span>\\n    <span>{{pickupLocation.address.addressLocality}}</span>\\n    <span>{{pickupLocation.address.addressRegion}}</span>\\n    <span>{{pickupLocation.address.postalCode}}</span>\\n    <span>{{pickupLocation.address.addressCountry}}</span>\\n</p>\\n\";\n" +
                        "\n" +
                        "var subFlightReservation = \"<!-- the part underneath enables \\\"tabs\\\" in case of multiple instances -->\\n{{#ld2hIsArray}}\\n<div id=\\\"{{ld2hTabId}}\\\" class=\\\"tabcontent {{ld2hTabBarId}} ld-card__tab-content_flight-reservations\\\" style=\\\"display: {{#ld2hIsFirst}}flex{{/ld2hIsFirst}}{{^ld2hIsFirst}}none{{/ld2hIsFirst}};\\\">\\n{{/ld2hIsArray}}\\n    <div class=\\\"ld-card__first-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold; margin-bottom: 5px;\\\">Departure :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.departureAirport.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.departureAirport.iataCode}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hStartDate}} <br> {{reservationFor.ld2hStartTime}}</p>\\n    </div>\\n    <div class=\\\"ld-card__mid-column_flight-reservations\\\">\\n        {{> transportIconTemplate}}\\n        <br>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.flightNumber}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.airline.name}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationNumber}}</p>\\n    </div>\\n    <div class=\\\"ld-card__last-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold;margin-bottom: 5px;\\\">Arrival :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.arrivalAirport.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.arrivalAirport.iataCode}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hEndDate}}<br> {{reservationFor.ld2hEndTime}} </p>\\n    </div>\\n{{#ld2hIsArray}}\\n</div>\\n{{/ld2hIsArray}}\\n\";\n" +
                        "\n" +
                        "var subEventReservation = \"{{#ld2hIsArray}}\\n<div id=\\\"{{ld2hTabId}}\\\" class=\\\"tabcontent {{ld2hTabBarId}}\\\" style=\\\"display: {{#ld2hIsFirst}}flex{{/ld2hIsFirst}}{{^ld2hIsFirst}}none{{/ld2hIsFirst}};\\\">\\n{{/ld2hIsArray}}\\n    <div class=\\\"ld-card__first-column_flight-reservations\\\">\\n        {{> imageIconTemplate}}\\n    </div>\\n    <div class=\\\"ld-card__mid-column_flight-reservations\\\" style=\\\"flex-direction: column;align-items: flex-start;\\\">\\n        <h1 class=\\\"ld-card__title\\\">{{reservationFor.name}}</h1>\\n        \\n        <p class=\\\"ld-card__content\\\" style=\\\"padding-left: 0;\\\">{{{reservationFor.location.name}}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"padding-left: 0;\\\">{{{reservationFor.ld2hStartDate}}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"padding-left: 0;\\\">{{{reservationFor.ld2hStartTime}}}</p>\\n    </div>\\n{{#ld2hIsArray}}\\n</div>\\n{{/ld2hIsArray}}\\n\";\n" +
                        "\n" +
                        "var subReservation = \"<h1 class=\\\"ld-card__title\\\">{{reservationFor.name}}</h1>\\n\\n<p class=\\\"ld-card__content\\\">{{{reservationFor.description}}}</p>\\n\";\n" +
                        "\n" +
                        "var subTrainReservation = \"<!-- the part underneath enables \\\"tabs\\\" in case of multiple instances -->\\n{{#ld2hIsArray}}\\n<div id=\\\"{{ld2hTabId}}\\\" class=\\\"tabcontent {{ld2hTabBarId}}\\\" style=\\\"display: {{#ld2hIsFirst}}flex{{/ld2hIsFirst}}{{^ld2hIsFirst}}none{{/ld2hIsFirst}};\\\">\\n{{/ld2hIsArray}}\\n    <div class=\\\"ld-card__first-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold; margin-bottom: 5px;\\\">Departure :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.departureStation.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hStartDate}} <br> {{reservationFor.ld2hStartTime}}</p>\\n    </div>\\n    <div class=\\\"ld-card__mid-column_flight-reservations\\\">\\n        {{> transportIconTemplate}}\\n        <br>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;padding-top: 2px!important;\\\">{{reservationNumber}}</p>\\n    </div>\\n    <div class=\\\"ld-card__last-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold;margin-bottom: 5px;\\\">Arrival :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.arrivalStation.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hEndDate}}<br> {{reservationFor.ld2hEndTime}} </p>\\n    </div>\\n{{#ld2hIsArray}}\\n</div>\\n{{/ld2hIsArray}}\\n\";\n" +
                        "\n" +
                        "var subBusReservation = \"<!-- the part underneath enables \\\"tabs\\\" in case of multiple instances -->\\n{{#ld2hIsArray}}\\n<div id=\\\"{{ld2hTabId}}\\\" class=\\\"tabcontent {{ld2hTabBarId}}\\\" style=\\\"display: {{#ld2hIsFirst}}flex{{/ld2hIsFirst}}{{^ld2hIsFirst}}none{{/ld2hIsFirst}};\\\">\\n{{/ld2hIsArray}}\\n    <div class=\\\"ld-card__first-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold; margin-bottom: 5px;\\\">Departure :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.departureBusStop.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hStartDate}} <br> {{reservationFor.ld2hStartTime}}</p>\\n    </div>\\n    <div class=\\\"ld-card__mid-column_flight-reservations\\\">\\n        {{> transportIconTemplate}}\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationNumber}}</p>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{underName.name}}</p>\\n    </div>\\n    <div class=\\\"ld-card__last-column_flight-reservations\\\">\\n        <!-- <p style=\\\"margin: 0;font-weight: bold;margin-bottom: 5px;\\\">Arrival :</p> -->\\n        <h4 style=\\\"margin: 0;\\\">{{reservationFor.arrivalBusStop.name}}</h4>\\n        <p class=\\\"ld-card__content\\\" style=\\\"margin: 0!important;\\\">{{reservationFor.ld2hEndDate}}<br> {{reservationFor.ld2hEndTime}} </p>\\n    </div>\\n{{#ld2h.IsArray}}\\n</div>\\n{{/ld2h.IsArray}}\\n\";\n" +
                        "\n" +
                        "/*\n" +
                        " * Exports all available templates to be used for rendering\n" +
                        " */\n" +
                        "\n" +
                        "\n" +
                        "// Available templates for exporting\n" +
                        "const allTemplates = {\n" +
                        "    cardDefault,\n" +
                        "    cardDefaultFluent,\n" +
                        "    cardReservationsFluent,\n" +
                        "//    cardDefaultBackgroundImage,\n" +
                        "//    cardDefaultPlaceholder,\n" +
                        "//    cardDefaultSlim,\n" +
                        "//    subDefaultArraySupport,\n" +
                        "    cardReservations,\n" +
                        "    cardPromotionCards,\n" +
                        "    cardPromotionCardsFluent\n" +
                        "};\n" +
                        "\n" +
                        "// Available subtemplates for exporting\n" +
                        "const allSubtemplates = {\n" +
                        "    subDefault,\n" +
                        "    subArticle,\n" +
                        "    subEmailMessage,\n" +
                        "    subEventReservation,\n" +
                        "    subFlightReservation,\n" +
                        "    subFoodEstablishmentReservation,\n" +
                        "    subNewsArticle,\n" +
                        "    subParcelDelivery,\n" +
                        "    subPlace,\n" +
                        "    subPromotionCards,\n" +
                        "    subTrainReservation,\n" +
                        "    subRentalCarReservation,\n" +
                        "    subReservation\n" +
                        "};\n" +
                        "\n" +
                        "/* --- Comment out templates above that you do not want to include in the jsonld2html bundle --- */\n" +
                        "\n" +
                        "/// Mapping schema type to template file\n" +
                        "//   TODO provide function to build templates so leaving out templates works\n" +
                        "//     programmatically with tree-shaking from users library\n" +
                        "const availableTemplates = new Map;\n" +
                        "availableTemplates.set(\"https://ld2h/Default\", cardDefault);\n" +
                        "\n" +
                        "// Custom generic templates. Suitable for more than one type\n" +
                        "if (typeof cardDefaultBackgroundImage !== 'undefined') {\n" +
                        "    availableTemplates.set(\"NewsArticle\", cardDefaultBackgroundImage);\n" +
                        "}\n" +
                        "\n" +
                        "// Custom templates for specific schema.org types\n" +
                        "{\n" +
                        "    availableTemplates.set(\"https://ld2h/PromotionCards\", cardPromotionCards);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableTemplates.set(\"https://ld2h/Reservations\", cardReservations);\n" +
                        "    availableTemplates.set(\"Reservation\", cardReservations);\n" +
                        "    availableTemplates.set(\"EventReservation\", cardReservations);\n" +
                        "    availableTemplates.set(\"FlightReservation\", cardReservations);\n" +
                        "    availableTemplates.set(\"TrainReservation\", cardReservations);\n" +
                        "    availableTemplates.set(\"BusReservation\", cardReservations);\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "/// Mapping schema type to subtemplate file\n" +
                        "const availableSubtemplates = new Map();\n" +
                        "availableSubtemplates.set(\"https://ld2h/Default\", subDefault);\n" +
                        "\n" +
                        "// Custom generic sub templates. Suitable for more than one type\n" +
                        "if (typeof subDefaultArraySupport !== 'undefined') {\n" +
                        "    availableSubtemplates.set(\"???\", subDefaultArraySupport);\n" +
                        "}\n" +
                        "\n" +
                        "// Custom subtemplates for specific JSON-LD types\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"https://ld2h/PromotionCards\", subPromotionCards);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"RentalCarReservation\", subRentalCarReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"ParcelDelivery\", subParcelDelivery);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"FoodEstablishmentReservation\", subFoodEstablishmentReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"NewsArticle\", subNewsArticle);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"Article\", subArticle);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"Place\", subPlace);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"EmailMessage\", subEmailMessage);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"FlightReservation\", subFlightReservation);\n" +
                        "    availableSubtemplates.set(\"https://ld2h/FlightReservations\", subFlightReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"EventReservation\", subEventReservation);\n" +
                        "    availableSubtemplates.set(\"https://ld2h/EventReservations\", subEventReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"Reservation\", subReservation);\n" +
                        "    availableSubtemplates.set(\"https://ld2h/Reservations\", subReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"TrainReservation\", subTrainReservation);\n" +
                        "    availableSubtemplates.set(\"https://ld2h/TrainReservations\", subTrainReservation);\n" +
                        "}\n" +
                        "{\n" +
                        "    availableSubtemplates.set(\"BusReservation\", subBusReservation);\n" +
                        "    availableSubtemplates.set(\"https://ld2h/BusReservations\", subBusReservation);\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "/* Edit above in case you added your own templates. */\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "/**\n" +
                        " * Get subtemplate for a JSON-LD type. Use https://ld2h/Default as fallback.\n" +
                        " * The subtemplate is supposed to be as part of a template.\n" +
                        " *\n" +
                        " * @param {string} jsonLdType: @type of a JSON-LD\n" +
                        " * @returns {string} HTML mustache template\n" +
                        " */\n" +
                        "function getSubtemplateOfType(jsonLdType){\n" +
                        "    if(availableSubtemplates.has(jsonLdType)){\n" +
                        "        return availableSubtemplates.get(jsonLdType)\n" +
                        "    } else {\n" +
                        "        return availableSubtemplates.get(\"https://ld2h/Default\");\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Get template for a JSON-LD type. Use https://ld2h/Default as fallback.\n" +
                        " *\n" +
                        " * @param {string} jsonLdType: @type of a JSON-LD\n" +
                        " * @returns {string} HTML mustache template\n" +
                        " */\n" +
                        "function getTemplateOfType(jsonLdType) {\n" +
                        "    if(availableTemplates.has(jsonLdType)){\n" +
                        "        return availableTemplates.get(jsonLdType)\n" +
                        "    }\n" +
                        "    return availableTemplates.get(\"https://ld2h/Default\");\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Set template for a JSON-LD type.\n" +
                        " * This will override mappings in case a mapping had already existed.\n" +
                        " *\n" +
                        " * @param {string} jsonLdType: @type of a JSON-LD\n" +
                        " * @param {object} template: mustache template to map to\n" +
                        " * @returns {Map} Map containing all template mappings\n" +
                        " */\n" +
                        "function setTemplateOfType(jsonLdType, template) {\n" +
                        "    availableTemplates.set(jsonLdType, template);\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Set subtemplate for a JSON-LD type.\n" +
                        " * This will override mappings in case a mapping had already existed.\n" +
                        " *\n" +
                        " * @param {string} jsonLdType: @type of a JSON-LD\n" +
                        " * @param {object} template: mustache template to map to\n" +
                        " * @returns {Map} Map containing all subtemplate mappings\n" +
                        " */\n" +
                        "function setSubtemplateOfType(jsonLdType, template) {\n" +
                        "    availableSubtemplates.set(jsonLdType, template);\n" +
                        "}\n" +
                        "\n" +
                        "\n" +
                        "function hasSubtemplateOfType(jsonLdType){\n" +
                        "\n" +
                        "    return availableSubtemplates.has(jsonLdType)\n" +
                        "\n" +
                        "}\n" +
                        "function hasTemplateOfType(jsonLdType){\n" +
                        "\n" +
                        "    return availableTemplates.has(jsonLdType)\n" +
                        "\n" +
                        "}\n" +
                        "\n" +
                        "/*!\n" +
                        " * Renders JSON-LD as HTML\n" +
                        " */\n" +
                        "\n" +
                        "var jsonld2html = {\n" +
                        "    name: 'jsonld2html.js',\n" +
                        "    version: '0.0.1'\n" +
                        "};\n" +
                        "\n" +
                        "/**\n" +
                        "* Find values for a specified key in an array of json objects\n" +
                        "* @param object object - the object to be searched\n" +
                        "* @return object Returns the value matching to the provided key\n" +
                        "*/\n" +
                        "function findValueInArray(object,key){\n" +
                        "    // case object is array\n" +
                        "    if(Array.isArray(object)){\n" +
                        "        for (const iterator of object) {\n" +
                        "            if(key in iterator){\n" +
                        "                return iterator[key];\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Create two dedicated properties from startDate of Reservation object\n" +
                        " * @param object jsonObject - JSON-LD object of type Reservation\n" +
                        " * @return object Returns JSON-LD object with two addtional properties\n" +
                        " */\n" +
                        "function splitStartDateTime(jsonObject) {\n" +
                        "    if(\"reservationFor\" in jsonObject)\n" +
                        "    {    if (\"startDate\" in jsonObject.reservationFor) {\n" +
                        "            const startDate = jsonObject.reservationFor.startDate.toString();\n" +
                        "            const iDate = new Date(Date.parse(startDate.split('T')[0]));\n" +
                        "            const iDateTime = new Date(Date.parse(startDate));\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hStartDate\"] = iDate.toLocaleDateString();// TODO I18N\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hStartTime\"] = iDateTime.toLocaleTimeString([], { timeStyle: 'short' }); // TODO I18N\n" +
                        "        }\n" +
                        "        if(\"arrivalTime\" in jsonObject.reservationFor) {\n" +
                        "            const arrivalTime = jsonObject.reservationFor.arrivalTime.toString();\n" +
                        "            const iDate = new Date(Date.parse(arrivalTime.split('T')[0]));\n" +
                        "            const iDateTime = new Date(Date.parse(arrivalTime));\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hEndDate\"] = iDate.toLocaleDateString();\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hEndTime\"] = iDateTime.toLocaleTimeString([], { timeStyle: 'short' }); // TODO I18N\n" +
                        "\n" +
                        "        }\n" +
                        "        if(\"departureTime\" in jsonObject.reservationFor) {\n" +
                        "            const departureTime = jsonObject.reservationFor.departureTime.toString();\n" +
                        "            const iDate = new Date(Date.parse(departureTime.split('T')[0]));\n" +
                        "            const iDateTime = new Date(Date.parse(departureTime));\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hStartDate\"] = iDate.toLocaleDateString();\n" +
                        "            jsonObject[\"reservationFor\"][\"ld2hStartTime\"] = iDateTime.toLocaleTimeString([], { timeStyle: 'short' }); // TODO I18N\n" +
                        "\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    return jsonObject;\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Preprocess a JSON-LD by doing:\n" +
                        " *  * Only return the JSON-LD Object with the mainEntityOfPage property\n" +
                        " *  * Creates an additional viewAction for mainEntityOfPage property\n" +
                        " *  * Extract the image into either the thumbnail or thumbnailUrl property\n" +
                        " * @param object jsonld       - JSON-LD object\n" +
                        " * @param bool   stripActions - Whether to strip actions from the JSON-LD. This will avoid rendering buttons.\n" +
                        " * @return object Returns the preprocessed JSON-LD object\n" +
                        " */\n" +
                        "function preprocess(jsonLd, stripActions = false) {\n" +
                        "    jsonLd = getMainEntity(jsonLd);\n" +
                        "    if (stripActions) {\n" +
                        "        delete jsonLd[\"potentialAction\"];\n" +
                        "    } else {\n" +
                        "        jsonLd = createPotentialViewAction(jsonLd);\n" +
                        "    }\n" +
                        "    return extractImage(jsonLd);\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * @param {object} jsonLd - As a parsed object\n" +
                        " * @param {string} template - The mustache template as a string\n" +
                        " * @param {string} arrayType - Optional type if the provided jsonLd dont have one. For example if arrays of Objects need to be rendered\n" +
                        " * @returns {string} Returns rendered card template\n" +
                        " */\n" +
                        "function renderFromTemplate(jsonLd, template, arrayType = \"\") {\n" +
                        "    let partials = {headerIconTemplate, imageIconTemplate,transportIconTemplate};\n" +
                        "\n" +
                        "    // Determine icon based on schema type\n" +
                        "    // Prefer arrayType over actual @type, fallback to https://ld2h/Default\n" +
                        "    if(arrayType !== \"\"){\n" +
                        "        for (const jsonLditem of jsonLd) {\n" +
                        "            jsonLditem[\"iconName\"] = typeToIconMap.get(arrayType);\n" +
                        "        }\n" +
                        "    } else if(jsonLd[\"@type\"] !== undefined && typeToIconMap.has(jsonLd[\"@type\"])) {\n" +
                        "        jsonLd[\"iconName\"] = typeToIconMap.get(jsonLd[\"@type\"]);\n" +
                        "    } else {\n" +
                        "        jsonLd[\"iconName\"] = typeToIconMap.get(\"https://ld2h/Default\");\n" +
                        "    }\n" +
                        "\n" +
                        "\n" +
                        "    // ===== Special case \"PromotionCards\" =====\n" +
                        "    if(arrayType === \"https://ld2h/PromotionCards\" &&\n" +
                        "        getSubtemplateOfType(\"https://ld2h/PromotionCards\") == allSubtemplates.subPromotionCards){\n" +
                        "\n" +
                        "        let mustacheDataObj = new Object();\n" +
                        "        mustacheDataObj[\"promotionCards\"] = [];\n" +
                        "        mustacheDataObj[\"logo\"] = findValueInArray(jsonLd,\"logo\");\n" +
                        "        mustacheDataObj[\"subjectLine\"] = findValueInArray(jsonLd,\"subjectLine\");\n" +
                        "        mustacheDataObj[\"description\"] = findValueInArray(jsonLd,\"description\");\n" +
                        "        mustacheDataObj[\"discountCode\"] = findValueInArray(jsonLd,\"discountCode\");\n" +
                        "        let foundObjects = [];\n" +
                        "        for (const iterator of jsonLd) {\n" +
                        "            if(iterator[\"@type\"] === \"PromotionCard\"){\n" +
                        "                foundObjects.push(iterator);\n" +
                        "            }\n" +
                        "        }\n" +
                        "        for (const obj of foundObjects){\n" +
                        "            let promoCard = {\n" +
                        "                image: obj[\"image\"],\n" +
                        "                headline: obj[\"headline\"],\n" +
                        "                discountValue: obj[\"discountValue\"],\n" +
                        "                newPrice: String(obj[\"price\"] - obj[\"discountValue\"]),\n" +
                        "                oldPrice: String(obj[\"price\"]),\n" +
                        "                priceCurrency: obj[\"priceCurrency\"]\n" +
                        "            };\n" +
                        "            mustacheDataObj[\"promotionCards\"].push(mustache.render(getSubtemplateOfType(arrayType), transformArrayProperties(promoCard)));\n" +
                        "        }\n" +
                        "        // Do not transform Arrays here as PromotionCards are inside an array\n" +
                        "        let finalCard = mustache.render(template, mustacheDataObj, partials);\n" +
                        "        return finalCard;\n" +
                        "    }\n" +
                        "\n" +
                        "    // ===== Custom base template with custom subtemplate\n" +
                        "    // Case: Reservation base template with Reservation subtemplate\n" +
                        "    if (arrayType != \"\" && arrayType.endsWith(\"Reservations\")){\n" +
                        "        console.log(\"Applying special rendering for Reservations\");\n" +
                        "        // Creating ID for the bar to wire it with its tabs\n" +
                        "        let tabBarId = \"bar\" + Math.floor(Math.random() * 1000000000000001);\n" +
                        "\n" +
                        "        // adding an synthetic ID to the instances\n" +
                        "        let i = 1;\n" +
                        "        let first = true;\n" +
                        "        let output = \"\";\n" +
                        "\n" +
                        "        // wire the IDs to each Reservation item\n" +
                        "        for (let iterator of jsonLd) {\n" +
                        "            // Creating tab specific values\n" +
                        "            iterator[\"ld2hTabBarId\"] = tabBarId;\n" +
                        "\n" +
                        "            if (first) {\n" +
                        "                iterator[\"ld2hIsFirst\"] = true;\n" +
                        "                first = false;\n" +
                        "                iterator[\"ld2hIsArray\"] = false;\n" +
                        "            }\n" +
                        "            // TODO move this flag assignment to \"preprocessing\", we check there anyway,\n" +
                        "            iterator[\"ld2hIsArray\"] = true;\n" +
                        "            iterator[\"ld2hTabId\"] = tabBarId + i;\n" +
                        "            iterator[\"ld2hElementNumber\"] = i;\n" +
                        "\n" +
                        "            iterator = transformArrayProperties(iterator);\n" +
                        "            iterator = splitStartDateTime(iterator);\n" +
                        "\n" +
                        "            // Fallbak to https://ld2h/Reservations for Reservations without dedicated subtemplate\n" +
                        "            if (hasSubtemplateOfType(arrayType)) {\n" +
                        "                output += mustache.render(getSubtemplateOfType(arrayType), transformArrayProperties(iterator), partials);\n" +
                        "            } else {\n" +
                        "                output += mustache.render(getSubtemplateOfType(\"https://ld2h/Reservations\"), transformArrayProperties(iterator), partials);\n" +
                        "            }\n" +
                        "            i++;\n" +
                        "        }\n" +
                        "\n" +
                        "        partials[\"tabContent\"] = output;\n" +
                        "\n" +
                        "        if (arrayType.endsWith(\"Reservations\") && !hasTemplateOfType(arrayType)) {\n" +
                        "            return mustache.render(getTemplateOfType(\"https://ld2h/Reservations\"), jsonLd, partials);\n" +
                        "        }\n" +
                        "\n" +
                        "        // Do not transform Arrays here as FlightReservations are inside an array\n" +
                        "        return mustache.render(getTemplateOfType(arrayType), jsonLd, partials);\n" +
                        "    }\n" +
                        "\n" +
                        "    jsonLd = transformArrayProperties(jsonLd);\n" +
                        "    let subTemplate;\n" +
                        "    if (jsonLd[\"@type\"].endsWith(\"Reservation\")){\n" +
                        "        if (jsonLd[\"@type\"] === \"EventReservation\") {\n" +
                        "            jsonLd = splitStartDateTime(jsonLd);\n" +
                        "        }\n" +
                        "        if (!hasSubtemplateOfType(jsonLd[\"@type\"])) {\n" +
                        "            subTemplate = getSubtemplateOfType(\"https://ld2h/Reservations\");\n" +
                        "        }\n" +
                        "        if(jsonLd[\"@type\"] === \"FlightReservation\"){\n" +
                        "            jsonLd = splitStartDateTime(jsonLd);\n" +
                        "        }\n" +
                        "        if(jsonLd[\"@type\"] === \"TrainReservation\"){\n" +
                        "            jsonLd = splitStartDateTime(jsonLd);\n" +
                        "        }\n" +
                        "        if(jsonLd[\"@type\"] === \"BusReservation\"){\n" +
                        "            jsonLd = splitStartDateTime(jsonLd);\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    subTemplate = getSubtemplateOfType(jsonLd[\"@type\"]);\n" +
                        "    let output =  mustache.render(subTemplate, jsonLd, partials);\n" +
                        "    jsonLd[\"ld2hSubtemplateContent\"] = output;\n" +
                        "\n" +
                        "    // Log unmatched fields\n" +
                        "    if(jsonLd[\"name\"] === undefined || jsonLd[\"name\"] === \"\"){\n" +
                        "        console.log(`in ${jsonLd[\"@type\"]}[name] property not found`);\n" +
                        "    }\n" +
                        "    if(jsonLd[\"description\"] === undefined || jsonLd[\"description\"] === \"\"){\n" +
                        "        console.log(`in ${jsonLd[\"@type\"]}[description] property not found`);\n" +
                        "    }\n" +
                        "\n" +
                        "    return mustache.render(template, jsonLd, partials);\n" +
                        "}\n" +
                        "\n" +
                        "/**\n" +
                        " * Return HTML of an input JSON-LD. Inlcudes preprocessing.\n" +
                        " *\n" +
                        " * @param object jsonld        - JSON-LD object\n" +
                        " * @param bool   doPreprocess  - Whether to do some preprocessing in general.\n" +
                        " * @param bool   renderButtons - Whether to strip actions from the JSON-LD. This will avoid rendering buttons.\n" +
                        " * @return object Returns the preprocessed JSON-LD object\n" +
                        " */\n" +
                        "function render(jsonLdin, renderButtons = true, doPreprocess = true) {\n" +
                        "    // Fallback to FontAwesome in case no icon\n" +
                        "    if (typeToIconMap.size == 0) {\n" +
                        "        setIcons(icons.FontAwesome);\n" +
                        "    }\n" +
                        "\n" +
                        "    let jsonLd = structuredClone(jsonLdin);\n" +
                        "\n" +
                        "    // Detect special json-lds which cannot be determined after getMainEntity\n" +
                        "    // Bypass getMainEntity for special cases\n" +
                        "    // Special case: Reservation\n" +
                        "    if(Array.isArray(jsonLd)){\n" +
                        "        let isReservationArray = false;\n" +
                        "        // Check if all Elements are Reservations\n" +
                        "        for (const iterator of jsonLd) {\n" +
                        "            if(iterator[\"@type\"] !== undefined\n" +
                        "                    && iterator[\"@type\"].endsWith(\"Reservation\")){\n" +
                        "                isReservationArray = true;\n" +
                        "            }\n" +
                        "        }\n" +
                        "        let type = `https://ld2h/${jsonLd[0][\"@type\"]}s`;\n" +
                        "        if(isReservationArray) {\n" +
                        "            console.log(\"Applying special rendering for JSON-LD array\");\n" +
                        "            if (hasSubtemplateOfType(type) && hasTemplateOfType(type)) {\n" +
                        "                return renderFromTemplate(jsonLd, getTemplateOfType(type), type);\n" +
                        "            } else {\n" +
                        "                return renderFromTemplate(jsonLd, getTemplateOfType(\"https://ld2h/Reservations\"), type);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    // Special case: \"PromotionCard\"\n" +
                        "    if(Array.isArray(jsonLd))\n" +
                        "    {\n" +
                        "        let promoCardCounter = 0;\n" +
                        "        for (const iterator of jsonLd) {\n" +
                        "            // The most noticeable attribute of a \"PromotionCard\" is: containing three of them\n" +
                        "            if(iterator[\"@type\"] === \"PromotionCard\"){\n" +
                        "                promoCardCounter +=1;\n" +
                        "            }\n" +
                        "        }\n" +
                        "        if(promoCardCounter === 3){\n" +
                        "            let arrayType = \"https://ld2h/PromotionCards\";\n" +
                        "            return renderFromTemplate(jsonLd,getTemplateOfType(arrayType),arrayType);\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    if (!doPreprocess && !renderButtons) {\n" +
                        "        console.error(\"Not rendering buttons requires preprocessing. Will preprocess anyway.\");\n" +
                        "    }\n" +
                        "    if (doPreprocess || !renderButtons) {\n" +
                        "        jsonLd = preprocess(jsonLd, !renderButtons);\n" +
                        "    }\n" +
                        "\n" +
                        "    return renderFromTemplate(jsonLd, getTemplateOfType(jsonLd[\"@type\"]));\n" +
                        "}\n" +
                        "\n" +
                        "jsonld2html.render = render;\n" +
                        "\n" +
                        "jsonld2html.renderFromTemplate = renderFromTemplate;\n" +
                        "\n" +
                        "jsonld2html.getMainEntity = getMainEntity;\n" +
                        "\n" +
                        "jsonld2html.createPotentialViewAction = createPotentialViewAction;\n" +
                        "\n" +
                        "jsonld2html.extractImage = extractImage;\n" +
                        "\n" +
                        "jsonld2html.preprocess = preprocess;\n" +
                        "\n" +
                        "jsonld2html.allTemplates = allTemplates;\n" +
                        "\n" +
                        "jsonld2html.allSubtemplates = allSubtemplates;\n" +
                        "\n" +
                        "jsonld2html.setTemplateOfType = setTemplateOfType;\n" +
                        "\n" +
                        "jsonld2html.setSubtemplateOfType = setSubtemplateOfType;\n" +
                        "\n" +
                        "jsonld2html.setIcons = setIcons;\n" +
                        "\n" +
                        "jsonld2html.icons = icons;\n" +
                        "\n" +
                        "        let jsonLdInput =`"+ jsonld+ "`\n" +
                        "        document.addEventListener('DOMContentLoaded', () => {\n" +
                        "            let jsonLdInputDes = JSON.parse(jsonLdInput);\n" +
                        "            document.getElementById(\"output-target\").innerHTML = jsonld2html.render(jsonLdInputDes);\n" +
                        "        });\n" +
                        "    </script>";
                    sanitizedHtml = css + snippet1 + snippet2 + sanitizedHtml;
                }
            }

            return new ViewableExtractedText(text.toString(), sanitizedHtml);
        } catch (Exception e) {
            throw new MessagingException("Couldn't extract viewable parts", e);
        }
    }

    /**
     * Use the contents of a {@link com.fsck.k9.mail.internet.Viewable} to create the HTML to be displayed.
     *
     * <p>
     * This will use {@link HtmlConverter#textToHtml(String)} to convert plain text parts
     * to HTML if necessary.
     * </p>
     *
     * @param viewable
     *         The viewable part to build the HTML from.
     * @param prependDivider
     *         {@code true}, if the HTML divider should be inserted as first element.
     *         {@code false}, otherwise.
     *
     * @return The contents of the supplied viewable instance as HTML.
     */
    private StringBuilder buildHtml(Viewable viewable, boolean prependDivider) {
        StringBuilder html = new StringBuilder();
        if (viewable instanceof Textual) {
            Part part = ((Textual)viewable).getPart();
            addHtmlDivider(html, part, prependDivider);

            String t = getTextFromPart(part);
            if (t == null) {
                t = "";
            } else if (viewable instanceof Text) {
                t = HtmlConverter.textToHtml(t);
            } else if (!(viewable instanceof Html)) {
                throw new IllegalStateException("unhandled case!");
            }
            html.append(t);
        } else if (viewable instanceof Alternative) {
            // That's odd - an Alternative as child of an Alternative; go ahead and try to use the
            // text/html child; fall-back to the text/plain part.
            Alternative alternative = (Alternative) viewable;

            List<Viewable> htmlAlternative = alternative.getHtml().isEmpty() ?
                    alternative.getText() : alternative.getHtml();

            boolean divider = prependDivider;
            for (Viewable htmlViewable : htmlAlternative) {
                html.append(buildHtml(htmlViewable, divider));
                divider = true;
            }
        }

        return html;
    }

    private StringBuilder buildText(Viewable viewable, boolean prependDivider) {
        StringBuilder text = new StringBuilder();
        if (viewable instanceof Textual) {
            Part part = ((Textual)viewable).getPart();
            addTextDivider(text, part, prependDivider);

            String t = getTextFromPart(part);
            if (t == null) {
                t = "";
            } else if (viewable instanceof Html) {
                t = HtmlConverter.htmlToText(t);
            } else if (!(viewable instanceof Text)) {
                throw new IllegalStateException("unhandled case!");
            }
            text.append(t);
        } else if (viewable instanceof Alternative) {
            // That's odd - an Alternative as child of an Alternative; go ahead and try to use the
            // text/plain child; fall-back to the text/html part.
            Alternative alternative = (Alternative) viewable;

            List<Viewable> textAlternative = alternative.getText().isEmpty() ?
                    alternative.getHtml() : alternative.getText();

            boolean divider = prependDivider;
            for (Viewable textViewable : textAlternative) {
                text.append(buildText(textViewable, divider));
                divider = true;
            }
        }

        return text;
    }

    /**
     * Add an HTML divider between two HTML message parts.
     *
     * @param html
     *         The {@link StringBuilder} to append the divider to.
     * @param part
     *         The message part that will follow after the divider. This is used to extract the
     *         part's name.
     * @param prependDivider
     *         {@code true}, if the divider should be appended. {@code false}, otherwise.
     */
    private void addHtmlDivider(StringBuilder html, Part part, boolean prependDivider) {
        if (prependDivider) {
            String filename = getPartName(part);

            html.append("<p style=\"margin-top: 2.5em; margin-bottom: 1em; border-bottom: 1px solid #000\">");
            html.append(TextUtils.htmlEncode(filename));
            html.append("</p>");
        }
    }

    private String getTextFromPart(Part part) {
        String textFromPart = MessageExtractor.getTextFromPart(part);

        String extractedClearsignedMessage = OpenPgpUtils.extractClearsignedMessage(textFromPart);
        if (extractedClearsignedMessage != null) {
            textFromPart = extractedClearsignedMessage;
        }

        return textFromPart;
    }

    /**
     * Get the name of the message part.
     *
     * @param part
     *         The part to get the name for.
     *
     * @return The (file)name of the part if available. An empty string, otherwise.
     */
    private static String getPartName(Part part) {
        String disposition = part.getDisposition();
        if (disposition != null) {
            String name = getHeaderParameter(disposition, "filename");
            return (name == null) ? "" : name;
        }

        return "";
    }

    /**
     * Add a plain text divider between two plain text message parts.
     *
     * @param text
     *         The {@link StringBuilder} to append the divider to.
     * @param part
     *         The message part that will follow after the divider. This is used to extract the
     *         part's name.
     * @param prependDivider
     *         {@code true}, if the divider should be appended. {@code false}, otherwise.
     */
    private void addTextDivider(StringBuilder text, Part part, boolean prependDivider) {
        if (prependDivider) {
            String filename = getPartName(part);

            text.append("\r\n\r\n");
            int len = filename.length();
            if (len > 0) {
                if (len > TEXT_DIVIDER_LENGTH - FILENAME_PREFIX_LENGTH - FILENAME_SUFFIX_LENGTH) {
                    filename = filename.substring(0, TEXT_DIVIDER_LENGTH - FILENAME_PREFIX_LENGTH -
                            FILENAME_SUFFIX_LENGTH - 3) + "...";
                }
                text.append(FILENAME_PREFIX);
                text.append(filename);
                text.append(FILENAME_SUFFIX);
                text.append(TEXT_DIVIDER.substring(0, TEXT_DIVIDER_LENGTH -
                        FILENAME_PREFIX_LENGTH - filename.length() - FILENAME_SUFFIX_LENGTH));
            } else {
                text.append(TEXT_DIVIDER);
            }
            text.append("\r\n\r\n");
        }
    }

    /**
     * Extract important header values from a message to display inline (plain text version).
     *
     * @param text
     *         The {@link StringBuilder} that will receive the (plain text) output.
     * @param message
     *         The message to extract the header values from.
     *
     * @throws com.fsck.k9.mail.MessagingException
     *          In case of an error.
     */
    private void addMessageHeaderText(StringBuilder text, Message message)
            throws MessagingException {
        // From: <sender>
        Address[] from = message.getFrom();
        if (from != null && from.length > 0) {
            text.append(resourceProvider.messageHeaderFrom());
            text.append(' ');
            text.append(Address.toString(from));
            text.append("\r\n");
        }

        // To: <recipients>
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        if (to != null && to.length > 0) {
            text.append(resourceProvider.messageHeaderTo());
            text.append(' ');
            text.append(Address.toString(to));
            text.append("\r\n");
        }

        // Cc: <recipients>
        Address[] cc = message.getRecipients(Message.RecipientType.CC);
        if (cc != null && cc.length > 0) {
            text.append(resourceProvider.messageHeaderCc());
            text.append(' ');
            text.append(Address.toString(cc));
            text.append("\r\n");
        }

        // Date: <date>
        Date date = message.getSentDate();
        if (date != null) {
            text.append(resourceProvider.messageHeaderDate());
            text.append(' ');
            text.append(date.toString());
            text.append("\r\n");
        }

        // Subject: <subject>
        String subject = message.getSubject();
        text.append(resourceProvider.messageHeaderSubject());
        text.append(' ');
        if (subject == null) {
            text.append(resourceProvider.noSubject());
        } else {
            text.append(subject);
        }
        text.append("\r\n\r\n");
    }

    /**
     * Extract important header values from a message to display inline (HTML version).
     *
     * @param html
     *         The {@link StringBuilder} that will receive the (HTML) output.
     * @param message
     *         The message to extract the header values from.
     *
     * @throws com.fsck.k9.mail.MessagingException
     *          In case of an error.
     */
    private void addMessageHeaderHtml(StringBuilder html, Message message)
            throws MessagingException {

        html.append("<table style=\"border: 0\">");

        // From: <sender>
        Address[] from = message.getFrom();
        if (from != null && from.length > 0) {
            addTableRow(html, resourceProvider.messageHeaderFrom(),
                    Address.toString(from));
        }

        // To: <recipients>
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        if (to != null && to.length > 0) {
            addTableRow(html, resourceProvider.messageHeaderTo(),
                    Address.toString(to));
        }

        // Cc: <recipients>
        Address[] cc = message.getRecipients(Message.RecipientType.CC);
        if (cc != null && cc.length > 0) {
            addTableRow(html, resourceProvider.messageHeaderCc(),
                    Address.toString(cc));
        }

        // Date: <date>
        Date date = message.getSentDate();
        if (date != null) {
            addTableRow(html, resourceProvider.messageHeaderDate(),
                    date.toString());
        }

        // Subject: <subject>
        String subject = message.getSubject();
        addTableRow(html, resourceProvider.messageHeaderSubject(),
                (subject == null) ? resourceProvider.noSubject() : subject);

        html.append("</table>");
    }

    /**
     * Output an HTML table two column row with some hardcoded style.
     *
     * @param html
     *         The {@link StringBuilder} that will receive the output.
     * @param header
     *         The string to be put in the {@code TH} element.
     * @param value
     *         The string to be put in the {@code TD} element.
     */
    private static void addTableRow(StringBuilder html, String header, String value) {
        html.append("<tr><th style=\"text-align: left; vertical-align: top;\">");
        html.append(TextUtils.htmlEncode(header));
        html.append("</th>");
        html.append("<td>");
        html.append(TextUtils.htmlEncode(value));
        html.append("</td></tr>");
    }

    @VisibleForTesting
    static class ViewableExtractedText {
        public final String text;
        public final String html;

        ViewableExtractedText(String text, String html) {
            this.text = text;
            this.html = html;
        }
    }
}
