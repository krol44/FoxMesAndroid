package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

public final class FoxMesCallSettings {

    private FoxMesCallSettings() {
    }

    public static void loadAllowsP2P(int account, Utilities.Callback<Boolean> done) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.io.execute(() -> deliver(done, read(() -> runtime.api.callSettings())));
    }

    public static void setAllowsP2P(int account, boolean value, Utilities.Callback<Boolean> done) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.serial.execute(() -> deliver(done, read(() -> runtime.api.updateCallSettings(value))));
    }

    private interface Call {
        JsonObject run() throws Exception;
    }

    private static boolean read(Call call) {
        try {
            return Boolean.TRUE.equals(FoxMesJson.optBoolean(call.run(), "p2p_allowed"));
        } catch (Exception e) {
            FoxMesLog.e("call settings failed", e);
            return false;
        }
    }

    private static void deliver(Utilities.Callback<Boolean> done, boolean value) {
        AndroidUtilities.runOnUIThread(() -> done.run(value));
    }
}
