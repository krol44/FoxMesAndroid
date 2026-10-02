package org.telegram.messenger.foxmes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.Draft;
import org.telegram.messenger.foxmes.FoxMesModels.Event;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationDefaults;
import org.telegram.messenger.foxmes.FoxMesModels.Reaction;
import org.telegram.messenger.foxmes.FoxMesModels.Reminder;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class FoxMesEvents {

    private static final FoxMesEvents[] instances = new FoxMesEvents[UserConfig.MAX_ACCOUNT_COUNT];

    public static FoxMesEvents getInstance(int account) {
        synchronized (instances) {
            FoxMesEvents events = instances[account];
            if (events == null) {
                events = new FoxMesEvents(account);
                instances[account] = events;
            }
            return events;
        }
    }

    private final int account;
    private final ArrayDeque<Event> pendingEvents = new ArrayDeque<>();
    private boolean retryScheduled;
    private long retryDelay = 1000;
    private long replayGeneration;

    private FoxMesEvents(int account) {
        this.account = account;
    }

    private FoxMesRuntime runtime() {
        return FoxMesRuntime.getInstance(account);
    }

    public void apply(Event event) {
        FoxMesRuntime runtime = runtime();
        if (!runtime.isStarted()) {
            return;
        }
        if (event.seq <= 0) {
            try {
                handle(runtime, event.type, event.dataObject(), event.seq);
            } catch (Exception e) {
                FoxMesLog.e("event " + event.type, e);
            }
            return;
        }
        pendingEvents.addLast(event);
        drain(runtime);
    }

    void reset() {
        pendingEvents.clear();
        retryScheduled = false;
        retryDelay = 1000;
        replayGeneration++;
    }

    private void drain(FoxMesRuntime runtime) {
        if (retryScheduled) {
            return;
        }
        while (runtime.isStarted() && !pendingEvents.isEmpty()) {
            Event event = pendingEvents.peekFirst();
            try {
                boolean dispatched = handle(runtime, event.type, event.dataObject(), event.seq);
                if (!dispatched && event.seq > 0) {
                    runtime.saveEventSeq(event.seq);
                }
            } catch (Exception e) {
                FoxMesLog.e("event " + event.type, e);
                if (FoxMesHttpException.isTransient(e)) {
                    retryScheduled = true;
                    long generation = replayGeneration;
                    Utilities.stageQueue.postRunnable(() -> runtime.serial.execute(() -> {
                        if (generation != replayGeneration) {
                            return;
                        }
                        retryScheduled = false;
                        drain(runtime);
                    }), retryDelay);
                    retryDelay = Math.min(retryDelay * 2, 30_000);
                    return;
                }
                if (event.seq > 0) {
                    runtime.saveEventSeq(event.seq);
                }
            }
            pendingEvents.removeFirst();
            retryDelay = 1000;
        }
    }

    private boolean handle(FoxMesRuntime runtime, String type, JsonObject data, long seq) throws IOException {
        if (type.startsWith("call.")) {
            FoxMesCalls.handleEvent(runtime, type, data);
            return false;
        }
        if (type.startsWith("conference.")) {
            FoxMesConferences.handleEvent(runtime, type, data);
            return false;
        }
        switch (type) {
            case "message.created":
            case "message.updated":
                return messageChanged(runtime, type.equals("message.created"), data, seq);
            case "message.deleted":
                return messageDeleted(runtime, data, seq);
            case "message.viewed":
                return messageViewed(runtime, data, seq);
            case "message.expired":
                return messageExpired(runtime, data, seq);
            case "reaction.updated":
                return reactionUpdated(runtime, data, seq);
            case "read.updated":
            case "chat.read":
                return readUpdated(runtime, data, seq);
            case "settings.updated":
                return settingsUpdated(runtime, data, seq);
            case "user.created":
            case "user.updated":
                return userUpdated(runtime, data, seq);
            case "gifs.updated":
                dispatch(runtime, Collections.singletonList(new TL_update.TL_updateSavedGifs()), new ArrayList<>(), seq);
                return true;
            case "reminder.created":
            case "reminder.updated":
            case "reminder.deleted":
                return reminderChanged(runtime, type, data, seq);
            case "draft.updated":
            case "draft.deleted":
                return draftChanged(runtime, type.equals("draft.deleted"), data, seq);
            case "chat.deleted":
                return chatDeleted(runtime, data, seq, true);
            case "chat.history_cleared":
                return chatDeleted(runtime, data, seq, false);
            case "chat.updated":
                return chatUpdated(runtime, data, seq);
            case "chat.pinned":
            case "chat.settings.updated":
                return chatsRefreshed(runtime, seq);
            case "chat.typing":
                typing(runtime, data);
                return false;
            case "chat.presence":
                presence(runtime, data);
                return false;
            default:
                return false;
        }
    }

    private void dispatch(FoxMesRuntime runtime, List<TLRPC.Update> updates, ArrayList<TLRPC.User> users, long seq) {
        runtime.updates.dispatch(new ArrayList<>(updates), users, seq > 0 ? () -> runtime.saveEventSeq(seq) : null);
    }

    private static long chatIdOf(JsonObject data) {
        Long chatId = FoxMesJson.optLong(data, "chat_id");
        if (chatId == null) {
            chatId = FoxMesJson.optLong(data, "id");
        }
        return chatId != null ? chatId : 0;
    }

    private static int messageIdOf(JsonObject data) {
        Long id = FoxMesJson.optLong(data, "message_id");
        if (id == null) {
            id = FoxMesJson.optLong(data, "id");
        }
        return id != null ? (int) (long) id : 0;
    }

    private static long ensurePeer(FoxMesRuntime runtime, long chatId) throws IOException {
        long peer = runtime.peerForChat(chatId);
        if (peer != 0 || chatId == 0) {
            return peer;
        }
        Chat chat = runtime.api.chat(chatId);
        if (!FoxMesFeatureGate.supportsChat(chat.type, chat.isSavedChat())) {
            return 0;
        }
        return runtime.registerChat(chat);
    }


    private boolean messageChanged(FoxMesRuntime runtime, boolean created, JsonObject data, long seq) throws IOException {
        JsonObject dto = FoxMesJson.object(data, "message");
        Message message = FoxMesJson.parse(dto != null ? dto : data, Message.class);
        if (message == null || message.id == 0) {
            return false;
        }
        long peer = ensurePeer(runtime, message.chatId);
        if (peer == 0) {
            return false;
        }
        if (created && message.clientNonce != null && runtime.pendingNonces.contains(message.clientNonce)) {
            return false;
        }
        long[] known = runtime.store().revisions(message.id);
        if (known != null && message.revision <= known[0] && created) {
            return false;
        }
        if (!created && known != null && message.revision < known[0]) {
            return false;
        }
        TLRPC.Message tl = runtime.tlMessage(message);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.add(peer);
        FoxMesRuntime.collectUserIds(message, ids);
        ArrayList<TLRPC.User> users = runtime.tlUsers(ids);
        TLRPC.Update update;
        if (created) {
            TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
            newMessage.message = tl;
            update = newMessage;
        } else {
            TL_update.TL_updateEditMessage edit = new TL_update.TL_updateEditMessage();
            edit.message = tl;
            update = edit;
        }
        dispatch(runtime, Collections.singletonList(update), users, seq);
        if (created && message.senderId != runtime.selfId()) {
            final long chatId = message.chatId;
            final int id = message.id;
            runtime.io.execute(() -> {
                try {
                    runtime.api.markDelivered(chatId, Collections.singletonList(id));
                } catch (IOException e) {
                    FoxMesLog.w("delivered: " + e.getMessage());
                }
            });
        }
        return true;
    }

    private boolean messageDeleted(FoxMesRuntime runtime, JsonObject data, long seq) {
        int id = messageIdOf(data);
        if (id == 0) {
            return false;
        }
        runtime.store().removeMessages(Collections.singletonList(id));
        TL_update.TL_updateDeleteMessages update = new TL_update.TL_updateDeleteMessages();
        update.messages.add(id);
        dispatch(runtime, Collections.singletonList(update), new ArrayList<>(), seq);
        return true;
    }

    private boolean messageViewed(FoxMesRuntime runtime, JsonObject data, long seq) {
        int id = messageIdOf(data);
        if (id == 0 || runtime.store().revisions(id) == null) {
            return false;
        }
        if (runtime.openedHere.remove(id)) {
            return false;
        }
        TL_update.TL_updateReadMessagesContents update = new TL_update.TL_updateReadMessagesContents();
        update.messages.add(id);
        JsonObject ephemeral = FoxMesJson.object(data, "ephemeral");
        int openedAt = FoxMesJson.unixTime(FoxMesJson.optString(ephemeral, "opened_at"));
        if (openedAt > 0) {
            update.date = openedAt;
            update.flags |= 1;
        }
        dispatch(runtime, Collections.singletonList(update), new ArrayList<>(), seq);
        return true;
    }

    private boolean messageExpired(FoxMesRuntime runtime, JsonObject data, long seq) throws IOException {
        int id = messageIdOf(data);
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        if (id == 0 || chatId == 0 || runtime.store().revisions(id) == null) {
            return false;
        }
        Message message = runtime.api.message(chatId, id);
        TL_update.TL_updateEditMessage update = new TL_update.TL_updateEditMessage();
        update.message = runtime.tlMessage(message);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        FoxMesRuntime.collectUserIds(message, ids);
        dispatch(runtime, Collections.singletonList(update), runtime.tlUsers(ids), seq);
        return true;
    }

    private boolean reactionUpdated(FoxMesRuntime runtime, JsonObject data, long seq) throws IOException {
        int id = messageIdOf(data);
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        long peer = ensurePeer(runtime, chatId);
        if (id == 0 || peer == 0) {
            return false;
        }
        Long revision = FoxMesJson.optLong(data, "reaction_revision");
        long[] known = runtime.store().revisions(id);
        if (revision != null && known != null && revision < known[1]) {
            return false;
        }
        if (revision != null) {
            runtime.store().setReactionRevision(id, revision);
        }
        ArrayList<Reaction> reactions = new ArrayList<>();
        if (data.has("reactions") && data.get("reactions").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("reactions")) {
                reactions.add(FoxMesJson.parse(element, Reaction.class));
            }
        }
        TL_update.TL_updateMessageReactions update = new TL_update.TL_updateMessageReactions();
        update.peer = FoxMesTL.peer(peer);
        update.msg_id = id;
        TLRPC.TL_messageReactions mapped = FoxMesTL.reactions(reactions, runtime);
        update.reactions = mapped != null ? mapped : new TLRPC.TL_messageReactions();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Reaction reaction : reactions) {
            ids.add(reaction.userId);
        }
        dispatch(runtime, Collections.singletonList(update), runtime.tlUsers(ids), seq);
        return true;
    }

    private boolean readUpdated(FoxMesRuntime runtime, JsonObject data, long seq) throws IOException {
        JsonObject state = FoxMesRuntime.readStateObject(data);
        long chatId = chatIdOf(data);
        long peer = ensurePeer(runtime, chatId);
        if (peer == 0) {
            return false;
        }
        long reader = FoxMesJson.getLong(state, "reader_id", FoxMesJson.getLong(state, "user_id", 0));
        int readThrough = (int) FoxMesJson.getLong(state, "read_through_id", FoxMesJson.getLong(state, "message_id", 0));
        if (readThrough <= 0) {
            return false;
        }
        TLRPC.Update update;
        if (reader == 0 || reader == runtime.selfId()) {
            TL_update.TL_updateReadHistoryInbox inbox = new TL_update.TL_updateReadHistoryInbox();
            inbox.peer = FoxMesTL.peer(peer);
            inbox.max_id = readThrough;
            inbox.still_unread_count = (int) FoxMesJson.getLong(state, "unread_count", 0);
            update = inbox;
        } else {
            runtime.rememberOutboxRead(chatId, readThrough, FoxMesJson.optString(state, "read_at"));
            TL_update.TL_updateReadHistoryOutbox outbox = new TL_update.TL_updateReadHistoryOutbox();
            outbox.peer = FoxMesTL.peer(peer);
            outbox.max_id = readThrough;
            update = outbox;
        }
        dispatch(runtime, Collections.singletonList(update), runtime.tlUsers(Collections.singletonList(peer)), seq);
        return true;
    }

    private long appliedSettingsRevision = -1;

    private boolean settingsUpdated(FoxMesRuntime runtime, JsonObject data, long seq) {
        JsonObject defaultsDto = FoxMesJson.object(data, "notification_defaults");
        if (defaultsDto == null) {
            return false;
        }
        NotificationDefaults defaults = FoxMesJson.parse(defaultsDto, NotificationDefaults.class);
        if (defaults.settingsRevision < appliedSettingsRevision) {
            return false;
        }
        appliedSettingsRevision = defaults.settingsRevision;
        TL_update.TL_updateNotifySettings update = new TL_update.TL_updateNotifySettings();
        update.peer = new TLRPC.TL_notifyUsers();
        update.notify_settings = FoxMesConfigHandlers.defaultsSettings(defaults);
        dispatch(runtime, Collections.singletonList(update), new ArrayList<>(), seq);
        return true;
    }

    private boolean userUpdated(FoxMesRuntime runtime, JsonObject data, long seq) {
        JsonObject dto = FoxMesJson.object(data, "user");
        User user = FoxMesJson.parse(dto != null ? dto : data, User.class);
        if (user == null || user.id == 0) {
            return false;
        }
        runtime.rememberUser(user);
        ArrayList<TLRPC.User> users = runtime.tlUsers(Collections.singletonList(user.id));
        TLRPC.User tl = null;
        for (TLRPC.User candidate : users) {
            if (candidate.id == user.id) {
                tl = candidate;
            }
        }
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        if (tl != null) {
            TL_update.TL_updateUserName name = new TL_update.TL_updateUserName();
            name.user_id = tl.id;
            name.first_name = tl.first_name != null ? tl.first_name : "";
            name.last_name = "";
            if (tl.username != null) {
                TLRPC.TL_username username = new TLRPC.TL_username();
                username.username = tl.username;
                username.active = true;
                name.usernames.add(username);
            }
            updates.add(name);
        }
        TL_update.TL_updateUser changed = new TL_update.TL_updateUser();
        changed.user_id = user.id;
        updates.add(changed);
        dispatch(runtime, updates, users, seq);
        return true;
    }


    private boolean reminderChanged(FoxMesRuntime runtime, String type, JsonObject data, long seq) throws IOException {
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        long peer = ensurePeer(runtime, chatId);
        if (peer == 0) {
            return false;
        }
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        if (type.equals("reminder.deleted")) {
            int id = (int) FoxMesJson.getLong(data, "reminder_id", FoxMesJson.getLong(data, "id", 0));
            if (id != 0) {
                TL_update.TL_updateDeleteScheduledMessages delete = new TL_update.TL_updateDeleteScheduledMessages();
                delete.peer = FoxMesTL.peer(peer);
                delete.messages.add(id);
                updates.add(delete);
            }
        } else {
            for (Reminder reminder : runtime.api.reminders(chatId).items()) {
                runtime.store().putLong("rem_rev_" + reminder.id, reminder.revision);
                TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
                update.message = FoxMesTL.scheduledMessage(reminder, runtime);
                updates.add(update);
            }
        }
        if (updates.isEmpty()) {
            return false;
        }
        dispatch(runtime, updates, runtime.tlUsers(Collections.singletonList(peer)), seq);
        return true;
    }

    private boolean draftChanged(FoxMesRuntime runtime, boolean deleted, JsonObject data, long seq) throws IOException {
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        long peer = ensurePeer(runtime, chatId);
        if (peer == 0) {
            return false;
        }
        JsonObject dto = FoxMesJson.object(data, "draft");
        Draft draft = dto != null ? FoxMesJson.parse(dto, Draft.class) : null;
        Long stored = runtime.store().draftRevision(chatId);
        if (!deleted && draft != null && stored != null && draft.revision == stored) {
            return false;
        }
        if (deleted || draft == null) {
            runtime.store().removeDraftRevision(chatId);
        } else {
            runtime.store().putDraftRevision(chatId, draft.revision);
        }
        TL_update.TL_updateDraftMessage update = new TL_update.TL_updateDraftMessage();
        update.peer = FoxMesTL.peer(peer);
        update.draft = FoxMesTL.draft(deleted ? null : draft, FoxMesHandlers.now());
        dispatch(runtime, Collections.singletonList(update), runtime.tlUsers(Collections.singletonList(peer)), seq);
        return true;
    }


    private boolean chatDeleted(FoxMesRuntime runtime, JsonObject data, long seq, boolean removeChat) {
        long chatId = chatIdOf(data);
        long peer = runtime.peerForChat(chatId);
        if (peer == 0) {
            return false;
        }
        List<Integer> ids = runtime.store().messagesOfChat(chatId);
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 100) {
            TL_update.TL_updateDeleteMessages update = new TL_update.TL_updateDeleteMessages();
            update.messages.addAll(ids.subList(i, Math.min(ids.size(), i + 100)));
            updates.add(update);
        }
        runtime.store().removeMessagesOfChat(chatId);
        runtime.invalidateSnapshot();
        if (removeChat) {
            runtime.forgetChat(chatId);
            final long dialogId = peer;
            AndroidUtilities.runOnUIThread(() -> AccountInstance.getInstance(account).getMessagesController().deleteDialog(dialogId, 0));
        }
        if (updates.isEmpty()) {
            return false;
        }
        dispatch(runtime, updates, new ArrayList<>(), seq);
        return true;
    }

    private boolean chatUpdated(FoxMesRuntime runtime, JsonObject data, long seq) throws IOException {
        long chatId = chatIdOf(data);
        if (chatId == 0) {
            return false;
        }
        Chat previous = runtime.chat(chatId);
        Chat chat = runtime.api.chat(chatId);
        if (!FoxMesFeatureGate.supportsChat(chat.type, chat.isSavedChat())) {
            return false;
        }
        long peer = runtime.registerChat(chat);
        if (peer == 0) {
            return false;
        }
        runtime.invalidateSnapshot();
        ArrayList<TLRPC.Update> updates = diff(previous, chat, peer);
        if (updates.isEmpty()) {
            return false;
        }
        dispatch(runtime, updates, runtime.tlUsers(Collections.singletonList(peer)), seq);
        return true;
    }

    private boolean chatsRefreshed(FoxMesRuntime runtime, long seq) throws IOException {
        ArrayList<Chat> before = new ArrayList<>(runtime.knownChats());
        FoxMesRuntime.Snapshot snapshot = runtime.snapshot(true);
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        LinkedHashSet<Long> peers = new LinkedHashSet<>();
        for (Chat chat : snapshot.chats) {
            Chat previous = null;
            for (Chat candidate : before) {
                if (candidate.id == chat.id) {
                    previous = candidate;
                    break;
                }
            }
            long peer = runtime.peerOf(chat);
            if (peer == 0) {
                continue;
            }
            peers.add(peer);
            for (TLRPC.Update update : diff(previous, chat, peer)) {
                if (!(update instanceof TL_update.TL_updateDialogPinned)) {
                    updates.add(update);
                }
            }
        }
        for (int folder = 0; folder <= 1; folder++) {
            ArrayList<Chat> pinned = new ArrayList<>();
            for (Chat chat : snapshot.chats) {
                if (chat.pinned && (chat.archived ? 1 : 0) == folder) {
                    pinned.add(chat);
                }
            }
            Collections.sort(pinned, (a, b) -> Long.compare(a.pinnedRank, b.pinnedRank));
            TL_update.TL_updatePinnedDialogs order = new TL_update.TL_updatePinnedDialogs();
            order.folder_id = folder;
            if (folder != 0) {
                order.flags |= 2;
            }
            order.flags |= 1;
            for (Chat chat : pinned) {
                TLRPC.TL_dialogPeer dialogPeer = new TLRPC.TL_dialogPeer();
                dialogPeer.peer = FoxMesTL.peer(runtime.peerOf(chat));
                order.order.add(dialogPeer);
            }
            updates.add(order);
        }
        dispatch(runtime, updates, runtime.tlUsers(peers), seq);
        return true;
    }

    private static ArrayList<TLRPC.Update> diff(Chat previous, Chat chat, long peer) {
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        TLRPC.TL_dialogPeer dialogPeer = new TLRPC.TL_dialogPeer();
        dialogPeer.peer = FoxMesTL.peer(peer);
        if (previous == null || previous.markedUnread != chat.markedUnread) {
            TL_update.TL_updateDialogUnreadMark mark = new TL_update.TL_updateDialogUnreadMark();
            mark.peer = dialogPeer;
            mark.unread = chat.markedUnread;
            updates.add(mark);
        }
        long previousMute = previous != null && previous.notificationSettings != null ? previous.notificationSettings.muteUntil : -1;
        long mute = chat.notificationSettings != null ? chat.notificationSettings.muteUntil : 0;
        boolean previousSound = previous != null && previous.notificationSettings != null && previous.notificationSettings.soundNone;
        boolean sound = chat.notificationSettings != null && chat.notificationSettings.soundNone;
        if (previous == null || previousMute != mute || previousSound != sound) {
            TL_update.TL_updateNotifySettings notify = new TL_update.TL_updateNotifySettings();
            TLRPC.TL_notifyPeer notifyPeer = new TLRPC.TL_notifyPeer();
            notifyPeer.peer = FoxMesTL.peer(peer);
            notify.peer = notifyPeer;
            notify.notify_settings = FoxMesTL.notifySettings(chat.notificationSettings);
            updates.add(notify);
        }
        if (previous == null || previous.archived != chat.archived) {
            TL_update.TL_updateFolderPeers folders = new TL_update.TL_updateFolderPeers();
            TLRPC.TL_folderPeer folderPeer = new TLRPC.TL_folderPeer();
            folderPeer.peer = FoxMesTL.peer(peer);
            folderPeer.folder_id = chat.archived ? 1 : 0;
            folders.folder_peers.add(folderPeer);
            updates.add(folders);
        }
        if (previous != null && previous.pinned != chat.pinned) {
            TL_update.TL_updateDialogPinned pinned = new TL_update.TL_updateDialogPinned();
            pinned.peer = dialogPeer;
            pinned.pinned = chat.pinned;
            if (chat.archived) {
                pinned.folder_id = 1;
                pinned.flags |= 2;
            }
            updates.add(pinned);
        }
        if (previous != null && previous.unreadCount != chat.unreadCount && chat.receiptState != null && chat.receiptState.inbox != null
                && chat.receiptState.inbox.readThroughId > 0) {
            TL_update.TL_updateReadHistoryInbox inbox = new TL_update.TL_updateReadHistoryInbox();
            inbox.peer = FoxMesTL.peer(peer);
            inbox.max_id = chat.receiptState.inbox.readThroughId;
            inbox.still_unread_count = chat.unreadCount;
            updates.add(inbox);
        }
        return updates;
    }


    private void typing(FoxMesRuntime runtime, JsonObject data) {
        long user = FoxMesJson.getLong(data, "user_id", 0);
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        if (user == 0 || user == runtime.selfId() || runtime.peerForChat(chatId) == 0) {
            return;
        }
        TL_update.TL_updateUserTyping update = new TL_update.TL_updateUserTyping();
        update.user_id = user;
        if ("cancel".equals(FoxMesJson.optString(data, "action"))) {
            update.action = new TLRPC.TL_sendMessageCancelAction();
        } else {
            update.action = new TLRPC.TL_sendMessageTypingAction();
        }
        runtime.updates.dispatch(Collections.singletonList(update), runtime.tlUsers(Collections.singletonList(user)), null);
    }

    private void presence(FoxMesRuntime runtime, JsonObject data) {
        long user = FoxMesJson.getLong(data, "user_id", 0);
        long chatId = FoxMesJson.getLong(data, "chat_id", 0);
        boolean online = Boolean.TRUE.equals(FoxMesJson.optBoolean(data, "online"));
        long revision = FoxMesJson.getLong(data, "revision", 0);
        if (runtime.applyPresence(user, chatId, online, revision)) {
            runtime.dispatchStatuses(Collections.singletonList(user));
        }
    }
}
