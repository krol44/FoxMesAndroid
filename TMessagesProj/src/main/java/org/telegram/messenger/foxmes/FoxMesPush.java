package org.telegram.messenger.foxmes;

import android.text.TextUtils;
import android.util.Base64;

import androidx.collection.LongSparseArray;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class FoxMesPush {

    private static final byte VERSION = 1;
    private static final byte SEALED_VERSION = 2;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final byte[] KEY_INFO = "fxl-push-v1".getBytes(StandardCharsets.UTF_8);
    private static final long SYNC_TIMEOUT_MS = 15_000;
    private static final long QUIET_MS = 1_500;

    private FoxMesPush() {
    }

    public static void processRemoteMessage(String data) {
        CountDownLatch init = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            init.countDown();
        });
        try {
            init.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return;
        }

        byte[] envelope = decode(data);
        if (envelope == null) {
            FoxMesLog.w("push: not a FoxMes envelope");
            return;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) {
                continue;
            }
            String token = FoxMesTokenStore.loadToken(config.getClientUserId());
            if (token == null || token.isEmpty()) {
                continue;
            }
            byte[] key = envelope[0] == SEALED_VERSION ? FoxMesSealed.pushKeyV2(token) : key(token);
            if (key == null) {
                continue;
            }
            JSONObject payload = open(key, envelope);
            if (payload == null) {
                continue;
            }
            String event = payload.optString("event");
            FoxMesLog.d("push " + event + " for account " + account);
            ConnectionsManager.getInstance(account);
            if ("call_discarded".equals(event)) {
                FoxMesCalls.handleDiscardPush(FoxMesRuntime.getInstance(account),
                        Utilities.parseLong(payload.optString("callId")),
                        "1".equals(payload.optString("video")),
                        payload.optString("reason"));
                return;
            }
            FoxMesRuntime.getInstance(account).syncForPush(SYNC_TIMEOUT_MS, QUIET_MS);
            if ("chat_message_reaction".equals(event)) {
                showReaction(account, payload);
            } else if ("chat_revoke".equals(event) && payload.optString("scope").endsWith("#reaction")) {
                removeReaction(account, payload);
            }
            return;
        }
        FoxMesLog.w("push: no signed-in account opens it");
    }

    static void processReactionEvent(FoxMesRuntime runtime, com.google.gson.JsonObject data) throws java.io.IOException {
        com.google.gson.JsonObject notice = FoxMesJson.object(data, "reaction_notification");
        if (notice == null) return;
        long recipient = FoxMesJson.getLong(notice, "recipient_id", 0);
        long actor = FoxMesJson.getLong(notice, "actor_id", 0);
        long date = FoxMesJson.getLong(notice, "date", 0);
        long now = System.currentTimeMillis() / 1000;
        if (recipient != runtime.selfId() || actor == 0 || actor == recipient || date < now - (48 * 60 * 60) || date > now + 300) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("actorId", Long.toString(actor));
            payload.put("messageId", data.get("id").getAsString());
            payload.put("text", FoxMesJson.optString(data, "text"));
            com.google.gson.JsonArray added = notice.getAsJsonArray("emoji_ids");
            if (added == null || added.size() == 0) {
                boolean remains = false;
                if (data.has("reactions")) {
                    for (com.google.gson.JsonElement entry : data.getAsJsonArray("reactions")) {
                        if (FoxMesJson.getLong(entry.getAsJsonObject(), "user_id", 0) == actor) remains = true;
                    }
                }
                if (!remains) removeReaction(runtime.account, payload);
                return;
            }
            int emojiId = added.get(0).getAsInt();
            FoxMesModels.ReactionCatalog catalog = runtime.ensureCatalog();
            long revision = FoxMesJson.getLong(data, "reaction_revision", 0);
            android.content.SharedPreferences prefs = MessagesController.getNotificationsSettings(runtime.account);
            String key = "foxmes_reaction_notice_" + FoxMesConfiguration.profileKey() + "_" + recipient + "_" + data.get("id").getAsString();
            synchronized (FoxMesPush.class) {
                if (revision <= prefs.getLong(key, -1)) return;
                prefs.edit().putLong(key, revision).apply();
            }
            for (FoxMesModels.ReactionCatalogItem item : catalog.availableReactions()) {
                if (item.id == emojiId) {
                    payload.put("emoji", item.emoji);
                    showReaction(runtime.account, payload);
                    return;
                }
            }
        } catch (org.json.JSONException e) {
            FoxMesLog.e("reaction event", e);
        }
    }

    private static void showReaction(int account, JSONObject payload) {
        long actorId = Utilities.parseLong(payload.optString("actorId"));
        int messageId = Utilities.parseInt(payload.optString("messageId"));
        String emoji = payload.optString("emoji");
        if (actorId == 0 || messageId == 0 || TextUtils.isEmpty(emoji)) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.User actor = MessagesController.getInstance(account).getUser(actorId);
            String name = actor != null ? UserObject.getUserName(actor) : payload.optString("title");
            TLRPC.Message reacted = MessagesStorage.getInstance(account).getMessage(actorId, messageId);
            String text = reacted != null && !TextUtils.isEmpty(reacted.message) ? reacted.message : payload.optString("text");
            String messageText = TextUtils.isEmpty(text)
                    ? LocaleController.formatString(R.string.PushReactNoText, name, emoji)
                    : LocaleController.formatString(R.string.PushReactText, name, emoji, text);

            TLRPC.TL_message owner = new TLRPC.TL_message();
            owner.id = messageId;
            owner.message = messageText;
            owner.date = ConnectionsManager.getInstance(account).getCurrentTime();
            owner.dialog_id = actorId;
            owner.peer_id = new TLRPC.TL_peerUser();
            owner.peer_id.user_id = actorId;
            owner.flags |= 256;
            owner.from_id = owner.peer_id;
            MessageObject message = new MessageObject(account, owner, messageText, name, null, false, false, false, false);
            message.isReactionPush = true;
            ArrayList<MessageObject> messages = new ArrayList<>();
            messages.add(message);
            NotificationsController.getInstance(account).processNewMessages(messages, true, true, done);
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
    }

    private static void removeReaction(int account, JSONObject payload) {
        int messageId = Utilities.parseInt(payload.optString("messageId"));
        if (messageId == 0) {
            return;
        }
        LongSparseArray<ArrayList<Integer>> reactions = new LongSparseArray<>();
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        reactions.put(0, ids);
        NotificationsController.getInstance(account).removeDeletedMessagesFromNotifications(reactions, true);
        android.util.SparseBooleanArray unread = new android.util.SparseBooleanArray();
        unread.put(messageId, false);
        MessagesController.getInstance(account).checkUnreadReactions(
                Utilities.parseLong(payload.optString("actorId")), 0, unread);
    }

    private static byte[] decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            byte[] envelope = Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (envelope.length < 1 + NONCE_LENGTH + TAG_LENGTH || (envelope[0] != VERSION && envelope[0] != SEALED_VERSION)) {
                return null;
            }
            return envelope;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static byte[] key(String sessionToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(sessionToken.getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(KEY_INFO);
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject open(byte[] key, byte[] envelope) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH * 8, envelope, 1, NONCE_LENGTH));
            cipher.updateAAD(envelope, 0, 1);
            byte[] plaintext = cipher.doFinal(envelope, 1 + NONCE_LENGTH, envelope.length - 1 - NONCE_LENGTH);
            return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
