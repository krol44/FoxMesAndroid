package org.telegram.messenger.foxmes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.telegram.messenger.BuildVars;

public class FoxMesDebugReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildVars.DEBUG_VERSION || intent == null || !intent.hasExtra("foxmes_env")) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(FoxMesConfiguration.DEBUG_PREFERENCES, Context.MODE_PRIVATE).edit();
        put(editor, FoxMesConfiguration.KEY_ENV, intent.getStringExtra("foxmes_env"));
        put(editor, FoxMesConfiguration.KEY_URL, intent.getStringExtra("foxmes_url"));
        put(editor, FoxMesConfiguration.KEY_WEB_URL, intent.getStringExtra("foxmes_web_url"));
        editor.putBoolean(FoxMesConfiguration.KEY_DISABLED, intent.getBooleanExtra("foxmes_disabled", false));
        editor.commit();
        FoxMesLog.d("debug environment: env=" + intent.getStringExtra("foxmes_env") + " url=" + intent.getStringExtra("foxmes_url"));
    }

    private static void put(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }
}
