package com.fsck.k9.activity.compose;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.content.Intent;
//import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.audriga.h2lj.parser.StructuredDataExtractionUtils;
import com.fsck.k9.activity.misc.Attachment;
import com.fsck.k9.helper.MimeTypeUtil;
import com.fsck.k9.sml.SMLUtil;
import com.fsck.k9.view.MessageWebView;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.audriga.ld2h.ButtonDescription;
import org.json.JSONException;
import org.json.JSONObject;


public class SMLMessageComposeUtil {
    public static final String IS_SML = "isSML";
    public static final String SML_PAYLOAD = "sml_payload";


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
                displayLd2hResultAndUpdateSubject(ld2hResult);
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


    public void enrichTextToSmlIfUrl(CharSequence text) {
        if (Patterns.WEB_URL.matcher(text).matches()) {
            // Input is exactl one url
            enrichSharedUrlToSml(text.toString());
        }
    }

    public void enrichSharedUrlToSml(String url) {
            List<JSONObject> data = StructuredDataExtractionUtils.downloadParseAndRefineStructuredData(url, true, true);
        try {
            org.audriga.ld2h.MustacheRenderer ld2hRenderer = new org.audriga.ld2h.MustacheRenderer();
            data = ld2hRenderer.filterRenderable(data);
        } catch (IOException ignored) {
        }
            if (!data.isEmpty()) {
                if (smlPayload == null) {
                    smlPayload = data;
                } else {
                    smlPayload.addAll(data);
                }
                Ld2hResult ld2hResult = ld2hRenderSmlPayload(smlPayload);
                displayLd2hResultAndUpdateSubject(ld2hResult);
            }
            // else: No structured data found, todo: treat link as normal?

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
        return SMLUtil.css() + ld2hRenderResult;
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
                    boolean hadResult = displayLd2hResultAndUpdateSubject(ld2hResult);
                    if (hadResult) {
                        attachmentPresenter.onClickRemoveAttachment(attachment.uri);
                        // todo: This function is called in updateAttachmentView, and after that function returns, the
                        //       attachment gets added in the attachments list in AttachmentPresenter.
                        //       so either need to change where this function is called, or add attachements on send
                        return true;
                    }
                }
            } catch (IOException ignored) {
            }

        }
        return false;
    }

    private boolean displayLd2hResultAndUpdateSubject(Ld2hResult ld2hResult) {
        if (!ld2hResult.renderedDisplayHTMLs.isEmpty()) {
            String joinedDisplayHTMLRenderResults = String.join("\n", ld2hResult.renderedDisplayHTMLs);
            displayLd2hResult(joinedDisplayHTMLRenderResults);
            if (subjectView.getText().length() == 0 || subjectView.getText().toString().startsWith("Check out this ")) {
                String subject = "Check out this " + String.join(", ", ld2hResult.types);
                subjectView.setText(subject);
            }
            return true;
        }
        return false;
    }


    /**
     * Inlines images (if not already inlined) via H2LD, and then renders the given payload using LD2H.
     * @param smlPayload schema to render. This is modified if inline is necessary and possible.
     * @return the resulting HTMLs of the LD2H render, together with the schema types.
     */
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
            // For freshly downloaded schema, the image has already been inlined.
            // But inlineImages is smart enough to detect the already inlined image.
            //
            // Since this modifies the actual jsonObject, and we iterate over call-by-reference smlPayload here,
            // this also modifies the smlPayload this function was called wit (which is what we want).
            StructuredDataExtractionUtils.inlineImages(jsonObject);
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


    static class Ld2hResult {
        public final ArrayList<String> renderedDisplayHTMLs;
        public final ArrayList<String> types;

        public Ld2hResult(ArrayList<String> renderedDisplayHTMLs, ArrayList<String> types) {
            this.renderedDisplayHTMLs = renderedDisplayHTMLs;
            this.types = types;
        }
    }
}
