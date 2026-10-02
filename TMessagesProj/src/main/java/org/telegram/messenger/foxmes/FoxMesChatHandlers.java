package org.telegram.messenger.foxmes;

import android.text.TextUtils;
import android.util.Patterns;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.foxmes.FoxMesModels.CallHistory;
import org.telegram.messenger.foxmes.FoxMesModels.CallHistoryItem;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.LinkPreview;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.MessagesPage;
import org.telegram.messenger.foxmes.FoxMesModels.PinnedState;
import org.telegram.messenger.foxmes.FoxMesModels.Resolved;
import org.telegram.messenger.foxmes.FoxMesModels.SearchPage;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;

import static org.telegram.messenger.foxmes.FoxMesHandlers.now;
import static org.telegram.messenger.foxmes.FoxMesHandlers.peerUserId;
import static org.telegram.messenger.foxmes.FoxMesHandlers.userId;

final class FoxMesChatHandlers {

    private FoxMesChatHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_getDialogs.class, FoxMesTransport.Lane.SERIAL, r -> getDialogs(r, runtime));
        transport.on(TLRPC.TL_messages_getPinnedDialogs.class, FoxMesTransport.Lane.SERIAL, r -> getPinnedDialogs(r, runtime));
        transport.on(TLRPC.TL_messages_getPeerDialogs.class, FoxMesTransport.Lane.SERIAL, r -> getPeerDialogs(r, runtime));
        transport.on(TLRPC.TL_messages_getHistory.class, FoxMesTransport.Lane.IO, r -> getHistory(r, runtime));
        transport.on(TLRPC.TL_messages_getMessages.class, FoxMesTransport.Lane.IO, r -> getMessages(r, runtime));
        transport.on(TLRPC.TL_messages_search.class, FoxMesTransport.Lane.IO, r -> search(r, runtime));
        transport.on(TLRPC.TL_messages_searchGlobal.class, FoxMesTransport.Lane.IO, r -> searchGlobal(r, runtime));
        transport.on(TLRPC.TL_messages_getSearchCounters.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_messages_getSearchCounters req = (TLRPC.TL_messages_getSearchCounters) r.request;
            Vector<TLRPC.TL_messages_searchCounter> result = new Vector<>(TLRPC.TL_messages_searchCounter::TLdeserialize);
            for (TLRPC.MessagesFilter filter : req.filters) {
                TLRPC.TL_messages_searchCounter counter = new TLRPC.TL_messages_searchCounter();
                counter.filter = filter;
                counter.count = 0;
                counter.inexact = true;
                result.objects.add(counter);
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_users_getFullUser.class, FoxMesTransport.Lane.IO, r -> getFullUser(r, runtime));
        transport.on(TLRPC.TL_users_getUsers.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_users_getUsers req = (TLRPC.TL_users_getUsers) r.request;
            ArrayList<Long> ids = new ArrayList<>();
            for (TLRPC.InputUser user : req.id) {
                long id = userId(user, runtime);
                if (id != 0) {
                    ids.add(id);
                }
            }
            Vector<TLRPC.User> result = new Vector<>(TLRPC.User::TLdeserialize);
            for (TLRPC.User user : runtime.tlUsers(ids)) {
                if (ids.contains(user.id)) {
                    result.objects.add(user);
                }
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_photos_getUserPhotos.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_photos_getUserPhotos req = (TLRPC.TL_photos_getUserPhotos) r.request;
            long id = userId(req.user_id, runtime);
            TLRPC.TL_photos_photos result = new TLRPC.TL_photos_photos();
            User user = knownOrFetched(runtime, id);
            TLRPC.Photo photo = user != null && req.offset == 0 ? FoxMesTL.avatarPhoto(user, now()) : null;
            if (photo != null) {
                result.photos.add(photo);
            }
            result.count = result.photos.size();
            result.users.addAll(runtime.tlUsers(Collections.singletonList(id)));
            r.reply(result);
        });
        transport.on(TLRPC.TL_contacts_search.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_contacts_search req = (TLRPC.TL_contacts_search) r.request;
            TLRPC.TL_contacts_found found = new TLRPC.TL_contacts_found();
            long self = runtime.selfId();
            ArrayList<Long> ids = new ArrayList<>();
            if (!TextUtils.isEmpty(req.q)) {
                for (User user : runtime.api.users(req.q)) {
                    if (user.id == self) {
                        continue;
                    }
                    runtime.rememberUser(user);
                    ids.add(user.id);
                    if (runtime.chatForPeer(user.id) != 0) {
                        found.my_results.add(FoxMesTL.peer(user.id));
                    } else {
                        found.results.add(FoxMesTL.peer(user.id));
                    }
                }
            }
            found.users.addAll(runtime.tlUsers(ids));
            r.reply(found);
        });
        transport.on(TLRPC.TL_contacts_resolveUsername.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_contacts_resolveUsername req = (TLRPC.TL_contacts_resolveUsername) r.request;
            Resolved resolved = runtime.api.resolve(req.username);
            if (resolved == null || resolved.user == null) {
                r.fail(400, "USERNAME_NOT_OCCUPIED");
                return;
            }
            runtime.rememberUser(resolved.user);
            TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
            result.peer = FoxMesTL.peer(resolved.user.id);
            result.users.addAll(runtime.tlUsers(Collections.singletonList(resolved.user.id)));
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getSavedDialogs.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "SAVED_DIALOGS_UNSUPPORTED"));
        transport.on(TLRPC.TL_messages_getPinnedSavedDialogs.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "SAVED_DIALOGS_UNSUPPORTED"));
        transport.on(TLRPC.TL_messages_getAllDrafts.class, FoxMesTransport.Lane.SERIAL, r -> {
            FoxMesRuntime.Snapshot snapshot = runtime.snapshot(false);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            for (Chat chat : snapshot.chats) {
                if (!FoxMesTL.hasDraft(chat.draft)) {
                    continue;
                }
                long peer = runtime.peerOf(chat);
                if (peer == 0) {
                    continue;
                }
                runtime.store().putDraftRevision(chat.id, chat.draft.revision);
                TL_update.TL_updateDraftMessage update = new TL_update.TL_updateDraftMessage();
                update.peer = FoxMesTL.peer(peer);
                update.draft = FoxMesTL.draft(chat.draft, FoxMesJson.unixTimeOrNow(chat.updatedAt));
                updates.updates.add(update);
            }
            r.reply(updates);
        });
        transport.on(TLRPC.TL_messages_getOutboxReadDate.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_messages_getOutboxReadDate req = (TLRPC.TL_messages_getOutboxReadDate) r.request;
            long peer = peerUserId(req.peer, runtime);
            int date = runtime.outboxReadDate(runtime.chatForPeer(peer), req.msg_id);
            if (date <= 0) {
                r.fail(400, "MESSAGE_NOT_READ_YET");
                return;
            }
            TLRPC.TL_outboxReadDate result = new TLRPC.TL_outboxReadDate();
            result.date = date;
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getMessagesReactions.class, FoxMesTransport.Lane.INLINE, r -> r.reply(FoxMesHandlers.emptyUpdates()));
        transport.on(TL_account.getWebPagePreview.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.getWebPagePreview req = (TL_account.getWebPagePreview) r.request;
            TL_account.webPagePreview result = new TL_account.webPagePreview();
            String url = firstUrl(req.message, req.entities);
            TLRPC.MessageMedia media = null;
            if (url != null) {
                LinkPreview preview = runtime.api.linkPreview(url);
                if (preview != null) {
                    media = FoxMesTL.webPageMedia(preview.webPage, 0, now());
                }
            }
            result.media = media != null ? FoxMesTL.normalizeMedia(media) : new TLRPC.TL_messageMediaEmpty();
            r.reply(result);
        });
    }

    private static User knownOrFetched(FoxMesRuntime runtime, long id) throws IOException {
        if (id == runtime.selfId()) {
            return runtime.me();
        }
        User user = runtime.knownUser(id);
        if (user == null && id != 0) {
            user = runtime.api.user(id);
            runtime.rememberUser(user);
        }
        return user;
    }

    private static String firstUrl(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null) {
            return null;
        }
        if (entities != null) {
            for (TLRPC.MessageEntity entity : entities) {
                if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    return ((TLRPC.TL_messageEntityTextUrl) entity).url;
                }
                if (entity instanceof TLRPC.TL_messageEntityUrl && entity.offset >= 0 && entity.offset + entity.length <= text.length()) {
                    return text.substring(entity.offset, entity.offset + entity.length);
                }
            }
        }
        Matcher matcher = Patterns.WEB_URL.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }


    private static void getDialogs(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_getDialogs req = (TLRPC.TL_messages_getDialogs) r.request;
        int folder = (req.flags & 2) != 0 ? req.folder_id : 0;
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        if (req.offset_id != 0 || req.offset_date != 0) {
            r.reply(result);
            return;
        }
        FoxMesRuntime.Snapshot snapshot = runtime.snapshot(false);
        ArrayList<Chat> selected = new ArrayList<>();
        for (Chat chat : snapshot.chats) {
            if ((chat.archived ? 1 : 0) != folder) {
                continue;
            }
            if (req.exclude_pinned && chat.pinned) {
                continue;
            }
            selected.add(chat);
        }
        fillDialogs(runtime, selected, result.dialogs, result.messages, result.users);
        r.reply(result);
    }

    private static void getPinnedDialogs(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_getPinnedDialogs req = (TLRPC.TL_messages_getPinnedDialogs) r.request;
        FoxMesRuntime.Snapshot snapshot = runtime.snapshot(false);
        ArrayList<Chat> pinned = new ArrayList<>();
        for (Chat chat : snapshot.chats) {
            if (chat.pinned && (chat.archived ? 1 : 0) == req.folder_id) {
                pinned.add(chat);
            }
        }
        Collections.sort(pinned, (a, b) -> Long.compare(a.pinnedRank, b.pinnedRank));
        TLRPC.TL_messages_peerDialogs result = new TLRPC.TL_messages_peerDialogs();
        fillDialogs(runtime, pinned, result.dialogs, result.messages, result.users);
        if (req.folder_id == 0) {
            TLRPC.TL_dialogFolder archive = archiveFolder(runtime, snapshot.chats, result.messages, result.users);
            if (archive != null) {
                result.dialogs.add(0, archive);
            }
        }
        r.replyLate(() -> {
            result.state = runtime.updates.state();
            return result;
        });
    }

    private static TLRPC.TL_dialogFolder archiveFolder(FoxMesRuntime runtime, List<Chat> chats, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users) {
        Chat top = null;
        int unmutedPeers = 0;
        int mutedPeers = 0;
        int unmutedMessages = 0;
        int mutedMessages = 0;
        long now = System.currentTimeMillis() / 1000;
        for (Chat chat : chats) {
            if (!chat.archived || runtime.peerOf(chat) == 0 || chat.lastMessage == null) {
                continue;
            }
            if (top == null || chat.lastMessage.id > top.lastMessage.id) {
                top = chat;
            }
            int unread = Math.max(0, chat.unreadCount);
            if (unread == 0 && !chat.markedUnread) {
                continue;
            }
            TLRPC.PeerNotifySettings settings = FoxMesTL.notifySettings(chat.notificationSettings);
            if (settings != null && settings.mute_until > now) {
                mutedPeers++;
                mutedMessages += unread;
            } else {
                unmutedPeers++;
                unmutedMessages += unread;
            }
        }
        if (top == null) {
            return null;
        }
        ArrayList<TLRPC.Dialog> ignored = new ArrayList<>();
        fillDialogs(runtime, Collections.singletonList(top), ignored, messages, users);
        if (ignored.isEmpty() || ignored.get(0).top_message == 0) {
            return null;
        }
        TLRPC.TL_dialogFolder folder = new TLRPC.TL_dialogFolder();
        folder.pinned = true;
        folder.folder = new TLRPC.TL_folder();
        folder.folder.id = 1;
        folder.folder.title = LocaleController.getString(R.string.ArchivedChats);
        folder.peer = ignored.get(0).peer;
        folder.top_message = ignored.get(0).top_message;
        folder.unread_muted_peers_count = mutedPeers;
        folder.unread_unmuted_peers_count = unmutedPeers;
        folder.unread_muted_messages_count = mutedMessages;
        folder.unread_unmuted_messages_count = unmutedMessages;
        return folder;
    }

    private static void getPeerDialogs(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_getPeerDialogs req = (TLRPC.TL_messages_getPeerDialogs) r.request;
        ArrayList<Chat> selected = new ArrayList<>();
        for (TLRPC.InputDialogPeer peer : req.peers) {
            long user = peerUserId(peer, runtime);
            long chatId = runtime.chatForPeer(user);
            if (chatId == 0) {
                continue;
            }
            try {
                Chat chat = runtime.api.chat(chatId);
                if (runtime.registerChat(chat) != 0) {
                    selected.add(chat);
                }
            } catch (FoxMesHttpException e) {
                if (e.status != 404 && e.status != 403) {
                    throw e;
                }
            }
        }
        TLRPC.TL_messages_peerDialogs result = new TLRPC.TL_messages_peerDialogs();
        fillDialogs(runtime, selected, result.dialogs, result.messages, result.users);
        r.replyLate(() -> {
            result.state = runtime.updates.state();
            return result;
        });
    }

    static void fillDialogs(FoxMesRuntime runtime, List<Chat> chats, ArrayList<TLRPC.Dialog> dialogs, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users) {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        ArrayList<Message> last = new ArrayList<>();
        for (Chat chat : chats) {
            long peer = runtime.peerOf(chat);
            if (peer == 0) {
                continue;
            }
            userIds.add(peer);
            Message message = chat.lastMessage;
            if (message != null && message.clientNonce != null && runtime.pendingNonces.contains(message.clientNonce)) {
                message = null;
            }
            if (message != null) {
                last.add(message);
                FoxMesRuntime.collectUserIds(message, userIds);
            }
            if (chat.receiptState != null && chat.receiptState.outbox != null) {
                runtime.rememberOutboxRead(chat.id, chat.receiptState.outbox.readThroughId, chat.receiptState.outbox.readAt);
            }
            if (chat.draft != null) {
                runtime.store().putDraftRevision(chat.id, chat.draft.revision);
            }
            dialogs.add(FoxMesTL.dialog(chat, peer, message != null ? message.id : 0));
        }
        messages.addAll(runtime.tlMessages(last));
        users.addAll(runtime.tlUsers(userIds));
    }


    private static void getHistory(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_getHistory req = (TLRPC.TL_messages_getHistory) r.request;
        long peer = peerUserId(req.peer, runtime);
        long chatId = chatFor(runtime, peer);
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        if (chatId == 0) {
            r.reply(result);
            return;
        }
        int limit = Math.max(1, Math.min(100, req.limit));
        ArrayList<Message> items = new ArrayList<>();
        if (req.offset_id <= 0) {
            MessagesPage page = runtime.api.history(chatId, limit, null, null, null);
            items.addAll(page.items());
        } else {
            int newer = Math.max(0, -req.add_offset);
            int older = Math.max(0, limit + req.add_offset);
            if (newer > 0) {
                MessagesPage page = runtime.api.history(chatId, Math.min(100, newer), null, req.offset_id - 1, null);
                items.addAll(page.items());
            }
            if (older > 0) {
                MessagesPage page = runtime.api.history(chatId, Math.min(100, older), req.offset_id, null, null);
                items.addAll(page.items());
            }
        }
        dropPending(runtime, items);
        Collections.sort(items, (a, b) -> Integer.compare(b.id, a.id));
        dedupe(items);
        while (items.size() > limit) {
            items.remove(items.size() - 1);
        }
        fillMessages(runtime, items, result.messages, result.users);
        r.reply(result);
    }

    static long chatFor(FoxMesRuntime runtime, long peer) throws IOException {
        if (peer == 0) {
            return 0;
        }
        long chatId = runtime.chatForPeer(peer);
        if (chatId == 0 && runtime.cachedSnapshot() == null) {
            runtime.snapshot(false);
            chatId = runtime.chatForPeer(peer);
        }
        return chatId;
    }

    private static void dropPending(FoxMesRuntime runtime, List<Message> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Message message = items.get(i);
            if (message.clientNonce != null && runtime.pendingNonces.contains(message.clientNonce)) {
                items.remove(i);
            }
        }
    }

    private static void dedupe(List<Message> sorted) {
        for (int i = sorted.size() - 1; i > 0; i--) {
            if (sorted.get(i).id == sorted.get(i - 1).id) {
                sorted.remove(i);
            }
        }
    }

    static void fillMessages(FoxMesRuntime runtime, List<Message> items, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users) {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (Message message : items) {
            FoxMesRuntime.collectUserIds(message, userIds);
            long peer = runtime.peerForChat(message.chatId);
            if (peer != 0) {
                userIds.add(peer);
            }
        }
        messages.addAll(runtime.tlMessages(items));
        users.addAll(runtime.tlUsers(userIds));
    }

    private static void getMessages(FoxMesRequest r, FoxMesRuntime runtime) {
        TLRPC.TL_messages_getMessages req = (TLRPC.TL_messages_getMessages) r.request;
        ArrayList<Message> items = new ArrayList<>();
        for (Integer id : req.id) {
            long chatId = runtime.store().chatOfMessage(id);
            if (chatId == 0) {
                continue;
            }
            try {
                items.add(runtime.api.message(chatId, id));
            } catch (IOException e) {
                FoxMesLog.w("message " + id + ": " + e.getMessage());
            }
        }
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        fillMessages(runtime, items, result.messages, result.users);
        r.reply(result);
    }


    private static void search(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_search req = (TLRPC.TL_messages_search) r.request;
        int limit = Math.max(1, Math.min(100, req.limit));
        if (req.filter instanceof TLRPC.TL_inputMessagesFilterPhoneCalls) {
            callHistory(r, runtime, limit, req.offset_id);
            return;
        }
        long peer = peerUserId(req.peer, runtime);
        long chatId = chatFor(runtime, peer);
        TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
        if (chatId == 0 && !(req.peer instanceof TLRPC.TL_inputPeerEmpty)) {
            r.reply(slice);
            return;
        }
        Integer before = req.offset_id > 0 ? req.offset_id : null;
        if (req.filter instanceof TLRPC.TL_inputMessagesFilterPinned) {
            PinnedState pinned = runtime.api.pinnedMessages(chatId);
            ArrayList<Message> items = new ArrayList<>();
            ArrayList<Integer> ids = new ArrayList<>(pinned.messageIds());
            Collections.sort(ids, Collections.reverseOrder());
            for (Integer id : ids) {
                if (before != null && id >= before) {
                    continue;
                }
                if (items.size() >= limit) {
                    break;
                }
                try {
                    items.add(runtime.api.message(chatId, id));
                } catch (IOException e) {
                    FoxMesLog.w("pinned " + id + ": " + e.getMessage());
                }
            }
            slice.count = ids.size();
            fillMessages(runtime, items, slice.messages, slice.users);
            r.reply(slice);
            return;
        }
        String kind = mediaKind(req.filter);
        if (kind != null) {
            MessagesPage page = runtime.api.chatMedia(chatId, kind, limit, before, TextUtils.isEmpty(req.q) ? null : req.q);
            slice.count = page.total != null ? page.total : page.items().size();
            fillMessages(runtime, page.items(), slice.messages, slice.users);
            r.reply(slice);
            return;
        }
        if (TextUtils.isEmpty(req.q)) {
            r.reply(slice);
            return;
        }
        SearchPage page = runtime.api.searchPage(req.q, chatId != 0 ? chatId : null, before, limit);
        slice.count = page.total;
        fillMessages(runtime, page.items(), slice.messages, slice.users);
        r.reply(slice);
    }

    private static String mediaKind(TLRPC.MessagesFilter filter) {
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotos) {
            return "photo";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterVideo) {
            return "video";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotoVideo) {
            return "photo_video";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterDocument) {
            return "file";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterMusic) {
            return "music";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterVoice || filter instanceof TLRPC.TL_inputMessagesFilterRoundVoice) {
            return "voice";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterGif) {
            return "gif";
        }
        if (filter instanceof TLRPC.TL_inputMessagesFilterUrl) {
            return "link";
        }
        return null;
    }

    private static void searchGlobal(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_searchGlobal req = (TLRPC.TL_messages_searchGlobal) r.request;
        TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
        if (TextUtils.isEmpty(req.q)) {
            r.reply(slice);
            return;
        }
        int limit = Math.max(1, Math.min(100, req.limit));
        SearchPage page = runtime.api.searchPage(req.q, null, req.offset_id > 0 ? req.offset_id : null, limit);
        slice.count = page.total;
        if (page.hasMore && page.nextBefore > 0) {
            slice.next_rate = page.nextBefore;
            slice.flags |= TLObject.FLAG_0;
        }
        fillMessages(runtime, page.items(), slice.messages, slice.users);
        r.reply(slice);
    }

    private static void callHistory(FoxMesRequest r, FoxMesRuntime runtime, int limit, int offsetId) throws IOException {
        CallHistory history = runtime.api.request("GET", "/calls/history",
                FoxMesApi.q("limit", limit, "offset_id", offsetId > 0 ? offsetId : null), null, CallHistory.class);
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        long self = runtime.selfId();
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (User user : history.users()) {
            runtime.rememberUser(user);
        }
        for (CallHistoryItem item : history.calls()) {
            FoxMesModels.CallInfo info = new FoxMesModels.CallInfo();
            boolean conference = item.conference != null && item.conference;
            info.callId = String.valueOf(conference && item.callId != null ? item.callId : item.id);
            info.video = item.video;
            info.reason = item.reason;
            info.duration = item.duration;
            info.conference = item.conference;
            info.state = item.state;
            info.participants = item.participants;
            TLRPC.MessageAction action = FoxMesTL.callAction(info);
            if (action == null) {
                continue;
            }
            TLRPC.TL_messageService service = new TLRPC.TL_messageService();
            service.id = item.messageId;
            service.out = item.outgoing;
            long author = item.outgoing ? self : item.peerId;
            service.from_id = FoxMesTL.peer(author);
            service.flags |= TLObject.FLAG_8;
            service.peer_id = FoxMesTL.peer(item.peerId);
            service.dialog_id = item.peerId;
            service.date = item.date;
            service.action = action;
            result.messages.add(service);
            userIds.add(item.peerId);
            userIds.addAll(item.participants());
        }
        result.users.addAll(runtime.tlUsers(userIds));
        r.reply(result);
    }


    private static void getFullUser(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_users_getFullUser req = (TLRPC.TL_users_getFullUser) r.request;
        long id = userId(req.id, runtime);
        User user = id == runtime.selfId() ? runtime.me() : runtime.api.user(id);
        runtime.rememberUser(user);
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = id;
        full.settings = new TLRPC.TL_peerSettings();
        long chatId = runtime.chatForPeer(id);
        Chat chat = chatId != 0 ? runtime.chat(chatId) : null;
        full.notify_settings = FoxMesTL.notifySettings(chat != null ? chat.notificationSettings : null);
        full.phone_calls_available = FoxMesFeatureGate.calls && id != runtime.selfId();
        full.video_calls_available = full.phone_calls_available;
        full.can_pin_message = true;
        full.common_chats_count = 0;
        if (chatId != 0) {
            try {
                PinnedState pinned = runtime.api.pinnedMessages(chatId);
                if (pinned.topMessageId > 0) {
                    full.pinned_msg_id = pinned.topMessageId;
                    full.flags |= TLObject.FLAG_6;
                }
            } catch (IOException e) {
                FoxMesLog.w("pinned of " + chatId + ": " + e.getMessage());
            }
        }
        TLRPC.Photo photo = FoxMesTL.avatarPhoto(user, now());
        if (photo != null) {
            full.profile_photo = photo;
            full.flags |= TLObject.FLAG_2;
        }
        TLRPC.TL_users_userFull result = new TLRPC.TL_users_userFull();
        result.full_user = full;
        result.users.addAll(runtime.tlUsers(Collections.singletonList(id)));
        r.reply(result);
    }
}
