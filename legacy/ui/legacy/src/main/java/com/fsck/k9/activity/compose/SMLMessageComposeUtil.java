package com.fsck.k9.activity.compose;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
//import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.audriga.h2lj.model.StructuredData;
import com.audriga.h2lj.model.StructuredSyntax;
import com.audriga.h2lj.parser.StructuredDataExtractionUtils;
import com.fsck.k9.activity.misc.Attachment;
//import com.fsck.k9.helper.MailTo;
import com.fsck.k9.helper.MimeTypeUtil;
import com.fsck.k9.logging.Timber;
import com.fsck.k9.sml.SMLUtil;
import com.fsck.k9.view.MessageWebView;
import com.google.android.material.materialswitch.MaterialSwitch;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.audriga.ld2h.ButtonDescription;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class SMLMessageComposeUtil {
    public static final String IS_SML = "isSML";
    public static final String SML_PAYLOAD = "sml_payload";
//    public static final String ACTION_COMPOSE_APPROVE = "com.fsck.k9.intent.action.COMPOSE_APPROVE";


    private final EditText subjectView;
    private final AttachmentPresenter attachmentPresenter;
    private final EditText messageContentView;
    // SML
//    private String smlJsonLd = null;
    private List<JSONObject> smlPayload = null;
//    private String smlHTMLEmail = null;
    private final MessageWebView messageContentViewSML;
    private final MaterialSwitch smlModeSwitch;

    public SMLMessageComposeUtil(EditText subjectView, AttachmentPresenter attachmentPresenter,
        EditText messageContentView,
        MessageWebView messageContentViewSML, MaterialSwitch smlModeSwitch) {
        this.subjectView = subjectView;
        this.attachmentPresenter = attachmentPresenter;
        this.messageContentView = messageContentView;
        this.messageContentViewSML = messageContentViewSML;
        this.smlModeSwitch = smlModeSwitch;

        this.messageContentView.addTextChangedListener(new TextWatcher() {
            private int insertedStartIndex = -1;
            private int insertedEndIndex = -1;
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s == null || count < 2) {
                    insertedStartIndex = -1;
                } else {
                    insertedStartIndex = start;
                    insertedEndIndex = start + count;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                int insertedStartIndex = this.insertedStartIndex;
                int insertedEndIndex = this.insertedEndIndex;
                if (s != null && insertedStartIndex != -1) {
                    CharSequence insertedText = s.subSequence(insertedStartIndex, insertedEndIndex);
                    enrichTextToSmlIfUrl(insertedText);
                }
            }
        });
    }

    @Nullable
    public List<JSONObject> getSmlPayload() {
        return smlPayload;
    }


    @SuppressLint("SetTextI18n")
    public void initializePayloadFromIntent(Intent intent) {
        if (intent.getBooleanExtra(IS_SML, false)) {
            String payload = intent.getStringExtra(SML_PAYLOAD);
            if (payload != null) {
                smlPayload = org.audriga.hetc.JsonLdDeserializer.deserialize(payload);
                Ld2hResult ld2hResult = ld2hRenderSmlPayload(smlPayload);
                updateSubjectAndDisplayLd2hResult(ld2hResult);
            } else {
                messageContentView.setVisibility(View.GONE);
                if (subjectView.getText().length() == 0) {
                    subjectView.setText("SML Mail");
                }
                messageContentViewSML.setVisibility(View.VISIBLE);
                messageContentViewSML.displayHtmlContentWithInlineAttachments("<b>Testing</b>", null, null);
            }
        }
    }

