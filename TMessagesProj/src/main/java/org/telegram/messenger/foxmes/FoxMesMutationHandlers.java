package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.foxmes.FoxMesModels.Attachment;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.DeleteResult;
import org.telegram.messenger.foxmes.FoxMesModels.Gif;
import org.telegram.messenger.foxmes.FoxMesModels.GifList;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationSettings;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalog;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalogItem;
import org.telegram.messenger.foxmes.FoxMesModels.ReadState;
import org.telegram.messenger.foxmes.FoxMesModels.Reminder;
import org.telegram.messenger.foxmes.FoxMesModels.ReminderList;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.telegram.messenger.foxmes.FoxMesHandlers.boolTrue;
import static org.telegram.messenger.foxmes.FoxMesHandlers.now;
import static org.telegram.messenger.foxmes.FoxMesHandlers.peerUserId;

final class FoxMesMutationHandlers {

    private FoxMesMutationHandlers() {
    }

    private static int storagePts(FoxMesRuntime runtime) {
        return AccountInstance.getInstance(runtime.account).getMessagesStorage().getLastPtsValue();
    }

    static TLRPC.TL_messages_affectedMessages affectedMessages(FoxMesRuntime runtime) {
        TLRPC.TL_messages_affectedMessages result = new TLRPC.TL_messages_affectedMessages();
        result.pts = storagePts(runtime);
        result.pts_count = 0;
        return result;
    }

