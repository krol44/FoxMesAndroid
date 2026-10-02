package org.telegram.messenger.foxmes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

public final class FoxMesJson {

    public static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private FoxMesJson() {
    }

    public static <T> T parse(String json, Class<T> type) {
        return gson.fromJson(json, type);
    }

    public static <T> T parse(String json, Type type) {
        return gson.fromJson(json, type);
    }

    public static <T> T parse(JsonElement json, Class<T> type) {
        return gson.fromJson(json, type);
    }

    public static Body body() {
        return new Body();
    }

    public static String operationId() {
        return UUID.randomUUID().toString().toLowerCase(Locale.US);
    }

    public static final class Body {
        final JsonObject object = new JsonObject();

        public Body put(String key, String value) {
            if (value != null) {
                object.addProperty(key, value);
            }
            return this;
        }

        public Body put(String key, Number value) {
            if (value != null) {
                object.addProperty(key, value);
            }
            return this;
        }

        public Body put(String key, Boolean value) {
            if (value != null) {
                object.addProperty(key, value);
            }
            return this;
        }

        public Body put(String key, JsonElement value) {
            if (value != null) {
                object.add(key, value);
            }
            return this;
        }

        public Body put(String key, Body value) {
            if (value != null) {
                object.add(key, value.object);
            }
            return this;
        }

        public Body putNumbers(String key, Collection<? extends Number> values) {
            if (values != null) {
                JsonArray array = new JsonArray();
                for (Number value : values) {
                    array.add(value);
                }
                object.add(key, array);
            }
            return this;
        }

        public Body putStrings(String key, Collection<String> values) {
            if (values != null) {
                JsonArray array = new JsonArray();
                for (String value : values) {
                    array.add(value);
                }
                object.add(key, array);
            }
            return this;
        }

        public Body putBodies(String key, Collection<Body> values) {
            if (values != null) {
                JsonArray array = new JsonArray();
                for (Body value : values) {
                    array.add(value.object);
                }
                object.add(key, array);
            }
            return this;
        }

        public Body putMap(String key, Map<String, ?> values) {
            if (values != null) {
                JsonObject map = new JsonObject();
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    map.add(entry.getKey(), toElement(entry.getValue()));
                }
                object.add(key, map);
            }
            return this;
        }

        public boolean isEmpty() {
            return object.size() == 0;
        }

        public JsonObject toJson() {
            return object;
        }

        @Override
        public String toString() {
            return gson.toJson(object);
        }
    }

    static JsonElement toElement(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Body) {
            return ((Body) value).object;
        }
        if (value instanceof JsonElement) {
            return (JsonElement) value;
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        return gson.toJsonTree(value);
    }


    public static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static Long optLong(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return primitive.getAsLong();
            }
            if (primitive.isString()) {
                return Long.parseLong(primitive.getAsString().trim());
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static long getLong(JsonObject object, String key, long fallback) {
        Long value = optLong(object, key);
        return value != null ? value : fallback;
    }

    public static String optString(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    public static Boolean optBoolean(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        return null;
    }


    public static int unixTime(String value) {
        long millis = unixMillis(value);
        return millis > 0 ? (int) (millis / 1000) : 0;
    }

    public static long unixMillis(String value) {
        if (value == null) {
            return 0;
        }
        String s = value.trim();
        try {
            if (s.length() < 19 || s.charAt(4) != '-' || s.charAt(7) != '-' || (s.charAt(10) != 'T' && s.charAt(10) != ' ') || s.charAt(13) != ':' || s.charAt(16) != ':') {
                return 0;
            }
            int year = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(5, 7));
            int day = Integer.parseInt(s.substring(8, 10));
            int hour = Integer.parseInt(s.substring(11, 13));
            int minute = Integer.parseInt(s.substring(14, 16));
            int second = Integer.parseInt(s.substring(17, 19));
            int index = 19;
            int millis = 0;
            if (index < s.length() && s.charAt(index) == '.') {
                index++;
                int digits = 0;
                while (index < s.length() && Character.isDigit(s.charAt(index))) {
                    if (digits < 3) {
                        millis = millis * 10 + (s.charAt(index) - '0');
                    }
                    digits++;
                    index++;
                }
                for (int i = digits; i < 3; i++) {
                    millis *= 10;
                }
            }
            int offsetMinutes = 0;
            if (index < s.length()) {
                char zone = s.charAt(index);
                if (zone == '+' || zone == '-') {
                    String rest = s.substring(index + 1).replace(":", "");
                    int hours = Integer.parseInt(rest.substring(0, 2));
                    int minutes = rest.length() >= 4 ? Integer.parseInt(rest.substring(2, 4)) : 0;
                    offsetMinutes = (hours * 60 + minutes) * (zone == '-' ? -1 : 1);
                }
            }
            java.util.Calendar calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            long result = calendar.getTimeInMillis() + millis - offsetMinutes * 60_000L;
            return result > 0 ? result : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int unixTimeOrNow(String value) {
        int time = unixTime(value);
        return time > 0 ? time : (int) (System.currentTimeMillis() / 1000);
    }
}
