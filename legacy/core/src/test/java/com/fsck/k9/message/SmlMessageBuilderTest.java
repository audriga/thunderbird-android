package com.fsck.k9.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import app.k9mail.core.android.testing.RobolectricTest;
import com.fsck.k9.CoreResourceProvider;
import app.k9mail.legacy.account.Identity;
import com.fsck.k9.TestCoreResourceProvider;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.BoundaryGenerator;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MessageIdGenerator;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.message.MessageBuilder.Callback;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@LooperMode(LooperMode.Mode.LEGACY)
public class SmlMessageBuilderTest extends RobolectricTest {
    private static final String TEST_MESSAGE_TEXT = "Event Reservation Confirmation\n" +
        "\n" +
        "Dear Noah Baumbach,\n" +
        "\n" +
        "Thank you for your reservation. Here are the details:\n" +
        "\n" +
        "Reservation Number: MBE12345\n" +
        "Event Name: Make Better Email 2024\n" +
        "Start Date: 2024-10-30\n" +
        "Location:\n" +
        "    Isode Ltd\n" +
        "    14 Castle Mews\n" +
        "    Hampton TW12 2NP\n" +
        "    UK\n" +
        "\n" +
        "We look forward to seeing you at the event!\n" +
        "\n" +
        "Best regards,\n" +
        "The Event Team";
    private static final String TEST_HTML_TEXT = "" +
    "<h1>Event Reservation Confirmation</h1>\n" +
        "<p>Dear Noah Baumbach,</p>\n" +
        "<p>Thank you for your reservation. Here are the details:</p>\n" +
        "<table>\n" +
        "    <tr>\n" +
        "        <td><strong>Reservation Number:</strong></td>\n" +
        "        <td>MBE12345</td>\n" +
        "    </tr>\n" +
        "    <tr>\n" +
        "        <td><strong>Event Name:</strong></td>\n" +
        "        <td>Make Better Email 2024</td>\n" +
        "    </tr>\n" +
        "    <tr>\n" +
        "        <td><strong>Start Date:</strong></td>\n" +
        "        <td>2024-10-30</td>\n" +
        "    </tr>\n" +
        "    <tr>\n" +
        "        <td><strong>Location:</strong></td>\n" +
        "        <td>\n" +
        "            Isode Ltd<br>\n" +
        "            14 Castle Mews<br>\n" +
        "            Hampton TW12 2NP<br>\n" +
        "            UK\n" +
        "        </td>\n" +
        "    </tr>\n" +
        "</table>\n" +
        "<p>We look forward to seeing you at the event!</p>\n" +
        "<p>Best regards,<br>The Event Team</p>";

    private static final String TEST_SUBJECT = "Make Email Better Again!";
    private static final Address TEST_IDENTITY_ADDRESS = new Address("test@example.org", "tester");
    private static final Address[] TEST_REPLY_TO = new Address[] {
            new Address("reply-to1@example.org", "reply 1"),
            new Address("reply-to2@example.org", "reply 2")
    };
    private static final Address[] TEST_TO = new Address[] {
            new Address("to1@example.org", "recip 1"),
            new Address("to2@example.org", "recip 2")
    };
    private static final Address[] TEST_CC = new Address[] {
            new Address("cc@example.org", "cc recip") };
    private static final Address[] TEST_BCC = new Address[] {
            new Address("bcc@example.org", "bcc recip") };
    private static final String TEST_MESSAGE_ID = "<00000000-0000-007B-0000-0000000000EA@example.org>";
    private static final Date SENT_DATE = new Date(10000000000L);

    private static final String BOUNDARY_1 = "----boundary1";
    private static final String BOUNDARY_2 = "----boundary2";
    private static final String BOUNDARY_3 = "----boundary3";

