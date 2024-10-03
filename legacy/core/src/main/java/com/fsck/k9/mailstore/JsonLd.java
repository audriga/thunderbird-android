package com.fsck.k9.mailstore;

import java.util.Map;

/* TODO proper deserialization at some point */
public class JsonLd {
    private Map<String, Object> data;

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
