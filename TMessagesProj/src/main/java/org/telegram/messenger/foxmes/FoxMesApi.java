package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.foxmes.FoxMesModels.AuthExchange;
import org.telegram.messenger.foxmes.FoxMesModels.AuthStart;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.DeleteResult;
import org.telegram.messenger.foxmes.FoxMesModels.Draft;
import org.telegram.messenger.foxmes.FoxMesModels.EphemeralViewed;
import org.telegram.messenger.foxmes.FoxMesModels.GifList;
import org.telegram.messenger.foxmes.FoxMesModels.ItemsResult;
import org.telegram.messenger.foxmes.FoxMesModels.LanguageList;
import org.telegram.messenger.foxmes.FoxMesModels.LanguagePack;
import org.telegram.messenger.foxmes.FoxMesModels.LinkPreview;
import org.telegram.messenger.foxmes.FoxMesModels.Me;
import org.telegram.messenger.foxmes.FoxMesModels.Meet;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.MessagesPage;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationDefaults;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationSettings;
import org.telegram.messenger.foxmes.FoxMesModels.PinnedState;
import org.telegram.messenger.foxmes.FoxMesModels.PresenceList;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalog;
import org.telegram.messenger.foxmes.FoxMesModels.ReadState;
import org.telegram.messenger.foxmes.FoxMesModels.ReminderList;
import org.telegram.messenger.foxmes.FoxMesModels.Resolved;
import org.telegram.messenger.foxmes.FoxMesModels.SearchPage;
import org.telegram.messenger.foxmes.FoxMesModels.User;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public final class FoxMesApi {

    private static final Type CHAT_LIST = new TypeToken<List<Chat>>() {
    }.getType();
    private static final Type USER_LIST = new TypeToken<List<User>>() {
    }.getType();

    public final FoxMesHttp http;

    public FoxMesApi(FoxMesHttp http) {
        this.http = http;
    }

    private static Map<String, String> query(Object... pairs) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                result.put((String) pairs[i], String.valueOf(value));
            }
        }
        return result;
    }


    public AuthStart startDevice(String deviceName) throws IOException {
        return http.request("POST", "/auth/device/start", null, FoxMesJson.body().put("device_name", deviceName), false, AuthStart.class);
    }

    public AuthExchange exchangeDevice(String request, String code) throws IOException {
        return http.request("POST", "/auth/device/exchange", null, FoxMesJson.body().put("request", request).put("code", code), false, AuthExchange.class);
    }

    public void logout(String token) throws IOException {
        http.requestString("POST", "/auth/logout", null, null, token);
    }

    public Me me() throws IOException {
        return http.request("GET", "/me", null, null, Me.class);
    }

    public User updateMe(String displayName) throws IOException {
        return http.request("PUT", "/me", null, FoxMesJson.body().put("display_name", displayName), User.class);
    }


    public List<User> users(String q) throws IOException {
        List<User> result = http.request("GET", "/users", query("q", q != null && !q.isEmpty() ? q : null), null, USER_LIST);
        return result != null ? result : Collections.emptyList();
    }

    public User user(long id) throws IOException {
        return http.request("GET", "/users/" + id, null, null, User.class);
    }

    public Resolved resolve(String name) throws IOException {
        return http.request("GET", "/resolve/" + name, query("light", "1"), null, Resolved.class);
    }

    public List<Chat> chats(boolean light) throws IOException {
        List<Chat> result = http.request("GET", "/chats", query("light", light ? "1" : "0"), null, CHAT_LIST);
        return result != null ? result : Collections.emptyList();
    }

    public Chat savedChat() throws IOException {
        return http.request("GET", "/chats/saved", null, null, Chat.class);
    }

    public Chat chat(long id) throws IOException {
        return http.request("GET", "/chats/" + id, null, null, Chat.class);
    }

    public Chat directChat(long userId) throws IOException {
        return http.request("POST", "/chats/direct", null,
                FoxMesJson.body().put("user_id", userId).put("operation_id", FoxMesJson.operationId()), Chat.class);
    }

    public PresenceList chatPresence(long chatId) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/presence", null, null, PresenceList.class);
    }


    public MessagesPage history(long chatId, int limit, Integer before, Integer after, Integer around) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/messages",
                query("limit", limit, "before", before, "after", after, "around", around), null, MessagesPage.class);
    }

    public Message message(long chatId, int id) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/messages/" + id, null, null, Message.class);
    }

    public MessagesPage chatMedia(long chatId, String kind, int limit, Integer before, String q) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/media",
                query("kind", kind, "limit", limit, "before", before, "q", q), null, MessagesPage.class);
    }

    public SearchPage searchPage(String q, Long chatId, Integer before, int limit) throws IOException {
        return http.request("GET", "/search/messages",
                query("q", q, "limit", limit, "chat_id", chatId, "before", before != null && before > 0 ? before : null), null, SearchPage.class);
    }

    public PinnedState pinnedMessages(long chatId) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/pinned", null, null, PinnedState.class);
    }

    public LinkPreview linkPreview(String url) throws IOException {
        return http.request("GET", "/link-preview", query("url", url), null, LinkPreview.class);
    }


    public static ItemsResult parseSendResult(String text) {
        JsonObject object = FoxMesJson.parse(text, JsonObject.class);
        ItemsResult result = new ItemsResult();
        if (object == null) {
            result.items = new ArrayList<>();
            return result;
        }
        if (object.has("items") && object.get("items").isJsonArray()) {
            result = FoxMesJson.parse(object, ItemsResult.class);
            if (result.items == null) {
                result.items = new ArrayList<>();
            }
            return result;
        }
        result.items = new ArrayList<>();
        if (object.has("id") && object.has("chat_id")) {
            result.items.add(FoxMesJson.parse(object, Message.class));
        }
        return result;
    }

    public ItemsResult sendMessage(long chatId, FoxMesJson.Body body) throws IOException {
        String text = http.requestString("POST", "/chats/" + chatId + "/messages", null, body, true);
        return parseSendResult(text);
    }

    public ItemsResult forwardMessages(long chatId, long sourceChatId, List<Integer> messageIds, boolean dropAuthor, Integer videoTimestamp, String operationId) throws IOException {
        FoxMesJson.Body body = FoxMesJson.body()
                .put("source_chat_id", sourceChatId)
                .putNumbers("message_ids", messageIds)
                .put("operation_id", operationId)
                .put("drop_author", dropAuthor)
                .put("video_timestamp", videoTimestamp);
        String text = http.requestString("POST", "/chats/" + chatId + "/messages/forward", null, body, true);
        return parseSendResult(text);
    }

    public Message editMessage(int id, String text, List<FoxMesJson.Body> entities, int expectedRevision) throws IOException {
        return http.request("PATCH", "/messages/" + id, null,
                FoxMesJson.body().put("text", text).putBodies("entities", entities).put("expected_revision", expectedRevision), Message.class);
    }

    public DeleteResult deleteMessage(int id) throws IOException {
        return http.request("DELETE", "/messages/" + id, null, null, DeleteResult.class);
    }

    public DeleteResult batchDeleteMessages(long chatId, List<Integer> ids) throws IOException {
        return http.request("DELETE", "/chats/" + chatId + "/messages/batch-delete", null,
                FoxMesJson.body().putNumbers("message_ids", ids).put("operation_id", FoxMesJson.operationId()), DeleteResult.class);
    }

    public ReadState markRead(long chatId, Integer messageId) throws IOException {
        return http.request("POST", "/chats/" + chatId + "/read", null, FoxMesJson.body().put("message_id", messageId), ReadState.class);
    }

    public void markDelivered(long chatId, List<Integer> ids) throws IOException {
        http.requestString("POST", "/chats/" + chatId + "/delivered", null, FoxMesJson.body().putNumbers("message_ids", ids), true);
    }

    public void setTyping(long chatId) throws IOException {
        http.requestString("POST", "/chats/" + chatId + "/typing", null, null, true);
    }

    public EphemeralViewed markEphemeralViewed(int messageId) throws IOException {
        return http.request("POST", "/messages/" + messageId + "/viewed", null, null, EphemeralViewed.class);
    }

    public Message replaceReactions(int id, List<Integer> reactions, long expectedRevision) throws IOException {
        return http.request("PUT", "/messages/" + id + "/reactions", null,
                FoxMesJson.body().putNumbers("reactions", reactions).put("operation_id", FoxMesJson.operationId()).put("expected_revision", expectedRevision),
                Message.class);
    }

    public ReactionCatalog reactionCatalog() throws IOException {
        return http.request("GET", "/reactions", null, null, ReactionCatalog.class);
    }


    public Draft draft(long chatId) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/draft", null, null, Draft.class);
    }

    public JsonObject saveDraft(long chatId, String text, Integer replyTo, Long baseRevision, long clientRevision) throws IOException {
        return http.request("PUT", "/chats/" + chatId + "/draft", null, FoxMesJson.body()
                .put("text", text)
                .put("reply_to_id", replyTo)
                .put("base_revision", baseRevision)
                .put("client_revision", clientRevision)
                .put("operation_id", FoxMesJson.operationId()), JsonObject.class);
    }


    public void setChatPinned(long chatId, boolean pinned) throws IOException {
        http.requestString("PUT", "/chats/" + chatId + "/list-pin", null, FoxMesJson.body().put("pinned", pinned), true);
    }

    public void savePinnedOrder(List<Long> chatIds) throws IOException {
        http.requestString("PUT", "/chats/list-pin-order", null, FoxMesJson.body().putNumbers("ordered_ids", chatIds), true);
    }

    public void setChatArchived(long chatId, boolean archived) throws IOException {
        http.requestString("PUT", "/chats/" + chatId + "/archive", null, FoxMesJson.body().put("archived", archived), true);
    }

    public void setChatUnreadMark(long chatId, boolean markedUnread) throws IOException {
        http.requestString("PUT", "/chats/" + chatId + "/unread-mark", null, FoxMesJson.body().put("marked_unread", markedUnread), true);
    }

    public NotificationSettings setChatNotificationSettings(long chatId, long muteUntil, boolean showPreviews, boolean soundNone, Long settingsRevision) throws IOException {
        return http.request("PUT", "/chats/" + chatId + "/notification-settings", null, FoxMesJson.body()
                .put("mute_until", muteUntil)
                .put("show_previews", showPreviews)
                .put("sound_none", soundNone)
                .put("settings_revision", settingsRevision), NotificationSettings.class);
    }

    public NotificationDefaults notificationDefaults() throws IOException {
        return http.request("GET", "/notification-settings/defaults", null, null, NotificationDefaults.class);
    }

    public NotificationDefaults updateNotificationDefaults(long muteUntil, boolean soundNone, String operationId) throws IOException {
        return http.request("PUT", "/notification-settings/defaults", null, FoxMesJson.body()
                .put("scope", "user")
                .put("mute_until", muteUntil)
                .put("sound_none", soundNone)
                .put("operation_id", operationId), NotificationDefaults.class);
    }

    public void clearHistory(long chatId) throws IOException {
        http.requestString("DELETE", "/chats/" + chatId + "/history", null, null, true);
    }

    public void deleteMessagesByDate(long chatId, long minDate, long maxDate) throws IOException {
        http.requestString("DELETE", "/chats/" + chatId + "/messages/date-range", null,
                FoxMesJson.body().put("min_date", minDate).put("max_date", maxDate), true);
    }

    public void deleteChat(long chatId) throws IOException {
        http.requestString("DELETE", "/chats/" + chatId, null, null, true);
    }

    public void pinMessage(long chatId, int messageId, boolean forEveryone) throws IOException {
        http.requestString("PUT", "/chats/" + chatId + "/pinned", null, FoxMesJson.body()
                .put("message_id", messageId).put("for_everyone", forEveryone).put("operation_id", FoxMesJson.operationId()), true);
    }

    public void unpinMessage(long chatId, int messageId) throws IOException {
        http.requestString("DELETE", "/chats/" + chatId + "/pinned", null, FoxMesJson.body()
                .put("message_id", messageId).put("operation_id", FoxMesJson.operationId()), true);
    }

    public void unpinAllMessages(long chatId) throws IOException {
        http.requestString("DELETE", "/chats/" + chatId + "/pinned", null, FoxMesJson.body()
                .put("all", true).put("operation_id", FoxMesJson.operationId()), true);
    }

    public Meet createMeet(long chatId) throws IOException {
        return http.request("POST", "/chats/" + chatId + "/meet", null, FoxMesJson.body().put("operation_id", FoxMesJson.operationId()), Meet.class);
    }

    public void heartbeatCall(long callId) throws IOException {
        http.requestString("POST", "/calls/" + callId + "/heartbeat", null, null, true);
    }


    public JsonObject callSettings() throws IOException {
        return http.request("GET", "/calls/settings", null, null, JsonObject.class);
    }

    public JsonObject updateCallSettings(boolean p2pAllowed) throws IOException {
        return http.request("PUT", "/calls/settings", null, FoxMesJson.body().put("p2p_allowed", p2pAllowed), JsonObject.class);
    }


    public void registerPushDevice(String token) throws IOException {
        http.request("PUT", "/push-device", null, FoxMesJson.body().put("platform", "android").put("token", token), JsonObject.class);
    }

    public void deletePushDevice() throws IOException {
        http.request("DELETE", "/push-device", null, null, JsonObject.class);
    }


    public ReminderList reminders(long chatId) throws IOException {
        return http.request("GET", "/chats/" + chatId + "/reminders", null, null, ReminderList.class);
    }

    public JsonObject createReminder(long chatId, FoxMesJson.Body body) throws IOException {
        return http.request("POST", "/chats/" + chatId + "/reminders", null, body, JsonObject.class);
    }

    public void updateReminder(int id, String text, List<FoxMesJson.Body> entities, long deliverAt, long expectedRevision) throws IOException {
        http.requestString("PATCH", "/reminders/" + id, null, FoxMesJson.body()
                .put("text", text)
                .putBodies("entities", entities)
                .put("deliver_at", deliverAt)
                .put("expected_revision", expectedRevision)
                .put("operation_id", FoxMesJson.operationId()), true);
    }

    public void deleteReminder(int id) throws IOException {
        http.requestString("DELETE", "/reminders/" + id, query("operation_id", FoxMesJson.operationId()), null, true);
    }

    public void sendReminderNow(int id) throws IOException {
        http.requestString("POST", "/reminders/" + id + "/send-now", null, FoxMesJson.body().put("operation_id", FoxMesJson.operationId()), true);
    }


    public GifList gifs(int limit, Long beforeId) throws IOException {
        return http.request("GET", "/gifs", query("limit", limit, "before_id", beforeId != null && beforeId > 0 ? beforeId : null), null, GifList.class);
    }

    public void saveGif(long chatId, String sha256) throws IOException {
        http.requestString("POST", "/gifs", null, FoxMesJson.body().put("chat_id", chatId).put("sha256", sha256).put("operation_id", FoxMesJson.operationId()), true);
    }

    public void deleteGif(long id) throws IOException {
        http.requestString("DELETE", "/gifs/" + id, null, null, true);
    }


    public LanguageList languages() throws IOException {
        return http.request("GET", "/langs", null, null, LanguageList.class);
    }

    public LanguagePack language(String id) throws IOException {
        return http.request("GET", "/langs/" + id, null, null, LanguagePack.class);
    }


    public static final class Uploaded {
        public final long id;
        public final String poster;

        Uploaded(long id, String poster) {
            this.id = id;
            this.poster = poster;
        }
    }

    public interface UploadProgress {
        void onProgress(long done, long total);
    }

    private static final class UploadSessionLost extends IOException {
    }

    public Uploaded uploadAttachment(File file, String name, String mime, String type, long chatId, UploadProgress progress) throws IOException {
        final long size = file.length();
        if (size <= 0) {
            throw new IOException("Empty attachment");
        }
        Map<String, String> chatQuery = query("chatId", chatId);
        if ("image".equals(type)) {
            RequestBody part = RequestBody.create(file, MediaType.parse(mime));
            MultipartBody body = new MultipartBody.Builder("FoxMes-" + UUID.randomUUID())
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", name, part)
                    .build();
            final long overhead = Math.max(0, body.contentLength() - size);
            String text = http.upload("POST", "/chat/upload/" + type, chatQuery, body, null, new String[1], 300, sent -> {
                if (progress != null) {
                    progress.onProgress(Math.min(size, Math.max(0, sent - overhead)), size);
                }
            });
            return parseUploaded(text);
        }
        int restarts = 0;
        while (true) {
            try {
                return uploadChunkSession(file, size, name, mime, type, chatQuery, progress);
            } catch (UploadSessionLost lost) {
                if (restarts >= 1) {
                    throw new FoxMesHttpException(404, "upload_session_lost", "upload session lost", "");
                }
                restarts++;
                if (progress != null) {
                    progress.onProgress(0, size);
                }
            }
        }
    }

    private static Uploaded parseUploaded(String text) throws IOException {
        JsonObject envelope = FoxMesJson.parse(text, JsonObject.class);
        JsonObject data = FoxMesJson.object(envelope, "data");
        Long id = FoxMesJson.optLong(data, "id");
        if (id == null || id <= 0) {
            throw new IOException("FoxMes upload answered without an id");
        }
        return new Uploaded(id, FoxMesJson.optString(data, "poster"));
    }

    private Uploaded uploadChunkSession(File file, long size, String name, String mime, String type, Map<String, String> chatQuery, UploadProgress progress) throws IOException {
        final long chunkSize = Math.min(Math.max(4L * 1024 * 1024, (size + 1023) / 1024), 32L * 1024 * 1024);
        final int totalChunks = (int) Math.max(1, (size + chunkSize - 1) / chunkSize);
        final String path = "/chat/uploadChunk/" + type;
        final String[] lane = new String[1];

        Map<String, String> initQuery = new HashMap<>(chatQuery);
        initQuery.put("action", "init");
        FoxMesJson.Body initBody = FoxMesJson.body()
                .put("name", name)
                .put("size", size)
                .put("mimeType", mime)
                .put("totalChunks", totalChunks)
                .put("chunkSize", chunkSize);
        String initText = http.upload("POST", path, initQuery, RequestBody.create(initBody.toString(), FoxMesHttp.JSON), null, lane, 300, null);
        final String uploadId = FoxMesJson.optString(FoxMesJson.parse(initText, JsonObject.class), "uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new IOException("FoxMes chunk upload init answered without uploadId");
        }
        final boolean[] received = new boolean[totalChunks];
        try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
            syncStatus(path, uploadId, lane, received, chunkSize, size, progress);
            int resyncs = 0;
            int index = 0;
            while (index < totalChunks) {
                if (received[index]) {
                    index++;
                    continue;
                }
                long lower = index * chunkSize;
                int length = (int) Math.min(chunkSize, size - lower);
                byte[] bytes = new byte[length];
                source.seek(lower);
                source.readFully(bytes);
                final long base = sentBytes(received, chunkSize, size);
                int attempt = 0;
                IOException failure = null;
                while (true) {
                    try {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("X-Upload-ID", uploadId);
                        headers.put("X-Chunk-Index", String.valueOf(index));
                        headers.put("X-Total-Chunks", String.valueOf(totalChunks));
                        http.upload("POST", path, query("action", "chunk"), RequestBody.create(bytes, MediaType.parse("application/octet-stream")),
                                headers, lane, 300, sent -> {
                                    if (progress != null) {
                                        progress.onProgress(Math.min(size, base + sent), size);
                                    }
                                });
                        break;
                    } catch (InterruptedIOException cancelled) {
                        throw cancelled;
                    } catch (IOException error) {
                        if (FoxMesHttpException.isStatus(error, 404)) {
                            throw new UploadSessionLost();
                        }
                        attempt++;
                        if (attempt >= 3) {
                            failure = error;
                            break;
                        }
                        sleep(600L * attempt);
                    }
                }
                if (failure != null) {
                    if (resyncs >= 2) {
                        throw failure;
                    }
                    resyncs++;
                    syncStatus(path, uploadId, lane, received, chunkSize, size, progress);
                    index = 0;
                    continue;
                }
                received[index] = true;
                if (progress != null) {
                    progress.onProgress(sentBytes(received, chunkSize, size), size);
                }
                index++;
            }
            FoxMesJson.Body completeBody = FoxMesJson.body()
                    .put("uploadId", uploadId)
                    .put("totalChunks", totalChunks)
                    .put("name", name)
                    .put("mimeType", mime);
            String completeText;
            try {
                completeText = http.upload("POST", path, query("action", "complete"), RequestBody.create(completeBody.toString(), FoxMesHttp.JSON), null, lane, 300, null);
            } catch (IOException error) {
                if (FoxMesHttpException.isStatus(error, 404)) {
                    throw new UploadSessionLost();
                }
                throw error;
            }
            JsonObject envelope = FoxMesJson.parse(completeText, JsonObject.class);
            Boolean processing = FoxMesJson.optBoolean(envelope, "processing");
            String processId = FoxMesJson.optString(envelope, "processId");
            if (processing != null && processing && processId != null && !processId.isEmpty()) {
                while (!Boolean.TRUE.equals(FoxMesJson.optBoolean(envelope, "done"))) {
                    sleep(2000);
                    String pollText = http.upload("POST", path, query("action", "process", "processId", processId), null, null, lane, 300, null);
                    envelope = FoxMesJson.parse(pollText, JsonObject.class);
                    Long percent = FoxMesJson.optLong(envelope, "progress");
                    if (!Boolean.TRUE.equals(FoxMesJson.optBoolean(envelope, "done")) && percent != null && progress != null) {
                        progress.onProgress(size * Math.min(100, Math.max(0, percent)) / 100, size);
                    }
                }
            }
            return parseUploaded(FoxMesJson.gson.toJson(envelope));
        } catch (UploadSessionLost lost) {
            throw lost;
        } catch (IOException error) {
            abortUpload(path, uploadId, lane);
            throw error;
        }
    }

    private void abortUpload(String path, String uploadId, String[] lane) {
        try {
            http.upload("POST", path, query("action", "abort", "uploadId", uploadId), null, null, lane, 30, null);
        } catch (Exception ignore) {
        }
    }

    private void syncStatus(String path, String uploadId, String[] lane, boolean[] received, long chunkSize, long size, UploadProgress progress) throws IOException {
        try {
            String text = http.upload("GET", path, query("action", "status", "uploadId", uploadId), null, null, lane, 60, null);
            JsonObject envelope = FoxMesJson.parse(text, JsonObject.class);
            if (envelope != null && envelope.has("receivedChunks") && envelope.get("receivedChunks").isJsonArray()) {
                java.util.Arrays.fill(received, false);
                for (com.google.gson.JsonElement element : envelope.getAsJsonArray("receivedChunks")) {
                    int index = element.getAsInt();
                    if (index >= 0 && index < received.length) {
                        received[index] = true;
                    }
                }
                if (progress != null) {
                    progress.onProgress(sentBytes(received, chunkSize, size), size);
                }
            }
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException error) {
            if (FoxMesHttpException.isStatus(error, 404)) {
                throw new UploadSessionLost();
            }
        }
    }

    private static long sentBytes(boolean[] received, long chunkSize, long size) {
        long result = 0;
        for (int i = 0; i < received.length; i++) {
            if (received[i]) {
                result += Math.min(chunkSize, size - i * chunkSize);
            }
        }
        return result;
    }

    private static void sleep(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("upload interrupted");
        }
    }


    public <T> T request(String method, String path, Map<String, String> query, FoxMesJson.Body body, Type type) throws IOException {
        return http.request(method, path, query, body, type);
    }

    public String requestString(String method, String path, Map<String, String> query, FoxMesJson.Body body) throws IOException {
        return http.requestString(method, path, query, body, true);
    }

    public static Map<String, String> q(Object... pairs) {
        return query(pairs);
    }
}