    private static final String MESSAGE_HEADERS = "" +
            "Date: Sun, 26 Apr 1970 17:46:40 +0000\r\n" +
            "From: tester <test@example.org>\r\n" +
            "To: recip 1 <to1@example.org>, recip 2 <to2@example.org>\r\n" +
            "CC: cc recip <cc@example.org>\r\n" +
            "BCC: bcc recip <bcc@example.org>\r\n" +
            "Subject: " + TEST_SUBJECT + "\r\n" +
            "User-Agent: K-9 Mail for Android\r\n" +
            "Message-ID: " + TEST_MESSAGE_ID + "\r\n" +
            "MIME-Version: 1.0\r\n";

    private static final String MESSAGE_CONTENT = "" +
        "Content-Type: multipart/alternative;\r\n" +
        " boundary=----boundary1\r\n" +
        "Content-Transfer-Encoding: 7bit\r\n" +
        "\r\n" +
        "------boundary1\r\n" +
        "Content-Type: text/plain;\r\n" +
        " charset=utf-8\r\n" +
        "Content-Transfer-Encoding: quoted-printable\r\n" +
        "\r\n" +
        "Event Reservation Confirmation\r\n" +
        "\r\n" +
        "Dear Noah Baumbach,\r\n" +
        "\r\n" +
        "Thank you for your reservation=2E Here are the details:\r\n" +
        "\r\n" +
        "Reservation Number: MBE12345\r\n" +
        "Event Name: Make Better Email 2024\r\n" +
        "Start Date: 2024-10-30\r\n" +
        "Location:\r\n" +
        "    Isode Ltd\r\n" +
        "    14 Castle Mews\r\n" +
        "    Hampton TW12 2NP\r\n" +
        "    UK\r\n" +
        "\r\n" +
        "We look forward to seeing you at the event!\r\n" +
        "\r\n" +
        "Best regards,\r\n" +
        "The Event Team\r\n" +
        "------boundary1\r\n";

