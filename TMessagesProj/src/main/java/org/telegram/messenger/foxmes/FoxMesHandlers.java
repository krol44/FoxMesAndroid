package org.telegram.messenger.foxmes;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

final class FoxMesHandlers {

    private FoxMesHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        FoxMesConfigHandlers.register(transport, runtime);
        FoxMesChatHandlers.register(transport, runtime);
        FoxMesMutationHandlers.register(transport, runtime);
        FoxMesSender.register(transport, runtime);
        FoxMesFiles.register(transport, runtime);
        FoxMesStickerHandlers.register(transport, runtime);
        FoxMesCalls.register(transport, runtime);
        FoxMesConferences.register(transport, runtime);
    }


    static long peerUserId(TLRPC.InputPeer peer, FoxMesRuntime runtime) {
        if (peer == null) {
            return 0;
        }
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return runtime.selfId();
        }
        return peer.user_id;
    }

    static long peerUserId(TLRPC.InputDialogPeer peer, FoxMesRuntime runtime) {
        if (peer instanceof TLRPC.TL_inputDialogPeer) {
            return peerUserId(((TLRPC.TL_inputDialogPeer) peer).peer, runtime);
        }
        return 0;
    }

    static long userId(TLRPC.InputUser user, FoxMesRuntime runtime) {
        if (user == null) {
            return 0;
        }
        if (user instanceof TLRPC.TL_inputUserSelf) {
            return runtime.selfId();
        }
        return user.user_id;
    }

    static TLRPC.TL_boolTrue boolTrue() {
        return new TLRPC.TL_boolTrue();
    }

    static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    static TLRPC.TL_updates emptyUpdates() {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = now();
        return updates;
    }

    static <T extends TLObject> ArrayList<T> single(T value) {
        ArrayList<T> result = new ArrayList<>();
        result.add(value);
        return result;
    }

    static ArrayList<Long> ids(List<? extends Number> values) {
        ArrayList<Long> result = new ArrayList<>();
        for (Number value : values) {
            result.add(value.longValue());
        }
        return result;
    }
}
