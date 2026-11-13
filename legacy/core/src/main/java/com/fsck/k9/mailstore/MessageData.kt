package com.fsck.k9.mailstore

import org.json.JSONArray
import org.json.JSONObject

class MessageData {
    /**
     * Structured data deliberately placed in the message. Note this currently also contains data gotten via attachments
     * (currently ics files, but vcard and pkpass are planned as well)
     */
    val structuredData: MutableList<JSONObject>

    /**
     * Additional structured data, extracted from message contents (currently includes verification codes)
     */
    val extracted: List<JSONObject>

    /**
     * Additional structured data, extracted from message contents via KItinerary library
     */
    val itiExtracted: List<JSONObject>

    constructor(
        structuredData: MutableList<JSONObject>,
        extracted: List<JSONObject>,
        itiExtracted: List<JSONObject>
    ) {
        this.structuredData = structuredData
        this.extracted = extracted
        this.itiExtracted = itiExtracted
    }

    constructor(
        structuredData: MutableList<JSONObject>,
        extracted: List<JSONObject>,
        itiExtracted: JSONArray
    ) {
        this.extracted = extracted
        this.structuredData = structuredData
        this.itiExtracted = ArrayList(itiExtracted.length())
        for (i in 0..<itiExtracted.length()) {
            val jsonObject = itiExtracted.optJSONObject(i)
            if (jsonObject != null) {
                this.itiExtracted.add(jsonObject)
            }
        }
    }
}

fun JSONArray.toJSONObjectList(): List<JSONObject> {
    val jsonList = ArrayList<JSONObject>(this.length())
    for (i in 0..<this.length()) {
        val jsonObject = this.optJSONObject(i)
        if (jsonObject != null) {
           jsonList.add(jsonObject)
        }
    }
    return jsonList
}
