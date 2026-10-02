package org.telegram.messenger.foxmes;

import static org.telegram.tgnet.TLObject.FLAG_0;
import static org.telegram.tgnet.TLObject.FLAG_1;
import static org.telegram.tgnet.TLObject.FLAG_10;
import static org.telegram.tgnet.TLObject.FLAG_15;
import static org.telegram.tgnet.TLObject.FLAG_17;
import static org.telegram.tgnet.TLObject.FLAG_2;
import static org.telegram.tgnet.TLObject.FLAG_20;
import static org.telegram.tgnet.TLObject.FLAG_3;
import static org.telegram.tgnet.TLObject.FLAG_4;
import static org.telegram.tgnet.TLObject.FLAG_5;
import static org.telegram.tgnet.TLObject.FLAG_6;
import static org.telegram.tgnet.TLObject.FLAG_7;
import static org.telegram.tgnet.TLObject.FLAG_8;
import static org.telegram.tgnet.TLObject.FLAG_9;

import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.foxmes.FoxMesModels.Attachment;
import org.telegram.messenger.foxmes.FoxMesModels.CallInfo;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.Draft;
import org.telegram.messenger.foxmes.FoxMesModels.Entity;
import org.telegram.messenger.foxmes.FoxMesModels.Ephemeral;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationSettings;
import org.telegram.messenger.foxmes.FoxMesModels.Reaction;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.messenger.foxmes.FoxMesModels.WebPage;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoxMesTL {

    public static final int DC = 1;

    public static final long WEBPAGE_PHOTO_BIT = 1L << 40;
    public static final long GIF_BIT = 1L << 42;
    public static final long GIF_POSTER_BIT = 1L << 43;
    public static final long STICKER_BIT = 1L << 44;

    public static final int TTL_ONCE = 0x7FFFFFFF;

    private FoxMesTL() {
    }

    public interface MapContext {
        long selfId();

        long peerForChat(long chatId);

        boolean isCatalogReaction(int emojiId);

        void recordPhotoUrl(long photoId, String url);

        boolean isOnline(long userId);
    }


    public static long accessHash(long id) {
        long value = (id ^ 0x5DEECE66DL) * 0x9E3779B97F4A7C15L;
        return value != 0 ? value : 1;
    }

    public static long positiveHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (bytes[i] & 0xFF);
            }
            result &= Long.MAX_VALUE;
            return result != 0 ? result : 1;
        } catch (Exception e) {
            long hash = value.hashCode() & 0x7FFFFFFFL;
            return hash != 0 ? hash : 1;
        }
    }

    public static TLRPC.Peer peer(long userId) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        return peer;
    }

    public static TLRPC.TL_inputPeerUser inputPeer(long userId) {
        TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
        peer.user_id = userId;
        peer.access_hash = accessHash(userId);
        return peer;
    }


    public interface Reader<T> {
        T read(SerializedData stream, int constructor);
    }

    public static <T extends TLObject> T normalize(T object, Reader<T> reader) {
        if (object == null) {
            return null;
        }
        SerializedData out = new SerializedData(object.getObjectSize());
        object.serializeToStream(out);
        byte[] bytes = out.toByteArray();
        out.cleanup();
        SerializedData in = new SerializedData(bytes);
        try {
            int constructor = in.readInt32(true);
            T result = reader.read(in, constructor);
            return result != null ? result : object;
        } catch (Exception e) {
            FoxMesLog.e("normalize " + object.getClass().getSimpleName(), e);
            return object;
        } finally {
            in.cleanup();
        }
    }

    public static TLRPC.User normalizeUser(TLRPC.User user) {
        return normalize(user, (stream, constructor) -> TLRPC.User.TLdeserialize(stream, constructor, true));
    }

    public static TLRPC.MessageMedia normalizeMedia(TLRPC.MessageMedia media) {
        return normalize(media, (stream, constructor) -> TLRPC.MessageMedia.TLdeserialize(stream, constructor, true));
    }

    public static TLRPC.Document normalizeDocument(TLRPC.Document document) {
        return normalize(document, (stream, constructor) -> TLRPC.Document.TLdeserialize(stream, constructor, true));
    }

    public static TLRPC.Photo normalizePhoto(TLRPC.Photo photo) {
        return normalize(photo, (stream, constructor) -> TLRPC.Photo.TLdeserialize(stream, constructor, true));
    }


    public static TLRPC.User user(User source, MapContext context) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = source.id;
        boolean self = source.id == context.selfId();
        user.self = self;
        user.premium = self;
        user.flags = TLObject.setFlag(user.flags, FLAG_0, true);
        user.access_hash = accessHash(source.id);
        String username = TextUtils.isEmpty(source.username) ? null : source.username;
        String name = !TextUtils.isEmpty(source.displayName) ? source.displayName : (username != null ? username : "FoxMes user " + source.id);
        user.first_name = name;
        user.flags = TLObject.setFlag(user.flags, FLAG_1, true);
        if (username != null) {
            user.username = username;
            user.flags = TLObject.setFlag(user.flags, FLAG_3, true);
        }
        String avatar = FoxMesHttp.absolute(source.avatarUrl);
        if (!TextUtils.isEmpty(avatar)) {
            TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
            photo.photo_id = avatarPhotoId(source);
            photo.dc_id = DC;
            user.photo = photo;
            user.flags = TLObject.setFlag(user.flags, FLAG_5, true);
            context.recordPhotoUrl(photo.photo_id, avatar);
        }
        boolean online = !self && context.isOnline(source.id);
        user.status = status(online);
        user.flags = TLObject.setFlag(user.flags, FLAG_6, true);
        TLRPC.User result = normalizeUser(user);
        result.status = status(online);
        return result;
    }

    public static final int ONLINE_WINDOW = 120;

    public static TLRPC.UserStatus status(boolean online) {
        if (online) {
            TLRPC.TL_userStatusOnline status = new TLRPC.TL_userStatusOnline();
            status.expires = (int) (System.currentTimeMillis() / 1000) + ONLINE_WINDOW;
            return status;
        }
        TLRPC.TL_userStatusRecently status = new TLRPC.TL_userStatusRecently();
        status.expires = -100;
        return status;
    }

    public static long avatarPhotoId(User source) {
        String key = !TextUtils.isEmpty(source.avatarId) ? source.avatarId : FoxMesHttp.absolute(source.avatarUrl);
        return positiveHash("avatar:" + key);
    }

    public static TLRPC.Photo avatarPhoto(User source, int date) {
        String avatar = FoxMesHttp.absolute(source.avatarUrl);
        if (TextUtils.isEmpty(avatar)) {
            return null;
        }
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.flags = FoxMesFileRef.FLAG_AVATAR | (FoxMesFileRef.isCdnOriginalURL(avatar) ? FoxMesFileRef.FLAG_CDN_RESIZABLE : 0);
        ref.url = avatar;
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = avatarPhotoId(source);
        photo.access_hash = accessHash(photo.id);
        photo.file_reference = ref.encode();
        photo.date = date;
        photo.dc_id = DC;
        TLRPC.TL_photoSize small = new TLRPC.TL_photoSize();
        small.type = "a";
        small.w = small.h = 160;
        photo.sizes.add(small);
        TLRPC.TL_photoSize big = new TLRPC.TL_photoSize();
        big.type = "c";
        big.w = big.h = 640;
        photo.sizes.add(big);
        return normalizePhoto(photo);
    }

    public static TLRPC.User placeholderUser(long id, String name, MapContext context) {
        User source = new User();
        source.id = id;
        source.displayName = !TextUtils.isEmpty(name) ? name.trim() : "FoxMes user " + id;
        source.username = "";
        return user(source, context);
    }


    public static TLRPC.TL_peerNotifySettings notifySettings(NotificationSettings source) {
        TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
        if (source == null) {
            return settings;
        }
        settings.show_previews = source.showPreviews();
        settings.flags = TLObject.setFlag(settings.flags, FLAG_0, true);
        long now = System.currentTimeMillis() / 1000;
        if (source.muteUntil > now) {
            settings.mute_until = (int) Math.min(Integer.MAX_VALUE, source.muteUntil);
            settings.flags = TLObject.setFlag(settings.flags, FLAG_2, true);
        } else {
            settings.mute_until = 0;
            settings.flags = TLObject.setFlag(settings.flags, FLAG_2, true);
        }
        if (source.soundNone) {
            settings.android_sound = new TLRPC.TL_notificationSoundNone();
            settings.flags = TLObject.setFlag(settings.flags, FLAG_4, true);
        }
        return settings;
    }


    public static ArrayList<TLRPC.MessageEntity> entities(List<Entity> source) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (Entity item : source) {
            TLRPC.MessageEntity entity = entity(item);
            if (entity != null) {
                entity.offset = item.offset;
                entity.length = item.length;
                result.add(entity);
            }
        }
        return result;
    }

    private static TLRPC.MessageEntity entity(Entity item) {
        if (item.type == null || item.length <= 0) {
            return null;
        }
        switch (item.type) {
            case "bold":
                return new TLRPC.TL_messageEntityBold();
            case "italic":
                return new TLRPC.TL_messageEntityItalic();
            case "underline":
                return new TLRPC.TL_messageEntityUnderline();
            case "strikethrough":
            case "strike":
                return new TLRPC.TL_messageEntityStrike();
            case "code":
                return new TLRPC.TL_messageEntityCode();
            case "spoiler":
                return new TLRPC.TL_messageEntitySpoiler();
            case "pre": {
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = item.data != null ? item.data : "";
                return pre;
            }
            case "blockquote":
                return new TLRPC.TL_messageEntityBlockquote();
            case "text_url": {
                if (TextUtils.isEmpty(item.data)) {
                    return null;
                }
                TLRPC.TL_messageEntityTextUrl url = new TLRPC.TL_messageEntityTextUrl();
                url.url = item.data;
                return url;
            }
            case "url":
                return new TLRPC.TL_messageEntityUrl();
            case "mention":
                return new TLRPC.TL_messageEntityMention();
            case "hashtag":
                return new TLRPC.TL_messageEntityHashtag();
            case "email":
                return new TLRPC.TL_messageEntityEmail();
            case "bot_command":
                return new TLRPC.TL_messageEntityBotCommand();
            case "custom_emoji": {
                if (item.emojiId == null || item.emojiId == 0) {
                    return null;
                }
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                emoji.document_id = item.emojiId;
                return emoji;
            }
            default:
                return null;
        }
    }

    public static ArrayList<FoxMesJson.Body> entitiesOut(List<TLRPC.MessageEntity> source, String text) {
        ArrayList<FoxMesJson.Body> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (TLRPC.MessageEntity entity : source) {
            String type = null;
            String data = null;
            Long emojiId = null;
            if (entity instanceof TLRPC.TL_messageEntityBold) {
                type = "bold";
            } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                type = "italic";
            } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                type = "underline";
            } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                type = "strikethrough";
            } else if (entity instanceof TLRPC.TL_messageEntityCode) {
                type = "code";
            } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                type = "spoiler";
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                type = "pre";
                data = ((TLRPC.TL_messageEntityPre) entity).language;
            } else if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                type = "blockquote";
            } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                type = "text_url";
                data = ((TLRPC.TL_messageEntityTextUrl) entity).url;
            } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                type = "text_url";
                if (text != null && entity.offset >= 0 && entity.offset + entity.length <= text.length()) {
                    data = text.substring(entity.offset, entity.offset + entity.length);
                }
            } else if (entity instanceof TLRPC.TL_messageEntityMention) {
                type = "mention";
            } else if (entity instanceof TLRPC.TL_messageEntityHashtag) {
                type = "hashtag";
            } else if (entity instanceof TLRPC.TL_messageEntityEmail) {
                type = "email";
            } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                type = "custom_emoji";
                emojiId = ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id;
            }
            if (type == null || ("text_url".equals(type) && TextUtils.isEmpty(data))) {
                continue;
            }
            result.add(FoxMesJson.body()
                    .put("type", type)
                    .put("offset", entity.offset)
                    .put("length", entity.length)
                    .put("data", data)
                    .put("emoji_id", emojiId));
        }
        return result;
    }


    public static TLRPC.Message message(Message source, MapContext context) {
        long self = context.selfId();
        long peerUser = context.peerForChat(source.chatId);
        if (peerUser == 0) {
            peerUser = source.senderId != self ? source.senderId : self;
        }
        boolean out = source.senderId == self;
        CallInfo call = source.call;
        if (call != null) {
            TLRPC.MessageAction action = callAction(call);
            if (action != null) {
                TLRPC.TL_messageService service = new TLRPC.TL_messageService();
                service.id = source.id;
                service.out = out;
                service.from_id = peer(source.senderId);
                service.flags = TLObject.setFlag(service.flags, FLAG_8, true);
                service.peer_id = peer(peerUser);
                service.date = FoxMesJson.unixTimeOrNow(source.createdAt);
                service.action = action;
                service.dialog_id = peerUser;
                return service;
            }
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.id;
        message.out = out;
        message.silent = source.silent != null && source.silent;
        message.from_scheduled = source.fromScheduled != null && source.fromScheduled;
        message.from_id = peer(source.senderId);
        message.flags = TLObject.setFlag(message.flags, FLAG_8, true);
        message.peer_id = peer(peerUser);
        message.dialog_id = peerUser;
        message.date = FoxMesJson.unixTimeOrNow(source.createdAt);
        message.message = source.text();
        ArrayList<TLRPC.MessageEntity> entities = entities(source.entities());
        if (!entities.isEmpty()) {
            message.entities = entities;
            message.flags = TLObject.setFlag(message.flags, FLAG_7, true);
        }
        TLRPC.MessageReplyHeader reply = replyHeader(source, context);
        if (reply != null) {
            message.reply_to = reply;
            message.flags = TLObject.setFlag(message.flags, FLAG_3, true);
        }
        if (source.forwardedFrom != null) {
            TLRPC.TL_messageFwdHeader forward = new TLRPC.TL_messageFwdHeader();
            if (source.forwardedFrom.authorId != null && source.forwardedFrom.authorId > 0) {
                forward.from_id = peer(source.forwardedFrom.authorId);
                forward.flags = TLObject.setFlag(forward.flags, FLAG_0, true);
            } else {
                forward.from_name = !TextUtils.isEmpty(source.forwardedFrom.authorName) ? source.forwardedFrom.authorName : "FoxMes";
                forward.flags = TLObject.setFlag(forward.flags, FLAG_5, true);
            }
            forward.date = FoxMesJson.unixTimeOrNow(source.forwardedFrom.sourceDate);
            message.fwd_from = forward;
            message.flags = TLObject.setFlag(message.flags, FLAG_2, true);
        }
        int editDate = FoxMesJson.unixTime(source.editedAt);
        if (editDate > 0) {
            message.edit_date = editDate;
            message.flags = TLObject.setFlag(message.flags, FLAG_15, true);
        }
        if (source.groupedId != null && source.groupedId != 0) {
            message.grouped_id = source.groupedId;
            message.flags = TLObject.setFlag(message.flags, FLAG_17, true);
        }
        TLRPC.TL_messageReactions reactions = reactions(source.reactions(), context);
        if (reactions != null) {
            message.reactions = reactions;
            message.flags = TLObject.setFlag(message.flags, FLAG_20, true);
        }
        TLRPC.MessageMedia media = media(source, context);
        if (media != null) {
            message.media = media;
            message.flags = TLObject.setFlag(message.flags, FLAG_9, true);
            message.ttl = media.ttl_seconds;
        }
        if (source.ephemeral != null && Ephemeral.STATE_PENDING.equals(source.ephemeral.state)) {
            message.media_unread = true;
            message.flags = TLObject.setFlag(message.flags, FLAG_5, true);
        }
        return message;
    }

    private static TLRPC.MessageReplyHeader replyHeader(Message source, MapContext context) {
        if (source.replyToId == null || source.replyToId <= 0) {
            return null;
        }
        TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
        header.reply_to_msg_id = source.replyToId;
        header.flags = TLObject.setFlag(header.flags, FLAG_4, true);
        if (source.replyTo != null && source.replyTo.chatId != null && source.replyTo.chatId != source.chatId) {
            long replyPeer = context.peerForChat(source.replyTo.chatId);
            if (replyPeer != 0) {
                header.reply_to_peer_id = peer(replyPeer);
                header.flags = TLObject.setFlag(header.flags, FLAG_0, true);
            }
        }
        return header;
    }

    public static TLRPC.TL_messageReactions reactions(List<Reaction> source, MapContext context) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        long self = context.selfId();
        LinkedHashMap<Integer, int[]> counts = new LinkedHashMap<>();
        Map<Integer, Integer> chosen = new LinkedHashMap<>();
        ArrayList<TLRPC.MessagePeerReaction> recent = new ArrayList<>();
        int now = (int) (System.currentTimeMillis() / 1000);
        for (Reaction reaction : source) {
            if (reaction.emojiId == 0 || !context.isCatalogReaction(reaction.emojiId)) {
                continue;
            }
            int[] count = counts.get(reaction.emojiId);
            if (count == null) {
                count = new int[1];
                counts.put(reaction.emojiId, count);
            }
            count[0]++;
            if (reaction.userId == self && !chosen.containsKey(reaction.emojiId)) {
                chosen.put(reaction.emojiId, chosen.size() + 1);
            }
            TLRPC.TL_messagePeerReaction peerReaction = new TLRPC.TL_messagePeerReaction();
            peerReaction.peer_id = peer(reaction.userId);
            peerReaction.date = now;
            peerReaction.reaction = customEmojiReaction(reaction.emojiId);
            recent.add(peerReaction);
        }
        if (counts.isEmpty()) {
            return null;
        }
        TLRPC.TL_messageReactions result = new TLRPC.TL_messageReactions();
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            TLRPC.TL_reactionCount count = new TLRPC.TL_reactionCount();
            count.reaction = customEmojiReaction(entry.getKey());
            count.count = entry.getValue()[0];
            Integer order = chosen.get(entry.getKey());
            if (order != null) {
                count.chosen_order = order;
                count.chosen = true;
                count.flags = TLObject.setFlag(count.flags, FLAG_0, true);
            }
            result.results.add(count);
        }
        result.recent_reactions = recent;
        result.flags = TLObject.setFlag(result.flags, FLAG_1, true);
        result.can_see_list = true;
        return result;
    }

    public static TLRPC.TL_reactionCustomEmoji customEmojiReaction(long documentId) {
        TLRPC.TL_reactionCustomEmoji reaction = new TLRPC.TL_reactionCustomEmoji();
        reaction.document_id = documentId;
        return reaction;
    }


    public static TLRPC.MessageMedia media(Message source, MapContext context) {
        Ephemeral ephemeral = source.ephemeral;
        if (ephemeral != null && ephemeral.isExpired()) {
            return normalizeMedia(expiredMedia(ephemeral));
        }
        List<Attachment> attachments = source.attachments();
        int date = FoxMesJson.unixTimeOrNow(source.createdAt);
        if (!attachments.isEmpty()) {
            TLRPC.MessageMedia media = attachmentMedia(attachments.get(0), source.chatId, date);
            if (media != null) {
                if (ephemeral != null) {
                    int ttl = ephemeral.isViewOnce() ? TTL_ONCE : (ephemeral.ttlSeconds != null ? ephemeral.ttlSeconds : 0);
                    if (ttl > 0) {
                        media.ttl_seconds = ttl;
                        media.flags = TLObject.setFlag(media.flags, FLAG_2, true);
                    }
                }
                if (source.videoTimestamp != null && source.videoTimestamp > 0 && media instanceof TLRPC.TL_messageMediaDocument) {
                    media.video_timestamp = source.videoTimestamp;
                    media.flags = TLObject.setFlag(media.flags, FLAG_10, true);
                }
                return normalizeMedia(media);
            }
        }
        if (source.location != null) {
            TLRPC.TL_geoPoint geo = new TLRPC.TL_geoPoint();
            geo.lat = source.location.latitude;
            geo._long = source.location.longitude;
            if (!TextUtils.isEmpty(source.location.title)) {
                TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
                venue.geo = geo;
                venue.title = source.location.title;
                venue.address = source.location.address != null ? source.location.address : "";
                venue.provider = "";
                venue.venue_id = "";
                venue.venue_type = "";
                return venue;
            }
            TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
            media.geo = geo;
            return media;
        }
        TLRPC.MessageMedia webpage = webPageMedia(source.webPage, source.id, date);
        if (webpage != null) {
            return normalizeMedia(webpage);
        }
        return null;
    }

    private static TLRPC.MessageMedia expiredMedia(Ephemeral ephemeral) {
        if (ephemeral.mediaKind == 1) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.ttl_seconds = 1;
            media.flags = TLObject.setFlag(media.flags, FLAG_2, true);
            return media;
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.ttl_seconds = 1;
        media.flags = TLObject.setFlag(media.flags, FLAG_2, true);
        media.voice = ephemeral.mediaKind == 5;
        media.round = ephemeral.mediaKind == 6;
        media.video = ephemeral.mediaKind == 2 || ephemeral.mediaKind == 6;
        return media;
    }

    private static boolean isPhotoAttachment(Attachment item) {
        String kind = item.kind != null ? item.kind : "";
        String mime = item.mime != null ? item.mime : "";
        return kind.equals("photo") || (kind.isEmpty() && mime.startsWith("image/") && (item.asFile == null || !item.asFile));
    }

    public static TLRPC.MessageMedia attachmentMedia(Attachment item, long chatId, int date) {
        String url = FoxMesHttp.absolute(item.url);
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (isPhotoAttachment(item)) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = photo(item, url, chatId, date);
            media.flags = TLObject.setFlag(media.flags, FLAG_0, true);
            media.spoiler = item.spoiler != null && item.spoiler;
            return media;
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document(item, url, chatId, date, item.id, 0);
        media.flags = TLObject.setFlag(media.flags, FLAG_0, true);
        String kind = item.kind != null ? item.kind : "";
        media.voice = kind.equals("voice");
        media.round = kind.equals("video_note");
        media.video = kind.equals("video") || kind.equals("video_note");
        media.spoiler = item.spoiler != null && item.spoiler;
        return media;
    }

    public static TLRPC.TL_photo photo(Attachment item, String url, long chatId, int date) {
        int width = item.width != null ? Math.max(0, item.width) : 0;
        int height = item.height != null ? Math.max(0, item.height) : 0;
        boolean resizable = FoxMesFileRef.mimeHasCdnPreview(item.mime != null ? item.mime : "image/jpeg") && FoxMesFileRef.isCdnOriginalURL(url);
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.url = url;
        ref.sha256 = item.sha256;
        ref.fileUniqueId = item.fileUniqueId;
        ref.chatId = chatId;
        ref.attachmentId = item.id;
        ref.width = width;
        ref.height = height;
        if (resizable) {
            ref.flags |= FoxMesFileRef.FLAG_CDN_RESIZABLE;
        }
        return photoWithSizes(item.id, ref, width, height, resizable, item.size != null ? item.size : 0, date);
    }

    public static TLRPC.TL_photo photoWithSizes(long id, FoxMesFileRef ref, int width, int height, boolean resizable, long originalSize, int date) {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = id != 0 ? id : 1;
        photo.access_hash = accessHash(photo.id);
        photo.file_reference = ref.encode();
        photo.date = date;
        photo.dc_id = DC;
        if (!resizable) {
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = "y";
            size.w = width;
            size.h = height;
            size.size = (int) Math.min(Integer.MAX_VALUE, Math.max(0, originalSize));
            photo.sizes.add(size);
            return photo;
        }
        for (int i = 0; i < FoxMesFileRef.CDN_PREVIEW_SIDES.length; i++) {
            int side = FoxMesFileRef.CDN_PREVIEW_SIDES[i];
            boolean fits = width > 0 && height > 0 && Math.max(width, height) <= side;
            int[] dimensions = FoxMesFileRef.previewDimensions(width, height, side);
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = FoxMesFileRef.CDN_PREVIEW_TYPES[i];
            size.w = dimensions[0] > 0 ? dimensions[0] : side;
            size.h = dimensions[1] > 0 ? dimensions[1] : side;
            size.size = fits ? (int) Math.min(Integer.MAX_VALUE, Math.max(0, originalSize)) : 0;
            photo.sizes.add(size);
            if (fits) {
                break;
            }
        }
        return photo;
    }

    public static TLRPC.TL_document document(Attachment item, String url, long chatId, int date, long id, int extraFlags) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = id != 0 ? id : 1;
        document.access_hash = accessHash(document.id);
        document.date = date;
        document.mime_type = !TextUtils.isEmpty(item.mime) ? item.mime : "application/octet-stream";
        document.size = item.size != null ? item.size : 0;
        document.dc_id = DC;
        int width = item.width != null ? Math.max(0, item.width) : 0;
        int height = item.height != null ? Math.max(0, item.height) : 0;
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.flags = extraFlags;
        ref.url = url;
        ref.sha256 = item.sha256;
        ref.fileUniqueId = item.fileUniqueId;
        ref.chatId = chatId;
        ref.attachmentId = item.id;
        ref.width = width;
        ref.height = height;
        String poster = FoxMesHttp.absolute(item.posterUrl);
        if (!TextUtils.isEmpty(poster)) {
            ref.posterUrl = poster;
            for (int i = 0; i < FoxMesFileRef.CDN_PREVIEW_SIDES.length; i++) {
                int side = FoxMesFileRef.CDN_PREVIEW_SIDES[i];
                int[] dimensions = FoxMesFileRef.previewDimensions(width, height, side);
                TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
                size.type = FoxMesFileRef.CDN_PREVIEW_TYPES[i];
                size.w = dimensions[0] > 0 ? dimensions[0] : side;
                size.h = dimensions[1] > 0 ? dimensions[1] : side;
                document.thumbs.add(size);
                if (width > 0 && height > 0 && Math.max(width, height) <= side) {
                    break;
                }
            }
            document.flags = TLObject.setFlag(document.flags, FLAG_0, true);
        }
        document.file_reference = ref.encode();
        if (!TextUtils.isEmpty(item.name)) {
            TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
            name.file_name = item.name;
            document.attributes.add(name);
        }
        String kind = item.kind != null ? item.kind : "";
        double duration = (item.durationMs != null ? item.durationMs : 0) / 1000.0;
        switch (kind) {
            case "video":
            case "animation":
            case "video_note": {
                TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
                video.duration = duration;
                video.w = width;
                video.h = height;
                video.round_message = kind.equals("video_note");
                video.supports_streaming = true;
                document.attributes.add(video);
                if (kind.equals("animation")) {
                    document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
                }
                break;
            }
            case "audio":
            case "voice": {
                TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
                audio.voice = kind.equals("voice");
                audio.duration = (int) duration;
                if (!TextUtils.isEmpty(item.title)) {
                    audio.title = item.title;
                    audio.flags = TLObject.setFlag(audio.flags, FLAG_0, true);
                }
                if (!TextUtils.isEmpty(item.performer)) {
                    audio.performer = item.performer;
                    audio.flags = TLObject.setFlag(audio.flags, FLAG_1, true);
                }
                if (!TextUtils.isEmpty(item.waveform)) {
                    try {
                        audio.waveform = Base64.decode(item.waveform, Base64.DEFAULT);
                        audio.flags = TLObject.setFlag(audio.flags, FLAG_2, true);
                    } catch (Exception ignore) {
                    }
                }
                document.attributes.add(audio);
                break;
            }
            default:
                if (document.mime_type.startsWith("image/") && width > 0 && height > 0) {
                    TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
                    size.w = width;
                    size.h = height;
                    document.attributes.add(size);
                }
                break;
        }
        return document;
    }

    public static TLRPC.MessageMedia webPageMedia(WebPage source, int messageId, int date) {
        if (source == null || TextUtils.isEmpty(source.url) || (source.pending != null && source.pending)) {
            return null;
        }
        if (TextUtils.isEmpty(source.title) && TextUtils.isEmpty(source.description) && TextUtils.isEmpty(source.siteName)) {
            return null;
        }
        TLRPC.TL_webPage page = new TLRPC.TL_webPage();
        page.id = messageId;
        page.url = source.url;
        page.display_url = !TextUtils.isEmpty(source.displayUrl) ? source.displayUrl : source.url;
        page.hash = 0;
        if (!TextUtils.isEmpty(source.type)) {
            page.type = source.type;
            page.flags = TLObject.setFlag(page.flags, FLAG_0, true);
        }
        if (!TextUtils.isEmpty(source.siteName)) {
            page.site_name = source.siteName;
            page.flags = TLObject.setFlag(page.flags, FLAG_1, true);
        }
        if (!TextUtils.isEmpty(source.title)) {
            page.title = source.title;
            page.flags = TLObject.setFlag(page.flags, FLAG_2, true);
        }
        if (!TextUtils.isEmpty(source.description)) {
            page.description = source.description;
            page.flags = TLObject.setFlag(page.flags, FLAG_3, true);
        }
        String imageUrl = FoxMesHttp.absolute(source.imageUrl);
        if (!TextUtils.isEmpty(imageUrl)) {
            int width = source.imageWidth != null ? source.imageWidth : 0;
            int height = source.imageHeight != null ? source.imageHeight : 0;
            boolean resizable = FoxMesFileRef.isCdnOriginalURL(imageUrl);
            FoxMesFileRef ref = new FoxMesFileRef();
            ref.url = imageUrl;
            ref.width = width;
            ref.height = height;
            if (resizable) {
                ref.flags |= FoxMesFileRef.FLAG_CDN_RESIZABLE;
            }
            long id = (messageId & 0xFFFFFFFFL) | WEBPAGE_PHOTO_BIT;
            page.photo = photoWithSizes(id, ref, width, height, resizable, 0, date);
            page.flags = TLObject.setFlag(page.flags, FLAG_4, true);
        }
        page.has_large_media = source.large != null && source.large;
        TLRPC.TL_messageMediaWebPage media = new TLRPC.TL_messageMediaWebPage();
        media.webpage = page;
        media.force_large_media = source.large != null && source.large;
        media.force_small_media = source.small != null && source.small;
        return media;
    }


    public static TLRPC.MessageAction callAction(CallInfo call) {
        long callId;
        try {
            callId = Long.parseLong(call.callId);
        } catch (Exception e) {
            return null;
        }
        if (call.conference != null && call.conference) {
            TLRPC.TL_messageActionConferenceCall action = new TLRPC.TL_messageActionConferenceCall();
            action.call_id = callId;
            action.video = call.video != null && call.video;
            action.active = CallInfo.STATE_ACTIVE.equals(call.state);
            action.missed = CallInfo.STATE_MISSED.equals(call.state);
            if (CallInfo.STATE_ENDED.equals(call.state) && call.duration != null && call.duration > 0) {
                action.duration = call.duration;
                action.flags = TLObject.setFlag(action.flags, FLAG_2, true);
            }
            List<Long> participants = call.participants();
            if (!participants.isEmpty()) {
                for (Long id : participants) {
                    action.other_participants.add(peer(id));
                }
                action.flags = TLObject.setFlag(action.flags, FLAG_3, true);
            }
            return action;
        }
        TLRPC.TL_messageActionPhoneCall action = new TLRPC.TL_messageActionPhoneCall();
        action.call_id = callId;
        action.video = call.video != null && call.video;
        TLRPC.PhoneCallDiscardReason reason = discardReason(call.reason);
        if (reason != null) {
            action.reason = reason;
            action.flags = TLObject.setFlag(action.flags, FLAG_0, true);
        }
        if (call.duration != null && call.duration > 0) {
            action.duration = call.duration;
            action.flags = TLObject.setFlag(action.flags, FLAG_1, true);
        }
        return action;
    }

    public static TLRPC.PhoneCallDiscardReason discardReason(String reason) {
        if (reason == null) {
            return null;
        }
        switch (reason) {
            case "missed":
                return new TLRPC.TL_phoneCallDiscardReasonMissed();
            case "disconnect":
                return new TLRPC.TL_phoneCallDiscardReasonDisconnect();
            case "busy":
                return new TLRPC.TL_phoneCallDiscardReasonBusy();
            case "hangup":
                return new TLRPC.TL_phoneCallDiscardReasonHangup();
            default:
                return null;
        }
    }


    public static final int SCHEDULE_WHEN_ONLINE = 0x7FFFFFFE;

    public static TLRPC.Message scheduledMessage(FoxMesModels.Reminder reminder, MapContext context) {
        Message source = new Message();
        source.id = reminder.id;
        source.chatId = reminder.chatId;
        source.senderId = reminder.senderId != 0 ? reminder.senderId : context.selfId();
        source.text = reminder.text();
        source.entities = reminder.entities;
        source.replyToId = reminder.replyToId;
        source.attachments = reminder.attachments;
        source.groupedId = reminder.groupedId;
        source.silent = reminder.silent;
        int date = reminder.deliverWhenOnline ? SCHEDULE_WHEN_ONLINE : (int) Math.min(Integer.MAX_VALUE, reminder.deliverAt);
        source.createdAt = null;
        TLRPC.Message message = message(source, context);
        message.date = date;
        message.out = true;
        return message;
    }


    public static TLRPC.DraftMessage draft(Draft source, int date) {
        if (source == null || TextUtils.isEmpty(source.text) && source.replyToId <= 0) {
            TLRPC.TL_draftMessageEmpty empty = new TLRPC.TL_draftMessageEmpty();
            empty.date = date;
            empty.flags = TLObject.setFlag(empty.flags, FLAG_0, true);
            return empty;
        }
        TLRPC.TL_draftMessage draft = new TLRPC.TL_draftMessage();
        draft.message = source.text != null ? source.text : "";
        draft.date = date;
        if (source.replyToId > 0) {
            TLRPC.TL_inputReplyToMessage reply = new TLRPC.TL_inputReplyToMessage();
            reply.reply_to_msg_id = source.replyToId;
            draft.reply_to = reply;
            draft.flags = TLObject.setFlag(draft.flags, FLAG_4, true);
        }
        return draft;
    }

    public static boolean hasDraft(Draft source) {
        return source != null && (!TextUtils.isEmpty(source.text) || source.replyToId > 0);
    }

    public static TLRPC.TL_dialog dialog(Chat chat, long peerUserId, int topMessage) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.peer = peer(peerUserId);
        dialog.id = peerUserId;
        dialog.top_message = topMessage;
        int maxKnown = topMessage;
        Integer inbox = chat.receiptState != null && chat.receiptState.inbox != null ? chat.receiptState.inbox.readThroughId : null;
        Integer outbox = chat.receiptState != null && chat.receiptState.outbox != null ? chat.receiptState.outbox.readThroughId : null;
        dialog.read_inbox_max_id = inbox != null && inbox > 0 ? inbox : (chat.unreadCount == 0 ? maxKnown : 0);
        dialog.read_outbox_max_id = outbox != null && outbox > 0 ? outbox : 0;
        dialog.unread_count = Math.max(0, chat.unreadCount);
        dialog.unread_mark = chat.markedUnread;
        dialog.pinned = chat.pinned;
        dialog.notify_settings = notifySettings(chat.notificationSettings);
        if (hasDraft(chat.draft)) {
            dialog.draft = draft(chat.draft, FoxMesJson.unixTimeOrNow(chat.updatedAt));
            dialog.flags = TLObject.setFlag(dialog.flags, FLAG_1, true);
        }
        dialog.folder_id = chat.archived ? 1 : 0;
        dialog.flags = TLObject.setFlag(dialog.flags, FLAG_4, true);
        return dialog;
    }
}
