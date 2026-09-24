package com.fsck.k9.mailstore;


import java.util.List;

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

    public MessageData(List<JSONObject> structuredData, JSONObject extracted) {
        this.extracted = extracted;
        this.structuredData = structuredData;
    }
}
