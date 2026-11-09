package org.telegram.messenger.foxmes;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.List;

final class FoxMesArchive {

    static void apply(FoxMesRuntime runtime, List<FoxMesModels.Chat> chats,
                      List<TLRPC.Update> updates, ArrayList<TLRPC.User> users, Runnable afterApplied) {
        long generation = runtime.sessionGeneration();
        AndroidUtilities.runOnUIThread(() -> {
            if (!runtime.isStarted() || generation != runtime.sessionGeneration()) {
                return;
            }
            ArrayList<TLRPC.Update> result = new ArrayList<>(updates);
            TL_update.TL_updateFolderPeers folders = new TL_update.TL_updateFolderPeers();
            boolean reloadPins = false;
            for (FoxMesModels.Chat chat : chats) {
                if (runtime.chat(chat.id) != chat) {
                    continue;
                }
                long peer = runtime.peerOf(chat);
                TLRPC.Dialog dialog = AccountInstance.getInstance(runtime.account)
                        .getMessagesController().dialogs_dict.get(peer);
                int folder = chat.archived ? 1 : 0;
                if (dialog == null) {
                    boolean createsDialog = false;
                    for (TLRPC.Update update : updates) {
                        if (update instanceof TL_update.TL_updateNewMessage
                                && DialogObject.getPeerDialogId(((TL_update.TL_updateNewMessage) update).message.peer_id) == peer) {
                            createsDialog = true;
                            break;
                        }
                    }
                    if (!createsDialog || folder == 0) {
                        continue;
                    }
                } else if (dialog.folder_id == folder) {
                    continue;
                }
                TLRPC.TL_folderPeer value = new TLRPC.TL_folderPeer();
                value.peer = FoxMesTL.peer(peer);
                value.folder_id = folder;
                folders.folder_peers.add(value);
                reloadPins |= chat.pinned;
            }
            if (!folders.folder_peers.isEmpty()) {
                result.add(folders);
            }
            boolean refreshPins = reloadPins;
            runtime.updates.dispatch(result, users, () -> AndroidUtilities.runOnUIThread(() -> {
                if (!runtime.isStarted() || generation != runtime.sessionGeneration()) {
                    return;
                }
                AccountInstance.getInstance(runtime.account).getMessagesStorage().getStorageQueue().postRunnable(() -> {
                    if (!runtime.isStarted() || generation != runtime.sessionGeneration()) {
                        return;
                    }
                    if (refreshPins) {
                        ArrayList<TLRPC.Update> pins = new ArrayList<>();
                        for (int folder = 0; folder <= 1; folder++) {
                            TL_update.TL_updatePinnedDialogs update = new TL_update.TL_updatePinnedDialogs();
                            update.folder_id = folder;
                            if (folder != 0) {
                                update.flags |= 2;
                            }
                            pins.add(update);
                        }
                        runtime.updates.dispatch(pins, new ArrayList<>(), afterApplied);
                    } else if (afterApplied != null) {
                        afterApplied.run();
                    }
                });
            }));
        });
    }
}