    private static final String SML_IN_HTML_CONTENT = "" +
        "Content-Type: text/html;\r\n" +
        " charset=utf-8\r\n" +
        "Content-Transfer-Encoding: quoted-printable\r\n" +
        "\r\n" +
        "<!DOCTYPE html><html><script type=3D\"application/ld+json\">{\r\n" +
        "  \"@context\": \"http:\\/\\/schema=2Eorg\",\r\n" +
        "  \"@type\": \"EventReservation\",\r\n" +
        "  \"reservationId\": \"MBE12345\",\r\n" +
        "  \"underName\": {\r\n" +
        "    \"@type\": \"Person\",\r\n" +
        "    \"name\": \"Noah Baumbach\"\r\n" +
        "  },\r\n" +
        "  \"reservationFor\": {\r\n" +
        "    \"@type\": \"Event\",\r\n" +
        "    \"name\": \"Make Better Email 2024\",\r\n" +
        "    \"startDate\": \"2024-10-15\",\r\n" +
        "    \"organizer\": {\r\n" +
        "      \"@type\": \"Organization\",\r\n" +
        "      \"name\": \"Fastmail Pty Ltd=2E\",\r\n" +
        "      \"logo\": \"https:\\/\\/www=2Efastmail=2Ecom\\/assets\\/images\\/FM-Logo-RGB=\r\n" +
        "-IiFj8alCx1-3073=2Ewebp\"\r\n" +
        "    },\r\n" +
        "    \"location\": {\r\n" +
        "      \"@type\": \"Place\",\r\n" +
        "      \"name\": \"Isode Ltd\",\r\n" +
        "      \"address\": {\r\n" +
        "        \"@type\": \"PostalAddress\",\r\n" +
        "        \"streetAddress\": \"14 Castle Mews\",\r\n" +
        "        \"addressLocality\": \"Hampton\",\r\n" +
        "        \"addressRegion\": \"Greater London\",\r\n" +
        "        \"postalCode\": \"TW12 2NP\",\r\n" +
        "        \"addressCountry\": \"UK\"\r\n" +
        "      }\r\n" +
        "    }\r\n" +
        "  }\r\n" +
        "}</script></head><body><!-- TODO The difference to the main super_Thing_em=\r\n" +
        "ail_card template is that we are not using partials here=2E We are using pa=\r\n" +
        "rtials in a wrong way in the main super_Thing_email_card=2Ehtml=2E This sho=\r\n" +
        "uld be fixed at some point (also in JS code)=2E -->\r\n" +
        "<tr>\r\n" +
        "    <td style=3D'padding: 30px; background-color: #ffffff;' class=3D'sm-p =\r\n" +
        "bar'>\r\n" +
        "        <table border=3D'0' cellpadding=3D'0' cellspacing=3D'0' role=3D'pr=\r\n" +
        "esentation' style=3D'width:100%;'>\r\n" +
        "            <!-- Team Name : BEGIN -->\r\n" +
        "           =20\r\n" +
        "            <!-- Team Name : END -->\r\n" +
        "            <!-- Rich Text : BEGIN -->\r\n" +
        "           =20\r\n" +
        "            <tr>\r\n" +
        "                <td style=3D'background-color: #ffffff;max-width: 680px;ma=\r\n" +
        "x-height:150px;' class=3D'sm-p'>\r\n" +
        "                    <div class=3D'warpper' dir=3D'ltr' style=3D'margin: 0 =\r\n" +
        "auto; text-align: center; font-size: 0;max-width: 680px;min-height:150px;'>\r\n" +
        "                        <!--[if mso]>\r\n" +
        "                        <table role=3D'presentation' border=3D'0' cellspac=\r\n" +
        "ing=3D'0' cellpadding=3D'0' width=3D'620'>\r\n" +
        "                        <tr>\r\n" +
        "                        <td align=3D'left' valign=3D'top' width=3D'128'>\r\n" +
        "                        <![endif]-->\r\n" +
        "                        <div style=3D'display:inline-block; margin: 0 -1px=\r\n" +
        "; width: 150px; vertical-align: middle;margin-left: 10px;\r\n" +
        "                        margin-top: 10px;\r\n" +
        "                        margin-bottom: 10px;\r\n" +
        "                        margin-left:0px;\r\n" +
        "                        margin-right: 28px;\r\n" +
        "                        min-height:inherit;' class=3D'stack-column'>\r\n" +
        "                            <table role=3D'presentation' cellspacing=3D'0'=\r\n" +
        " cellpadding=3D'0' border=3D'0' width=3D'100%' style=3D'min-height:inherit;=\r\n" +
        "'>\r\n" +
        "                                <tr>\r\n" +
        "                                    <td dir=3D'ltr' style=3D'min-height:in=\r\n" +
        "herit;'>\r\n" +
        "                                                                          =\r\n" +
        " =20\r\n" +
        "                                        <img src=3D'' width=3D'auto'  alt=\r\n" +
        "=3D'' border=3D'0' style=3D'max-width:150px; max-height:150px ;font-family:=\r\n" +
        " arial, sans-serif; font-size: 15px; line-height: 21px; color: #3C3F44; mar=\r\n" +
        "gin: 0;' class=3D'g-img'>                            =20\r\n" +
        "\r\n" +
        "                                    </td>\r\n" +
        "                                </tr>\r\n" +
        "                            </table>\r\n" +
        "                        </div>\r\n" +
        "                        <!--[if mso]>\r\n" +
        "                        </td>\r\n" +
        "                        <td align=3D'left' valign=3D'top' width=3D'552'>\r\n" +
        "                        <![endif]-->\r\n" +
        "                        <div style=3D'display:inline-block; margin: 0 -1px=\r\n" +
        "; width: 94%; max-width: 462px;vertical-align: middle;padding-left: 20px;pa=\r\n" +
        "dding-bottom: 8px;\r\n" +
        "                        padding-top: 3px;' class=3D'stack-column'>\r\n" +
        "                            <table role=3D'presentation' cellspacing=3D'0'=\r\n" +
        " cellpadding=3D'0' border=3D'0' width=3D'100%'>\r\n" +
        "                                <tr>\r\n" +
        "                                    <td dir=3D'ltr' style=3D'font-family: =\r\n" +
        "arial, sans-serif; font-size: 15px; line-height: 21px; color: #3C3F44; text=\r\n" +
        "-align: left;'>\r\n" +
        "                                       =20\r\n" +
        "                                       =20\r\n" +
        "                                        <h3 style=3D'margin: 0 4px 5px 0px=\r\n" +
        ";\r\n" +
        "           color: #0C0D0E;\r\n" +
        "           font-weight: bold;\r\n" +
        "           font-size: 19px;\r\n" +
        "           line-height: 21px;\r\n" +
        "           overflow: hidden;\r\n" +
        "           word-break: break-all;\r\n" +
        "           text-overflow: ellipsis;\r\n" +
        "           display: -webkit-box;\r\n" +
        "           -webkit-line-clamp: 1;\r\n" +
        "           -webkit-box-orient: vertical;\r\n" +
        "           max-height: 23px;'>\r\n" +
        "    Make Better Email 2024\r\n" +
        "</h3>\r\n" +
        "\r\n" +
        "<p style=3D'margin: 0 0 15px;\r\n" +
        "          font-size: 15px;\r\n" +
        "          line-height: 21px;\r\n" +
        "          overflow: hidden;\r\n" +
        "          text-overflow: ellipsis;\r\n" +
        "          display: -webkit-box;\r\n" +
        "          -webkit-line-clamp: 3;\r\n" +
        "          -webkit-box-orient: vertical;\r\n" +
        "          margin-right: 5px;\r\n" +
        "          max-height: 67px;\r\n" +
        "          margin-bottom: 8px;'\r\n" +
        "   class=3D'hetc-3-line-paragraph'>\r\n" +
        "    Isode Ltd <br>\r\n" +
        "    2024-10-15\r\n" +
        "</p>\r\n" +
        "\r\n" +
        "<ul style=3D'padding: 0; margin: 0; margin-left:15px; list-style-type: non=\r\n" +
        "e; display: table; max-width: 83%;min-width: 77%;'>\r\n" +
        "   =20\r\n" +
        "        <li style=3D'display: table-cell; position: relative;min-width: 45=\r\n" +
        "%;padding-right:15px; width: 45%;'>\r\n" +
        "           =20\r\n" +
        "            <p style=3D'max-height: 20px;\r\n" +
        "                overflow: hidden;\r\n" +
        "                text-overflow: ellipsis;\r\n" +
        "                margin: 0'>\r\n" +
        "\r\n" +
        "                <span style=3D'padding-right: 5px;font-size: larger;'>=E2=\r\n" +
        "=80=A2</span>Noah Baumbach\r\n" +
        "           =20\r\n" +
        "            </p>\r\n" +
        "        </li>\r\n" +
        "   =20\r\n" +
        "\r\n" +
        "\r\n" +
        "</ul>\r\n" +
        "                                       =20\r\n" +
        "                                </td>\r\n" +
        "                                </tr>\r\n" +
        "                            </table>\r\n" +
        "                        </div>\r\n" +
        "                        <!--[if mso]>\r\n" +
        "                        </td>\r\n" +
        "                        </tr>\r\n" +
        "                        </table>\r\n" +
        "                        <![endif]-->\r\n" +
        "                    </div>\r\n" +
        "                </td>\r\n" +
        "            </tr>\r\n" +
        "           =20\r\n" +
        "\r\n" +
        "           =20\r\n" +
        "            <!-- Rich Text : END -->\r\n" +
        "\r\n" +
        "            <!-- Button Row : BEGIN -->\r\n" +
        "            <tr>\r\n" +
        "                <td>\r\n" +
        "                    <!-- Button : BEGIN -->\r\n" +
        "                    <table align=3D'right' border=3D'0' cellpadding=3D'0' =\r\n" +
        "cellspacing=3D'0' role=3D'presentation'>\r\n" +
        "                        <tr>\r\n" +
        "                        </tr>\r\n" +
        "                    </table>\r\n" +
        "                    <!-- Button : END -->\r\n" +
        "                </td>\r\n" +
        "            </tr>\r\n" +
        "            <!-- Button Row : END -->\r\n" +
        "           =20\r\n" +
        "\r\n" +
        "        </table>\r\n" +
        "    </td>\r\n" +
        "</tr></body></html>\r\n" +
        "------boundary1--\r\n";

