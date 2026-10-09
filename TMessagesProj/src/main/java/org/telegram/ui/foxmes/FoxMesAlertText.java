package org.telegram.ui.foxmes;

import org.telegram.messenger.foxmes.FoxMesErrorText;
import org.telegram.ui.ActionBar.AlertDialog;
import java.util.function.Consumer;

public final class FoxMesAlertText {
    private FoxMesAlertText() {
    }

    private static CharSequence format(CharSequence text) {
        return text instanceof String ? FoxMesErrorText.alertText((String) text) : text;
    }

    public static void setMessage(CharSequence text, Consumer<CharSequence> setter) {
        setter.accept(format(text));
    }

    public static AlertDialog.Builder setMessage(AlertDialog.Builder builder, CharSequence text, Consumer<CharSequence> setter) {
        setMessage(text, setter);
        return builder;
    }

    public static void showCallFailure(String error, Consumer<CharSequence> show, Consumer<String> nativeFailure) {
        if (error != null && error.startsWith("FOXMES_CALL_MESSAGE:")) {
            show.accept(error.substring("FOXMES_CALL_MESSAGE:".length()));
        } else {
            nativeFailure.accept(error);
        }
    }
}
