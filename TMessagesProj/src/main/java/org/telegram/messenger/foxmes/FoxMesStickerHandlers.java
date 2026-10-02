package org.telegram.messenger.foxmes;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;

import org.telegram.messenger.foxmes.FoxMesModels.Language;
import org.telegram.messenger.foxmes.FoxMesModels.LanguagePack;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalog;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalogItem;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Request;
import okhttp3.Response;

final class FoxMesStickerHandlers {

    private static final long EMOJI_PACK_BASE = 0x0F0E5E77L << 32;
    private static final long STICKER_PACK_BASE = 0x0F0E5E78L << 32;
    private static final String SHORT_NAME_PREFIX = "foxmes_";
    private static final int SET_FORMAT = 6;
    private static final String PREFERENCES = "foxmes_assets";
    private static final String SIDE_KEY_PREFIX = "side_";
    private static final int NATIVE_SIZE_LIMIT = 512;

    private static final class AssetInfo {
        String url;
        String mime = "image/webp";
        long size;
    }

    private static final ConcurrentHashMap<Integer, AssetInfo> assetInfos = new ConcurrentHashMap<>();
    private static Map<Long, Integer> sides;

    private FoxMesStickerHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_getAvailableReactions.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getAvailableReactions req = (TLRPC.TL_messages_getAvailableReactions) r.request;
            ArrayList<ReactionCatalogItem> items = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int hash = SET_FORMAT;
            for (ReactionCatalogItem item : sortedCatalog(runtime.ensureCatalog())) {
                if (TextUtils.isEmpty(item.emoji) || TextUtils.isEmpty(item.assetUrl) || !seen.add(item.emoji)) {
                    continue;
                }
                items.add(item);
                hash = hash * 31 + item.id;
                hash = hash * 31 + item.assetUrl.hashCode();
            }
            if (hash != 0 && req.hash == hash) {
                r.reply(new TLRPC.TL_messages_availableReactionsNotModified());
                return;
            }
            TLRPC.TL_messages_availableReactions result = new TLRPC.TL_messages_availableReactions();
            result.hash = hash;
            probeAll(runtime, items);
            for (ReactionCatalogItem item : items) {
                TLRPC.Document document = catalogDocument(runtime, item, false);
                TLRPC.TL_availableReaction reaction = new TLRPC.TL_availableReaction();
                reaction.reaction = item.emoji;
                reaction.title = item.emoji;
                reaction.static_icon = document;
                reaction.appear_animation = document;
                reaction.select_animation = document;
                reaction.activate_animation = document;
                reaction.effect_animation = document;
                result.reactions.add(reaction);
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getTopReactions.class, FoxMesTransport.Lane.IO, r -> {
            ReactionCatalog catalog = runtime.ensureCatalog();
            List<Integer> ids = new ArrayList<>(catalog.topReactions());
            if (ids.isEmpty()) {
                for (ReactionCatalogItem item : catalog.availableReactions()) {
                    if (ids.size() >= 12) {
                        break;
                    }
                    ids.add(item.id);
                }
            }
            r.reply(reactions(ids));
        });
        transport.on(TLRPC.TL_messages_getRecentReactions.class, FoxMesTransport.Lane.IO, r -> r.reply(reactions(runtime.ensureCatalog().recentReactions())));
        transport.on(TLRPC.TL_messages_getDefaultTagReactions.class, FoxMesTransport.Lane.INLINE, r -> r.reply(reactions(new ArrayList<>())));
        transport.on(TLRPC.TL_messages_getCustomEmojiDocuments.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getCustomEmojiDocuments req = (TLRPC.TL_messages_getCustomEmojiDocuments) r.request;
            runtime.ensureCatalog();
            Vector<TLRPC.Document> result = new Vector<>(TLRPC.Document::TLdeserialize);
            for (Long id : req.document_id) {
                ReactionCatalogItem item = runtime.catalogItem(id);
                if (item != null) {
                    result.objects.add(catalogDocument(runtime, item, false));
                } else {
                    TLRPC.TL_documentEmpty empty = new TLRPC.TL_documentEmpty();
                    empty.id = id;
                    result.objects.add(empty);
                }
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getEmojiStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(allSets(runtime, true)));
        transport.on(TLRPC.TL_messages_getAllStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(allSets(runtime, false)));
        transport.on(TLRPC.TL_messages_getStickerSet.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getStickerSet req = (TLRPC.TL_messages_getStickerSet) r.request;
            TLRPC.TL_messages_stickerSet set = stickerSet(runtime, req.stickerset);
            if (set == null) {
                r.fail(400, "STICKERSET_INVALID");
                return;
            }
            r.reply(set);
        });
        transport.on(TLRPC.TL_messages_getFeaturedStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_featuredStickers()));
        transport.on(TLRPC.TL_messages_getFeaturedEmojiStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_featuredStickers()));
        transport.on(TLRPC.TL_messages_getRecentStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_recentStickers()));
        transport.on(TLRPC.TL_messages_getFavedStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_favedStickers()));

        transport.on(TLRPC.TL_langpack_getLanguages.class, FoxMesTransport.Lane.IO, r -> {
            Vector<TLRPC.TL_langPackLanguage> result = new Vector<>(TLRPC.TL_langPackLanguage::TLdeserialize);
            for (Language language : runtime.api.languages().items()) {
                result.objects.add(language(language));
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_langpack_getLanguage.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_langpack_getLanguage req = (TLRPC.TL_langpack_getLanguage) r.request;
            for (Language language : runtime.api.languages().items()) {
                if (language.id.equalsIgnoreCase(req.lang_code)) {
                    r.reply(language(language));
                    return;
                }
            }
            r.fail(400, "LANG_CODE_NOT_SUPPORTED");
        });
        transport.on(TLRPC.TL_langpack_getLangPack.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_langpack_getLangPack req = (TLRPC.TL_langpack_getLangPack) r.request;
            r.reply(difference(runtime, req.lang_code, 0));
        });
        transport.on(TLRPC.TL_langpack_getDifference.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_langpack_getDifference req = (TLRPC.TL_langpack_getDifference) r.request;
            r.reply(difference(runtime, req.lang_code, req.from_version));
        });
    }

    private static List<ReactionCatalogItem> sortedCatalog(ReactionCatalog catalog) {
        ArrayList<ReactionCatalogItem> items = new ArrayList<>(catalog.availableReactions());
        java.util.Collections.sort(items, (a, b) -> Integer.compare(a.order, b.order));
        return items;
    }

    private static TLRPC.TL_messages_reactions reactions(List<Integer> ids) {
        TLRPC.TL_messages_reactions result = new TLRPC.TL_messages_reactions();
        result.hash = ids.hashCode();
        for (Integer id : ids) {
            result.reactions.add(FoxMesTL.customEmojiReaction(id));
        }
        return result;
    }


    static long fnv1a(String value) {
        long hash = 0x811C9DC5L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash = (hash * 0x01000193L) & 0xFFFFFFFFL;
        }
        return hash;
    }

    private static long packId(String category, boolean emoji) {
        return (emoji ? EMOJI_PACK_BASE : STICKER_PACK_BASE) | fnv1a(category);
    }

    private static String category(ReactionCatalogItem item) {
        return TextUtils.isEmpty(item.category) ? "FoxMes" : item.category;
    }

    private static AssetInfo assetInfo(FoxMesRuntime runtime, ReactionCatalogItem item) throws IOException {
        AssetInfo known = assetInfos.get(item.id);
        if (known != null) {
            return known;
        }
        String url = FoxMesHttp.absolute(item.assetUrl);
        AssetInfo info = new AssetInfo();
        info.url = url;
        try {
            Probe source = probe(runtime, url);
            if (source == null) {
                FoxMesLog.w("asset " + item.id + ": no answer for the source");
                return info;
            }
            info.size = source.size;
            if (source.webm) {
                info.mime = "video/webm";
            } else if (source.webp) {
                String renditionUrl = url + "/-/emoji_webm/";
                Probe rendition = probe(runtime, renditionUrl);
                if (rendition != null && rendition.webm) {
                    info.url = renditionUrl;
                    info.mime = "video/webm";
                    info.size = rendition.size;
                }
            }
            rememberSide(item.id, source.side);
        } catch (IOException e) {
            FoxMesLog.w("asset " + item.id + ": " + e.getMessage());
            throw e;
        } catch (Exception e) {
            FoxMesLog.w("asset " + item.id + ": " + e.getMessage());
            throw new IOException("asset " + item.id, e);
        }
        assetInfos.put(item.id, info);
        return info;
    }

    private static android.content.SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE);
    }

    private static synchronized Map<Long, Integer> sides() {
        if (sides == null) {
            sides = new HashMap<>();
            for (Map.Entry<String, ?> entry : preferences().getAll().entrySet()) {
                if (entry.getKey().startsWith(SIDE_KEY_PREFIX) && entry.getValue() instanceof Integer) {
                    try {
                        sides.put(Long.parseLong(entry.getKey().substring(SIDE_KEY_PREFIX.length())), (Integer) entry.getValue());
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }
        return sides;
    }

    private static synchronized void rememberSide(long id, int side) {
        Integer known = sides().put(id, side);
        if (known == null || known != side) {
            preferences().edit().putInt(SIDE_KEY_PREFIX + id, side).apply();
        }
    }

    private static synchronized int side(long id) {
        Integer side = sides().get(id);
        return side != null ? side : 0;
    }

    static float chatScale(int account, MessageObject message, int drawnSide) {
        TLRPC.Document document = message.getDocument();
        if (document == null || drawnSide <= 0) {
            return 1f;
        }
        FoxMesFileRef ref = FoxMesFileRef.decode(document.file_reference);
        if (ref == null || !ref.has(FoxMesFileRef.FLAG_CATALOG)) {
            return 1f;
        }
        float zoom = MessagesController.getInstance(account).animatedEmojisZoom;
        float stickerScale = message.isAnimatedEmoji() && zoom > 0 ? 1f / zoom : 1f;
        int side = side(document.id & ~FoxMesTL.STICKER_BIT);
        if (side > 0 && side < NATIVE_SIZE_LIMIT) {
            return Math.min(stickerScale, AndroidUtilities.dp(side) / (float) drawnSide);
        }
        return stickerScale;
    }

    private static void probeAll(FoxMesRuntime runtime, List<ReactionCatalogItem> items) throws IOException {
        ArrayList<java.util.concurrent.Callable<AssetInfo>> tasks = new ArrayList<>();
        for (ReactionCatalogItem item : items) {
            if (!assetInfos.containsKey(item.id)) {
                tasks.add(() -> assetInfo(runtime, item));
            }
        }
        if (tasks.isEmpty()) {
            return;
        }
        try {
            for (java.util.concurrent.Future<AssetInfo> future : runtime.downloads.invokeAll(tasks)) {
                future.get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static final class Probe {
        boolean webm;
        boolean webp;
        int side;
        long size;
    }

    private static Probe probe(FoxMesRuntime runtime, String url) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get().header("Range", "bytes=0-29");
        if (FoxMesConfiguration.allowsBearer(url)) {
            String token = runtime.http.token();
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(url);
        try (Response response = FoxMesHttp.clientFor(parsed, true).newCall(builder.build()).execute()) {
            if (response.code() != 200 && response.code() != 206) {
                FoxMesLog.w("asset probe " + url + ": HTTP " + response.code());
                return null;
            }
            byte[] head = new byte[0];
            okhttp3.ResponseBody body = response.body();
            if (body != null) {
                java.io.InputStream in = body.byteStream();
                byte[] buffer = new byte[30];
                int read = 0;
                while (read < buffer.length) {
                    int n = in.read(buffer, read, buffer.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                head = java.util.Arrays.copyOf(buffer, read);
            }
            String type = response.header("Content-Type");
            Probe probe = new Probe();
            probe.webm = type != null && type.toLowerCase(Locale.US).startsWith("video/webm")
                    || head.length >= 4 && (head[0] & 0xff) == 0x1a && (head[1] & 0xff) == 0x45 && (head[2] & 0xff) == 0xdf && (head[3] & 0xff) == 0xa3;
            probe.webp = head.length >= 12 && new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            probe.side = imageSide(head);
            probe.size = totalSize(response);
            return probe;
        }
    }

    private static int imageSide(byte[] head) {
        if (head.length >= 24 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return Math.max(bigEndian(head, 16), bigEndian(head, 20));
        }
        if (head.length < 30 || !new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return 0;
        }
        String chunk = new String(head, 12, 4, StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X":
                return Math.max(littleEndian24(head, 24), littleEndian24(head, 27)) + 1;
            case "VP8 ":
                return Math.max(((head[26] & 0xff) | (head[27] & 0xff) << 8) & 0x3fff,
                        ((head[28] & 0xff) | (head[29] & 0xff) << 8) & 0x3fff);
            case "VP8L": {
                int bits = (head[21] & 0xff) | (head[22] & 0xff) << 8 | (head[23] & 0xff) << 16 | (head[24] & 0xff) << 24;
                return Math.max((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1);
            }
            default:
                return 0;
        }
    }

    private static int bigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
    }

    private static int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8 | (bytes[offset + 2] & 0xff) << 16;
    }

    private static long totalSize(Response response) {
        String range = response.header("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignore) {
                }
            }
        }
        if (response.code() == 200) {
            String length = response.header("Content-Length");
            if (length != null) {
                try {
                    return Long.parseLong(length.trim());
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return 0;
    }

    static TLRPC.Document catalogDocument(FoxMesRuntime runtime, ReactionCatalogItem item, boolean asSticker) throws IOException {
        AssetInfo info = assetInfo(runtime, item);
        String mime = info.mime;
        boolean webm = "video/webm".equals(mime);
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.flags = FoxMesFileRef.FLAG_CATALOG;
        ref.url = info.url;
        ref.mime = mime;
        TLRPC.TL_document document = new TLRPC.TL_document();
        if (!webm) {
            ref.posterUrl = info.url;
            TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize();
            thumb.type = "m";
            thumb.w = 100;
            thumb.h = 100;
            document.thumbs.add(thumb);
            document.flags = TLObject.setFlag(document.flags, TLObject.FLAG_0, true);
        }
        document.id = asSticker ? (item.id | FoxMesTL.STICKER_BIT) : item.id;
        document.access_hash = FoxMesTL.accessHash(document.id);
        document.file_reference = ref.encode();
        document.date = FoxMesHandlers.now();
        document.mime_type = mime;
        document.size = info.size;
        document.dc_id = FoxMesTL.DC;
        TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
        fileName.file_name = (asSticker ? "sticker" : "emoji") + (webm ? ".webm" : ".webp");
        document.attributes.add(fileName);
        TLRPC.TL_inputStickerSetID set = new TLRPC.TL_inputStickerSetID();
        set.id = packId(category(item), !asSticker);
        set.access_hash = FoxMesTL.accessHash(set.id);
        if (asSticker) {
            TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
            sticker.alt = item.emoji != null ? item.emoji : "";
            sticker.stickerset = set;
            document.attributes.add(sticker);
        } else {
            TLRPC.TL_documentAttributeCustomEmoji emoji = new TLRPC.TL_documentAttributeCustomEmoji();
            emoji.alt = item.emoji != null ? item.emoji : "";
            emoji.stickerset = set;
            document.attributes.add(emoji);
        }
        if (webm) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.w = 100;
            video.h = 100;
            video.duration = 3;
            document.attributes.add(video);
        } else {
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = 100;
            size.h = 100;
            document.attributes.add(size);
        }
        return FoxMesTL.normalizeDocument(document);
    }

    private static TLRPC.TL_stickerSet set(String category, List<ReactionCatalogItem> items, boolean emoji) {
        TLRPC.TL_stickerSet set = new TLRPC.TL_stickerSet();
        set.id = packId(category, emoji);
        set.access_hash = FoxMesTL.accessHash(set.id);
        set.title = category;
        set.short_name = SHORT_NAME_PREFIX + (emoji ? "emoji_" : "stickers_") + Long.toHexString(fnv1a(category));
        set.count = items.size();
        int hash = SET_FORMAT;
        for (ReactionCatalogItem item : items) {
            hash = hash * 31 + item.id;
            hash = hash * 31 + (item.assetUrl != null ? item.assetUrl.hashCode() : 0);
        }
        set.hash = hash;
        set.emojis = emoji;
        set.installed = true;
        set.official = true;
        set.installed_date = FoxMesHandlers.now();
        return set;
    }

    private static TLRPC.TL_messages_allStickers allSets(FoxMesRuntime runtime, boolean emoji) throws IOException {
        runtime.ensureCatalog();
        TLRPC.TL_messages_allStickers result = new TLRPC.TL_messages_allStickers();
        for (Map.Entry<String, List<ReactionCatalogItem>> entry : runtime.catalogCategories().entrySet()) {
            result.sets.add(set(entry.getKey(), entry.getValue(), emoji));
        }
        result.hash2 = result.sets.hashCode();
        return result;
    }

    private static TLRPC.TL_messages_stickerSet stickerSet(FoxMesRuntime runtime, TLRPC.InputStickerSet input) throws IOException {
        runtime.ensureCatalog();
        for (Map.Entry<String, List<ReactionCatalogItem>> entry : runtime.catalogCategories().entrySet()) {
            for (int kind = 0; kind < 2; kind++) {
                boolean emoji = kind == 0;
                TLRPC.TL_stickerSet set = set(entry.getKey(), entry.getValue(), emoji);
                boolean matches = input instanceof TLRPC.TL_inputStickerSetID && input.id == set.id
                        || input instanceof TLRPC.TL_inputStickerSetShortName && set.short_name.equalsIgnoreCase(input.short_name);
                if (!matches) {
                    continue;
                }
                TLRPC.TL_messages_stickerSet result = new TLRPC.TL_messages_stickerSet();
                result.set = set;
                probeAll(runtime, entry.getValue());
                for (ReactionCatalogItem item : entry.getValue()) {
                    TLRPC.Document document = catalogDocument(runtime, item, !emoji);
                    result.documents.add(document);
                    TLRPC.TL_stickerPack pack = new TLRPC.TL_stickerPack();
                    pack.emoticon = item.emoji != null ? item.emoji : "";
                    pack.documents.add(document.id);
                    result.packs.add(pack);
                }
                return result;
            }
        }
        return null;
    }


    private static TLRPC.TL_langPackLanguage language(Language source) {
        TLRPC.TL_langPackLanguage language = new TLRPC.TL_langPackLanguage();
        language.name = source.name;
        language.native_name = source.nativeName;
        language.lang_code = source.id;
        language.plural_code = source.id;
        language.official = true;
        language.translations_url = "";
        return language;
    }

    private static TLRPC.TL_langPackDifference difference(FoxMesRuntime runtime, String code, int fromVersion) throws IOException {
        LanguagePack pack = runtime.api.language(code);
        TLRPC.TL_langPackDifference result = new TLRPC.TL_langPackDifference();
        result.lang_code = code;
        result.from_version = fromVersion;
        result.version = pack.language != null ? pack.language.version : 0;
        if (pack.strings != null && (fromVersion == 0 || result.version != fromVersion)) {
            for (Map.Entry<String, String> entry : pack.strings.entrySet()) {
                TLRPC.TL_langPackString string = new TLRPC.TL_langPackString();
                string.key = entry.getKey();
                string.value = entry.getValue();
                result.strings.add(string);
            }
        }
        return result;
    }
}