    static TLRPC.TL_messages_affectedHistory affectedHistory(FoxMesRuntime runtime) {
        TLRPC.TL_messages_affectedHistory result = new TLRPC.TL_messages_affectedHistory();
        result.pts = storagePts(runtime);
        result.pts_count = 0;
        result.offset = 0;
        return result;
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_getMessageEditData.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_messageEditData()));
        transport.on(TLRPC.TL_messages_editMessage.class, FoxMesTransport.Lane.SERIAL, r -> editMessage(r, runtime));
        transport.on(TLRPC.TL_messages_deleteMessages.class, FoxMesTransport.Lane.SERIAL, r -> deleteMessages(r, runtime));
        transport.on(TLRPC.TL_messages_deleteHistory.class, FoxMesTransport.Lane.SERIAL, r -> deleteHistory(r, runtime));
        transport.on(TLRPC.TL_messages_readHistory.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_readHistory req = (TLRPC.TL_messages_readHistory) r.request;
            long chatId = runtime.chatForPeer(peerUserId(req.peer, runtime));
            if (chatId != 0) {
                ReadState state = runtime.api.markRead(chatId, req.max_id > 0 ? req.max_id : null);
                if (state != null && state.readerId != 0 && state.readerId != runtime.selfId()) {
                    runtime.rememberOutboxRead(chatId, state.readThroughId, state.readAt);
                }
            }
            r.replyLate(() -> affectedMessages(runtime));
        });
        transport.on(TLRPC.TL_messages_readMessageContents.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_readMessageContents req = (TLRPC.TL_messages_readMessageContents) r.request;
            for (Integer id : req.id) {
                if (!runtime.store().isEphemeral(id)) {
                    continue;
                }
                runtime.openedHere.add(id);
                try {
                    runtime.api.markEphemeralViewed(id);
                } catch (FoxMesHttpException e) {
                    FoxMesLog.w("viewed " + id + ": " + e.getMessage());
                }
            }
            r.replyLate(() -> affectedMessages(runtime));
        });
        transport.on(TLRPC.TL_messages_setTyping.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_setTyping req = (TLRPC.TL_messages_setTyping) r.request;
            if (!(req.action instanceof TLRPC.TL_sendMessageCancelAction)) {
                long peer = peerUserId(req.peer, runtime);
                long chatId = runtime.chatForPeer(peer);
                if (chatId != 0 && peer != runtime.selfId()) {
                    runtime.api.setTyping(chatId);
                }
            }
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_messages_sendReaction.class, FoxMesTransport.Lane.SERIAL, r -> sendReaction(r, runtime));
        transport.on(TLRPC.TL_messages_saveDraft.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_saveDraft req = (TLRPC.TL_messages_saveDraft) r.request;
            long chatId = runtime.chatForPeer(peerUserId(req.peer, runtime));
            if (chatId != 0) {
                Integer replyTo = req.reply_to instanceof TLRPC.TL_inputReplyToMessage ? ((TLRPC.TL_inputReplyToMessage) req.reply_to).reply_to_msg_id : null;
                JsonObject answer = runtime.api.saveDraft(chatId, req.message != null ? req.message : "", replyTo, runtime.store().draftRevision(chatId), 0);
                Long revision = FoxMesJson.optLong(answer, "revision");
                if (revision == null) {
                    revision = FoxMesJson.optLong(FoxMesJson.object(answer, "draft"), "revision");
                }
                if (revision != null) {
                    runtime.store().putDraftRevision(chatId, revision);
                }
            }
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_messages_toggleDialogPin.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_toggleDialogPin req = (TLRPC.TL_messages_toggleDialogPin) r.request;
            long chatId = runtime.chatForPeer(peerUserId(req.peer, runtime));
            if (chatId != 0) {
                runtime.api.setChatPinned(chatId, req.pinned);
                runtime.invalidateSnapshot();
            }
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_messages_reorderPinnedDialogs.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_reorderPinnedDialogs req = (TLRPC.TL_messages_reorderPinnedDialogs) r.request;
            ArrayList<Long> ids = new ArrayList<>();
            for (TLRPC.InputDialogPeer peer : req.order) {
                long chatId = runtime.chatForPeer(peerUserId(peer, runtime));
                if (chatId == 0) {
                    r.reply(boolTrue());
                    return;
                }
                ids.add(chatId);
            }
            runtime.api.savePinnedOrder(ids);
            runtime.invalidateSnapshot();
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_folders_editPeerFolders.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_folders_editPeerFolders req = (TLRPC.TL_folders_editPeerFolders) r.request;
            TL_update.TL_updateFolderPeers update = new TL_update.TL_updateFolderPeers();
            for (TLRPC.TL_inputFolderPeer folderPeer : req.folder_peers) {
                long peer = peerUserId(folderPeer.peer, runtime);
                long chatId = runtime.chatForPeer(peer);
                if (chatId == 0) {
                    continue;
                }
                runtime.api.setChatArchived(chatId, folderPeer.folder_id == 1);
                TLRPC.TL_folderPeer applied = new TLRPC.TL_folderPeer();
                applied.peer = FoxMesTL.peer(peer);
                applied.folder_id = folderPeer.folder_id;
                update.folder_peers.add(applied);
            }
            runtime.invalidateSnapshot();
            ArrayList<TLRPC.User> users = new ArrayList<>();
            r.replyLate(() -> runtime.updates.container(FoxMesHandlers.single(update), users));
        });
        transport.on(TLRPC.TL_messages_markDialogUnread.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_markDialogUnread req = (TLRPC.TL_messages_markDialogUnread) r.request;
            long chatId = runtime.chatForPeer(peerUserId(req.peer, runtime));
            if (chatId != 0) {
                runtime.api.setChatUnreadMark(chatId, req.unread);
            }
            r.reply(boolTrue());
        });
        transport.on(TL_account.updateNotifySettings.class, FoxMesTransport.Lane.SERIAL, r -> updateNotifySettings(r, runtime));
        transport.on(TLRPC.TL_messages_updatePinnedMessage.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_updatePinnedMessage req = (TLRPC.TL_messages_updatePinnedMessage) r.request;
            long peer = peerUserId(req.peer, runtime);
            long chatId = runtime.chatForPeer(peer);
            if (chatId == 0) {
                r.fail(400, "PEER_ID_INVALID");
                return;
            }
            if (req.unpin) {
                runtime.api.unpinMessage(chatId, req.id);
            } else {
                runtime.api.pinMessage(chatId, req.id, !req.pm_oneside);
            }
            TL_update.TL_updatePinnedMessages update = new TL_update.TL_updatePinnedMessages();
            update.pinned = !req.unpin;
            update.peer = FoxMesTL.peer(peer);
            update.messages.add(req.id);
            ArrayList<TLRPC.User> users = runtime.tlUsers(Collections.singletonList(peer));
            r.replyLate(() -> runtime.updates.container(FoxMesHandlers.single(update), users));
        });
        transport.on(TLRPC.TL_messages_unpinAllMessages.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_unpinAllMessages req = (TLRPC.TL_messages_unpinAllMessages) r.request;
            long chatId = runtime.chatForPeer(peerUserId(req.peer, runtime));
            if (chatId != 0) {
                runtime.api.unpinAllMessages(chatId);
            }
            r.replyLate(() -> affectedHistory(runtime));
        });
        transport.on(TLRPC.TL_messages_getSavedGifs.class, FoxMesTransport.Lane.IO, r -> r.reply(savedGifs(runtime)));
        transport.on(TLRPC.TL_messages_saveGif.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_saveGif req = (TLRPC.TL_messages_saveGif) r.request;
            if (req.unsave) {
                long id = req.id.id;
                if ((id & FoxMesTL.GIF_BIT) != 0) {
                    runtime.api.deleteGif(id & ~FoxMesTL.GIF_BIT);
                }
            } else {
                FoxMesFileRef ref = FoxMesFileRef.decode(req.id.file_reference);
                if (ref != null && ref.sha256 != null && !ref.sha256.isEmpty()) {
                    long chatId = ref.chatId != 0 ? ref.chatId : runtime.ensureChatId(runtime.selfId());
                    runtime.api.saveGif(chatId, ref.sha256);
                }
            }
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_messages_deletePhoneCallHistory.class, FoxMesTransport.Lane.SERIAL, r -> {
            runtime.api.requestString("DELETE", "/calls/history", null, null);
            r.replyLate(() -> {
                TLRPC.TL_messages_affectedFoundMessages result = new TLRPC.TL_messages_affectedFoundMessages();
                result.pts = storagePts(runtime);
                result.pts_count = 0;
                result.offset = 0;
                return result;
            });
        });
        transport.on(TLRPC.TL_messages_getScheduledHistory.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getScheduledHistory req = (TLRPC.TL_messages_getScheduledHistory) r.request;
            long peer = peerUserId(req.peer, runtime);
            long chatId = runtime.chatForPeer(peer);
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            if (chatId != 0) {
                ReminderList list = runtime.api.reminders(chatId);
                for (Reminder reminder : list.items()) {
                    runtime.store().putLong("rem_rev_" + reminder.id, reminder.revision);
                    result.messages.add(FoxMesTL.scheduledMessage(reminder, runtime));
                }
            }
            result.users.addAll(runtime.tlUsers(Collections.singletonList(peer)));
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_sendScheduledMessages.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_sendScheduledMessages req = (TLRPC.TL_messages_sendScheduledMessages) r.request;
            long peer = peerUserId(req.peer, runtime);
            for (Integer id : req.id) {
                runtime.api.sendReminderNow(id);
            }
            TL_update.TL_updateDeleteScheduledMessages update = new TL_update.TL_updateDeleteScheduledMessages();
            update.peer = FoxMesTL.peer(peer);
            update.messages.addAll(req.id);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            updates.updates.add(update);
            r.reply(updates);
        });
        transport.on(TLRPC.TL_messages_deleteScheduledMessages.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_deleteScheduledMessages req = (TLRPC.TL_messages_deleteScheduledMessages) r.request;
            long peer = peerUserId(req.peer, runtime);
            for (Integer id : req.id) {
                runtime.api.deleteReminder(id);
            }
            TL_update.TL_updateDeleteScheduledMessages update = new TL_update.TL_updateDeleteScheduledMessages();
            update.peer = FoxMesTL.peer(peer);
            update.messages.addAll(req.id);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            updates.updates.add(update);
            r.reply(updates);
        });
    }


    private static void editMessage(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_editMessage req = (TLRPC.TL_messages_editMessage) r.request;
        long peer = peerUserId(req.peer, runtime);
        if (req.media != null && !(req.media instanceof TLRPC.TL_inputMediaEmpty)) {
            r.fail(400, "MEDIA_EDIT_UNSUPPORTED");
            return;
        }
        String text = req.message != null ? req.message : "";
        ArrayList<FoxMesJson.Body> entities = FoxMesTL.entitiesOut(req.entities, text);
        if (req.schedule_date != 0) {
            long revision = runtime.store().getLong("rem_rev_" + req.id, 0);
            runtime.api.updateReminder(req.id, text, entities, req.schedule_date, revision);
            r.reply(FoxMesHandlers.emptyUpdates());
            return;
        }
        long[] revisions = runtime.store().revisions(req.id);
        int expected = revisions != null ? (int) revisions[0] : 0;
        Message message = runtime.api.editMessage(req.id, text, entities, expected);
        TLRPC.Message tl = runtime.tlMessage(message);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        FoxMesRuntime.collectUserIds(message, ids);
        ids.add(peer);
        ArrayList<TLRPC.User> users = runtime.tlUsers(ids);
        TL_update.TL_updateEditMessage update = new TL_update.TL_updateEditMessage();
        update.message = tl;
        r.replyLate(() -> runtime.updates.container(FoxMesHandlers.single(update), users));
    }


    private static void deleteMessages(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_deleteMessages req = (TLRPC.TL_messages_deleteMessages) r.request;
        LinkedHashMap<Long, List<Integer>> byChat = new LinkedHashMap<>();
        for (Integer id : req.id) {
            if (id == null || id <= 0) {
                continue;
            }
            long chatId = runtime.store().chatOfMessage(id);
            if (chatId == 0) {
                continue;
            }
            List<Integer> list = byChat.get(chatId);
            if (list == null) {
                list = new ArrayList<>();
                byChat.put(chatId, list);
            }
            list.add(id);
        }
        ArrayList<Integer> deleted = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> entry : byChat.entrySet()) {
            List<Integer> ids = entry.getValue();
            if (ids.size() > 1) {
                try {
                    DeleteResult result = runtime.api.batchDeleteMessages(entry.getKey(), ids);
                    if (result != null && result.deletedIds != null) {
                        deleted.addAll(result.deletedIds);
                    } else if (result != null && Boolean.TRUE.equals(result.ok)) {
                        deleted.addAll(ids);
                    }
                    continue;
                } catch (FoxMesHttpException e) {
                    if (e.isTransient()) {
                        throw e;
                    }
                }
            }
            for (Integer id : ids) {
                try {
                    runtime.api.deleteMessage(id);
                    deleted.add(id);
                } catch (FoxMesHttpException e) {
                    if (e.isTransient()) {
                        throw e;
                    }
                    FoxMesLog.w("delete " + id + ": " + e.getMessage());
                }
            }
        }
        runtime.store().removeMessages(deleted);
        r.replyLate(() -> affectedMessages(runtime));
    }

    private static void deleteHistory(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_deleteHistory req = (TLRPC.TL_messages_deleteHistory) r.request;
        long peer = peerUserId(req.peer, runtime);
        long chatId = runtime.chatForPeer(peer);
        if (chatId != 0) {
            if (req.min_date != 0 || req.max_date != 0) {
                runtime.api.deleteMessagesByDate(chatId, req.min_date, req.max_date);
            } else if (req.just_clear || peer == runtime.selfId()) {
                runtime.api.clearHistory(chatId);
                runtime.store().removeMessagesOfChat(chatId);
            } else {
                try {
                    runtime.api.deleteChat(chatId);
                } catch (FoxMesHttpException e) {
                    if (e.status != 404) {
                        throw e;
                    }
                }
                runtime.store().removeMessagesOfChat(chatId);
                runtime.forgetChat(chatId);
            }
            runtime.invalidateSnapshot();
        }
        r.replyLate(() -> affectedHistory(runtime));
    }


    private static void sendReaction(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_sendReaction req = (TLRPC.TL_messages_sendReaction) r.request;
        long peer = peerUserId(req.peer, runtime);
        ReactionCatalog catalog = runtime.ensureCatalog();
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (TLRPC.Reaction reaction : req.reaction) {
            if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                long id = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
                if (runtime.catalogItem(id) != null) {
                    ids.add((int) id);
                }
            } else if (reaction instanceof TLRPC.TL_reactionEmoji) {
                ReactionCatalogItem item = runtime.catalogItemForEmoji(((TLRPC.TL_reactionEmoji) reaction).emoticon);
                if (item != null) {
                    ids.add(item.id);
                }
            }
        }
        ArrayList<Integer> list = new ArrayList<>(ids);
        int max = catalog != null && catalog.maxSelected > 0 ? catalog.maxSelected : list.size();
        while (list.size() > max) {
            list.remove(0);
        }
        long[] revisions = runtime.store().revisions(req.msg_id);
        long expected = revisions != null ? revisions[1] : 0;
        Message message;
        try {
            message = runtime.api.replaceReactions(req.msg_id, list, expected);
        } catch (FoxMesHttpException e) {
            if (e.isTransient()) {
                throw e;
            }
            long chatId = runtime.chatOfMessage(req.msg_id, peer);
            if (chatId == 0) {
                throw e;
            }
            message = runtime.api.message(chatId, req.msg_id);
        }
        runtime.store().setReactionRevision(message.id, message.reactionRevision);
        TL_update.TL_updateMessageReactions update = new TL_update.TL_updateMessageReactions();
        update.peer = FoxMesTL.peer(peer);
        update.msg_id = req.msg_id;
        TLRPC.TL_messageReactions reactions = FoxMesTL.reactions(message.reactions(), runtime);
        update.reactions = reactions != null ? reactions : new TLRPC.TL_messageReactions();
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        FoxMesRuntime.collectUserIds(message, userIds);
        ArrayList<TLRPC.User> users = runtime.tlUsers(userIds);
        r.replyLate(() -> runtime.updates.container(FoxMesHandlers.single(update), users));
    }


    private static void updateNotifySettings(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TL_account.updateNotifySettings req = (TL_account.updateNotifySettings) r.request;
        TLRPC.TL_inputPeerNotifySettings settings = req.settings;
        boolean soundNone = settings != null && settings.sound instanceof TLRPC.TL_notificationSoundNone;
        long muteUntil = settings != null && (settings.flags & TLObject.FLAG_2) != 0 ? settings.mute_until : 0;
        if (req.peer instanceof TLRPC.TL_inputNotifyPeer) {
            long peer = peerUserId(((TLRPC.TL_inputNotifyPeer) req.peer).peer, runtime);
            long chatId = runtime.chatForPeer(peer);
            if (chatId != 0) {
                Chat chat = runtime.chat(chatId);
                NotificationSettings current = chat != null ? chat.notificationSettings : null;
                boolean showPreviews = settings == null || (settings.flags & TLObject.FLAG_0) == 0 || settings.show_previews;
                if (settings != null && (settings.flags & TLObject.FLAG_2) == 0 && current != null) {
                    muteUntil = current.muteUntil;
                }
                NotificationSettings applied = runtime.api.setChatNotificationSettings(chatId, muteUntil, showPreviews, soundNone,
                        current != null ? current.settingsRevision : null);
                if (chat != null && applied != null) {
                    chat.notificationSettings = applied;
                }
            }
        } else if (req.peer instanceof TLRPC.TL_inputNotifyUsers) {
            String operationId = FoxMesJson.operationId();
            runtime.api.updateNotificationDefaults(muteUntil, soundNone, operationId);
        }
        r.reply(boolTrue());
    }


    static TLRPC.TL_messages_savedGifs savedGifs(FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_savedGifs result = new TLRPC.TL_messages_savedGifs();
        Long before = null;
        int total = 0;
        while (total < 1000) {
            GifList page = runtime.api.gifs(100, before);
            for (Gif gif : page.items()) {
                result.gifs.add(gifDocument(gif));
                before = gif.id;
                total++;
            }
            if (!page.hasMore || page.items().isEmpty()) {
                break;
            }
        }
        result.hash = 0;
        return result;
    }

    static TLRPC.Document gifDocument(Gif gif) {
        Attachment item = new Attachment();
        item.id = gif.id;
        item.url = gif.url;
        item.posterUrl = gif.posterUrl;
        item.name = gif.name != null ? gif.name : "animation.mp4";
        item.mime = gif.mime != null ? gif.mime : "video/mp4";
        item.size = gif.size;
        item.width = gif.width;
        item.height = gif.height;
        item.sha256 = gif.sha256;
        item.kind = "animation";
        String url = FoxMesHttp.absolute(gif.url);
        return FoxMesTL.normalizeDocument(FoxMesTL.document(item, url, 0, now(), gif.id | FoxMesTL.GIF_BIT, FoxMesFileRef.FLAG_GIF));
    }
}
