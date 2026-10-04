package org.telegram.messenger.foxmes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class FoxMesModels {

    private FoxMesModels() {
    }

    static <T> List<T> list(List<T> value) {
        return value != null ? value : Collections.emptyList();
    }

    public static final String CAPABILITY_EPHEMERAL_MEDIA = "ephemeral-media-v1";

    public static class User {
        public long id;
        public String username;
        @SerializedName("display_name")
        public String displayName;
        @SerializedName("avatar_url")
        public String avatarUrl;
        @SerializedName("avatar_id")
        public String avatarId;
        // Present only on payloads built for this account; null means "not stated".
        public Boolean blocked;
        @SerializedName("contact_name")
        public String contactName;
        @SerializedName("contact_photo_url")
        public String contactPhotoUrl;
        @SerializedName("original_display_name")
        public String originalDisplayName;
        @SerializedName("contact_note")
        public String contactNote;
    }

    public static class Me extends User {
        @SerializedName("event_seq")
        public Long eventSeq;
        public List<String> capabilities;

        public List<String> capabilities() {
            return list(capabilities);
        }
    }

    public static class Entity {
        public String type;
        public int offset;
        public int length;
        public String data;
        @SerializedName("emoji_id")
        public Long emojiId;
        @SerializedName("preview_disabled")
        public Boolean previewDisabled;
        @SerializedName("preview_above")
        public Boolean previewAbove;
        @SerializedName("preview_large")
        public Boolean previewLarge;
        @SerializedName("preview_small")
        public Boolean previewSmall;
    }

    public static class Reaction {
        @SerializedName("user_id")
        public long userId;
        @SerializedName("emoji_id")
        public int emojiId;
    }

    public static class ReactionCatalogItem {
        public int id;
        public String emoji;
        @SerializedName("asset_url")
        public String assetUrl;
        public String category;
        public int order;
    }

    public static class ReactionCatalog {
        @SerializedName("available_reactions")
        public List<ReactionCatalogItem> availableReactions;
        @SerializedName("max_selected")
        public int maxSelected;
        @SerializedName("top_reactions")
        public List<Integer> topReactions;
        @SerializedName("recent_reactions")
        public List<Integer> recentReactions;

        public List<ReactionCatalogItem> availableReactions() {
            return list(availableReactions);
        }

        public List<Integer> topReactions() {
            return list(topReactions);
        }

        public List<Integer> recentReactions() {
            return list(recentReactions);
        }
    }

    public static class Attachment {
        public long id;
        public String url;
        public String name;
        public String mime;
        public Long size;
        public Integer width;
        public Integer height;
        @SerializedName("as_file")
        public Boolean asFile;
        @SerializedName("poster_url")
        public String posterUrl;
        @SerializedName("file_unique_id")
        public String fileUniqueId;
        @SerializedName("poster_file_unique_id")
        public String posterFileUniqueId;
        public String kind;
        @SerializedName("duration_ms")
        public Long durationMs;
        public String performer;
        public String title;
        public Boolean spoiler;
        public String sha256;
        public String waveform;
    }

    public static class WebPage {
        public String url;
        @SerializedName("display_url")
        public String displayUrl;
        public String type;
        @SerializedName("site_name")
        public String siteName;
        public String title;
        public String description;
        @SerializedName("image_url")
        public String imageUrl;
        @SerializedName("image_width")
        public Integer imageWidth;
        @SerializedName("image_height")
        public Integer imageHeight;
        public Boolean pending;
        public Boolean above;
        public Boolean large;
        public Boolean small;
    }

    public static class ReplyTo {
        @SerializedName("chat_id")
        public Long chatId;
        @SerializedName("author_id")
        public Long authorId;
        @SerializedName("author_name")
        public String authorName;
        public String text;
    }

    public static class ForwardedFrom {
        @SerializedName("message_id")
        public Integer messageId;
        @SerializedName("author_id")
        public Long authorId;
        @SerializedName("author_name")
        public String authorName;
        @SerializedName("source_date")
        public String sourceDate;
    }

    public static class Location {
        public double latitude;
        public double longitude;
        public String title;
        public String address;
    }

    public static class CallInfo {
        public static final String STATE_RINGING = "ringing";
        public static final String STATE_ACTIVE = "active";
        public static final String STATE_MISSED = "missed";
        public static final String STATE_ENDED = "ended";

        @SerializedName("call_id")
        public String callId;
        public Boolean video;
        public String reason;
        public Integer duration;
        public Boolean conference;
        public String state;
        public List<Long> participants;

        public List<Long> participants() {
            return list(participants);
        }
    }

    public static class Ephemeral {
        public static final String MODE_TIMER = "timer";
        public static final String MODE_ONCE = "once";
        public static final String STATE_PENDING = "pending";
        public static final String STATE_OPENED = "opened";
        public static final String STATE_EXPIRED = "expired";

        public String mode;
        @SerializedName("ttl_seconds")
        public Integer ttlSeconds;
        public String state;
        @SerializedName("media_kind")
        public int mediaKind;
        @SerializedName("opened_at")
        public String openedAt;
        @SerializedName("expires_at")
        public String expiresAt;

        public boolean isExpired() {
            return STATE_EXPIRED.equals(state);
        }

        public boolean isOpened() {
            return STATE_OPENED.equals(state);
        }

        public boolean isViewOnce() {
            return MODE_ONCE.equals(mode);
        }
    }

    public static class EphemeralViewed {
        @SerializedName("message_id")
        public int messageId;
        public Ephemeral ephemeral;
    }

    public static class Message {
        public int id;
        @SerializedName("chat_id")
        public long chatId;
        @SerializedName("sender_id")
        public long senderId;
        public String text;
        @SerializedName("client_nonce")
        public String clientNonce;
        @SerializedName("reply_to_id")
        public Integer replyToId;
        @SerializedName("created_at")
        public String createdAt;
        @SerializedName("edited_at")
        public String editedAt;
        public int revision;
        public List<Entity> entities;
        @SerializedName("grouped_id")
        public Long groupedId;
        public List<Attachment> attachments;
        public List<Reaction> reactions;
        @SerializedName("reaction_revision")
        public long reactionRevision;
        public Boolean silent;
        @SerializedName("from_scheduled")
        public Boolean fromScheduled;
        @SerializedName("video_timestamp")
        public Integer videoTimestamp;
        public String delivery;
        @SerializedName("delivered_at")
        public String deliveredAt;
        @SerializedName("read_at")
        public String readAt;
        @SerializedName("web_page")
        public WebPage webPage;
        @SerializedName("reply_to")
        public ReplyTo replyTo;
        @SerializedName("forwarded_from")
        public ForwardedFrom forwardedFrom;
        public Location location;
        public CallInfo call;
        public Ephemeral ephemeral;

        public String text() {
            return text != null ? text : "";
        }

        public List<Entity> entities() {
            return list(entities);
        }

        public List<Attachment> attachments() {
            return list(attachments);
        }

        public List<Reaction> reactions() {
            return list(reactions);
        }
    }

    public static class Draft {
        public String text;
        @SerializedName("reply_to_id")
        public int replyToId;
        @SerializedName("reply_to_chat_id")
        public Long replyToChatId;
        public long revision;
    }

    public static class ReadState {
        @SerializedName("reader_id")
        public long readerId;
        @SerializedName("read_through_id")
        public int readThroughId;
        @SerializedName("read_at")
        public String readAt;
        @SerializedName("unread_count")
        public int unreadCount;
        @SerializedName("state_revision")
        public long stateRevision;
    }

    public static class ReceiptState {
        public ReadState inbox;
        public ReadState outbox;
    }

    public static class NotificationSettings {
        @SerializedName("mute_until")
        public long muteUntil;
        @SerializedName("show_previews")
        public Boolean showPreviews;
        @SerializedName("sound_none")
        public boolean soundNone;
        @SerializedName("settings_revision")
        public long settingsRevision;

        public boolean showPreviews() {
            return showPreviews == null || showPreviews;
        }
    }

    public static class NotificationDefaults {
        @SerializedName("mute_until")
        public long muteUntil;
        @SerializedName("sound_none")
        public boolean soundNone;
        @SerializedName("settings_revision")
        public long settingsRevision;
    }

    public static class Chat {
        public long id;
        public String type;
        public String title;
        @SerializedName("member_ids")
        public List<Long> memberIds;
        public List<User> members;
        @SerializedName("owner_id")
        public long ownerId;
        @SerializedName("last_message")
        public Message lastMessage;
        @SerializedName("unread_count")
        public int unreadCount;
        @SerializedName("receipt_state")
        public ReceiptState receiptState;
        public boolean pinned;
        @SerializedName("pinned_rank")
        public long pinnedRank;
        @SerializedName("marked_unread")
        public boolean markedUnread;
        public boolean archived;
        @SerializedName("created_at")
        public String createdAt;
        @SerializedName("updated_at")
        public String updatedAt;
        public Draft draft;
        @SerializedName("is_saved")
        public Boolean isSaved;
        public User peer;
        @SerializedName("notification_settings")
        public NotificationSettings notificationSettings;

        public List<Long> memberIds() {
            return list(memberIds);
        }

        public List<User> members() {
            return list(members);
        }

        public boolean isSavedChat() {
            return (isSaved != null && isSaved) || "saved".equals(type);
        }
    }

    public static class MessagesPage {
        public List<Message> items;
        @SerializedName("has_more_before")
        public boolean hasMoreBefore;
        @SerializedName("has_more_after")
        public boolean hasMoreAfter;
        @SerializedName("next_before")
        public int nextBefore;
        @SerializedName("next_after")
        public int nextAfter;
        public Integer total;

        public List<Message> items() {
            return list(items);
        }
    }

    public static class AuthStart {
        public String request;
        public String url;
        @SerializedName("expires_at")
        public String expiresAt;
    }

    public static class AuthExchange {
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("token_type")
        public String tokenType;
        public User user;
    }

    public static class DeleteResult {
        public Boolean ok;
        @SerializedName("deleted_ids")
        public List<Integer> deletedIds;
        @SerializedName("skipped_pinned_ids")
        public List<Integer> skippedPinnedIds;
        @SerializedName("rejected_ids")
        public List<Integer> rejectedIds;
    }

    public static class PinnedState {
        @SerializedName("message_ids")
        public List<Integer> messageIds;
        @SerializedName("top_message_id")
        public int topMessageId;

        public List<Integer> messageIds() {
            return list(messageIds);
        }
    }

    public static class Reminder {
        public int id;
        @SerializedName("chat_id")
        public long chatId;
        @SerializedName("sender_id")
        public long senderId;
        public String text;
        public List<Entity> entities;
        @SerializedName("reply_to_id")
        public Integer replyToId;
        public List<Attachment> attachments;
        @SerializedName("deliver_at")
        public long deliverAt;
        @SerializedName("deliver_when_online")
        public boolean deliverWhenOnline;
        public long revision;
        @SerializedName("grouped_id")
        public Long groupedId;
        public boolean silent;

        public String text() {
            return text != null ? text : "";
        }

        public List<Entity> entities() {
            return list(entities);
        }

        public List<Attachment> attachments() {
            return list(attachments);
        }
    }

    public static class ReminderList {
        public List<Reminder> items;

        public List<Reminder> items() {
            return list(items);
        }
    }

    public static class Gif {
        public long id;
        public String url;
        @SerializedName("poster_url")
        public String posterUrl;
        public String name;
        public String mime;
        public Long size;
        public Integer width;
        public Integer height;
        public String sha256;
    }

    public static class GifList {
        public List<Gif> items;
        @SerializedName("has_more")
        public boolean hasMore;

        public List<Gif> items() {
            return list(items);
        }
    }

    public static class PresenceList {
        public static class Item {
            public User user;
            public Boolean online;
            @SerializedName("last_seen_at")
            public String lastSeenAt;
        }

        public List<Item> items;

        public List<Item> items() {
            return list(items);
        }
    }

    public static class SearchPage {
        public List<Message> items;
        @SerializedName("has_more")
        public boolean hasMore;
        public int total;
        @SerializedName("next_before")
        public int nextBefore;

        public List<Message> items() {
            return list(items);
        }
    }

    public static class ItemsResult {
        public List<Message> items;
        @SerializedName("grouped_id")
        public Long groupedId;

        public List<Message> items() {
            return list(items);
        }
    }

    public static class CallHistoryItem {
        public long id;
        @SerializedName("peer_id")
        public long peerId;
        public boolean outgoing;
        public Boolean video;
        public String reason;
        public Integer duration;
        @SerializedName("message_id")
        public int messageId;
        public int date;
        public Boolean conference;
        @SerializedName("call_id")
        public Long callId;
        public String state;
        public List<Long> participants;

        public List<Long> participants() {
            return list(participants);
        }
    }

    public static class CallHistory {
        public List<CallHistoryItem> calls;
        public List<User> users;

        public List<CallHistoryItem> calls() {
            return list(calls);
        }

        public List<User> users() {
            return list(users);
        }
    }

    public static class Meet {
        public String url;
    }

    public static class Language {
        public String id;
        public String name;
        @SerializedName("native_name")
        public String nativeName;
        @SerializedName("built_in")
        public boolean builtIn;
        public int version;
    }

    public static class LanguageList {
        public List<Language> items;

        public List<Language> items() {
            return list(items);
        }
    }

    public static class LanguagePack {
        public Language language;
        public Map<String, String> strings;
    }

    public static class UserList {
        public List<User> items;

        public List<User> items() {
            return list(items);
        }
    }

    public static class ChatList {
        public List<Chat> items;

        public List<Chat> items() {
            return list(items);
        }
    }

    public static class Resolved {
        public User user;
        public Chat chat;
        public Boolean member;
    }

    public static class LinkPreview {
        @SerializedName("web_page")
        public WebPage webPage;
    }

    public static class UploadResult {
        public long id;
        public String poster;
    }

    public static class Event {
        public String type;
        public long seq;
        public JsonElement data;

        public JsonObject dataObject() {
            return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
        }
    }

    static <T> ArrayList<T> copy(List<T> value) {
        return new ArrayList<>(list(value));
    }
}