    private static final String HTML_MESSAGE_CONTENT = "" +
        "Content-Type: text/html;\r\n" +
        " charset=utf-8\r\n" +
        "Content-Transfer-Encoding: quoted-printable\r\n" +
        "\r\n" +
        "<h1>Event Reservation Confirmation</h1>\r\n" +
        "<p>Dear Noah Baumbach,</p>\r\n" +
        "<p>Thank you for your reservation=2E Here are the details:</p>\r\n" +
        "<table>\r\n" +
        "    <tr>\r\n" +
        "        <td><strong>Reservation Number:</strong></td>\r\n" +
        "        <td>MBE12345</td>\r\n" +
        "    </tr>\r\n" +
        "    <tr>\r\n" +
        "        <td><strong>Event Name:</strong></td>\r\n" +
        "        <td>Make Better Email 2024</td>\r\n" +
        "    </tr>\r\n" +
        "    <tr>\r\n" +
        "        <td><strong>Start Date:</strong></td>\r\n" +
        "        <td>2024-10-30</td>\r\n" +
        "    </tr>\r\n" +
        "    <tr>\r\n" +
        "        <td><strong>Location:</strong></td>\r\n" +
        "        <td>\r\n" +
        "            Isode Ltd<br>\r\n" +
        "            14 Castle Mews<br>\r\n" +
        "            Hampton TW12 2NP<br>\r\n" +
        "            UK\r\n" +
        "        </td>\r\n" +
        "    </tr>\r\n" +
        "</table>\r\n" +
        "<p>We look forward to seeing you at the event!</p>\r\n" +
        "<p>Best regards,<br>The Event Team</p>\r\n" +
        "------boundary1\r\n";

