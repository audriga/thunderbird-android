package com.fsck.k9.mailstore;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import android.net.Uri;
import android.net.Uri.Builder;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Pair;
import android.util.Patterns;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import app.k9mail.legacy.account.Account;
import app.k9mail.legacy.di.DI;
import app.k9mail.legacy.message.controller.MessageReference;
import app.k9mail.legacy.message.controller.SimpleMessagingListener;
import com.audriga.jakarta.sml.h2lj.model.StructuredData;
import com.audriga.jakarta.sml.h2lj.model.StructuredSyntax;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fsck.k9.Preferences;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.internet.TextBody;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import org.apache.james.mime4j.util.MimeUtil;
import org.audriga.ld2h.ButtonDescription;
import org.audriga.ld2h.JsonLdDeserializer;
import org.audriga.ld2h.MustacheRenderer; // todo this version causes an exception when created
import org.audriga.ld2h.TemplateLoader;
import com.fsck.k9.CoreResourceProvider;
import com.fsck.k9.controller.MessagingController;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mnode.ical4j.serializer.jsonld.EventJsonLdSerializer;
import org.openintents.openpgp.util.OpenPgpUtils;
import timber.log.Timber;
//import app.cash.barber.Barber;
//import com.github.mustachejava.DefaultMustacheFactory;
//import com.github.mustachejava.Mustache;
//import com.github.mustachejava.MustacheFactory;
import com.audriga.jakarta.sml.h2lj.parser.StructuredDataExtractionUtils;


import static com.fsck.k9.mail.internet.MimeUtility.getHeaderParameter;
import static com.fsck.k9.mail.internet.MimeUtility.isSameMimeType;
import static com.fsck.k9.mail.internet.Viewable.Alternative;
import static com.fsck.k9.mail.internet.Viewable.Html;
import static com.fsck.k9.mail.internet.Viewable.MessageHeader;
import static com.fsck.k9.mail.internet.Viewable.Text;
import static com.fsck.k9.mail.internet.Viewable.Textual;
import static org.apache.commons.io.IOUtils.close;
import org.apache.james.mime4j.codec.Base64InputStream;
import static org.json.JSONObject.NULL;


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
        MessageReference reference = null;
        if (message instanceof LocalMessage) {
            reference = ((LocalMessage) message).makeMessageReference();
        }
        ViewableExtractedText viewable = extractViewableAndAttachments(
                Collections.singletonList(contentPart), attachmentInfos);
        AttachmentResolver attachmentResolver = AttachmentResolver.createFromPart(contentPart);
        boolean isMessageIncomplete =
                !message.isSet(Flag.X_DOWNLOADED_FULL) || MessageExtractor.hasMissingParts(message);

        UnsubscribeUri preferredUnsubscribeUri = ListUnsubscribeHelper.INSTANCE.getPreferredListUnsubscribeUri(message);
