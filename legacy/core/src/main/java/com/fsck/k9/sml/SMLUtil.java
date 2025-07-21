package com.fsck.k9.sml;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import android.net.Uri;
import android.net.Uri.Builder;
import android.util.Base64;

import org.audriga.ld2h.ButtonDescription;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import timber.log.Timber;


public abstract class SMLUtil {
    /**
     * Creates descriptions of buttons to be included in a rendered card.
     * @param jsonObject the cards schema
     * @return the button descriptions
     */
    public static List<ButtonDescription> getButtons(JSONObject jsonObject) {
        List<ButtonDescription> buttons = new ArrayList<>();
        Object potentialActions = jsonObject.opt("potentialAction");
        if (potentialActions != null) {
            if (potentialActions instanceof  JSONObject) {
                handleExistingPotentialAction((JSONObject) potentialActions, buttons);
            } else if (potentialActions instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) potentialActions).length(); i++) {
                    Object potentialAction = ((JSONArray) potentialActions).opt(i);
                    if (potentialAction instanceof JSONObject) {
                        handleExistingPotentialAction((JSONObject) potentialAction, buttons);
                    }
                }
            }
        }
        Object url = jsonObject.opt("url");
        if (url != null) {
            buttons.add(new ButtonDescription(null,"open_in_browser", url.toString()));
        }
        String type = jsonObject.optString("@type");
        if (shouldMakeSharableAsFile(type)) {
            byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
            String fileName = jsonObject.optString("name", type) + ".json";
            Uri uri = new Builder()
                .scheme("xshareasfile")
                .authority(encodedJson)
                .appendQueryParameter("fileName", fileName)
                .build();
            buttons.add(new ButtonDescription(null, "share", uri.toString()));
        }
        if (isEvent(type) || hasStartAndEndDate(jsonObject)) {
            byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
            Uri uri = new Builder()
                .scheme("xshareascalendar")
                .authority(encodedJson)
                .build();
            buttons.add(new ButtonDescription(null, "event", uri.toString()));
        }
        // try to get phone number
        try {
            List<Object> phones = findAllRecursive(jsonObject, "telephone");
            for (Object phone : phones) {
                // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                if (phone instanceof String) {
                    buttons.add(new ButtonDescription(null, "call", "tel:" +  phone));
                }
            }
        } catch (JSONException e) {
            Timber.e(e, "Error trying to add phone button descriptions");
        }
        // try to get Place/ Map
        try {
            List<Object> geos = findAllRecursive(jsonObject, "geo");
            for (Object geo : geos) {
                if (geo instanceof JSONObject) {
                    Object latitude = ((JSONObject) geo).opt("latitude");
                    Object longitude = ((JSONObject) geo).opt("longitude");
                    // don't care if lat/ long is int, double or string, all of them should play nicely with string concatenation.
                    // likelyhood of being a JSONObject, or something unexpected is very low I would say
                    if (latitude != null && longitude != null) {
                        // todo the geo uris are hardcoded at the moment
                        buttons.add(new ButtonDescription(null, "assistant_direction", "google.navigation:q=" + latitude + "," + longitude));
                        buttons.add(new ButtonDescription(null, "map", "geo:" + latitude + "," + longitude));
                    }
                }
            }
        } catch (JSONException e) {
            Timber.e(e, "Error trying to add geo button descriptions");
        }
        if (shouldMakeSharableAsMail(type)) {
            byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
            Uri buttonUri = new Builder()
                .scheme("xshareasmail")
                .authority(encodedJson)
                .build();
            buttons.add(new ButtonDescription(null, "forward_to_inbox", buttonUri.toString()));
        }
        String liveUri = jsonObject.optString("liveUri");
        if (!liveUri.isEmpty()) {

            Uri buttonUri = Uri.parse(liveUri).buildUpon()
                .scheme("xreload")
                .build();
            buttons.add(new ButtonDescription(null, "replay", buttonUri.toString()));
        }
//        buttons.add(new ButtonDescription("Call", "tel:124"));
//        buttons.add(new ButtonDescription("Story", "xstory:#https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html"));
        return  buttons;
    }

    private static void handleExistingPotentialAction(JSONObject potentialAction, List<ButtonDescription> buttons) {
        String type = potentialAction.optString("@type");
        switch (type) {
            case "CopyToClipboardAction": {
                String name = potentialAction.optString("name", "Copy to clipboard ");
                String description = potentialAction.optString("description");
                if (!description.isEmpty()) {
                    buttons.add(new ButtonDescription(name, "content_paste", "xclipboard:" + description));
                }
                break;
            }
            case "ConfirmAction": {
                String name = potentialAction.optString("name", "Confirm");
                String target = potentialAction.optString("target");
                if (!target.isEmpty()) {
                    buttons.add(new ButtonDescription(name, target));
                }
                break;
            }
            case "CancelAction": {
                String name = potentialAction.optString("name", "Deny");
                String target = potentialAction.optString("target");
                if (!target.isEmpty()) {
                    buttons.add(new ButtonDescription(name, target));
                }
                break;
            }
        }
    }

    private static boolean shouldMakeSharableAsFile(String type) {
        return type.equals("Recipe") || type.endsWith("Reservation");
    }
    private static boolean shouldMakeSharableAsMail(String type) {
        return  true;
        // todo: long term wise should do some type based filtering
    }

    private static boolean isEvent(String type) {
        return type.endsWith("Event");
    }
    private static boolean hasStartAndEndDate(JSONObject jsonObject) {

        String startTime = jsonObject.optString("startTime");
        String startDate = jsonObject.optString("startDate", startTime);
        String endTime = jsonObject.optString("endTime");
        String endDate = jsonObject.optString("endDate", endTime);
        return  (!startDate.isEmpty()) || (!endDate.isEmpty());
    }

    static List<Object> findAllRecursive(JSONObject json, String searchKey) throws JSONException {
        return findAllRecursive(json, searchKey, Collections.emptyList());
    }
    static List<Object> findAllRecursive(JSONObject json, String searchKey, List<String> excluded) throws JSONException {
        List<Object> collectedResults = new ArrayList<>();
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            if (excluded.contains(key)) {
                continue;
            }
            Object value = json.get(key);
            if (key.equals(searchKey)) {
                collectedResults.add(value);
            } else if (value instanceof JSONObject) {
                List<Object> ret = findAllRecursive((JSONObject) value, searchKey, excluded);
                collectedResults.addAll(ret);
            } else if (value instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) value).length(); i++) {
                    Object element = ((JSONArray) value).get(i);
                    if (element instanceof JSONObject) {
                        List<Object> ret = findAllRecursive((JSONObject) element, searchKey, excluded);
                        collectedResults.addAll(ret);
                    }
                }
            }
        }
        return collectedResults;
    }
}
