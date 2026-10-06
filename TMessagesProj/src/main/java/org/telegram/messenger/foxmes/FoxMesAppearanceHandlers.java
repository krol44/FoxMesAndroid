package org.telegram.messenger.foxmes;

import android.content.Context;
import android.widget.Toast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.foxmes.FoxMesModels.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

final class FoxMesAppearanceHandlers {
    private static final long ICON_BASE = 520093696L;
    private static final ConcurrentHashMap<Integer, BackgroundIcons> icons = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ColorCatalog> palettes = new ConcurrentHashMap<>();
    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        if (FoxMesFeatureGate.enabled) {
            // Refresh once per transport instance, retaining the cached list for offline use.
            ApplicationLoader.applicationContext.getSharedPreferences("replyicons_" + runtime.account, Context.MODE_PRIVATE)
                    .edit().remove("replyicons_last_check").apply();
        }
        transport.on(TLRPC.TL_help_getPeerColors.class, FoxMesTransport.Lane.IO, r -> r.reply(palette(runtime, false)));
        transport.on(TLRPC.TL_help_getPeerProfileColors.class, FoxMesTransport.Lane.IO, r -> r.reply(palette(runtime, true)));
        transport.on(TL_account.getDefaultBackgroundEmojis.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_emojiList result = new TLRPC.TL_emojiList();
            BackgroundIcons catalog = icons(runtime);
            result.hash = catalog.hash;
            for (BackgroundIcon icon : FoxMesModels.list(catalog.emojis)) result.document_id.add(icon.id);
            r.reply(result);
        });
        transport.on(TL_account.updateColor.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.updateColor request = (TL_account.updateColor) r.request;
            User previous = runtime.knownUser(runtime.selfId());
            if (request.color != null && !(request.color instanceof TLRPC.TL_peerColor)) {
                if (previous != null) apply(runtime, previous);
                r.fail(400, "COLOR_INVALID");
                return;
            }
            FoxMesJson.Body choice = FoxMesJson.body();
            if (request.color != null) {
                if ((request.color.flags & 1) != 0) choice.put("color_id", request.color.color);
                if ((request.color.flags & 2) != 0) choice.put("background_emoji_id", request.color.background_emoji_id);
            }
            String operation = FoxMesJson.operationId();
            FoxMesJson.Body body = FoxMesJson.body().put("operation_id", operation).put(request.for_profile ? "profile" : "name", choice);
            try {
                User confirmed;
                try { confirmed = runtime.api.http.request("PATCH", "/me/appearance", null, body, User.class); }
                catch (IOException failure) {
                    if (failure instanceof FoxMesHttpException && !((FoxMesHttpException) failure).isTransient()) throw failure;
                    AppearanceOperation journal = runtime.api.http.request("GET", "/operations/" + operation, null, null, AppearanceOperation.class);
                    if ("done".equals(journal.status) && journal.result != null) confirmed = journal.result;
                    else if ("unknown".equals(journal.status)) confirmed = runtime.api.http.request("PATCH", "/me/appearance", null, body, User.class);
                    else throw failure;
                }
                apply(runtime, confirmed);
                r.reply(FoxMesHandlers.boolTrue());
            } catch (IOException failure) {
                try { apply(runtime, runtime.api.me()); }
                catch (IOException ignored) { if (previous != null) apply(runtime, previous); }
                r.fail(500, "APPEARANCE_SAVE_FAILED");
                AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_LONG).show());
            }
        });
    }
    private static void apply(FoxMesRuntime runtime, User source) {
        User current = runtime.knownUser(source.id);
        if (current != null) {
            User merged = FoxMesJson.parse(FoxMesJson.gson.toJson(current), User.class);
            merged.appearance = source.appearance;
            source = merged;
        }
        final long userId = source.id;
        runtime.rememberUser(source);
        ArrayList<TLRPC.User> users = runtime.tlUsers(Collections.singletonList(source.id));
        TL_update.TL_updateUser changed = new TL_update.TL_updateUser();
        changed.user_id = source.id;
        runtime.updates.dispatch(new ArrayList<>(Collections.singletonList(changed)), users, null);
        AndroidUtilities.runOnUIThread(() -> {
            if (userId == runtime.selfId() && !users.isEmpty()) {
                UserConfig config = UserConfig.getInstance(runtime.account);
                config.setCurrentUser(users.get(0));
                config.saveConfig(true);
                NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
            }
        });
    }
    static TLRPC.TL_peerColor color(Integer id, Long icon) {
        TLRPC.TL_peerColor result = new TLRPC.TL_peerColor();
        if (id != null) { result.flags |= 1; result.color = id; }
        if (icon != null && icon != 0) { result.flags |= 2; result.background_emoji_id = icon; }
        return result;
    }
    private static TLRPC.help_PeerColorSet pack(ColorOption option, boolean dark, boolean profile) {
        if (!profile) {
            TLRPC.TL_help_peerColorSet set = new TLRPC.TL_help_peerColorSet();
            set.colors.addAll(FoxMesModels.list(dark ? option.dark : option.light));
            return set;
        }
        TLRPC.TL_help_peerColorProfileSet set = new TLRPC.TL_help_peerColorProfileSet();
        set.palette_colors.addAll(FoxMesModels.list(dark ? option.darkPalette : option.palette));
        set.bg_colors.addAll(FoxMesModels.list(dark ? option.dark : option.light));
        set.story_colors.addAll(set.bg_colors);
        return set;
    }
    private static TLRPC.TL_help_peerColors palette(FoxMesRuntime runtime, boolean profile) throws IOException {
        String scope = profile ? "profile" : "replies";
        String key = runtime.account + ":" + scope;
        ColorCatalog catalog;
        try {
            catalog = runtime.api.http.request("GET", "/peer-colors", Collections.singletonMap("scope", scope), null, ColorCatalog.class);
            palettes.put(key, catalog);
        } catch (IOException failure) {
            catalog = palettes.get(key);
            if (catalog == null) throw failure;
        }
        TLRPC.TL_help_peerColors result = new TLRPC.TL_help_peerColors();
        result.hash = catalog.hash;
        for (ColorOption value : FoxMesModels.list(catalog.colors)) {
            TLRPC.TL_help_peerColorOption option = new TLRPC.TL_help_peerColorOption();
            option.flags = 2 | 4;
            option.color_id = value.id;
            option.colors = pack(value, false, profile);
            option.dark_colors = pack(value, true, profile);
            result.colors.add(option);
        }
        return result;
    }
    private static BackgroundIcons icons(FoxMesRuntime runtime) throws IOException {
        BackgroundIcons catalog = icons.get(runtime.account);
        if (catalog == null) {
            catalog = runtime.api.http.request("GET", "/background-emojis", null, null, BackgroundIcons.class);
            icons.put(runtime.account, catalog);
        }
        return catalog;
    }
    static boolean isIcon(long id) { return id > ICON_BASE && id <= ICON_BASE + 65535; }
    static TLRPC.Document document(FoxMesRuntime runtime, long id) throws IOException {
        for (BackgroundIcon icon : FoxMesModels.list(icons(runtime).emojis)) {
            if (icon.id != id) continue;
            FoxMesFileRef ref = new FoxMesFileRef();
            ref.flags = FoxMesFileRef.FLAG_CATALOG;
            ref.url = FoxMesHttp.absolute(icon.assetUrl);
            ref.mime = "image/webp";
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = icon.id;
            document.access_hash = FoxMesTL.accessHash(icon.id);
            document.file_reference = ref.encode();
            document.mime_type = "image/webp";
            document.size = icon.size;
            document.dc_id = FoxMesTL.DC;
            document.date = FoxMesHandlers.now();
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = size.h = 128;
            document.attributes.add(size);
            TLRPC.TL_documentAttributeCustomEmoji emoji = new TLRPC.TL_documentAttributeCustomEmoji();
            emoji.free = emoji.text_color = true;
            emoji.alt = icon.emoji;
            emoji.stickerset = new TLRPC.TL_inputStickerSetEmpty();
            document.attributes.add(emoji);
            return document;
        }
        TLRPC.TL_documentEmpty empty = new TLRPC.TL_documentEmpty(); empty.id = id; return empty;
    }
    static TLRPC.TL_messages_stickerSet iconSet(FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_stickerSet result = new TLRPC.TL_messages_stickerSet();
        result.set = new TLRPC.TL_stickerSet();
        result.set.id = ICON_BASE;
        result.set.emojis = result.set.text_color = true;
        result.set.title = "Background icons";
        result.set.short_name = "foxmes_background";
        result.set.hash = icons(runtime).hash;
        for (BackgroundIcon icon : FoxMesModels.list(icons(runtime).emojis)) result.documents.add(document(runtime, icon.id));
        result.set.count = result.documents.size();
        return result;
    }
}