// Not used right now since we send the approve/ deny email directly.
//    /**
//     * For Approve/ Deny reply mails
//     * @return if the initFromIntent function that calls this should return (true) or continue as normal (false)
//     */
//    public boolean initializeApproveDeny(Intent intent) {
//        final String action = intent.getAction();
//        if (ACTION_COMPOSE_APPROVE.equals(action)) {
//            if (intent.getExtras() != null) {
//                Uri uri = intent.getData();
//                if (MailTo.isMailTo(uri)) {
//                    // todo: This is the mailto entrypoint
//                    MailTo mailTo = MailTo.parse(uri);
//                    initializeFromMailto(mailTo);
//                }
//            }
//            if (intent.getData() != null) {
//                Bundle extras = intent.getExtras();
//                String requestAction = extras.getString("requestAction");
//                if ("ConfirmAction".equals(requestAction)) {
//                    smlPayload = org.audriga.hetc.JsonLdDeserializer.deserialize("{\r\n  \"@context\": \"http://schema.org\",\r\n  \"@type\": \"ConfirmAction\",\r\n  \"name\": \"Approved\"\r\n}");
//                    subjectView.setText("Approve");
//                } else if ("CancelAction".equals(requestAction)) {
//                    smlPayload = org.audriga.hetc.JsonLdDeserializer.deserialize("{\r\n  \"@context\": \"http://schema.org\",\r\n  \"@type\": \"CancelAction\",\r\n  \"name\": \"Denied\"\r\n})");
//                    subjectView.setText("Deny");
//                } else {
//                    return true;
//                }
//                org.audriga.ld2h.MustacheRenderer ld2hRenderer = null;
//                try {
//                    ld2hRenderer = new org.audriga.ld2h.MustacheRenderer();
//                } catch (IOException e) {
//                    //throw new RuntimeException(e);
//                }
//                JSONObject smlJSONObject = smlPayload.get(0);
//                String ld2hRenderResult = null;
//                try {
//                    ld2hRenderResult =  (ld2hRenderer != null) ? ld2hRenderer.render(smlJSONObject): null;
//                } catch (IOException e) {
//                    //throw new RuntimeException(e);
//                }
//                if (ld2hRenderResult != null) {
//                    displayLd2hResult(ld2hRenderResult);
//                }
//                return true;
//            }
//        }
//        return false;
//
//    }

    @Nullable
    public static String downloadHTML(String url) {
        OkHttpClient client = new OkHttpClient();
        String htmlSrc = null;
//        String okErr = null;
        Request request = new Builder()
            .url(url).build();
        try (Response response = client. newCall(request).execute()) {
            if (response.body() != null) {
                htmlSrc = response.body().string();
            }

        } catch (Exception e){
//            okErr = e.getMessage();
            Timber.e(e, "Error while downloading html");
        }
        return htmlSrc;
    }

    public void enrichTextToSmlIfUrl(CharSequence text) {
        if (Patterns.WEB_URL.matcher(text).matches()) {
            // Input is exactl one url
            enrichSharedUrlToSml(text.toString());
        }
    }

    public void enrichSharedUrlToSml(String text) {
        String htmlSrc = SMLMessageComposeUtil.downloadHTML(text);
        if (htmlSrc != null) {
            List<StructuredData> data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.JSON_LD);
            if (data.isEmpty()) {
                data = StructuredDataExtractionUtils.parseStructuredDataPart(htmlSrc, StructuredSyntax.MICRODATA);
            }
            if (!data.isEmpty()) {
                ArrayList<String> renderedDisplayHTMLs = ld2hRenderStructuredData(data);
                if (!renderedDisplayHTMLs.isEmpty()) {
                    String joinedDisplayHTMLRenderResults = String.join("\n", renderedDisplayHTMLs);
                    displayLd2hResult(joinedDisplayHTMLRenderResults);
                }
            }
            // else: No structured data found, todo: treat link as normal?

        }
    }
    public static boolean isJsonLd(JSONObject jsonObject) {
        try {
            String context = jsonObject.getString("@context");
            if (!(context.toLowerCase().contains("schema") || context.toLowerCase().contains("sml"))) {
                return false;
            }
            String type = jsonObject.getString("@type");
            return !type.isEmpty();
        } catch (JSONException ignored) {
            return false;
        }
    }

    @NonNull
    private static String addHeadToLd2hResult(String ld2hRenderResult) {
        return SMLUtil.CSS + ld2hRenderResult;
    }

    public void displayLd2hResult(String ld2hRenderResult) {
        String htmlDisplay = addHeadToLd2hResult(ld2hRenderResult);
        messageContentView.setVisibility(View.GONE);
        messageContentViewSML.setVisibility(View.VISIBLE);
        messageContentViewSML.displayHtmlContentWithInlineAttachments(htmlDisplay, null, null);
        smlModeSwitch.setVisibility(View.VISIBLE);
        smlModeSwitch.setChecked(true);
    }

    public boolean handleJsonLdAttachment(Attachment attachment) {
        // todo attachent loading is finished here
        if (MimeTypeUtil.isSameMimeType(attachment.contentType, "application/json") || MimeTypeUtil.isSameMimeType(
            attachment.contentType, "application/json+ld")) {
            String filename = attachment.filename;
            if (filename == null) {
                return false;
            }
            File fl = new File(filename);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fl)))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String ret =  sb.toString();
                // todo: popup to ask if they want to share as schema, or as attachment
                List<JSONObject> deserialized = org.audriga.hetc.JsonLdDeserializer.deserialize(ret);
                List<JSONObject> jsonLds = new ArrayList<>(deserialized.size());
                for (JSONObject jsonObject: deserialized) {
                    if (SMLMessageComposeUtil.isJsonLd(jsonObject)) {
                        jsonLds.add(jsonObject);
                    }
                }
                if (!jsonLds.isEmpty()) {
                    if (smlPayload == null) {
                        smlPayload = new ArrayList<>(deserialized.size());
                    }
                    smlPayload.addAll(jsonLds);
                    Ld2hResult ld2hResult = ld2hRenderSmlPayload(smlPayload);
                    if (!ld2hResult.renderedDisplayHTMLs.isEmpty()) {
                        String joinedDisplayHTMLRenderResults = String.join("\n", ld2hResult.renderedDisplayHTMLs);
                        displayLd2hResult(joinedDisplayHTMLRenderResults);
                        if (subjectView.getText().length() == 0) {
                            String subject = "Check out this " + String.join(", ", ld2hResult.types);
                            subjectView.setText(subject);
                        } else if (subjectView.getText().toString().startsWith("Check out this ")) {
                            String subjectAppend = ", " + String.join(", ", ld2hResult.types);
                            subjectView.append(subjectAppend);
                        }
                        // todo this only seems to revome the attachment from view, not from the actual mail. Maybe race condition?
                        attachmentPresenter.onClickRemoveAttachment(attachment.uri);
                        return true;
                    }
                }
            } catch (IOException ignored) {
            }

        }
        return false;
    }

    private void updateSubjectAndDisplayLd2hResult(Ld2hResult ld2hResult) {
        if (subjectView.getText().length() == 0) {
            String subject = "Check out this " + String.join(", ", ld2hResult.types);
            subjectView.setText(subject);
        }
        if (!ld2hResult.renderedDisplayHTMLs.isEmpty()) {
            String joinedDisplayHTMLRenderResults = String.join("\n", ld2hResult.renderedDisplayHTMLs);
            displayLd2hResult(joinedDisplayHTMLRenderResults);
        }
    }


    @NonNull
    private static Ld2hResult ld2hRenderSmlPayload(List<JSONObject> smlPayload) {
        org.audriga.ld2h.MustacheRenderer ld2hRenderer = null;
        try {
            ld2hRenderer = new org.audriga.ld2h.MustacheRenderer();
        } catch (IOException e) {
            //throw new RuntimeException(e);
        }
        ArrayList<String> renderedDisplayHTMLs = new ArrayList<>(smlPayload.size());
        ArrayList<String> types = new ArrayList<>(smlPayload.size());
        for (JSONObject jsonObject: smlPayload) {
            inlineImages(jsonObject);// todo: Since this modifies the actual jsonObject, and we iterate over smlPayload here, this might also modify smlPlayload (which is what we want). But need to test this.
            String type = jsonObject.optString("@type");
            types.add(type);
            String ld2hRenderResult = null;
            try {
                List<ButtonDescription> buttons = SMLUtil.getButtons(jsonObject);
                ld2hRenderResult = (ld2hRenderer != null) ? ld2hRenderer.render(jsonObject, buttons) : null;
            } catch (IOException e) {
                // todo handle
            }
            if (ld2hRenderResult != null) {
                renderedDisplayHTMLs.add(ld2hRenderResult);
            }
        }
        return new Ld2hResult(renderedDisplayHTMLs, types);
    }

    /**
     * This also calls inlineImages for all markups
     */
    @NonNull
    private ArrayList<String> ld2hRenderStructuredData(List<StructuredData> data) {
        org.audriga.ld2h.MustacheRenderer ld2hRenderer = null;
        try {
            ld2hRenderer = new org.audriga.ld2h.MustacheRenderer();
        } catch (IOException e) {
            //throw new RuntimeException(e);
        }

//                            ArrayList<String> renderedEmailHTMLs = new ArrayList<>(data.size());
        ArrayList<String> renderedDisplayHTMLs = new ArrayList<>(data.size());
//                            ArrayList<String> encodedJsonLds = new ArrayList<>(data.size());
        List<String> typesToSkip = (data.size() > 1) ?
            Arrays.asList("Organization", "NewsMediaOrganization", "WebSite", "BreadcrumbList", "WebPage") :
            null;
        smlPayload = new ArrayList<>(data.size());
        for (StructuredData structuredData: data) {
            JSONObject jsonObject = structuredData.getJson();
            String type = jsonObject.optString("@type");
            if (typesToSkip != null && typesToSkip.contains(type)) {
                continue;
            }
            smlPayload.add(inlineImages(jsonObject));
            String ld2hRenderResult = null;
            try {
                List<ButtonDescription> buttons = SMLUtil.getButtons(jsonObject);
                ld2hRenderResult = (ld2hRenderer != null) ? ld2hRenderer.render(jsonObject, buttons) : null;
            } catch (IOException e) {
                // todo handle
            }
            if (ld2hRenderResult != null) {
                renderedDisplayHTMLs.add(ld2hRenderResult);
            }
        }
        return renderedDisplayHTMLs;
    }

    private static JSONObject inlineImages(JSONObject jsonLd) {
        // First find all images in the order thumbnail, thumbnailUrl, image
        Object thumbnail = jsonLd.opt("thumbnail");
        List<String> thumbnails = Collections.emptyList();
        if (thumbnail != null){
            try {
                thumbnails = imagesFromNestedJson(thumbnail);
            } catch (JSONException e) {
                // todo log
            }
        }

        Object thumbnailUrl = jsonLd.opt("thumbnailUrl");
        List<String> thumbnailUrls = Collections.emptyList();
        if (thumbnailUrl != null){
            try {
                thumbnailUrls = imagesFromNestedJson(thumbnailUrl);
            } catch (JSONException e) {
                // todo log
            }
        }
        Object image = jsonLd.opt("image");

        List<String> images = Collections.emptyList();
        if (image != null){
            try {
                images = imagesFromNestedJson(image);
            } catch (JSONException e) {
                // todo log
            }
        }
        List<String> allImages = new ArrayList<>(thumbnails.size()+thumbnailUrls.size()+images.size());
        allImages.addAll(thumbnails);
        allImages.addAll(thumbnailUrls);
        allImages.addAll(images);
        for (String imageUriText : allImages) {
            Uri imageUri = Uri.parse(imageUriText);
            if (imageUri != null) {
                String scheme = imageUri.getScheme();
                if (scheme != null && scheme.equals("data")) {
                    // already have an inline image, set it as image.contentUrl
                    try {
                        jsonLd.put("image", new JSONObject().put("contentUrl", imageUriText));
                        return jsonLd;
                    } catch (JSONException e) {
                        // todo log
                    }
                }
            }
        }
        for (String imageUriText : allImages) {
            try {
                String imageDataUri = downloadImage(imageUriText);
                if (imageDataUri != null) {
                    try {
                        jsonLd.put("image", new JSONObject().put("contentUrl", imageDataUri));
                        return  jsonLd;
                    } catch (JSONException e) {
                        // todo log
                    }
                }
            } catch (Exception ignored){}
        }
        return jsonLd;
    }

    private static String downloadImage(String imageUriText) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request request = new Builder()
            .url(imageUriText).build();
        try (Response response = client. newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body != null) {
                MediaType mediaType = body.contentType();
                if (mediaType != null && mediaType.type().equals("image")) {
                    return "data:" + mediaType.type() + "/" + mediaType.subtype() +";base64," + Base64.encodeToString(body.bytes(), Base64.NO_WRAP);
                }
            }

        }
        return null;
    }

    @NonNull
    static List<String> imagesFromNestedJson(Object image) throws JSONException {
        if (image instanceof JSONObject) {
            Object imageContentUrl = ((JSONObject) image).opt("contentUrl");
            if (imageContentUrl instanceof  String) {
                return Collections.singletonList((String) imageContentUrl);
            } else {
                Object url = ((JSONObject) image).opt("url");
                if (url instanceof  String) {
                    return Collections.singletonList((String)url);
                }
            }
        } else if (image instanceof JSONArray) {
            int length = ((JSONArray) image).length();
            List<String> images = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object imageEntry = ((JSONArray) image).get(i);
                if (imageEntry instanceof JSONObject) {
                    Object imageContentUrl = ((JSONObject) imageEntry).opt("contentUrl");
                    if (imageContentUrl instanceof String) {
                        images.add((String) imageContentUrl);
                    } else {
                        Object url = ((JSONObject) imageEntry).opt("url");
                        if (url instanceof String) {
                            images.add((String) url);
                        }
                    }
                } else if (imageEntry instanceof String) {
                    images.add((String) imageEntry);
                }
            }
            return images;
        } else if (image instanceof  String) {
            return Collections.singletonList((String) image);
        }
        return Collections.emptyList();
    }

    static class Ld2hResult {
        public final ArrayList<String> renderedDisplayHTMLs;
        public final ArrayList<String> types;

        public Ld2hResult(ArrayList<String> renderedDisplayHTMLs, ArrayList<String> types) {
            this.renderedDisplayHTMLs = renderedDisplayHTMLs;
            this.types = types;
        }
    }
}
