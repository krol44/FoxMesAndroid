package org.telegram.messenger.foxmes;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;

public final class FoxMesTokenStore {

    private static final String KNOWN_USERS = "user-ids";
    private static final Object lock = new Object();
    private static SharedPreferences preferences;
    private static String preferencesProfile;

    private FoxMesTokenStore() {
    }

    private static SharedPreferences preferences() {
        synchronized (lock) {
            String profile = FoxMesConfiguration.profileKey();
            if (preferences != null && profile.equals(preferencesProfile)) {
                return preferences;
            }
            Context context = ApplicationLoader.applicationContext;
            String name = "foxmes_tokens_" + profile;
            SharedPreferences result;
            try {
                MasterKey key = new MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
                result = EncryptedSharedPreferences.create(context, name, key,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Exception e) {
                FoxMesLog.e("encrypted token store unavailable", e);
                result = context.getSharedPreferences(name + "_plain", Context.MODE_PRIVATE);
            }
            preferences = result;
            preferencesProfile = profile;
            return result;
        }
    }

    private static String tokenKey(long userId) {
        return "bearer-token-" + userId;
    }

    public static String loadToken(long userId) {
        if (userId == 0) {
            return null;
        }
        return preferences().getString(tokenKey(userId), null);
    }

    public static boolean hasToken(long userId) {
        return !TextUtils.isEmpty(loadToken(userId));
    }

    public static void save(String token, long userId) {
        synchronized (lock) {
            List<Long> users = knownUserIds();
            SharedPreferences.Editor editor = preferences().edit().putString(tokenKey(userId), token);
            if (!users.contains(userId)) {
                users.add(userId);
                editor.putString(KNOWN_USERS, TextUtils.join(",", users));
            }
            editor.commit();
        }
    }

    public static List<Long> knownUserIds() {
        ArrayList<Long> result = new ArrayList<>();
        String raw = preferences().getString(KNOWN_USERS, null);
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (hasToken(id) && !result.contains(id)) {
                    result.add(id);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    public static void clear(long userId) {
        synchronized (lock) {
            List<Long> users = knownUserIds();
            users.remove(userId);
            SharedPreferences.Editor editor = preferences().edit().remove(tokenKey(userId));
            if (users.isEmpty()) {
                editor.remove(KNOWN_USERS);
            } else {
                editor.putString(KNOWN_USERS, TextUtils.join(",", users));
            }
            editor.commit();
        }
    }
}
