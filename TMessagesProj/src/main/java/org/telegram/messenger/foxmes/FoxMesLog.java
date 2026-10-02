package org.telegram.messenger.foxmes;

import android.util.Log;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

public final class FoxMesLog {
    private static final String TAG = "FoxMes";

    private FoxMesLog() {
    }

    public static void d(String message) {
        if (BuildVars.DEBUG_VERSION) {
            Log.d(TAG, message);
        }
        FileLog.d("[FoxMes] " + message);
    }

    public static void w(String message) {
        if (BuildVars.DEBUG_VERSION) {
            Log.w(TAG, message);
        }
        FileLog.w("[FoxMes] " + message);
    }

    public static void e(String message, Throwable error) {
        if (BuildVars.DEBUG_VERSION) {
            Log.e(TAG, message, error);
        }
        FileLog.e("[FoxMes] " + message, error);
    }

    public static void e(String message) {
        if (BuildVars.DEBUG_VERSION) {
            Log.e(TAG, message);
        }
        FileLog.e("[FoxMes] " + message);
    }
}