    private static final String ALTERNATIVE_PART_CONTENTS = ""  +
        "Content-Type: application/ld+json;\r\n" +
        " charset=utf-8\r\n" +
        "Content-Transfer-Encoding: 8bit\r\n" +
        "\r\n" +
        "{\r\n" +
        "  \"@context\": \"http:\\/\\/schema.org\",\r\n" +
        "  \"@type\": \"EventReservation\",\r\n" +
        "  \"reservationId\": \"MBE12345\",\r\n" +
        "  \"underName\": {\r\n" +
        "    \"@type\": \"Person\",\r\n" +
        "    \"name\": \"Noah Baumbach\"\r\n" +
        "  },\r\n" +
        "  \"reservationFor\": {\r\n" +
        "    \"@type\": \"Event\",\r\n" +
        "    \"name\": \"Make Better Email 2024\",\r\n" +
        "    \"startDate\": \"2024-10-15\",\r\n" +
        "    \"organizer\": {\r\n" +
        "      \"@type\": \"Organization\",\r\n" +
        "      \"name\": \"Fastmail Pty Ltd.\",\r\n" +
        "      \"logo\": \"https:\\/\\/www.fastmail.com\\/assets\\/images\\/FM-Logo-RGB-IiFj8alCx1-3073.webp\"\r\n" +
        "    },\r\n" +
        "    \"location\": {\r\n" +
        "      \"@type\": \"Place\",\r\n" +
        "      \"name\": \"Isode Ltd\",\r\n" +
        "      \"address\": {\r\n" +
        "        \"@type\": \"PostalAddress\",\r\n" +
        "        \"streetAddress\": \"14 Castle Mews\",\r\n" +
        "        \"addressLocality\": \"Hampton\",\r\n" +
        "        \"addressRegion\": \"Greater London\",\r\n" +
        "        \"postalCode\": \"TW12 2NP\",\r\n" +
        "        \"addressCountry\": \"UK\"\r\n" +
        "      }\r\n" +
        "    }\r\n" +
        "  }\r\n" +
        "}\r\n" +
        "------boundary1--\r\n";

