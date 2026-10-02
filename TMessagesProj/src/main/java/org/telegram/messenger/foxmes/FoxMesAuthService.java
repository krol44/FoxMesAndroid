package org.telegram.messenger.foxmes;

import android.os.Build;

import org.telegram.messenger.foxmes.FoxMesModels.AuthExchange;
import org.telegram.messenger.foxmes.FoxMesModels.AuthStart;
import org.telegram.messenger.foxmes.FoxMesModels.Me;

import java.io.IOException;

public final class FoxMesAuthService {

    private volatile String pendingToken;
    private final FoxMesApi api = new FoxMesApi(new FoxMesHttp(() -> pendingToken));

    public static String deviceName() {
        String model = Build.MODEL != null ? Build.MODEL : "Android";
        return model + " FoxMes Android";
    }

    public AuthStart begin() throws IOException {
        return api.startDevice(deviceName());
    }

    public AuthExchange exchange(String request, String code) throws IOException {
        return api.exchangeDevice(request, code);
    }

    public Me restore(long userId) throws IOException {
        String token = FoxMesTokenStore.loadToken(userId);
        if (token == null) {
            return null;
        }
        pendingToken = token;
        try {
            return api.me();
        } catch (FoxMesHttpException e) {
            if (e.isSessionRejected()) {
                FoxMesTokenStore.clear(userId);
            }
            throw e;
        }
    }

    public Me me(String token) throws IOException {
        pendingToken = token;
        return api.me();
    }
}
