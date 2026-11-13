package com.fsck.k9.mailstore;


import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;


public class MessageData {
    /**
     * Structured data deliberately placed in the message. Note this currently also contains data gotten via attachments
     * (currently ics files, but vcard and pkpass are planned as well)
     */
    public final List<JSONObject> structuredData;

    /**
     * Additional structured data, extracted from message contents (currently includes verification codes)
     */
    public final JSONObject extracted;

    /**
     * Additional structured data, extracted from message contents via KItinerary library
     */
    public final List<JSONObject> itiExtracted;

    public MessageData(List<JSONObject> structuredData, JSONObject extracted, JSONArray itiExtracted) {
        this.extracted = extracted;
        this.structuredData = structuredData;
        this.itiExtracted = new ArrayList<JSONObject>(itiExtracted.length());
        for (int i = 0; i < itiExtracted.length(); i++) {
            JSONObject jsonObject = itiExtracted.optJSONObject(i);
            if (jsonObject != null) {
               this.itiExtracted.add(jsonObject);
            }
        }
    }
}
