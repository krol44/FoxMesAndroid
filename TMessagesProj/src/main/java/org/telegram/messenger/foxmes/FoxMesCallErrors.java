package org.telegram.messenger.foxmes;

import org.telegram.messenger.voip.Instance;
import org.telegram.tgnet.TLRPC;
import java.util.function.Consumer;

public final class FoxMesCallErrors {
    private FoxMesCallErrors() {
    }

    public static void failRequest(TLRPC.TL_error error, Consumer<String> failed) {
        if ("CALL_BUSY".equals(error.text)) {
            failed.accept("FOXMES_CALL_MESSAGE:" + FoxMesErrorText.message(error.text, error.code));
        } else if (error.code == 400 && "PARTICIPANT_VERSION_OUTDATED".equals(error.text)) {
            failed.accept(Instance.ERROR_PEER_OUTDATED);
        } else if (error.code == 403) {
            failed.accept(Instance.ERROR_PRIVACY);
        } else if (error.code == 406) {
            failed.accept(Instance.ERROR_LOCALIZED);
        } else {
            failed.accept(Instance.ERROR_UNKNOWN);
        }
    }
}
