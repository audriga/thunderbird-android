package com.fsck.k9.sml;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

import android.net.Uri;
import android.net.Uri.Builder;
import android.util.Base64;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.audriga.ld2h.ButtonDescription;
import org.audriga.ld2h.HeadProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import timber.log.Timber;


public abstract class SMLUtil {
    public static String css() {
        String css = HeadProvider.loadHead();
        return "<head>\n" + css + "\n</head>";
    }

    /**
     * Creates descriptions of buttons to be included in a rendered card.
     * @param jsonObject the cards schema
     * @return the button descriptions
     */
    public static List<ButtonDescription> getButtons(JSONObject jsonObject) {
        List<ButtonDescription> buttons = new ArrayList<>();
        String type = jsonObject.optString("@type");
        Object potentialActions = jsonObject.opt("potentialAction");
        if (potentialActions != null) {
            if (potentialActions instanceof  JSONObject) {
                handleExistingPotentialAction((JSONObject) potentialActions, buttons, type);
            } else if (potentialActions instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) potentialActions).length(); i++) {
                    Object potentialAction = ((JSONArray) potentialActions).opt(i);
                    if (potentialAction instanceof JSONObject) {
                        handleExistingPotentialAction((JSONObject) potentialAction, buttons, type);
                    }
                }
            }
        }
        ButtonDescription audioButtonDesc = getAudioContentButtonDesc(jsonObject);
        if (audioButtonDesc != null) {
            buttons.add(audioButtonDesc);
        }
        ButtonDescription videoButtonDesc = getVideoContentButtonDesc(jsonObject);
        if (videoButtonDesc != null) {
            buttons.add(videoButtonDesc);
        }
        ButtonDescription webUrlButtonDesc = getWebUrlButtonDesc(jsonObject);
        if (webUrlButtonDesc != null) {
            buttons.add(webUrlButtonDesc);
        }
        if (shouldMakeSharableAsFile(type)) {
            ButtonDescription shareAsFileButtonDesc = getShareAsFileButtonDesc(jsonObject, type);
            buttons.add(shareAsFileButtonDesc);
        }
        if (isEvent(type) || hasStartAndEndDate(jsonObject)) {
            ButtonDescription meetButtonDesc = getMeetButtonDesc(jsonObject);
            if (meetButtonDesc != null) {
                buttons.add(meetButtonDesc);
            }
            List<ButtonDescription> eventButtonDesc = getEventButtonDescs(jsonObject);
            buttons.addAll(eventButtonDesc);

        }
        if (!isPoll(type)) {
            // try to get phone number
            try {
                List<Object> telephone = findAllRecursive(jsonObject, "telephone");
                for (Object phone : telephone) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (phone instanceof String) {
                        buttons.add(new ButtonDescription(null, "call", "tel:" +  phone));
                    }
                }
                List<Object> phones = findAllRecursive(jsonObject, "phone");
                for (Object phone : phones) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (phone instanceof String) {
                        buttons.add(new ButtonDescription(null, "call", "tel:" +  phone));
                    }
                }
            } catch (JSONException e) {
                Timber.e(e, "Error trying to add phone button descriptions");
            }
            // try to get mail
            try {
                List<Object> mails = findAllRecursive(jsonObject, "email");
                for (Object email : mails) {
                    // todo: we might at some point if value is a JSONObject or JSONArray, still return it/ all of its values
                    if (email instanceof String) {
                        buttons.add(new ButtonDescription(null, "mail", "mailto:" +  email));
                    }
                }
            } catch (JSONException e) {
                Timber.e(e, "Error trying to add phone button descriptions");
            }
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
            ButtonDescription shareAsMailButtonDesc = getShareAsMailButtonDesc(jsonObject);
            buttons.add(shareAsMailButtonDesc);
        }
        String liveUri = jsonObject.optString("liveUri");
        if (!liveUri.isEmpty()) {
            ButtonDescription reloadButtonDesc = getReloadButtonDesc(liveUri);
            buttons.add(reloadButtonDesc);
        }
