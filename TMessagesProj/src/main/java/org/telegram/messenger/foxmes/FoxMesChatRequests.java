package org.telegram.messenger.foxmes;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.ChatRequestResult;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FoxMesChatRequests {

    public enum Kind { ACCEPTED, NONE, OUTGOING, INCOMING, DECLINED }

    public static final class State {
        public final Kind kind;
        public final long retryAt;

        State(Kind kind, long retryAt) {
            this.kind = kind;
            this.retryAt = retryAt;
        }

        public boolean gated() {
            return kind != Kind.ACCEPTED;
        }

        public boolean canRequest() {
            return kind == Kind.NONE || kind == Kind.DECLINED && System.currentTimeMillis() / 1000 >= retryAt;
        }
    }

    private static final State ACCEPTED = new State(Kind.ACCEPTED, 0);
    private static final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private FoxMesChatRequests() {
    }

    public static State state(FoxMesRuntime runtime, long userId) {
        if (userId <= 0 || userId == runtime.selfId()) {
            return ACCEPTED;
        }
        long chatId = runtime.chatForPeer(userId);
        if (chatId == 0) {
            return new State(Kind.NONE, 0);
        }
        Chat chat = runtime.chat(chatId);
        if (chat == null || chat.request == null) {
            return ACCEPTED;
        }
        if ("declined".equals(chat.request.status)) {
            return new State(Kind.DECLINED, FoxMesJson.unixTime(chat.request.retryAt));
        }
        return new State(chat.request.recipientId == runtime.selfId() ? Kind.INCOMING : Kind.OUTGOING, 0);
    }

    public static State state(int account, TLRPC.User user) {
        if (!FoxMesFeatureGate.enabled || user == null || user.bot || user.self || UserObject.isService(user.id) || UserObject.isReplyUser(user)) {
            return ACCEPTED;
        }
        return state(FoxMesRuntime.getInstance(account), user.id);
    }

    public static boolean gated(int account, TLRPC.User user) {
        return state(account, user).gated();
    }

    public static TLRPC.TL_peerSettings peerSettings(FoxMesRuntime runtime, long userId, User source) {
        TLRPC.TL_peerSettings settings = new TLRPC.TL_peerSettings();
        State state = state(runtime, userId);
        if (!state.gated()) {
            return settings;
        }
        if (state.kind == Kind.INCOMING) {
            settings.add_contact = true;
            settings.block_contact = true;
            settings.flags |= TLObject.FLAG_1 | TLObject.FLAG_2;
        }
        User user = source != null ? source : runtime.knownUser(userId);
        String month = registrationMonth(user != null ? user.registeredAt : null);
        if (month != null) {
            settings.registration_month = month;
            settings.flags |= TLObject.FLAG_15;
        }
        return settings;
    }

    private static String registrationMonth(String iso) {
        int time = iso != null ? FoxMesJson.unixTime(iso) : 0;
        if (time <= 0) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time * 1000L);
        return String.format(java.util.Locale.US, "%02d.%04d", calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.YEAR));
    }

    static void apply(FoxMesRuntime runtime, ChatRequestResult result) throws java.io.IOException {
        if (result == null || result.userId <= 0) {
            return;
        }
        if (result.hidden) {
            FoxMesChatRequestNotification.hide(runtime.account, result.userId);
            long chatId = runtime.chatForPeer(result.userId);
            if (chatId != 0) {
                runtime.forgetChat(chatId);
                runtime.invalidateSnapshot();
                final long dialogId = result.userId;
                AndroidUtilities.runOnUIThread(() -> AccountInstance.getInstance(runtime.account).getMessagesController().deleteDialog(dialogId, 0));
            }
        } else {
            Chat chat = runtime.chat(result.chatId);
            if (chat == null) {
                chat = runtime.api.chat(result.chatId);
                runtime.registerChat(chat);
                runtime.invalidateSnapshot();
            }
            chat.request = result.request;
            syncNotification(runtime, result.userId, true);
        }
        publish(runtime, result.userId);
    }

    // The notification lives while the request is incoming and the chat is
    // still marked unread: the server clears the mark when the recipient opens
    // the chat on any device. Only the chat.request event shows it; chat
    // updates only take it down.
    static void syncNotification(FoxMesRuntime runtime, long userId, boolean show) {
        long chatId = runtime.chatForPeer(userId);
        Chat chat = chatId != 0 ? runtime.chat(chatId) : null;
        if (chat != null && chat.markedUnread && state(runtime, userId).kind == Kind.INCOMING) {
            if (show) {
                FoxMesChatRequestNotification.show(runtime, userId);
            }
        } else {
            FoxMesChatRequestNotification.hide(runtime.account, userId);
        }
    }

    static void publish(FoxMesRuntime runtime, long userId) {
        TL_update.TL_updatePeerSettings update = new TL_update.TL_updatePeerSettings();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        update.peer = peer;
        update.settings = peerSettings(runtime, userId, null);
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        updates.add(update);
        // getPeerSettings prefers UserFull.settings, and ChatActivity counts its
        // card row from it: change it in place and announce it, so the list is
        // recounted through updateRowsSafe. A reloaded UserFull would drop the
        // row silently and the next inserted message would crash the list.
        final TLRPC.PeerSettings settings = update.settings;
        runtime.updates.dispatch(updates, runtime.tlUsers(Collections.singletonList(userId)), () -> AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = AccountInstance.getInstance(runtime.account).getMessagesController();
            TLRPC.UserFull full = controller.getUserFull(userId);
            if (full != null) {
                full.settings = settings;
            }
            NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.peerSettingsDidLoad, userId);
            // DialogCell shows FoxMesUi.chatRequestListPreview for a request chat without messages.
            NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }));
    }

    public static void request(int account, TLRPC.User user, Utilities.Callback<Boolean> done) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        long userId = user.id;
        if (!state(runtime, userId).canRequest() || !inFlight.add(account + ":" + userId)) {
            return;
        }
        runtime.io.execute(() -> {
            boolean ok = false;
            try {
                apply(runtime, runtime.api.requestChat(userId));
                ok = true;
            } catch (Exception e) {
                FoxMesLog.e("chat request failed", e);
            } finally {
                inFlight.remove(account + ":" + userId);
            }
            final boolean result = ok;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public static void answer(int account, TLRPC.User user, boolean accept, Utilities.Callback<Boolean> done) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        long userId = user.id;
        long chatId = runtime.chatForPeer(userId);
        if (chatId == 0 || state(runtime, userId).kind != Kind.INCOMING || !inFlight.add(account + ":" + userId)) {
            return;
        }
        runtime.io.execute(() -> {
            boolean ok = false;
            try {
                apply(runtime, runtime.api.answerChatRequest(chatId, accept));
                ok = true;
            } catch (Exception e) {
                FoxMesLog.e("chat request answer failed", e);
            } finally {
                inFlight.remove(account + ":" + userId);
            }
            final boolean result = ok;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }
}
