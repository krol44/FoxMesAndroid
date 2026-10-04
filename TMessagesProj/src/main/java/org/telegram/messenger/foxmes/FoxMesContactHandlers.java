package org.telegram.messenger.foxmes;

import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

// Blocking is the account's block list, the same one the site keeps. The
// upstream block/unblock requests and the block list map onto it one to one.
final class FoxMesContactHandlers {

    private FoxMesContactHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_contacts_block.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_contacts_block req = (TLRPC.TL_contacts_block) r.request;
            setBlocked(r, runtime, FoxMesHandlers.peerUserId(req.id, runtime), true);
        });
        transport.on(TLRPC.TL_contacts_unblock.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_contacts_unblock req = (TLRPC.TL_contacts_unblock) r.request;
            setBlocked(r, runtime, FoxMesHandlers.peerUserId(req.id, runtime), false);
        });
        transport.on(TLRPC.TL_updateContactNote.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_updateContactNote req = (TLRPC.TL_updateContactNote) r.request;
            long userId = FoxMesHandlers.userId(req.id, runtime);
            if (userId == 0 || userId == runtime.selfId()) {
                r.fail(400, "USER_ID_INVALID");
                return;
            }
            String note = req.note != null && req.note.text != null ? req.note.text : "";
            runtime.rememberUser(runtime.api.setContactFields(userId, null, note));
            r.reply(FoxMesHandlers.boolTrue());
        });
        transport.on(TLRPC.TL_contacts_getBlocked.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_contacts_getBlocked req = (TLRPC.TL_contacts_getBlocked) r.request;
            runtime.snapshot(false);
            TLRPC.TL_contacts_blocked result = new TLRPC.TL_contacts_blocked();
            ArrayList<Long> ids = req.offset == 0 ? runtime.blockedUserIds() : new ArrayList<>();
            int date = FoxMesHandlers.now();
            for (Long id : ids) {
                TLRPC.TL_peerBlocked blocked = new TLRPC.TL_peerBlocked();
                blocked.peer_id = FoxMesTL.peer(id);
                blocked.date = date;
                result.blocked.add(blocked);
            }
            result.users.addAll(runtime.tlUsers(ids));
            r.reply(result);
        });
    }

    private static void setBlocked(FoxMesRequest r, FoxMesRuntime runtime, long userId, boolean blocked) throws java.io.IOException {
        if (userId == 0 || userId == runtime.selfId()) {
            r.fail(400, "PEER_ID_INVALID");
            return;
        }
        User user = runtime.api.setUserBlocked(userId, blocked);
        runtime.rememberUser(user);
        r.reply(FoxMesHandlers.boolTrue());
    }
}
