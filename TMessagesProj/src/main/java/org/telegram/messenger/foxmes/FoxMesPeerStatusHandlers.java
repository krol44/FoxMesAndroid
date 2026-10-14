package org.telegram.messenger.foxmes;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public final class FoxMesPeerStatusHandlers {
    private static final ConcurrentHashMap<String, FoxMesModels.StatusAsset> assets = new ConcurrentHashMap<>();

    private static String scope(FoxMesRuntime runtime) {
        return FoxMesConfiguration.baseURL() + ":" + runtime.account + ":" + runtime.selfId() + ":" + FoxMesTL.positiveHash(String.valueOf(runtime.http.token()));
    }

    public static boolean available(int account) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        return FoxMesFeatureGate.enabled && runtime.statusesSupported;
    }

    static void remember(FoxMesRuntime runtime, FoxMesModels.EmojiStatus status) {
        if (status != null && status.asset != null) assets.put(scope(runtime) + ":" + status.asset.documentId, status.asset);
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TL_account.getDefaultEmojiStatuses.class, FoxMesTransport.Lane.IO, r -> r.reply(statuses(runtime)));
        transport.on(TL_account.getRecentEmojiStatuses.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TL_account.TL_emojiStatuses()));
        transport.on(TL_account.getChannelDefaultEmojiStatuses.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TL_account.TL_emojiStatuses()));
        transport.on(TL_account.clearRecentEmojiStatuses.class, FoxMesTransport.Lane.INLINE, r -> r.reply(FoxMesHandlers.boolTrue()));
        transport.on(TL_account.updateEmojiStatus.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.updateEmojiStatus request = (TL_account.updateEmojiStatus) r.request;
            long document = org.telegram.messenger.DialogObject.getEmojiStatusDocumentId(request.emoji_status);
            Integer until = request.emoji_status instanceof TLRPC.TL_emojiStatus && (((TLRPC.TL_emojiStatus) request.emoji_status).flags & 1) != 0 ? request.emoji_status.until : null;
            String owner = scope(runtime);
            try {
                FoxMesModels.EmojiStatus state = set(runtime, document, until);
                apply(runtime, owner, state);
                r.reply(FoxMesHandlers.boolTrue());
            } catch (IOException failure) {
                try { apply(runtime, owner, runtime.api.http.request("GET", "/me/emoji-status", null, null, FoxMesModels.EmojiStatus.class)); }
                catch (IOException ignored) {
                    FoxMesModels.User previous = runtime.knownUser(runtime.selfId());
                    if (previous != null) apply(runtime, owner, previous.emojiStatus);
                }
                r.fail(400, "EMOJI_STATUS_SAVE_FAILED");
            }
        });
    }

    private static FoxMesModels.StatusCatalog catalog(FoxMesRuntime runtime) throws IOException {
        String owner = scope(runtime);
        FoxMesModels.StatusCatalog value = runtime.api.http.request("GET", "/emoji-statuses", null, null, FoxMesModels.StatusCatalog.class);
        if (!owner.equals(scope(runtime))) throw new IOException("SESSION_REVOKED");
        for (FoxMesModels.StatusAsset asset : FoxMesModels.list(value.items)) assets.put(owner + ":" + asset.documentId, asset);
        return value;
    }

    static TL_account.TL_emojiStatuses statuses(FoxMesRuntime runtime) throws IOException {
        FoxMesModels.StatusCatalog catalog = catalog(runtime);
        TL_account.TL_emojiStatuses result = new TL_account.TL_emojiStatuses();
        for (FoxMesModels.StatusAsset asset : FoxMesModels.list(catalog.items)) {
            FoxMesModels.EmojiStatus status = new FoxMesModels.EmojiStatus();
            status.asset = asset;
            result.statuses.add(nativeStatus(status));
        }
        return result;
    }

    public static TLRPC.EmojiStatus nativeStatus(FoxMesModels.EmojiStatus source) {
        if (source == null || source.asset == null || source.until != null && source.until <= FoxMesHandlers.now()) return new TLRPC.TL_emojiStatusEmpty();
        TLRPC.TL_emojiStatus status = new TLRPC.TL_emojiStatus();
        status.document_id = source.asset.documentId;
        if (source.until != null) { status.flags |= 1; status.until = source.until.intValue(); }
        return status;
    }

    static FoxMesModels.EmojiStatus set(FoxMesRuntime runtime, long documentId, Integer until) throws IOException {
        FoxMesModels.EmojiStatus current = runtime.api.http.request("GET", "/me/emoji-status", null, null, FoxMesModels.EmojiStatus.class);
        FoxMesJson.Body body = FoxMesJson.body().put("asset_id", documentId == 0 ? null : documentId).put("until", until).put("expected_revision", current.revision);
        return mutation(runtime, "PUT", "/me/emoji-status", body, FoxMesModels.EmojiStatus.class);
    }

    public static <T> T mutation(FoxMesRuntime runtime, String method, String path, FoxMesJson.Body fields, Class<T> type) throws IOException {
        String owner = scope(runtime);
        String token = runtime.http.token();
        FoxMesHttp http = new FoxMesHttp(() -> token);
        SharedPreferences pending = ApplicationLoader.applicationContext.getSharedPreferences("foxmes_status_operations", Context.MODE_PRIVATE);
        String key = FoxMesTL.positiveHash(owner + method + path) + "";
        String fingerprint = fields.toJson().toString();
        String previous = pending.getString(key + ":fields", "");
        String operation = "";
        if (!previous.isEmpty()) {
            com.google.gson.JsonObject old = FoxMesJson.parse(previous, com.google.gson.JsonObject.class);
            com.google.gson.JsonObject oldIntent = old.deepCopy();
            oldIntent.remove("expected_revision");
            com.google.gson.JsonObject intent = fields.toJson().deepCopy();
            intent.remove("expected_revision");
            if (oldIntent.equals(intent)) {
                operation = pending.getString(key, "");
                if (!operation.isEmpty()) { fields.object.remove("expected_revision"); fields.put("expected_revision", old.get("expected_revision")); fingerprint = old.toString(); }
            }
        }
        if (operation.isEmpty()) operation = FoxMesJson.operationId();
        fields.put("operation_id", operation);
        pending.edit().putString(key, operation).putString(key + ":fields", fingerprint).commit();
        T result;
        try { result = http.request(method, path, null, fields, type); }
        catch (IOException failure) {
            if (!FoxMesHttpException.isTransient(failure)) {
                pending.edit().remove(key).remove(key + ":fields").apply();
                throw failure;
            }
            com.google.gson.JsonObject journal = http.request("GET", "/operations/" + operation, null, null, com.google.gson.JsonObject.class);
            String state = journal.get("status").getAsString();
            if ("done".equals(state)) result = FoxMesJson.gson.fromJson(journal.get("result"), type);
            else if ("unknown".equals(state)) result = http.request(method, path, null, fields, type);
            else throw failure;
        }
        if (!owner.equals(scope(runtime))) throw new IOException("SESSION_REVOKED");
        pending.edit().remove(key).remove(key + ":fields").apply();
        return result;
    }

    private static void apply(FoxMesRuntime runtime, String owner, FoxMesModels.EmojiStatus state) {
        if (!owner.equals(scope(runtime)) || state == null) return;
        FoxMesModels.User previous = runtime.knownUser(runtime.selfId());
        if (previous != null && previous.emojiStatus != null && previous.emojiStatus.revision > state.revision) return;
        if (previous != null) { previous.emojiStatus = state; runtime.rememberUser(previous); }
        remember(runtime, state);
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.equals(scope(runtime))) return;
            TLRPC.User user = MessagesController.getInstance(runtime.account).getUser(runtime.selfId());
            if (user == null) user = UserConfig.getInstance(runtime.account).getCurrentUser();
            if (user == null) return;
            user.emoji_status = nativeStatus(state);
            user.flags |= 1 << 30;
            MessagesController.getInstance(runtime.account).updateEmojiStatusUntilUpdate(user.id, user.emoji_status);
            ArrayList<TLRPC.User> users = new ArrayList<>(Collections.singletonList(user));
            MessagesController.getInstance(runtime.account).putUsers(users, false);
            MessagesStorage.getInstance(runtime.account).putUsersAndChats(users, null, true, true);
            UserConfig.getInstance(runtime.account).setCurrentUser(user);
            UserConfig.getInstance(runtime.account).saveConfig(true);
            NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
        });
    }

    static TLRPC.Document document(FoxMesRuntime runtime, long id) throws IOException {
        runtime.ensureCatalog();
        FoxMesModels.ReactionCatalogItem item = runtime.catalogItem(id);
        return item == null ? null : FoxMesStickerHandlers.catalogDocument(runtime, item, false);
    }
}
