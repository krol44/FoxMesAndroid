package org.telegram.messenger.foxmes;

import android.text.TextUtils;

import org.telegram.messenger.foxmes.FoxMesModels.EmojiDocument;
import org.telegram.messenger.foxmes.FoxMesModels.EmojiGroup;
import org.telegram.messenger.foxmes.FoxMesModels.Language;
import org.telegram.messenger.foxmes.FoxMesModels.LanguagePack;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalogItem;
import org.telegram.messenger.foxmes.FoxMesModels.StickerSet;
import org.telegram.messenger.foxmes.FoxMesModels.StickerSetsCatalog;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FoxMesStickerHandlers {

    private static final int SET_FORMAT = 8;
    private static final int FEATURED_COVERS = 5;

    private FoxMesStickerHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_getAvailableReactions.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getAvailableReactions req = (TLRPC.TL_messages_getAvailableReactions) r.request;
            ArrayList<ReactionCatalogItem> items = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int hash = SET_FORMAT;
            for (ReactionCatalogItem item : sortedCatalog(runtime.ensureCatalog().availableReactions())) {
                if (TextUtils.isEmpty(item.emoji) || TextUtils.isEmpty(item.url) || !seen.add(item.emoji)) {
                    continue;
                }
                items.add(item);
                hash = hash * 31 + item.id;
                hash = hash * 31 + item.url.hashCode();
            }
            if (hash != 0 && req.hash == hash) {
                r.reply(new TLRPC.TL_messages_availableReactionsNotModified());
                return;
            }
            TLRPC.TL_messages_availableReactions result = new TLRPC.TL_messages_availableReactions();
            result.hash = hash;
            for (ReactionCatalogItem item : items) {
                TLRPC.Document document = document(item, false);
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
            FoxMesModels.ReactionCatalog catalog = runtime.ensureCatalog();
            List<Integer> ids = new ArrayList<>(catalog.topReactions());
            if (ids.isEmpty()) {
                for (ReactionCatalogItem item : sortedCatalog(catalog.availableReactions())) {
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
            ArrayList<Integer> wanted = new ArrayList<>();
            for (Long id : req.document_id) {
                if (!FoxMesAppearanceHandlers.isIcon(id) && id > 0 && id <= Integer.MAX_VALUE) {
                    wanted.add(id.intValue());
                }
            }
            Map<Integer, EmojiDocument> documents = runtime.emojiDocuments(wanted);
            Vector<TLRPC.Document> result = new Vector<>(TLRPC.Document::TLdeserialize);
            for (Long id : req.document_id) {
                if (FoxMesAppearanceHandlers.isIcon(id)) {
                    result.objects.add(FoxMesAppearanceHandlers.document(runtime, id));
                    continue;
                }
                EmojiDocument item = id > 0 && id <= Integer.MAX_VALUE ? documents.get(id.intValue()) : null;
                if (item != null) {
                    result.objects.add(document(item, false));
                } else {
                    TLRPC.TL_documentEmpty empty = new TLRPC.TL_documentEmpty();
                    empty.id = id;
                    result.objects.add(empty);
                }
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getEmojiStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(installedSets(runtime, true)));
        transport.on(TLRPC.TL_messages_getAllStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(installedSets(runtime, false)));
        transport.on(TLRPC.TL_messages_getStickerSet.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getStickerSet req = (TLRPC.TL_messages_getStickerSet) r.request;
            if (req.stickerset instanceof TLRPC.TL_inputStickerSetEmojiDefaultStatuses) {
                TLRPC.TL_messages_stickerSet set = new TLRPC.TL_messages_stickerSet();
                set.set = new TLRPC.TL_stickerSet();
                set.set.id = 1L << 49;
                set.set.emojis = true;
                set.set.title = "Emoji statuses";
                set.set.short_name = "foxmes_statuses";
                for (TLRPC.EmojiStatus status : FoxMesPeerStatusHandlers.statuses(runtime).statuses) {
                    TLRPC.Document document = FoxMesPeerStatusHandlers.document(runtime, org.telegram.messenger.DialogObject.getEmojiStatusDocumentId(status));
                    if (document != null) set.documents.add(document);
                }
                set.set.count = set.documents.size();
                r.reply(set);
                return;
            }
            StickerSetsCatalog catalog = runtime.ensureStickerSets();
            StickerSet source = find(catalog, req.stickerset);
            if (source == null) {
                r.fail(400, "STICKERSET_INVALID");
                return;
            }
            r.reply(fullSet(catalog, source));
        });
        transport.on(TLRPC.TL_messages_getFeaturedStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(featured(runtime, false)));
        transport.on(TLRPC.TL_messages_getFeaturedEmojiStickers.class, FoxMesTransport.Lane.IO, r -> r.reply(featured(runtime, true)));
        transport.on(TLRPC.TL_messages_readFeaturedStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_boolTrue()));
        transport.on(TLRPC.TL_messages_getArchivedStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_archivedStickers()));
        transport.on(TLRPC.TL_messages_installStickerSet.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_installStickerSet req = (TLRPC.TL_messages_installStickerSet) r.request;
            StickerSet source = find(runtime.ensureStickerSets(), req.stickerset);
            if (source == null) {
                r.fail(400, "STICKERSET_INVALID");
                return;
            }
            runtime.setStickerSetInstalled(source.id, !req.archived);
            r.reply(new TLRPC.TL_messages_stickerSetInstallResultSuccess());
        });
        transport.on(TLRPC.TL_messages_uninstallStickerSet.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_uninstallStickerSet req = (TLRPC.TL_messages_uninstallStickerSet) r.request;
            StickerSet source = find(runtime.ensureStickerSets(), req.stickerset);
            if (source == null) {
                r.fail(400, "STICKERSET_INVALID");
                return;
            }
            runtime.setStickerSetInstalled(source.id, false);
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TLRPC.TL_messages_toggleStickerSets.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_toggleStickerSets req = (TLRPC.TL_messages_toggleStickerSets) r.request;
            StickerSetsCatalog catalog = runtime.ensureStickerSets();
            for (TLRPC.InputStickerSet input : req.stickersets) {
                StickerSet source = find(catalog, input);
                if (source != null) {
                    runtime.setStickerSetInstalled(source.id, req.unarchive);
                }
            }
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TLRPC.TL_messages_reorderStickerSets.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_reorderStickerSets req = (TLRPC.TL_messages_reorderStickerSets) r.request;
            if (req.masks) {
                r.reply(new TLRPC.TL_boolTrue());
                return;
            }
            runtime.orderStickerSets(req.emojis, req.order);
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TLRPC.TL_messages_getEmojiGroups.class, FoxMesTransport.Lane.IO, r -> r.reply(groups(runtime)));
        transport.on(TLRPC.TL_messages_getEmojiStatusGroups.class, FoxMesTransport.Lane.IO, r -> r.reply(groups(runtime)));
        transport.on(TLRPC.TL_messages_getEmojiProfilePhotoGroups.class, FoxMesTransport.Lane.IO, r -> r.reply(groups(runtime)));
        transport.on(TLRPC.TL_messages_getEmojiStickerGroups.class, FoxMesTransport.Lane.IO, r -> r.reply(groups(runtime)));
        transport.on(TLRPC.TL_messages_getStickers.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getStickers req = (TLRPC.TL_messages_getStickers) r.request;
            r.reply(byEmoji(runtime, req.emoticon));
        });
        transport.on(TLRPC.TL_messages_searchStickers.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_searchStickers req = (TLRPC.TL_messages_searchStickers) r.request;
            r.reply(search(runtime, req.emojis, req.q, req.emoticon));
        });
        transport.on(TLRPC.TL_messages_getRecentStickers.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_recentStickers()));
        transport.on(TLRPC.TL_messages_getFavedStickers.class, FoxMesTransport.Lane.IO, r -> runtime.stickerFavorites().load(r));
        transport.on(TLRPC.TL_messages_faveSticker.class, FoxMesTransport.Lane.INLINE, r -> runtime.stickerFavorites().toggle(r));

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

    private static List<ReactionCatalogItem> sortedCatalog(List<ReactionCatalogItem> source) {
        ArrayList<ReactionCatalogItem> items = new ArrayList<>(source);
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

    static long documentId(EmojiDocument item, boolean asSticker) {
        return asSticker ? (item.id | FoxMesTL.STICKER_BIT) : item.id;
    }

    static TLRPC.Document document(EmojiDocument item, boolean asSticker) {
        boolean webm = item.video();
        boolean lottie = item.lottie();
        String mime = webm ? "video/webm" : lottie ? "application/x-tgsticker" : "image/webp";
        int width = item.width > 0 ? item.width : (asSticker ? 512 : 100);
        int height = item.height > 0 ? item.height : (asSticker ? 512 : 100);
        String url = FoxMesHttp.absolute(item.url);
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.flags = FoxMesFileRef.FLAG_CATALOG;
        ref.url = url;
        ref.mime = mime;
        TLRPC.TL_document document = new TLRPC.TL_document();
        if (!webm && !lottie) {
            ref.posterUrl = url;
            TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize();
            thumb.type = "m";
            thumb.w = width;
            thumb.h = height;
            document.thumbs.add(thumb);
            document.flags = TLObject.setFlag(document.flags, TLObject.FLAG_0, true);
        }
        document.id = documentId(item, asSticker);
        document.access_hash = FoxMesTL.accessHash(document.id);
        document.file_reference = ref.encode();
        document.date = FoxMesHandlers.now();
        document.mime_type = mime;
        document.size = item.size;
        document.dc_id = FoxMesTL.DC;
        TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
        fileName.file_name = (asSticker ? "sticker" : "emoji") + (webm ? ".webm" : lottie ? ".tgs" : ".webp");
        document.attributes.add(fileName);
        TLRPC.InputStickerSet set;
        if (item.setId > 0) {
            TLRPC.TL_inputStickerSetID byId = new TLRPC.TL_inputStickerSetID();
            byId.id = item.setId;
            byId.access_hash = FoxMesTL.accessHash(byId.id);
            set = byId;
        } else {
            set = new TLRPC.TL_inputStickerSetEmpty();
        }
        if (asSticker) {
            TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
            sticker.alt = item.emoji != null ? item.emoji : "";
            sticker.stickerset = set;
            document.attributes.add(sticker);
        } else {
            TLRPC.TL_documentAttributeCustomEmoji emoji = new TLRPC.TL_documentAttributeCustomEmoji();
            emoji.alt = item.emoji != null ? item.emoji : "";
            emoji.stickerset = set;
            emoji.free = true;
            emoji.text_color = item.textColor;
            document.attributes.add(emoji);
        }
        if (webm) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.w = width;
            video.h = height;
            video.duration = 3;
            document.attributes.add(video);
        } else {
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = width;
            size.h = height;
            document.attributes.add(size);
        }
        return FoxMesTL.normalizeDocument(document);
    }

    private static TLRPC.TL_stickerSet set(StickerSetsCatalog catalog, StickerSet source) {
        boolean emoji = source.emoji();
        TLRPC.TL_stickerSet set = new TLRPC.TL_stickerSet();
        set.id = source.id;
        set.access_hash = FoxMesTL.accessHash(set.id);
        set.title = TextUtils.isEmpty(source.title) ? "FoxMes" : source.title;
        set.short_name = source.shortName != null ? source.shortName : "";
        set.count = source.items().size();
        int hash = SET_FORMAT;
        for (EmojiDocument item : source.items()) {
            hash = hash * 31 + item.id;
            hash = hash * 31 + (item.url != null ? item.url.hashCode() : 0);
        }
        set.hash = hash;
        set.emojis = emoji;
        set.official = true;
        set.installed = catalog.installed != null && catalog.installed.of(emoji).contains(source.id);
        if (set.installed) {
            set.installed_date = FoxMesHandlers.now();
            set.flags = TLObject.setFlag(set.flags, TLObject.FLAG_0, true);
        }
        if (!source.items().isEmpty()) {
            set.thumb_document_id = documentId(source.items().get(0), !emoji);
            set.flags = TLObject.setFlag(set.flags, TLObject.FLAG_8, true);
        }
        return set;
    }

    private static TLRPC.TL_messages_stickerSet fullSet(StickerSetsCatalog catalog, StickerSet source) {
        boolean emoji = source.emoji();
        TLRPC.TL_messages_stickerSet result = new TLRPC.TL_messages_stickerSet();
        result.set = set(catalog, source);
        for (EmojiDocument item : source.items()) {
            TLRPC.Document document = document(item, !emoji);
            result.documents.add(document);
            TLRPC.TL_stickerPack pack = new TLRPC.TL_stickerPack();
            pack.emoticon = item.emoji != null ? item.emoji : "";
            pack.documents.add(document.id);
            result.packs.add(pack);
        }
        return result;
    }

    private static StickerSet find(StickerSetsCatalog catalog, TLRPC.InputStickerSet input) {
        for (StickerSet source : catalog.sets()) {
            if (input instanceof TLRPC.TL_inputStickerSetID && input.id == source.id
                    || input instanceof TLRPC.TL_inputStickerSetShortName && source.shortName != null && source.shortName.equalsIgnoreCase(input.short_name)) {
                return source;
            }
        }
        return null;
    }

    private static TLRPC.TL_messages_emojiGroups groups(FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_emojiGroups result = new TLRPC.TL_messages_emojiGroups();
        int hash = SET_FORMAT;
        for (EmojiGroup source : runtime.ensureStickerSets().groups()) {
            if (source.icon == null || source.emoticons == null) {
                continue;
            }
            TLRPC.TL_emojiGroup group = new TLRPC.TL_emojiGroup();
            group.title = source.title != null ? source.title : "";
            group.icon_emoji_id = documentId(source.icon, false);
            group.emoticons.addAll(source.emoticons);
            result.groups.add(group);
            hash = hash * 31 + (int) group.icon_emoji_id;
            hash = hash * 31 + group.emoticons.hashCode();
        }
        result.hash = hash;
        return result;
    }

    private static String bareEmoji(String value) {
        return value == null ? "" : value.replace("\uFE0F", "").trim();
    }

    private static TLRPC.TL_messages_stickers byEmoji(FoxMesRuntime runtime, String emoticon) throws IOException {
        StickerSetsCatalog catalog = runtime.ensureStickerSets();
        String needle = bareEmoji(emoticon);
        ArrayList<StickerSet> order = new ArrayList<>();
        if (catalog.installed != null) {
            for (Integer id : catalog.installed.of(false)) {
                for (StickerSet source : catalog.sets()) {
                    if (source.id == id && !source.emoji()) {
                        order.add(source);
                    }
                }
            }
        }
        for (StickerSet source : catalog.sets()) {
            if (!source.emoji() && !order.contains(source)) {
                order.add(source);
            }
        }
        TLRPC.TL_messages_stickers result = new TLRPC.TL_messages_stickers();
        for (StickerSet source : order) {
            for (EmojiDocument item : source.items()) {
                if (!needle.isEmpty() && needle.equals(bareEmoji(item.emoji))) {
                    result.stickers.add(document(item, true));
                }
            }
        }
        return result;
    }

    private static TLRPC.TL_messages_foundStickers search(FoxMesRuntime runtime, boolean emoji, String query, String emoticon) throws IOException {
        String haystack = bareEmoji((emoticon != null ? emoticon : "") + (query != null ? query : ""));
        String needle = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        TLRPC.TL_messages_foundStickers result = new TLRPC.TL_messages_foundStickers();
        for (StickerSet source : runtime.ensureStickerSets().sets()) {
            if (source.emoji() != emoji) {
                continue;
            }
            boolean byTitle = !needle.isEmpty() && source.title != null && source.title.toLowerCase(Locale.ROOT).contains(needle);
            for (EmojiDocument item : source.items()) {
                String alt = bareEmoji(item.emoji);
                if (byTitle || !alt.isEmpty() && haystack.contains(alt)) {
                    result.stickers.add(document(item, !emoji));
                }
            }
        }
        return result;
    }

    private static TLRPC.TL_messages_allStickers installedSets(FoxMesRuntime runtime, boolean emoji) throws IOException {
        StickerSetsCatalog catalog = runtime.ensureStickerSets();
        TLRPC.TL_messages_allStickers result = new TLRPC.TL_messages_allStickers();
        if (catalog.installed != null) {
            for (Integer id : catalog.installed.of(emoji)) {
                for (StickerSet source : catalog.sets()) {
                    if (source.id == id && source.emoji() == emoji) {
                        result.sets.add(set(catalog, source));
                    }
                }
            }
        }
        long hash = SET_FORMAT;
        for (TLRPC.StickerSet set : result.sets) {
            hash = hash * 31 + set.id;
            hash = hash * 31 + set.hash;
        }
        result.hash2 = hash;
        return result;
    }

    private static TLRPC.TL_messages_featuredStickers featured(FoxMesRuntime runtime, boolean emoji) throws IOException {
        StickerSetsCatalog catalog = runtime.ensureStickerSets();
        TLRPC.TL_messages_featuredStickers result = new TLRPC.TL_messages_featuredStickers();
        long hash = SET_FORMAT;
        for (StickerSet source : catalog.sets()) {
            if (source.emoji() != emoji || catalog.installed != null && catalog.installed.of(emoji).contains(source.id)) {
                continue;
            }
            TLRPC.StickerSetCovered covered;
            if (emoji) {
                TLRPC.TL_stickerSetFullCovered full = new TLRPC.TL_stickerSetFullCovered();
                TLRPC.TL_messages_stickerSet set = fullSet(catalog, source);
                full.set = set.set;
                full.packs = set.packs;
                full.documents = set.documents;
                covered = full;
            } else {
                TLRPC.TL_stickerSetMultiCovered multi = new TLRPC.TL_stickerSetMultiCovered();
                multi.set = set(catalog, source);
                for (EmojiDocument item : source.items()) {
                    if (multi.covers.size() >= FEATURED_COVERS) {
                        break;
                    }
                    multi.covers.add(document(item, true));
                }
                covered = multi;
            }
            result.sets.add(covered);
            hash = hash * 31 + source.id;
        }
        result.count = result.sets.size();
        result.hash = hash;
        return result;
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
