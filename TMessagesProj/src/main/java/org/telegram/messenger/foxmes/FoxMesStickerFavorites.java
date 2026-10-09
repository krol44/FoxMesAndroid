package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalogItem;
import org.telegram.messenger.foxmes.FoxMesModels.StickerFavoritesState;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class FoxMesStickerFavorites {
    static final int LIMIT = 100;
    private final FoxMesRuntime runtime;
    private final FoxMesHttp http;
    private final long userId;
    private final String token;
    private final String profile;
    private final String key;
    private StickerFavoritesState state;
    private boolean serveCache;
    private boolean refreshing;
    private long generation;
    private boolean stopped;
    private final Map<Integer, Pending> pending = new HashMap<>();

    private static final class Pending {
        boolean desired;
        boolean sent;
        boolean uncertain;
        String operation;
        final ArrayList<FoxMesRequest> requests = new ArrayList<>();
    }

    FoxMesStickerFavorites(FoxMesRuntime runtime) {
        this.runtime = runtime;
        userId = runtime.selfId();
        token = FoxMesTokenStore.loadToken(userId);
        profile = FoxMesConfiguration.profileKey();
        key = "sticker_favorites_" + userId;
        http = new FoxMesHttp(() -> token);
        try {
            StickerFavoritesState cached = FoxMesJson.parse(runtime.store().get(key), StickerFavoritesState.class);
            if (cached != null && cached.valid()) state = cached;
        } catch (RuntimeException e) {
            FoxMesLog.w("favorite stickers cache: " + e.getMessage());
        }
        applyLimit();
    }

    synchronized boolean active() {
        return !stopped && runtime.isStarted() && userId == runtime.selfId()
                && Objects.equals(token, FoxMesTokenStore.loadToken(userId))
                && profile.equals(FoxMesConfiguration.profileKey());
    }

    private void applyLimit() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!active()) return;
            MessagesController controller = MessagesController.getInstance(runtime.account);
            controller.maxFaveStickersCount = LIMIT;
            controller.stickersFavedLimitDefault = LIMIT;
            controller.stickersFavedLimitPremium = LIMIT;
            controller.getMainSettings().edit().putInt("maxFaveStickersCount", LIMIT)
                    .putInt("stickersFavedLimitDefault", LIMIT).putInt("stickersFavedLimitPremium", LIMIT).apply();
        });
    }

    synchronized void stop() {
        stopped = true;
        generation++;
        for (Pending item : pending.values()) {
            for (FoxMesRequest request : item.requests) request.fail(401, "SESSION_REVOKED");
        }
        pending.clear();
    }

    synchronized boolean receive(StickerFavoritesState incoming) {
        if (!active() || incoming == null || !incoming.valid()
                || state != null && incoming.revision < state.revision) return false;
        state = incoming;
        serveCache = true;
        runtime.store().put(key, FoxMesJson.gson.toJson(incoming));
        return true;
    }

    void catalogChanged() {
        synchronized (this) {
            if (!active() || state == null) return;
            serveCache = true;
        }
        notifyNative();
    }

    private void notifyNative() {
        if (active()) runtime.updates.dispatch(Collections.singletonList(new TL_update.TL_updateFavedStickers()), new ArrayList<>(), null);
    }

    void refresh(boolean reset) {
        final long version;
        synchronized (this) {
            if (!active() || refreshing && !reset) return;
            if (reset) generation++;
            refreshing = true;
            version = generation;
        }
        runtime.io.execute(() -> {
            try {
                StickerFavoritesState incoming = fetch();
                synchronized (this) {
                    if (version != generation || !receive(incoming)) return;
                }
                notifyNative();
            } catch (Exception e) {
                FoxMesLog.w("favorite stickers refresh: " + e.getMessage());
            } finally {
                synchronized (this) { if (version == generation) refreshing = false; }
            }
        });
    }

    private StickerFavoritesState fetch() throws IOException {
        StickerFavoritesState incoming = http.request("GET", "/stickers/favorites", null, null, StickerFavoritesState.class);
        if (incoming == null || !incoming.valid()) throw new IllegalArgumentException("Invalid favorite stickers state");
        return incoming;
    }

    void load(FoxMesRequest request) throws IOException {
        boolean cached;
        long version;
        synchronized (this) { cached = serveCache && state != null; serveCache = false; version = generation; }
        if (!cached) {
            try {
                StickerFavoritesState incoming = fetch();
                synchronized (this) { if (version == generation) receive(incoming); }
            } catch (IOException e) {
                synchronized (this) { if (state == null) throw e; }
            }
        }
        runtime.ensureCatalog();
        StickerFavoritesState snapshot;
        synchronized (this) { snapshot = state; }
        if (!active() || snapshot == null) { request.fail(401, "SESSION_REVOKED"); return; }
        Map<Integer, TLRPC.Document> documents = new HashMap<>();
        for (Integer id : snapshot.emojiIds) {
            ReactionCatalogItem item = runtime.catalogItem(id);
            if (item != null) documents.put(id, FoxMesStickerHandlers.catalogDocument(runtime, item, true));
        }
        request.replyLate(() -> {
            synchronized (this) {
                if (!active()) throw new IllegalStateException("Session ended");
                TLRPC.TL_messages_favedStickers result = new TLRPC.TL_messages_favedStickers();
                Map<String, TLRPC.TL_stickerPack> packs = new HashMap<>();
                boolean missing = false;
                for (Integer id : state.emojiIds) {
                    TLRPC.Document document = documents.get(id);
                    if (document == null) { missing |= runtime.catalogItem(id) != null; continue; }
                    result.stickers.add(document);
                    ReactionCatalogItem item = runtime.catalogItem(id);
                    String emoji = item != null && item.emoji != null ? item.emoji : "";
                    TLRPC.TL_stickerPack pack = packs.get(emoji);
                    if (pack == null) {
                        pack = new TLRPC.TL_stickerPack(); pack.emoticon = emoji;
                        packs.put(emoji, pack); result.packs.add(pack);
                    }
                    pack.documents.add(document.id);
                }
                result.hash = MediaDataController.calcDocumentsHash(result.stickers);
                if (missing) AndroidUtilities.runOnUIThread(this::notifyNative, 500);
                long hash = ((TLRPC.TL_messages_getFavedStickers) request.request).hash;
                return !missing && hash != 0 && hash == result.hash ? new TLRPC.TL_messages_favedStickersNotModified() : result;
            }
        });
    }

    void toggle(FoxMesRequest request) {
        TLRPC.TL_messages_faveSticker input = (TLRPC.TL_messages_faveSticker) request.request;
        long nativeId = input.id != null ? input.id.id : 0;
        long rawId = nativeId & ~FoxMesTL.STICKER_BIT;
        if ((nativeId & FoxMesTL.STICKER_BIT) == 0 || rawId <= 0 || rawId > Integer.MAX_VALUE) {
            request.fail(400, "STICKER_INVALID"); return;
        }
        int id = (int) rawId;
        synchronized (this) {
            if (!active()) { request.fail(401, "SESSION_REVOKED"); return; }
            Pending item = pending.get(id);
            if (item != null) { item.desired = !input.unfave; item.requests.add(request); return; }
            item = new Pending(); item.desired = !input.unfave; item.requests.add(request);
            pending.put(id, item);
        }
        runtime.io.execute(() -> send(id));
    }

    private void send(int id) {
        final Pending item;
        synchronized (this) {
            item = pending.get(id);
            if (item == null || !active()) return;
            if (item.operation == null) { item.operation = FoxMesJson.operationId(); item.sent = item.desired; }
        }
        try {
            StickerFavoritesState incoming = null;
            if (item.uncertain) {
                JsonObject journal = http.request("GET", "/operations/" + item.operation, null, null, JsonObject.class);
                String status = FoxMesJson.optString(journal, "status");
                if ("done".equals(status)) incoming = FoxMesJson.parse(journal.get("result"), StickerFavoritesState.class);
                else if (!"unknown".equals(status)) throw new IllegalArgumentException("Invalid operation state");
            }
            if (incoming == null) {
                incoming = http.request("PUT", "/stickers/favorites/" + id, null,
                        FoxMesJson.body().put("favorited", item.sent).put("operation_id", item.operation), StickerFavoritesState.class);
            }
            if (incoming == null || !incoming.valid()) throw new IllegalArgumentException("Invalid favorite stickers state");
            synchronized (this) {
                if (!active() || pending.get(id) != item) return;
                receive(incoming);
                item.operation = null; item.uncertain = false;
                if (item.desired != item.sent) { runtime.io.execute(() -> send(id)); return; }
                pending.remove(id);
                for (FoxMesRequest request : item.requests) request.reply(new TLRPC.TL_boolTrue());
            }
            notifyNative();
        } catch (Exception e) {
            synchronized (this) {
                if (!active() || pending.get(id) != item) return;
                if (FoxMesHttpException.isTransient(e)) {
                    item.uncertain = true;
                    Utilities.stageQueue.postRunnable(() -> runtime.io.execute(() -> send(id)), 2000);
                    return;
                }
                pending.remove(id);
                serveCache = state != null;
                for (FoxMesRequest request : item.requests) {
                    request.fail(e instanceof FoxMesHttpException ? ((FoxMesHttpException) e).status : 400, "STICKER_FAVORITE_FAILED");
                }
            }
            notifyNative();
            AndroidUtilities.runOnUIThread(() -> {
                if (active()) android.widget.Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext,
                        org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ErrorOccurred), android.widget.Toast.LENGTH_SHORT).show();
            });
            FoxMesLog.w("favorite sticker update: " + e.getMessage());
        }
    }
}