//        //Send on render
//        MessagingController mc = DI.get(MessagingController.class);
//        Preferences preferences = DI.get(Preferences.class);
//        Account account = preferences.getDefaultAccount();
//        mc.sendMessageBlocking(account, message);
        return MessageViewInfo.createWithExtractedContent(
                message, contentPart, isMessageIncomplete, viewable.html, attachmentInfos, attachmentResolver,
                preferredUnsubscribeUri);
    }

    private ViewableExtractedText extractViewableAndAttachments(List<Part> parts,
            List<AttachmentViewInfo> attachmentInfos) throws MessagingException {
        MessagingController mc = DI.get(MessagingController.class);
        ArrayList<Viewable> viewableParts = new ArrayList<>();
        ArrayList<Part> attachments = new ArrayList<>();
        ArrayList<Part> parseableParts = new ArrayList<>();
        HashMap<AttachmentViewInfo, String> parseableAttachments = new HashMap<>();

        for (Part part : parts) {
            MessageExtractor.findViewablesAndAttachments(part, viewableParts, attachments, parseableParts);
        }

        attachmentInfos.addAll(attachmentInfoExtractor.extractAttachmentInfoForView(attachments));
        for (AttachmentViewInfo attachmentViewInfo :
            attachmentInfos) {
            String filename = attachmentViewInfo.displayName;
            if (filename != null && filename.lastIndexOf('.') != -1) {
                String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.US);
                ArrayList<String> extensionsOfParseable = new ArrayList<>(List.of("pkpass", "vcard", "ics"));
                if (extensionsOfParseable.contains(extension)) {
                    if (!attachmentViewInfo.isContentAvailable()) {
                        LocalPart localPart = (LocalPart) attachmentViewInfo.part;
                        String accountUuid = localPart.getAccountUuid();
                        Account account = Preferences.getPreferences().getAccount(accountUuid);
                        LocalMessage message = localPart.getMessage();
                        // this copies parts of downloadAttachment, from AttachmentController
                        mc.loadAttachment(account, message, attachmentViewInfo.part, new SimpleMessagingListener() {
                            @Override
                            public void loadAttachmentFinished(Account account, Message message, Part part) {
                                attachmentViewInfo.setContentAvailable();
                            }

                            @Override
                            public void loadAttachmentFailed(Account account, Message message, Part part, String reason) {
                                //
                            }
                        });
                    }
                    // todo: Wait until all attachments have loaded(?)
                    parseableAttachments.put(attachmentViewInfo, extension);
                }
            }
        }
        return extractTextFromViewables(viewableParts, parseableParts, parseableAttachments);
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
    ViewableExtractedText extractTextFromViewables(List<Viewable> viewables, @Nullable ArrayList<Part> parseableParts, @Nullable HashMap<AttachmentViewInfo, String> parseableAttachments)
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
//
//            String s1 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n     <div class=\"mdc-card__media mdc-card__media--square demo-card__media\">\r\n        <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\"><!--!Font Awesome Free 6.6.0 by @fontawesome - https://fontawesome.com License - https://fontawesome.com/license/free Copyright 2024 Fonticons, Inc.--><path d=\"M336 352c97.2 0 176-78.8 176-176S433.2 0 336 0S160 78.8 160 176c0 18.7 2.9 36.8 8.3 53.7L7 391c-4.5 4.5-7 10.6-7 17l0 80c0 13.3 10.7 24 24 24l80 0c13.3 0 24-10.7 24-24l0-40 40 0c13.3 0 24-10.7 24-24l0-40 40 0c6.4 0 12.5-2.5 17-7l33.3-33.3c16.9 5.4 35 8.3 53.7 8.3zM376 96a40 40 0 1 1 0 80 40 40 0 1 1 0-80z\"/></svg>\r\n     </div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\">Your confirmation code: <strong>ABCDE123</strong> <span class=\"data_to_copy\"></span></p>\r\n        <!-- p class=\"ld-card__content\"><strong>Expires:</strong> </p -->\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"file:ABCDE123\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Copy</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//            String s2img = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxMSEhUSExMWFRUXGRcaFhgYGBoeGhgaFxgdGhgeFxgYHCggGholHRcYITEhJSkrLy4uGCAzODMsNygtLisBCgoKDg0OGhAQGzIlHyUvLS0tLS0vLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAN0A5AMBIgACEQEDEQH/xAAbAAACAgMBAAAAAAAAAAAAAAAFBgMEAQIHAP/EAEUQAAIBAgQCCAMECAUDAwUAAAECEQADBBIhMQVBBhMiUWFxgZEyobFCUsHRFCNicoKSsvAHQ1PC4RWi0jNj8SQ0k7PT/8QAGQEAAwEBAQAAAAAAAAAAAAAAAQIDAAQF/8QAJxEAAgICAgIBBAIDAAAAAAAAAAECERIhAzFBURMEImFxMoFiofD/2gAMAwEAAhEDEQA/AABrNuZAG8iPOdK8awtzKQ33SD7Gakz0bDmL/TyERsrCS0QNcscwf2qtJxq+sC5grbwNwCD7xQxum1q4+dlYQOajWfBT/etTt0uw7c1U9+Rx9K4Wv8SlfksYzi9mYfBsv7v/ACRQ58ZhidBdXwKzH8pNWb3HbeY/r1B03Yjl41ovEQxDK6MRscwNDS9mpldsRhyCDcjwZGG/mKkscQw1uMrDMNZVWJO3hl5d9WHxDHe2razouvrlqF76fasqPcUajLtg+9eCn0g6RsP1doMAyoczQNCBEAeexpfGIvLs7e802YyzZ6xSyEjIm0SBkGmtbNh8G3+oPRT+IqsMIrRKWbYqf9avrrMneSsH3EU39Hf8QBITFKBP+Yo0n9pdx5j2qliuG21XPmIXvI5Exypi4Vw2zbAa2qtP29CT5HkPKhOUK6Ck32OCY+wAGNxYidNf6ZrJ4/YH32/dT8yKDpZ7tKnWwx1nTyoRkScAhb6SW50tXPUAfjpUrdIVBg2mHqKH/ozcz8qlODBEHWqqbEcUErfHEP2H/wC38xUn/U7R3DDzX/xmhdjBx/f4VIcKBzI8j+FUTJtISumnSTGktbsWblq1Bm4Vln5dkiQg+fltXOLPVjtMwB3bmxkxqT4n513PiGMt2LZa8yqgGpPPwA5nwFcT45xE38RcNkFLRbsKQNiO7YbExymKWSK8brwUsTxULpbX1b+/yqB7j3LCkkkm7cnu0RI/qNW14fPxa+g/AUXw/Dx1Schnuf026TKMS1NgGxwgv2yQoAQeMqqg/MGiVnDqk6cyde8+FFbvCrmfLaUssTPIT3k7b6ipLfCrKH/6jEKp3yJq3zGntS5p9szT9FXC286/FEyNoCxr9CKJYfgybgNc7zEL/MaiXjVizph7OuhzuSSSNjroPaq2L43iLxktA8PzP4VN2x0E7+EVRLFUXlrA+erHyqs+NsDRFLk7bqs+ZlvlQl7RYyxJPeTJ9zWBYraDsI2+OXABFu2vhlmPMk16quSvULDiSk1pcEg1sa1IrtZMBDhjRIcfMVCmEuG29wbICXH2hHgRRvqYyjWDz8ht4/8AFFOD8LbEC7ZeVW4pBjlBBXzgifGvOh9TK1Z0T4Uk2gBieEYh/wBYlsshiDI5abEzuK36OdGb+NAdFVbcxneQDG+URLfTxp4uY9US9mUjqiggR2gdFju2q50c4qyRbZMymWXIvaQGDlYbHUtrppFVfNLZDEr8N/w4trGe7dYj7hyD8T86ZsF0WsoNFZh+3cuN/UxA9KK28SOSkn2Hz1+VSfpz8kUeZJ/Kh32TcpeCsejlg72kOw1HICBv4Cq13oxhzvZX0JH0Iox+l3e5PY/nWGxVzmq+k/nVEoiZSFLivRG2ylVdl7gxzLpt4/OkROI3MFee31cFTDBXJVvGG8PI103pG+Je0RhurVzzYnb9nSJ89K45iLF5XYXhFwE5gTJnmSY1mjGKZWDb7OldHek9i/CnsP8AceNf3Ts3lvTZZRW5D2/EVwTI3cvuaa+jfTW9YIS8puW9pkF1HrGYeB18aHx10GcG+jrVmzHf71u+EB1DH3oBZ6Z4AiTiGB7jbcf7D9a1udPOHLp1zsYJgW35CTqVA2FUSRzOMvQedABrQbi/G7dnszmfkvd+8eX1pG6S/wCIdy9KYNXRObkA3DPdEhR4iT5UkDiF8HRrnfrJ95FZvwikePzIbukSvfJe4+Zvsj7Kg8lGw29e+g9jhnakDxnzoQeP39s+/wCys/Sm/h/RHGXlHX3+rH3VAzeRywAfU1KSmu2XTgioLdm0JuOo8zr6Dc+lQY7pDbW2htW84zOAW7IlchOm+zL3U3YT/DjCjVusuHmWaP6QD86M4foNgwoXqAQCSJZzqYB+Jj91fatHjT72CXMl0ch4xx+/chQ2RcqkhTG6g7jlrQ/hVqXJP3dfOQK7lc6CYNt8OvIaFxoBA2buqhif8PsEgLDNZABls5IA01PWTpp3irYUqRJ8yb2c1t2h3VZtpWb1sK7KrZlBIDRGYA6GDtO9S21rnbOhGpStclTkVjLSWMQxXqkK16hZiKvVit7J7S7HUaHY68/CvQfRFdl7h2BLLrqQV9CSI+Xypz6L8IIdoAZ2Q+kGdJBHh60R4FjMPcyoUt23OwAGU/un8PrTJawQXUQD3jSvG4OFzkpqVov9R9RScGqYkdIeBElrhQwCoMKCGy6qBvME91RcP4lasv1FyEbnoI2B1g6CGGpin5sFOh1A1E8vKk7pFwxku5iq5NMuUCQAI7QGvrt9K6uSGKs5+Pky0GreLsDe4vpr/TNWBxewOTN5L+ZFLWEsyNB699FbeEkbGfyoQ5H4Rp8a8sKf9Ytcrdz2X/yrB4pb+449B/5VCuDMTFYbDkaxV1JkcYgzpJxa4tubFprjHwHZ8SJlvIVyXE3Hd2dyS5JLE7z+HlXR+knSa3hQVUdZc+4CIWfvHl5b/WuZ4vibXGa641cknTme4CmTL8WvBnLWGWiS8JvFQ4VSGAI7XIiRvW1rgeJdcy2pEkaMsyACdCfEVskWU4+wSwqIbn925/Q1E7nCcRMdRcnwWfpNT4foxi2JPUMBluDUqNSjAaE95FFNAlJUBejKEh48KP4QZbqQwMg78pXUHxmYHlW3BOh+MtKwa3GaDoyHlsdd6lXgt+3dBuWmVYftEAgdhiJI0nb1rg5It8t0VhJOFWHsNhmawYtozE7sY0gcwpM+lFuB2cQhlivVn4bZliv8Wn4+lBeDXWW09wAFAe1qZWFG0jWRGg8ascAOJtGc6lCQQhltOcbQT4H0q99HNLdsc0a4e4d0D86nS1cP2j/fkKFjit37iqP3SfnP4VOmNvk7/wDaPxFdEGjlkmExbf7xoF0p4dZu25xN10ReYfKJ5SsQx7tCe6iC4y/zPyH5Ut9JeB275N7EX7i5Rp2h1aDwUjcx5mqvoWOmc7uhQ7BCWUE5SRBInQkctKlt1A6qGYK2ZQSFaIkToY5eVToa45HejZjU2Bwj3myW1zNvy275NV2qfhtovcCq62zyZmywfAjnS0Gw6nQ3EEatbHgS34LXqacAuKRArXUc95tmfcOJ84rNGkR+SRwUcYu94PoPwpo6LYG9iLii4y211aY7QgEgmTA2G/tVgcHw+GkKczEAZmAMkn7MfCY5DkRPKjnRq8lh877A5X0kw0rMDlBn0NO+ZyTxDOLg6Y38FxNt7Y6x0VgSpJyqSRpMbAGOWlF0tYc/5lr+dfzoQuDD3r6IOxaCiTuXKhio9CD5mKs2+HToY99KlG46aFdS2mEhhrH+pa/mX86q4trKyqlWaJiREeJ25bb1hOGRyFUXu2+3l+xGYjbXXT0NVbVdCJb7KeHxEXyojKWgCB9zN+B96ZVvgHLKgxOrAaGYPyNIdi+TiDJjIrN5nqdx3fapnxglhpqbTgea3Fj+uuXgb+79stzR2v0grexCIJe4i6T8XLvjupE6YdNsjNh7GYEaNc00/cBM+vt30S4pczI7EGFsj3AJPzB9qSuM9HcQbrMtuVJAHaXkoGxPhXXF5aZOMUnYvgZi8kmdQSNZMTOuvP3qLi9l7dk3A+0aZV5kDu8aLvwm9bGZ7ZAmN1Op8AZ5Vt0h4PiGwrBbFxpCkZVJ0kGdPCk/jJJdHascG73Q5cMScPa7zbT+kUR4Zb/VOCpjrNxv8K7+FDuEXF/R7Qzf5aQNNeyOc+NWcDjHCtkAO5ggGQRr+HvUnI4FFhzDXUkrlbsxqAIbyM1et3V5W29Y/M0F4ZxJwvbtqTyIJHvoaK2+I3fu2x6E/jVuNoE4stdZ/wC2ff8A4pb6SjEEHKFWz9oBu2wOhkkAegNMH6bc/Y/l/wCaX+kz627jXIZSxVAPiOQzG8ECTOtU5NxBDUgNwyyt2yyPJA1IgCCdJEamfGaCYfiLYWSsugeCrGVOpkq245b+3MkWWLRdVc3AYGTT5kgEST4nu0pN4tiBLKpIWV0O/Pfxmag02tHSqUqZ1zo/xmxiAACQ0fA2ja9w5jxFMqYcdx+dci6N306wdbbDkAEOuYleyNgNDufnvXRcO1llBN0gECAc4I81IkeRFVhKtEOWKvQUvWu4a0ldM7OHENirjzB6u0janxCbH947TvTIRh/vk/wt+VAOk95guXDYbr2I+JguVfRiCx8NvpTttsSKo5yE1MdkTpmI25SdJPpVi1Zn7Y9AW+lKmPsHMwOjS0wAIM66DQeldV6PdGEuAm6SdmAB2OWR/fjT/CmUnyuIt9Unex9QPzNZw9u2zBYCgmJhm12iDHdXUE6N4ZM+W0p7Vv4pPLWMxMVeGDRScqKP1oOgA2nuqi4EQfOxJwlu9bUKmIuBRsAEgeWZTA8KzT7Zw6kuSoJLtuB4V6kf08vf+jfMvRyji2JsXLqICxbKpLjVc7HPlHKBI8dhy1tYe1ni3pM6d8ZTm07wJ+VBv0VEuWbiFijGSAs6sSFJk9kRHLTTvo50Wt9bjAT8K5oIP7JOkVzyj6LZJ1f9jTZxgw0Wdczl2zat8RJJY7yZgeQ7qqcax6WpKyFe04kMf1ZEbDksxoNd634lhrpuCAIzZFuKe0YB0jQe8/CfOhvHMBcJeyozM1i9lBj4iyd0CTDxUfvTSZ0VBxyXf/dEmH4t+kI91CwQMwAJPbgEAnXYnMY8qHYLpFZuAhf85gBmMC2Ra7Ic/ezCANvlJrheFtWbBtoR+r+KTqGkkk+ppYw3AUS3ikVT1bDPh2nUhVVx6AgAHff1okiVhC1BxjJmE5WWTtrZmfKr17pFZDQWKlC6bznZinwEzIzgggbQe6gYxYXGMZUsVI+ITmFuMvgSQRWuK4MofBrqLdoBrr8pAVoM6AE8z31Phh3+2U5n1+kF+L49VU22YBrivk7mAnQk7EBv+01exGIW4nWKC6zmWBv2t9fy1igHTzhgu2VK/GkMqjUsNcwA9jPhVzA4VixLtFtVXqwIlVgzOnxFgwO+gAq6WiSey5xdZw6GACWUaeROvjp9a14pxVrOGs5FzF0j2XbzJOnrWnG+zh7Z/wDdXT+FpqXEW0fhw6xsoCntamDMKYGu8UrjZk6kBeGYoGzal0XLbUEEw3wxEcj789KZOBYuEZi6wzESNj2dgeZ0WPOlRODuXtZ5SzcPZtusnnlDmdI+KDuANJ2ZeDW0sYp7Q0DhSDAnOFBB8BC7f/NJKCT0xskltE14tbvDKZVlzMu4MQGyRqDsY13PdqVw/H8IIlwpnLBDHUctARQ3iOEKsHIlQ7MSJBRh2Z00KsF1H/FKnSMy6BVI7Wh+9MfPl6U8FTE5JWh54l0hTVbPbOstlICxPeJJ0ilPjFq472HLk5mbw0yN9r30qwyjq5UqrHeDE7eskP8AKpOIOypbJkHPEFT2cyHu1Pxa/StJNhg0nSIuGu3VksGKyCGjRdNZA1jmD47Uj8cLfrFafizKfM6nfyrovBMOLlh0VhbDakrBJlQNcwKxHd3Uo8VwjKl0F8xBAIYaiG5EaHlt/wDGcajY0Z3KjXoxi3tlO0cpUZhHgIJUbnU6jU+NPeF4lhycrXFQxJzEBf5jAnTbelnokSbiBDpkBOm/YA8lIbv7j317pRg7qlyXD/qrm8KQuRp+HTvI01iKr8aexJS3Q34ji2CtqWOKtaawtxGPoqkk1zvpL0vfETbszbtbH77j9ojYeA9TSwoqVEq0eNI1EDWoFdy4GsD0X6GuJ3hXb+EjfyX6VaJDm8Bm43xeacvAV64Rr++OXn41q/2tRuv0FYbnr9sU5AmtOO1v8R/vevVpbPxaj4jXqADj3GrN27iULnRVtEBfg0ZSx1Ij7WvgNOdT4biX6NdNwHKAxBgZoVgZIE6kAE/nTXieEo73HFzVVYjKRJAiAZ3Gn5RSRY4c1/sK0M8hdYAhGgGJkEwCPGuFX5OqaUX9o/4a9bfU4hnQW7bWmGQEZgysw7M5tI7wD4mq2LvW7L22ti47HMGNwuSZiTJB2y7AAClLg+JVTbRGdmRTKFYMTBAIlfiYjUnvJEGnFLha8LcM8D4hELP2SQRB8IJgg0nLlF0inA1NNvwKnHuOdXdu2+rMXBbLww5Nm5ppI0rV+mC5QqWmQqIUyDpp5chVXpvbjFn91KBZavCEaToLsrJilTiQv9Xo+YhZEg5dyY71Jpwu8cz2WW5aYkwrEMokkDWOQgCJ76V2sKdSAfStHsCNqZxTFpjNwjGm5eQESUtBAVIiBuT/ADLy5GjWLwzi4nVyOsE3G3XLbJ01MA9oaiNqXuhtkZcM2xa02ZhEk5p1ncx9KehbBHfGx7u/1/KpSNFsD9I//t07usERtGVtvDaivAWnC29fveozEQfWhPSsxZt7/H3/ALJq/wBGDmwaAby8elxqnJaCv5BPiNlWUTAMjXuEiflNRcO4dmvPeMEAKADvOXefAfOs37mZiBuqk76b/wDB8NqscKMBvNef7AO/rSJWxpLRHxA22hs8MNcrCQSTsy7wfHaB3UmdJgZUAZNSQJ+HMRrHIcxTjinBbKdQde89jXXmZJ2/ZpQ48f19sLBzlYnQqYWAZifL8aoluxWqQZ4XhbUK10rn55jER2WgeY9oqz0kTLbS4RK2yXffQBGk6amPDWgGKCw5aRlYSB+1JWJ7ivPuFX7Vxm4bfVp7C3VUkQSuSRofBvYU0aeieVsocL6TYZVY9eyP3dW5zc+12fId+nuv9IuMjI4sXSxJBBytJlgzfEPMelAyley1XBFa3Y48P45h+sD9bkYDRoeNhuMpzTr3Vtxvjlu51kXhcDI4jIw1KFVAkePuD50nKlSBaKigVuzyLUoFagVtVBiK/wAvOu4cLG/7q1xC/wAvMV3DhR305D3G/wCNPE5+bwFWtntbbrz8BWtxYO4nOKkKjX+H8KqXT29xGb6RtRIFtbWp1G5516qKXt9tSxHlJ769WsAhniK2MM9xbtq6xgHKwzRsDkBnXTTlOu1c/wCC9ILqXpdlAyPEqgGaAB8WkwSKuhBXmSoRSR3cilydsYOAYjBWkF9r1o3G/bUm3rtB0JJIM7CIjQy12ek+EN1VW9bnKO0bi+ozN/e9czyVmKR8abuwRjSoO9NWR8VmtMLiZE7SmRImdRQLIe41Zt7CvE0bov8AGqK2Q8xUdwVaaobu1MmBxQX4IcmHwTgkdgcp300Hf+VMH6dczJaQDVjmdwdANwADq08579NNBHBcL1mAw2pBCgqRuCrb+PlRy7w7NbVkJLrBkQswc0kDf+++oyJqiHpama1bCxo3f4EGr3RJh+i29Od3/wDa/wDzQTpMZw9ljqeskwCBOV5HZ8RG/P0on0JuE4ZF7xd5c+tbbXTc0jWgLstviLjKdCCWOg1ARYnUrzkac50rV+PYfDs6XbkEnMBlciCqjTKsbg1ZYlMyyWObQkzvsD3a6R5Uk9OVHXIRzT00YjT6VuONvY8uqD2I6W4RfhuMZImEeYHfmTXkPSlHjvGjnwzW3DnrS94qhELKhRqNOxIgeNCiKxlq+CFdtUNV3pDZeyULkHKDqr6sGUD7O+UMZmrWB43h/wBCvW2u/rWW7AKvJJTKomI1IpKispQXGlsVcez1ZC1tmr005WjwFbVrNemmQDcVma1msE0QGl4/UV3DB9oHTSEn0IrhzcvMfWu34MgBo2hflApkc/N4Lly3JiNMyn0IE1CyarGhJmfQfh9at3jptzX8qgutqo8fwWaxEiTYf3416pADy0r1EBxxbW089R4j8q0I1pww/D8b1mV+puWVO2RAGWZMLErOvPnVTjHRq8zu1pbVtT8ClojTwU865lNeT0U7FS3fRiQGBIqSKscK6CYq1OY2ZkRDN/4edE7HRHF6lrtsa6RJgfy0PkQF+UCl2FYJo2eiN+dbyAeAn8B40Pu8PsW7hS5jFzKRmWYjnGimDFD5EPmC8Xi1tjMxgTHr/Yqrf4hbKFg2m3rr+R9qvY3gFq6Cv6fbyzKyhkCdJOYaxUVrovYVDbOOU68rRPj96O/3ps0TlJ3+Bg4JxO2mDsgdq6ltRkkCSBMQxgamJq7h+O4mNMJv/wC6vtqvhShhug2dpsYpXykEqyEaTrsx05bc69x7gWMwytdZV6sAFmDRGwJIMEye4c6RpvoS15GbiuKxFy0Q2HRNZJa7PfA7IEanfXyox0aum1Zti5CsDczBSWUTcYiDAnQg8q5LZ6SuNOseO7MxB7uydKPcP6WuQBm7Z3LKuvdrEUMJByidOxGNSZE+x9NBvSr0uwr3nttZRnAUgwpmcxOx150CHTNkzZmRiPs6TI5aEa0IxPHDddne6UBPZXtQoAAMCdNZJ8TRjGUdmcl4LWJtPbID27iz3o35VHcYCSZUafECN9tDyrTB8RzMFS4GMwNO/QbjSTA8yKn/AEh2ZQCi6w2bKABBmQRHyp7YPk3RWF1T9of351DisYLcEyZ7opsTgOGcHLdw5bwy7+QFS2ejYYSy7GD2Z0G5A3PhHyoZsOTE7A8Stu4UyAdyRt46E7VexOJwyPkNxtN2ynmJ0Bg0ZfoLb6w3FuaEyF0VdoIiCRz586gxvQm3fuuUxQGWAVKZiumktmAPtWu/IMpHsDgsNeTOuJC8u2I1nuJ2jx5GiGH4DYyHNiUduRTlJ07IYzHOrHC+iiW1yG/m8kA/3GsXOHYJHNt8R2xuoExOusTB5+tbJgbJx0UtMsLdAOnaCt67vEVuOhVv/Wb2H51Pg0w6AKl64R90WyfqmlWnxCKG1uKCN4QHx+Jvwp1IS2vIHxvRTDWlzXMSyDkcoOsiNvEjTnTNg+keGUAfpK7QZRwdAB+HzoDd6K23FsqL11GYEnOgAEGGiBm1jSvYzofYVWuM1xEUFmOZdABJOo2ApsmJLfbGHGdIRfdEwmLso2pbrLZbNAkAHOMsan+9YP07HG7DXMIyqQWYq65hp8JLkEwKUuG2cGpLW77glSEZlXMjEaMoMA6E6Ee1YvcHzHOOIsSd5VROkbLdAHtQyBijoCcbtjTNb0PK7bg+5Br1cru8IuTpftN4lQD8s31r1H5DYIht8Wv/AOtc/mP51asYpjqWJNBLTVNjL7LbZkMMokHy30PhNSHsdn6ZCzaRTbuMYjPAytz0adYH0oIP8RznaLb8hyjQmdj4/KkNOJOBA0HcCwHtMUX4cFuhQ5Kq+ZCyjtW3ABVpXVgZGh8a1Mpca0M9/wDxAvEdmyR5n/ilO9hMRdZ7psXSXZn/APTf7RJ3jxqthuC3A465ii9YyHUkkruVB5ajU7zTFefEYSDZvs1vvUyoj76GQvnQbSdWZRtWybo/wG9cTt2CpAJ7VqCY2Go1NELfRu6VJ6oqe4qAfwqvhemTmOutJdA2IJU6+Gqn2pj4T0nwztHW3Lf7F2Mvo8GPKR5Ulv0NgvYHwfA8TmMB0XacwUlfELVnE8ExD2Xss7lXIzLIMgHvYkjlqO6nAXQdRqO8VBjMalpcznKNhtqYmBrqdKGTGwRzbFf4fOQptZYIMi4SrKfNQQwO9aYXoDilYMXs6EaEsR6dka102zfVxKkEee3mNxW3p8/+KK5JIDgjluL/AMP8U1wuGsjWRq3p9k61SxXQvFCZa0xGphm567lAPnXXj6Vq6A6EA+Yo/LIHxxOM4Do/i0cnqW2IJENHNTCkmMwXWp+kV9kaSCmYhoI5MNdx311u3YRfhVV8gB9KzcVSIMEeMUfkvsX4/RxtMeI2B9BqfWrVnHKNRKkfdJHpoa6PieAYV5zWLcnmFCn3WDNAMR0Lsmcqvb7ouZgfMMhPzpdD7McA6UrYtsbi3LslSJ7ZUHMNM50HYn1q0v8AiRazZVw7z4hB9CaBcT6IOYRbx1ymSjkdlQgEqNOZ/iqgOiGMtZoRHJ5q4kfz5asmkiLTbG67/iFdBhcKf/yJ9ACaTLV17l29edSDcuM0GeZnc7771Xu4LE2/jtXB4lSR7jSorfEco8RoaXJhwQdsUQsa0rf9TuHQEknQCeZ23NH8DicyydNWHs5H4R6UHbViNJM2fpvirVxrS3EFq3IGVASIMQ085/s1W4p0tuYm01p7/ZaMwCqAYIPNVI276V72MDFyD8ZO3PtSJ76otVNmVDXax9vQZx/flNMuFTB5VL4l1zD/AEWiRoQCJ2Omvyrl1NfBLvWW2RiSMqOvgyHqn5aSoU/w1nE2hxuJhBGTEgiOZCmfLLXqA4bgrXBmQOwmJCzr3fOvUn9B+0XbWOFTHG5gyxyM+Ua0Mu2j9Pr4VNg8P2o01Dj3VgOXfTJIfCgXV/AYnKjr4o481MH5Maq4q1lPnWtk6+envTPasWqdBnjnEHa6XDN21UnuYgQSRsdu6ocBjHkhfiI7MaGeW3fVa/czWxpqp135/wDNR4S6VZWG4IPsanVrY1h/G21uYYYq2AtxCFvAaKwYwrwNM0kA98zVLD3DEsyQecx+OtWrF9raYm1lm3cVhuOzEshjw07qWWFFRTBk0NOB4m1s/q7pU9waAf4djTHhulNzQXbaXR4iD8tPlXMABzFWrWMuIeySR3HUfPUVnw+jLm9nXcPxzC3YkvZYaA7R4BhIjwMCjK8Qs7dch8SyzXI+EcRN1gjAKe+dNO/uo/8ApdsgnMIBI9QYqTg0WjNPyP4xtr/Vt/zr+dZ69Ds6/wAw/OubXOKWBvcHz+sVr/1Owf8ANT3oYv0a17Oj3MSg3dB/EPzodjuM2lBAvWw0HLqWgkaEhQdKU7/FLCGC4neIM/Sk+7eLEknU6n1psRXI6TgOLWlSLt4swO69dt61M3H7A2uXPZj/AFVyzOe8+9YLnvNNiLmzp1zpLb5NdP8ACg/qFRv0wTlaPq6j8K5kTXko4oVzZ0630xExlQDvzE/IAaVjGcRF0dqzYcciUzT4jMa5/ZarF7il2SquRAEAeQouKXQFJvsLcXwSTbNu0isbiiF7IMzpqYBkCKkw9lFwpkubikrAUkHskzIWASWG576AWMW7tLOxCAvBJPwCRofanAcNttZtWmIMtbJMrMqQD7q7+1UgJIH8D6P2b13q7lswLTEHVTKuwB03Oka91Jd62VJVhDAkEdxGhHvXVOjuEtpjmS2QQLJ28TOsE95pZ6TcCtuBfw2kjtWspUiBMqGA5DUd/nTtCJiZRjgWMCEAkgEspIiMtxeYIOzKCKDmtkPzoMJ0HB9KsZbQIt64FA0AtWyAPAlRI9KzS1gOKFUClm08Y+VYpcmNiiG9bJBMHv3/AOanwtqHUnQBgTr90gn61hymUgTsY0bu8BFT2QpICgwQdweY8fKp2Wohv8PzDLABH1oLdtlTBpzup2ix56++tDsDhVe3bDDNmVmPmSO7umPShGVdhlC+gJbuSD3EajuPfFRRFGMVwRM6KhYZpmdYAB29qq8T4ebWUzmBB18QSI9gDTWvAmLXZLhHE6mQwEjv0g6+lEW6LBrS3bbMwI200I0I27waB220B+6fkf8AmmXgHEXUMF+GZ9dj9PlUZ5L+JSGL1IXL/DGUwZnkDzqpdUnlHkIpy4liFubxPhQHE4XmDTx5H5FcI+AMAQZBq7hcWkEOgJMw0DQnz8a89iN9Kguhe+qqViONB9cZh2thBMyubsE6AydgeQrN3B4RgYLKSQBlRxppO6Rp2jy2pdsyDI0NTm07/ExPmTQ0jbZJesqt1ghJQfCW3I219ZqMms/o+TnM1qaVu2FKkYJrFZrBrGMV4VisiiKWbRpixHCLJsi47qDkVyBq8RrA7wNYpbtmjl3CWxmYpmYEzmZz9nMYUFRESI1opWKysnDAtwJburdV4JYAyoBBOae/bxg0cbAqMsossTmjmu3mO0ymfCrOB4ZstskHUgAQNuQ2iBVxeD4h3KfdTwH/AKhO5g/6fIU6AT9DcKFxpA0myf6qrYuxiOqVYtvnZUkFkb9Z2NAcwmGJ3G00b6P8EZryXHYhGt8rjK8kzusEDwmt+JdGWDgZFuLaQ3nOdjoCAshuRAu/y0yFYtcV4NZvdYbli7audYqhlggSLYhipZecyRz86HjoBmYhb8QAe0k6Ge5v2TT2/DntrcQW1tjr7QgDQEmztEDnNT4nCvas4i82UsttsuUEfCGKjUmTJ38dqNAs5nhuiaFEZ7gllDLrEqdjBQ/WvV1i30bt5EVknIioPJRFYo4gyEi70HvruUPqR9RQm/0fv4dlzrFuYUzsSDp5bmnZunF7Llt4ZR+1duT/ANiL/upd47xPEXYLsGgghQuVFIM6AHUnaSSda5Ko7MrBmPsnIF+9lT/a3yDH0qLqeqct9mDA7jzjzgVew9xbtxcvwoGfxDP8PyNyrV9RSv0On5BnB7mGv4mWxCogtgDN2TmZjI7XMAD+amG9w3DPZdBkYS/azAnKHMEd2ms0nXrC53u5VZc5EECIAAPzB1rHFbVo2C621zMAqSonuEegJ9KfBMT5GijjOEPZtW8SCrW7gBiRmUNsHXxEaj5VXTHadm4FHcR+I/KmW9gES3IRZXKZgfZIP4VrkBV3Edq9bA8kdE+oamaTE2LYuM3+cD5Bj+FbphrjGAXPOMhG0Tqx8R700YjCFbtt0jVgpnkDqf6RHnRXqB1y+CN82X8qVtIZRs5+MEWudW2bNMGSNP5fzoynAUtugaWzEr3agFhtr9k86zjbeXGOe5lI9UX8Zo9xS+gFs5lzC5bMSJ7TBTp5MaNgS2xf4rwrMSLYAKKCABvmLT69mgqXeR0NdAsW5e6SPtBR5Ki/7i1An4Kl9XuTlY3LsHcQrlR5bbj50umNWxexA2quakxOHdYEGCoYSZ0YSNqrB/Ee4poxEbN6wax7e4rBbxHuKagWZr1SWcOzkBVJJ2gfjtRJeCXFGe4MqjVtZaOe2g0nnRoWyHg+Ca/et2kEl2A8hOpPgBrXTukHB7Vp5YoFHVMesjn1qzJjYqnuKC8Iw2GtE5LlxCdCBcZC0ctIzDyooiW1uBQgh0aZ1nKy7k7/AB0VSFdlHC8KF60ozsjWiUV7bHN2DCnMN5XKfWs4bF4mxdZRi7bsMul5ASV+z2lIjUsKlawq33AJXOodcp1LKcrzrqINvTxNSi5cG4W6P2hDa/tLsPQmm0DYY4PxPGgFRYsOVZpPWMvxnPoMp07cDyqxw/ieLf8ASbi2LX6zNaJ646LaDIRHV9qHNw8t6CcOv5evu21uWRaSWHIlQW7Cn4pBiWU6ijmDv4jC2FDWuvCgZur0ck/EYJhmkkn4RvTIVlhsXjL2Yuls2rmW5NqQ4YBcsBhrGRSORMzpvjiPVtbS2blwM12wrC4SunWKX2hCSitoNKO8CtZbNpWjMEQGDIkKOfOt+JWUuYnC22Ex1130RBb+t8e1EU2PB7XNAfMT8zXqmPArY+FnQfdRyq+wrNEFHFLTeNWbaTM86FW8RUl/HFUYjeNPM6D5xXG9ndF0XeBoB1rjZrhjyXs/Mgn1qziWABJOgEn0qhhHyIqDZQB7VX4piJtlfvQv8xyn5GhVsa6iS4BItpOhIzHzbtH5k1Sx/au205AhvUnT/tW5V036ENiiD1mWZc89lUFPzP8AEaoickF+IEdTc1+w30NULeNHVJbUfrQUJRuySc4JPlMmRNYxmLFy3kB1cqpHMAmT8gay+W5atZhM5PpyPKsYsYq7iCuZuqQKVbTMx7JB30Fb3FYXALuLZcywCoRNjJEkGORqvcs3VVlRutQgjI57QBEdl+fr71fwpW4qswGqg67gxrrypW9DJbA+MthMQyhmbRDLNmJkd/pR7FYG2LLsltA5QtIUSWiQZ3maXcbaC4khdoX+/nTMuJYKc1poAMFRIOmkjf2n0pZN6oaKVuypawz5Tftvldi7Mrao4LErmH2TlgZh3c6r8L4iosBWlHyMwDaZswLSh2O/nUoxgTBAsQG6oqJIksqlYHjIqHil60cObZ7UJC9kmCFgEGIBrben7NpbXoo8XtdpR3WkHsDR7GWB1eEWAcyW2OnIBm+qig3ELgdpE/CBr4T+dEuIX2/+jCrMYO0d+bEjuP3T70Y3TFl2jGPw1vIxFtJHa0Ua5TmjbnEetb9UmU5VUSOQFUrt28dgo75nbw11NWrIhQvcAPat4NqyhhjJw7TvbgjvhQR7S3vRkjMjKdmBB8iIpeGi2Y+wjn+QpI9RI9aNI9OxEYw0PbUtuVGbzjX5zW+Esi2xYEnSACdFGk5RsJge1VcE0Bl+67f9xz/76sF617NWiXG4kg23n4Wg+T9n+rKfSrlzGHYUKvjMrLtII8u6s4bEZgrd4E+B5j3pkxWhgtXyert6/rLiFj+zb/WGfAlQv8VMr47Q0jYG+TeJ+4uUebmW+Sp70TOMPMx506ZNoecDihAE91TYHEZ8ZcPK3ZtqD43HdnHslv3pQwXF0H+YpPcDJ9lk1Z4FxlQ1+52znumOyQALaraiWgDVG0ncmmFo6KLlepWXpGDtbf8Ansf/ANa9RAcTV69deWRfHMfJdvmV9q1Ra0zwzHeAv4muWjrCavVXEtLoO4lvYR9WFVrGOZ5gBY79fyrRCzXDLHQAaabkz9BQquw3Zbxd/KjEbxp58vnUKX0UBcw7IA01PsKhv4YZkB1kmZ12BPOpjYFa9G8lDFYgBlKhtJOgjU+e3OrGFxZ7FsjUGZ8AD/elYtoMz+BA+U/jUOOUgBgYI2pr8C15DnWk9/0rXBWyV7gGYfORHoRQzhfHDPbtq8eMa+lFcPjM73DliWBiduyB3fs0rTWh009lDGsq3VZpggifUEE9w3p0tY9Wt9pQTAgjTT0paxajNa03Yg+RRvyrVL/6PdW2utu5MKT8BH3T3eFZwtIEZ02EMTeXqrtoCJvBY8Lrox/rNW+NWbYtSoAllXYCczClfiXGCLhyoB2kbXXVAeWk/Z9qtYUNeBuXHLMIy9ywQwgDQCQKLjVNmU70i1j1l/T8TXsZgy64ZwzIRhbIUqe5rkyNiNqpcPxDvq5BPgI0onfwwe1hif8ARiPK9dGx0oR+20aW6Br457Wl0Bh99P8Acm/tVm3j0IlWzDwBPv3etSJhFXYf36VFe4YhOZZR/vLofXkfWmtAplHD4ggqQjNAugDTncHjyijGEt5UVSdgB8qFcHY9Y6MZyZtYiczkmRtuKK4m/kWYmjLuhY1VmiCLrCfiVSPNSQ31WpiKEYPHm7dVoywWSN5lc0z/AADSjWWs9GVPojiocEIZ07mzDyfX+rN7VbC1CRF5D95WB/hII+re9ZMDRJ+gh3zEkRyBMH94bGiGFwFsHYewH0FR2edWEOtMmK0HMLhrcSdgJ1J5etXuimEUYe0xQBmUOdBINztke7UDCF7bpmK5lZZG4zAiR5TVXoz0tu4h3soiW+qhZMtO42BWPh76ohGjpSZQNq9QAHE871v0smPncNeoin//2Q==";
//
//            String s2 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n    <div class=\"mdc-card__media mdc-card__media--square demo-card__media\" style=\"background-image: url(\'" + s2img + "\'); width: 110px;\" alt=\"Picture showing Restaurant\"></div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\"><strong>Sticks N Sushiy</strong> <span class=\"data_to_copy\"></span></p>\r\n        <p class=\"ld-card__content\">Kingston, London<br>(Open till 10am)</p>\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"tel:\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Call</a>&nbsp;<a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"geo:51.4111047,-0.3876285,20883\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Nav</a>&nbsp;<a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"mailto:sushi@london.test\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Request table</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//            String s3 = "<div class=\"mdc-card demo-card demo-ui-control\" style=\"width: 350px; margin: 48px 0\">\r\n  <div class=\"mdc-card__primary-action demo-card__primary-action\" tabindex=\"0\" style=\"display: flex;flex-direction: row;height: 110px;--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n     <div class=\"mdc-card__media mdc-card__media--square demo-card__media\">\r\n<video controls height=\"100\" poster=\"https://is1-ssl.mzstatic.com/image/thumb/Video124/v4/5c/d0/fc/5cd0fc4a-714c-e24f-e287-1361bf9c69d6/GB1108700010.sca1.jpg/640x480mv.jpg\" src=\"https://video-ssl.itunes.apple.com/itunes-assets/Video124/v4/25/c0/a7/25c0a7ef-34a6-c36d-c921-ad3d90cec9d6/mzvf_18388793884790474945.640x480.h264lc.U.p.m4v\"></video>\r\n     </div>\r\n     <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n        <p class=\"ld-card__content\"><strong>Rick Astley</strong> <span class=\"data_to_copy\"></span></p>\r\n        <!-- p class=\"ld-card__content\"><strong>Expires:</strong> </p -->\r\n     </div>\r\n  </div>\r\n  <div class=\"mdc-card__actions\">\r\n    <div class=\"mdc-card__action-buttons\">\r\n        <a class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" href=\"https://music.apple.com/us/music-video/never-gonna-give-you-up/1559900284\" target=\"_blank\"><span class=\"mdc-button__ripple\"></span>Play</a>\r\n    </div>\r\n  </div>\r\n</div>";
//
//
//            String video = "<video controls height=\"150\" poster=\"https://is1-ssl.mzstatic.com/image/thumb/Video124/v4/5c/d0/fc/5cd0fc4a-714c-e24f-e287-1361bf9c69d6/GB1108700010.sca1.jpg/640x480mv.jpg\" src=\"https://video-ssl.itunes.apple.com/itunes-assets/Video124/v4/25/c0/a7/25c0a7ef-34a6-c36d-c921-ad3d90cec9d6/mzvf_18388793884790474945.640x480.h264lc.U.p.m4v\"></video>\r\n<br/><br/>\r\n<audio controls style=\"width: 200px; height: 20px;\" src=\"https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview126/v4/b0/d8/aa/b0d8aa8e-1a38-6287-508d-27693174249e/mzaf_13677213744690699536.plus.aac.ep.m4a\">boo</audio>";

            String htmlString = html.toString();
            String textString = text.toString();
            JSONObject extracted = tryExtract(textString, htmlString);

            String sanitizedHtml = htmlProcessor.processForDisplay(htmlString);
            List<StructuredData> data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlString, StructuredSyntax.JSON_LD);
            if (data.isEmpty()) {
                data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlString, StructuredSyntax.MICRODATA);
            }

            if (parseableAttachments != null) {

                for (Map.Entry<AttachmentViewInfo, String> parseableAttachment: parseableAttachments.entrySet()) {
                    String ext = parseableAttachment.getValue();
                    AttachmentViewInfo attachment = parseableAttachment.getKey();
                    switch (ext) {
                        case "ics": {
                            //attachment.isContentAvailable();
                            Body body = attachment.part.getBody();
                            InputStream is = MimeUtility.decodeBody(body);

//                            BufferedReader r = new BufferedReader(new InputStreamReader(is));
//                            StringBuilder total = new StringBuilder();
//                            for (String line; (line = r.readLine()) != null; ) {
//                                total.append(line).append('\n');
//                            }
//                            String icsContent = total.toString();
                            CalendarBuilder cbuilder = new CalendarBuilder();
                            Calendar cal = cbuilder.build(is);

                            System.out.println(cal.getUid());
                            VEvent event = null;
                            for (CalendarComponent c : cal.getComponents()){
                                if (c instanceof VEvent) {
                                    event = (VEvent) c;
                                    break;
                                }
                            }
                            if (event != null) {
                                SimpleModule module = new SimpleModule();
                                module.addSerializer(VEvent.class, new EventJsonLdSerializer(VEvent.class));
                                ObjectMapper mapper = new ObjectMapper();
                                mapper.registerModule(module);

                                String serialized = mapper.writeValueAsString(event);
                                data.addAll(StructuredDataExtractionUtils.parseStructuredDataFromJsonStr(serialized));
                            }
                        }
                        case "vcard": {}
                        case "pkpass": {}
                    }
                }

            }

            if (parseableParts != null) {
                for (Part part : parseableParts) {
                    if (isSameMimeType(part.getMimeType(), "application/ld+json")) {
                        Body body = part.getBody();
                        StringBuilder textBuilder = new StringBuilder();
                        InputStream inputStream = body.getInputStream();
                        try (Reader reader = new BufferedReader(new InputStreamReader
                            (inputStream, StandardCharsets.UTF_8))) {
                            int c = 0;
                            while ((c = reader.read()) != -1) {
                                textBuilder.append((char) c);
                            }
                        }
                        String json = textBuilder.toString();
                        data.addAll(StructuredDataExtractionUtils.parseStructuredDataFromJsonStr(json));
                    }
                }
            }

            if (data.isEmpty() && extracted == null) {
                String url = tryExtractWhitelistedUrl(textString, htmlString);
                if (url != null) {
                    // Substring to remove https:// prefix
                    String button = "<a href=\"xloadcards://"+ url.substring(8) +"\">Load Cards</a><br><hr><br><br>";
                    sanitizedHtml = button + sanitizedHtml;
                } else {
                    sanitizedHtml = "<b>NO STRUCTURED DATA FOUND</b><br>" + sanitizedHtml;
                }
            } else {
                String css = "<head>\n" +
                    "        <link href=\"https://unpkg.com/material-components-web@latest/dist/material-components-web.min.css\" rel=\"stylesheet\">\n" +
                    "        <script src=\"https://unpkg.com/material-components-web@latest/dist/material-components-web.min.js\"></script>\n" +
                    "        <link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/icon?family=Material+Icons\">\n" +
                    "        <link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css?family=Roboto+Mono\">\n" +
                    "        <link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css?family=Roboto:300,400,500,600,700\">\n" +
                    "</head>";
                MustacheRenderer renderer = new MustacheRenderer();

                ArrayList<String> renderedHTMLs = new ArrayList<>(data.size());
                for (StructuredData structuredData: data) {
                    JSONObject jsonObject = structuredData.getJson();
                    List<ButtonDescription> buttons = getButtons(jsonObject);
                    String result = renderer.render(jsonObject, buttons);
                    renderedHTMLs.add(result);
                }
                if (extracted != null) {
                    List<ButtonDescription> buttonsForExtracted = getButtons(extracted);
                    String result = renderer.render(extracted, buttonsForExtracted);
                    renderedHTMLs.add(result);
                }

                String result = String.join("\n", renderedHTMLs);



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
//                    + "<br><hr><br><br>";
//
//
//                    sanitizedHtml = css  + "<br><br>SML:<br>" + result + "<br>XSML<br>" + linx + "<br>" + "<br><b>ACTUAL HTML MAIL BELOW</b><br>" + htmlProcessor.processForDisplay(htmlString);
                    sanitizedHtml = css + result + "<br><b>ACTUAL HTML MAIL BELOW</b><br>" + htmlProcessor.processForDisplay(htmlString);

            }

            return new ViewableExtractedText(textString, sanitizedHtml);
        } catch (Exception e) {
            throw new MessagingException("Couldn't extract viewable parts", e);
        }
    }

    /**
     * Creates descriptions of buttons to be included in a rendered card.
     * @param jsonObject the cards schema
     * @return the button descriptions
     */
    static private List<ButtonDescription> getButtons(JSONObject jsonObject) {
        List<ButtonDescription> buttons = new ArrayList<>();
        JSONObject potentialActions = jsonObject.optJSONObject("potentialAction");
        if (potentialActions != null) {
            String type = potentialActions.optString("@type");
            if (type.equals("CopyToClipboardAction")) {
                String name = potentialActions.optString("name", "Copy to clipboard ");
                String description = potentialActions.optString("description");
                if (!description.isEmpty()) {
                    buttons.add(new ButtonDescription(name, "xclipboard:" + description));
                }
            }
        }
        String type = jsonObject.optString("@type");
        if (type.equals("Recipe") || type.endsWith("Reservation")) {
            byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
            String fileName = jsonObject.optString("name", type) + ".json";
            Uri uri = new Builder()
                .scheme("xshareasfile")
                .authority(encodedJson)
                .appendQueryParameter("fileName", fileName)
                .build();
            buttons.add(new ButtonDescription("Share as file", uri.toString()));
        }
//        buttons.add(new ButtonDescription("Call", "tel:124"));
//        buttons.add(new ButtonDescription("Story", "xstory:#https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html"));
        return  buttons;
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

    @Nullable
    static JSONObject tryExtract(String text, String html) throws JSONException {
//        Pattern c2cpattern = Pattern.compile("[0-9]{6}");
        Pattern boldc2cpattern = Pattern.compile("<b>[0-9]{4,}</b>");
//        Matcher textMatcher = c2cpattern.matcher(text);
        Matcher htmlMatcher = boldc2cpattern.matcher(html);
        while (htmlMatcher.find()) {
//            String textMatch = textMatcher.group();
            String htmlMatch = htmlMatcher.group();
            String code = htmlMatch.substring(3, htmlMatch.length() -4);
            if (text.contains(code)) {
               return new JSONObject()
                   .put("@context", "https://schema.org")
                   .put("@type", "EmailMessage")
                   .put("description", "Confirmation code: " + code)
                   .put("potentialAction", new JSONObject()
                       .put("@type", "CopyToClipboardAction")
                       .put( "name", "Copy " + code)
                       .put("description", code));
            }
        }
        return null;
    }

    @Nullable
    static String tryExtractWhitelistedUrl(String text, String html) {
        List<String> whiteListedUrls = Arrays.asList("www.spiegel.de", "cooking.nytimes.com");
        Matcher urlPlaintextMatcher = Patterns.WEB_URL.matcher(text);
        while (urlPlaintextMatcher.find()) {
            String url = urlPlaintextMatcher.group();
            for (String whiteListedUrl : whiteListedUrls) {
                if (url.contains(whiteListedUrl)) {
                    return  url;
                }
            }
        }
        Matcher urlHtmlMatcher = Patterns.WEB_URL.matcher(html);
        while (urlHtmlMatcher.find()) {
            String url = urlHtmlMatcher.group();
            for (String whiteListedUrl : whiteListedUrls) {
                if (url.contains(whiteListedUrl)) {
                    return  url;
                }
            }
        }

        return null;
    }
}