    private static final String SML_CONTENT = "" +
        "{\n" +
        "    \"@context\":              \"http://schema.org\",\n" +
        "    \"@type\":                 \"EventReservation\",\n" +
        "    \"reservationId\":         \"MBE12345\",\n" +
        "    \"underName\": {\n" +
        "        \"@type\":               \"Person\",\n" +
        "        \"name\":                \"Noah Baumbach\"\n" +
        "    },\n" +
        "    \"reservationFor\": {\n" +
        "        \"@type\":               \"Event\",\n" +
        "        \"name\":                \"Make Better Email 2024\",\n" +
        "        \"startDate\":           \"2024-10-15\",\n" +
        "        \"organizer\": {\n" +
        "            \"@type\":            \"Organization\",\n" +
        "            \"name\":             \"Fastmail Pty Ltd.\",\n" +
        "            \"logo\":             \"https://www.fastmail.com/assets/images/FM-Logo-RGB-IiFj8alCx1-3073.webp\"\n" +
        "        },\n" +
        "        \"location\": {\n" +
        "            \"@type\":             \"Place\",\n" +
        "            \"name\":              \"Isode Ltd\",\n" +
        "            \"address\": {\n" +
        "                \"@type\":           \"PostalAddress\",\n" +
        "                \"streetAddress\":   \"14 Castle Mews\",\n" +
        "                \"addressLocality\": \"Hampton\",\n" +
        "                \"addressRegion\":   \"Greater London\",\n" +
        "                \"postalCode\":      \"TW12 2NP\",\n" +
        "                \"addressCountry\":  \"UK\"\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "}";

    private MessageIdGenerator messageIdGenerator;
    private BoundaryGenerator boundaryGenerator;
    private CoreResourceProvider resourceProvider = new TestCoreResourceProvider();
    private Callback callback;


    @Before
    public void setUp() throws Exception {
        messageIdGenerator = mock(MessageIdGenerator.class);
        when(messageIdGenerator.generateMessageId(any(Message.class))).thenReturn(TEST_MESSAGE_ID);

        boundaryGenerator = mock(BoundaryGenerator.class);
        when(boundaryGenerator.generateBoundary()).thenReturn(BOUNDARY_1, BOUNDARY_2, BOUNDARY_3);

        callback = mock(Callback.class);
    }

    @Test
    public void build_sml_in_html_shouldSucceed() throws Exception {
        MessageBuilder messageBuilder = createSmlMessageBuilder(SmlStandardVariant.SML_IN_HTML);

        messageBuilder.buildAsync(callback);

        MimeMessage message = getMessageFromCallback();
        assertEquals("multipart/alternative", message.getMimeType());
        assertEquals(TEST_SUBJECT, message.getSubject());
        assertEquals(TEST_IDENTITY_ADDRESS, message.getFrom()[0]);
        assertArrayEquals(TEST_TO, message.getRecipients(RecipientType.TO));
        assertArrayEquals(TEST_CC, message.getRecipients(RecipientType.CC));
        assertArrayEquals(TEST_BCC, message.getRecipients(RecipientType.BCC));
        assertEquals(MimeMultipart.class, message.getBody().getClass());
        assertEquals("multipart/alternative", ((MimeMultipart) message.getBody()).getMimeType());
        List<BodyPart> parts =  ((MimeMultipart) message.getBody()).getBodyParts();
        //RFC 2046 - 5.1.4. - Best type is last displayable
        assertEquals("text/plain", parts.get(0).getMimeType());
        assertEquals("text/html", parts.get(1).getMimeType());
//        assertEquals(MESSAGE_HEADERS + MESSAGE_CONTENT, getMessageContents(message));

        String messageContents = getMessageContents(message);
        assertEquals(MESSAGE_HEADERS + MESSAGE_CONTENT + SML_IN_HTML_CONTENT, messageContents);
    }

    @Test
    public void build_dedicated_multipart_shouldSucceed() throws Exception {
        MessageBuilder messageBuilder = createSmlMessageBuilder(SmlStandardVariant.DEDICATED_MULTIPART);

        messageBuilder.buildAsync(callback);

        MimeMessage message = getMessageFromCallback();
        assertEquals("multipart/alternative", message.getMimeType());
        assertEquals(TEST_SUBJECT, message.getSubject());
        assertEquals(TEST_IDENTITY_ADDRESS, message.getFrom()[0]);
        assertArrayEquals(TEST_TO, message.getRecipients(RecipientType.TO));
        assertArrayEquals(TEST_CC, message.getRecipients(RecipientType.CC));
        assertArrayEquals(TEST_BCC, message.getRecipients(RecipientType.BCC));
        assertEquals(MimeMultipart.class, message.getBody().getClass());
        assertEquals("multipart/alternative", ((MimeMultipart) message.getBody()).getMimeType());
        List<BodyPart> parts =  ((MimeMultipart) message.getBody()).getBodyParts();
        //RFC 2046 - 5.1.4. - Best type is last displayable
        assertEquals("text/plain", parts.get(0).getMimeType());
        assertEquals("text/html", parts.get(1).getMimeType());
        assertEquals("application/ld+json", parts.get(2).getMimeType());

        String messageContents = getMessageContents(message);
        assertEquals(MESSAGE_HEADERS + MESSAGE_CONTENT + HTML_MESSAGE_CONTENT + ALTERNATIVE_PART_CONTENTS, messageContents);
    }


    private MimeMessage getMessageFromCallback() {
        ArgumentCaptor<MimeMessage> mimeMessageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(callback).onMessageBuildSuccess(mimeMessageCaptor.capture(), eq(false));
        verifyNoMoreInteractions(callback);

        return mimeMessageCaptor.getValue();
    }

    private String getMessageContents(MimeMessage message) throws IOException, MessagingException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        message.writeTo(outputStream);
        return outputStream.toString();
    }

    private MessageBuilder createSmlMessageBuilder(SmlStandardVariant variant) {
        List<JSONObject> payload = createSMLPayload();
        SmlMessageBuilder builder = new SimpleSmlMessageBuilder(messageIdGenerator, boundaryGenerator, resourceProvider);
        builder = SmlMessageUtil.createSMLMessageBuilder(
            payload,
            variant,
            // Only sety html text for dedicated-multipart variant. For sml-in-html we want to test the html generation
            (variant == SmlStandardVariant.DEDICATED_MULTIPART) ? TEST_HTML_TEXT : null,
            TEST_MESSAGE_TEXT,
            builder
        );
        Identity identity = createIdentity();
        return builder
                .setSubject(TEST_SUBJECT)
                .setSentDate(SENT_DATE)
                .setHideTimeZone(true)
                .setTo(Arrays.asList(TEST_TO))
                .setCc(Arrays.asList(TEST_CC))
                .setBcc(Arrays.asList(TEST_BCC))
                .setIdentity(identity);
    }


    private List<JSONObject> createSMLPayload() {
        try {
            return Collections.singletonList(new JSONObject(SML_CONTENT));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private Identity createIdentity() {
        return new Identity(
                "test identity",
                TEST_IDENTITY_ADDRESS.getPersonal(),
                TEST_IDENTITY_ADDRESS.getAddress(),
                null,
                false,
                null
        );
    }
}