//        buttons.add(new ButtonDescription("Call", "tel:124"));
//        buttons.add(new ButtonDescription("Story", "xstory:#https://cdn.prod.www.spiegel.de/stories/66361/index.amp.html"));

        return  buttons;
    }

    @Nullable
    public static ButtonDescription getAudioContentButtonDesc(JSONObject jsonObject) {
        String mediaType = "audio";
        return getMediaContentButtonDesc(jsonObject, mediaType, "music_note");
    }


    @Nullable
    public static ButtonDescription getVideoContentButtonDesc(JSONObject jsonObject) {
        String mediaType = "video";
        return getMediaContentButtonDesc(jsonObject, mediaType, "play_circle");
    }

    @Nullable
    private static ButtonDescription getMediaContentButtonDesc(JSONObject jsonObject, String mediaType, String icon) {
        String mediaContentUrl = getMediaContentUrl(jsonObject, mediaType);
        if (mediaContentUrl == null || mediaContentUrl.isEmpty()) {
            return null;
        }
        String encodedContentUrl = Base64.encodeToString(mediaContentUrl.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP + Base64.URL_SAFE);
        Uri uri = new Builder()
            .scheme("xplaymedia")
            .authority(encodedContentUrl)
            .appendQueryParameter("mediaType", mediaType)
            .build();
        return new ButtonDescription(null, icon, uri.toString());
    }

    @Nullable
    private static String getMediaContentUrl(JSONObject jsonObject, String mediaType) {
        Object media = jsonObject.opt(mediaType);
        if (media != null) {
            if (media instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) media).length(); i++) {
                    try {
                        Object mediaElement = ((JSONArray) media).get(i);
                        if (mediaElement instanceof JSONObject) {
                            return  getPotentiallyNestedMediaContentUrl((JSONObject) mediaElement, mediaType);
                        }
                    } catch (JSONException e) {
                        Timber.e(e, "Error while iteration over audio or video jsonld element");
                    }
                }
            } else if (media instanceof JSONObject) {
                return  getPotentiallyNestedMediaContentUrl((JSONObject) media, mediaType);
            }

        }
        return null;
    }

    /**
     * Gets contentUrl either directly from object, or from direct child, but does not recurse
     */
    @Nullable
    private static String getPotentiallyNestedMediaContentUrl(JSONObject media, String mediaType) {
        String mediaContentUrl = media.optString("contentUrl");
        if (!mediaContentUrl.isEmpty()) {
           return mediaContentUrl;
        } else {
            JSONObject innerMedia = media.optJSONObject(mediaType);
            if (innerMedia != null) {
                mediaContentUrl = innerMedia.optString("contentUrl");
                if (!mediaContentUrl.isEmpty()) {
                    return mediaContentUrl;
                }
            }
        }
        return null;
    }


    public static ButtonDescription getMeetButtonDesc(JSONObject jsonObject) {
        String description = jsonObject.optString("description");
        if (!description.isEmpty()) {
        Matcher urlHtmlMatcher = Patterns.WEB_URL.matcher(description);
        while (urlHtmlMatcher.find()) {
            String url = urlHtmlMatcher.group();
                if (url.contains("meet.google.com")) {
                    return new ButtonDescription(null, "videocam", url);
            }
        }
        }
        return null;
    }
    @NonNull
    public static List<ButtonDescription> getEventButtonDescs(JSONObject jsonObject) {
        byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
        Uri uri = new Builder()
            .scheme("xshareascalendar")
            .authority(encodedJson)
            .build();
        String iTIPMethod = jsonObject.optString("iTIPMethod");
        ButtonDescription shareEventAsCalendar = new ButtonDescription(null, "event", uri.toString());
        if (iTIPMethod.isEmpty()) {
            return Collections.singletonList(shareEventAsCalendar);
        } else if (iTIPMethod.toLowerCase(Locale.ROOT).equals("request")){
            return getIMITButtonDescriptions(encodedJson);
        } else {
            return Collections.singletonList(shareEventAsCalendar);
        }
    }

    @NonNull
    private static List<ButtonDescription> getIMITButtonDescriptions(String encodedJson) {
        List<ButtonDescription> buttonDescs = new ArrayList<>(2);
//        buttonDescs.add(shareEventAsCalendar);
        Uri acceptUri = new Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("accept")
            .build();
        Uri declineUri = new Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("decline")
            .build();
        Uri tentativeUri = new Builder()
            .scheme("ximip")
            .authority(encodedJson)
            .query("tentative")
            .build();
        buttonDescs.add(new ButtonDescription("Accept", acceptUri.toString()));
        buttonDescs.add(new ButtonDescription("Decline", declineUri.toString()));
        buttonDescs.add(new ButtonDescription("Tentative", tentativeUri.toString()));
        return buttonDescs;
    }

    @Nullable
    public static ButtonDescription getWebUrlButtonDesc(JSONObject jsonObject) {
        Object url = jsonObject.opt("url");
        ButtonDescription webUrlButtonDesc = null;
        if (url != null) {
            webUrlButtonDesc = new ButtonDescription(null, "link", url.toString());
        }
        return webUrlButtonDesc;
    }

    @NonNull
    public static ButtonDescription getReloadButtonDesc(String liveUri) {
        Uri buttonUri = Uri.parse(liveUri).buildUpon()
            .scheme("xreload")
            .build();
        return new ButtonDescription(null, "replay", buttonUri.toString());
    }

    @NonNull
    public static ButtonDescription getShareAsMailButtonDesc(JSONObject jsonObject) {
        byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
        Uri buttonUri = new Builder()
            .scheme("xshareasmail")
            .authority(encodedJson)
            .build();
        return new ButtonDescription(null, "forward_to_inbox", buttonUri.toString());
    }

    @NonNull
    public static ButtonDescription getShareAsFileButtonDesc(JSONObject jsonObject, String fallbackFileName) {
        byte[] jsonBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        String encodedJson = Base64.encodeToString(jsonBytes, Base64.NO_WRAP + Base64.URL_SAFE);
        String fileName = jsonObject.optString("name", fallbackFileName) + ".json";
        Uri uri = new Builder()
            .scheme("xshareasfile")
            .authority(encodedJson)
            .appendQueryParameter("fileName", fileName)
            .build();
        return new ButtonDescription(null, "share", uri.toString());
    }

    private static void handleExistingPotentialAction(JSONObject potentialAction, List<ButtonDescription> buttons, String mainSchemaType) {
        String actionType = potentialAction.optString("@type");
        switch (actionType) {
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
            case "ChooseAction": // fallthrough
            case "UpdateAction": {
                if (isPoll(mainSchemaType)) {
                    Object target = potentialAction.opt("target");
                    if (target instanceof JSONArray) {
                        JSONArray targetArray = (JSONArray) target;
                        for (int i = 0; i < targetArray.length(); i++) {
                            Object targetMember = null;
                            try {
                                targetMember = targetArray.get(i);
                            } catch (JSONException ignored) {}
                            addPollButtonFromSingleTarget(potentialAction, actionType, targetMember, buttons);
                        }
                    } else {
                        addPollButtonFromSingleTarget(potentialAction, actionType, target, buttons);
                    }
                }
                break;
            }
        }
    }

    private static void addPollButtonFromSingleTarget(JSONObject potentialAction, String actionType, Object targetMember, List<ButtonDescription> buttons) {
        if (targetMember instanceof String && ((String) targetMember).startsWith("http")) {
            String encodedTarget = Uri.encode((String) targetMember);
            // todo not tested yet
            if (actionType.equals("ChooseAction")) {
                ButtonDescription submitButton =  new ButtonDescription("Submit",null, "xsubmit:"+encodedTarget+"?action=submit&vote={{user_vote}}");
                buttons.add(submitButton);
            } else if (actionType.equals("UpdateAction")) {
                String actionName = potentialAction.optString("name");
                switch(actionName) {
                    case "close": {
                        ButtonDescription closeButton =  new ButtonDescription("Close",null, "xsubmit:"+encodedTarget+"?action=close");
                        buttons.add(closeButton);
                    }
                    case "retract": {
                        ButtonDescription retractButton =  new ButtonDescription("Retract",null, "xsubmit:"+encodedTarget+"?action=retract");
                        buttons.add(retractButton);
                    }
                }
            }
        }
//        else {
            // todo: Ignoring mailto in target for now
//        }
    }

    private static boolean shouldMakeSharableAsFile(String type) {
        return type.equals("Recipe") || type.endsWith("Reservation");
    }
    private static boolean shouldMakeSharableAsMail(String type) {
        return  true;
        // todo: long term wise should do some type based filtering
    }


    private static boolean isPoll(String type) {
        return type != null && type.equals("SimplePoll");
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
