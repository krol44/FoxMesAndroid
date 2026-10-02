package org.telegram.messenger.foxmes;

import java.io.IOException;

public final class FoxMesHttpException extends IOException {
    public final int status;
    public final String code;
    public final String serverMessage;
    public final String body;

    public FoxMesHttpException(int status, String code, String serverMessage, String body) {
        super("FoxMes HTTP " + status + " " + (code != null ? code : "") + ": " + (serverMessage != null ? serverMessage : ""));
        this.status = status;
        this.code = code;
        this.serverMessage = serverMessage;
        this.body = body;
    }

    public boolean isSessionRejected() {
        return status == 401 || status == 403;
    }

    public boolean isTransient() {
        return status >= 500 || status == 408 || status == 429;
    }

    public static boolean isTransient(Throwable error) {
        if (error instanceof FoxMesHttpException) {
            return ((FoxMesHttpException) error).isTransient();
        }
        return error instanceof IOException;
    }

    public static boolean isStatus(Throwable error, int status) {
        return error instanceof FoxMesHttpException && ((FoxMesHttpException) error).status == status;
    }
}
