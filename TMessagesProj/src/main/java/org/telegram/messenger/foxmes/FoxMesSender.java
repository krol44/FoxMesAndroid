package org.telegram.messenger.foxmes;

import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.foxmes.FoxMesModels.ItemsResult;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.Reminder;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class FoxMesSender {

    private static final FoxMesSender[] instances = new FoxMesSender[UserConfig.MAX_ACCOUNT_COUNT];

    public static FoxMesSender getInstance(int account) {
        synchronized (instances) {
            FoxMesSender sender = instances[account];
            if (sender == null) {
                sender = new FoxMesSender(account);
                instances[account] = sender;
            }
            return sender;
        }
    }

    private final int account;

    private FoxMesSender(int account) {
        this.account = account;
    }

    public void flushQueued() {
        FoxMesTransport.getInstance(account).retryWaitingNow();
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_sendMessage.class, FoxMesTransport.Lane.SERIAL, r -> sendMessage(r, runtime));
        transport.on(TLRPC.TL_messages_sendMedia.class, FoxMesTransport.Lane.SERIAL, r -> sendMedia(r, runtime));
        transport.on(TLRPC.TL_messages_sendMultiMedia.class, FoxMesTransport.Lane.SERIAL, r -> sendMultiMedia(r, runtime));
        transport.on(TLRPC.TL_messages_uploadMedia.class, FoxMesTransport.Lane.SERIAL, r -> uploadMedia(r, runtime));
        transport.on(TLRPC.TL_messages_forwardMessages.class, FoxMesTransport.Lane.SERIAL, r -> forwardMessages(r, runtime));
    }

    static String nonce(FoxMesRuntime runtime, long randomId) {
        String seed = "fxl:" + runtime.selfId() + ":" + randomId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().toLowerCase(Locale.US);
    }

    private static Integer replyTo(TLRPC.InputReplyTo replyTo) {
        if (replyTo instanceof TLRPC.TL_inputReplyToMessage) {
            int id = ((TLRPC.TL_inputReplyToMessage) replyTo).reply_to_msg_id;
            return id > 0 ? id : null;
        }
        return null;
    }


    private static void sendMessage(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_sendMessage req = (TLRPC.TL_messages_sendMessage) r.request;
        long peer = FoxMesHandlers.peerUserId(req.peer, runtime);
        long chatId = runtime.ensureChatId(peer);
        String text = req.message != null ? req.message : "";
        String nonce = nonce(runtime, req.random_id);
        FoxMesJson.Body body = FoxMesJson.body()
                .put("text", text)
                .putBodies("entities", FoxMesTL.entitiesOut(req.entities, text))
                .put("reply_to_id", replyTo(req.reply_to))
                .put("client_nonce", nonce)
                .put("silent", req.silent);
        if (req.schedule_date != 0) {
            sendReminder(r, runtime, chatId, peer, body, req.schedule_date, Collections.singletonList(req.random_id));
            return;
        }
        deliver(r, runtime, chatId, peer, body, Collections.singletonList(req.random_id), Collections.singletonList(nonce));
    }

    private static void deliver(FoxMesRequest r, FoxMesRuntime runtime, long chatId, long peer, FoxMesJson.Body body, List<Long> randomIds, List<String> nonces) throws IOException {
        runtime.pendingNonces.addAll(nonces);
        r.quickAck();
        ItemsResult result;
        try {
            result = runtime.api.sendMessage(chatId, body);
        } catch (IOException e) {
            if (!FoxMesHttpException.isTransient(e)) {
                runtime.pendingNonces.removeAll(nonces);
            }
            throw e;
        }
        answer(r, runtime, peer, result.items(), randomIds, nonces);
    }

    private static void answer(FoxMesRequest r, FoxMesRuntime runtime, long peer, List<Message> items, List<Long> randomIds, List<String> nonces) {
        if (items.isEmpty()) {
            runtime.pendingNonces.removeAll(nonces);
            r.fail(500, "FOXMES_SEND_EMPTY");
            return;
        }
        ArrayList<Message> ordered = new ArrayList<>();
        ArrayList<Long> orderedRandom = new ArrayList<>();
        for (int i = 0; i < randomIds.size(); i++) {
            Message match = null;
            String nonce = i < nonces.size() ? nonces.get(i) : null;
            if (nonce != null) {
                for (Message item : items) {
                    if (nonce.equalsIgnoreCase(item.clientNonce)) {
                        match = item;
                        break;
                    }
                }
            }
            if (match == null && i < items.size() && !ordered.contains(items.get(i))) {
                match = items.get(i);
            }
            if (match != null) {
                ordered.add(match);
                orderedRandom.add(randomIds.get(i));
            }
        }
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        userIds.add(peer);
        for (Message message : ordered) {
            FoxMesRuntime.collectUserIds(message, userIds);
        }
        ArrayList<TLRPC.User> users = runtime.tlUsers(userIds);
        ArrayList<TLRPC.Message> messages = runtime.tlMessages(ordered);
        r.replyLate(() -> {
            ArrayList<TLRPC.Update> updates = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID();
                id.id = messages.get(i).id;
                id.random_id = orderedRandom.get(i);
                updates.add(id);
            }
            for (TLRPC.Message message : messages) {
                TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();
                update.message = message;
                updates.add(update);
            }
            TLRPC.TL_updates container = runtime.updates.container(updates, users);
            runtime.pendingNonces.removeAll(nonces);
            return container;
        });
    }


    private static void sendReminder(FoxMesRequest r, FoxMesRuntime runtime, long chatId, long peer, FoxMesJson.Body body, int scheduleDate, List<Long> randomIds) throws IOException {
        if (scheduleDate == FoxMesTL.SCHEDULE_WHEN_ONLINE) {
            body.put("deliver_when_online", true);
        } else {
            body.put("deliver_at", (long) scheduleDate);
        }
        body.put("operation_id", FoxMesJson.operationId());
        JsonObject answer = runtime.api.createReminder(chatId, body);
        JsonArray items = answer != null && answer.has("items") && answer.get("items").isJsonArray() ? answer.getAsJsonArray("items") : new JsonArray();
        if (answer != null && Boolean.TRUE.equals(FoxMesJson.optBoolean(answer, "delivered"))) {
            ArrayList<Message> messages = new ArrayList<>();
            for (JsonElement element : items) {
                messages.add(FoxMesJson.parse(element, Message.class));
            }
            ArrayList<String> nonces = new ArrayList<>();
            for (Message message : messages) {
                nonces.add(message.clientNonce);
            }
            answer(r, runtime, peer, messages, randomIds, nonces);
            return;
        }
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        int index = 0;
        for (JsonElement element : items) {
            Reminder reminder = FoxMesJson.parse(element, Reminder.class);
            runtime.store().putLong("rem_rev_" + reminder.id, reminder.revision);
            if (index < randomIds.size()) {
                TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID();
                id.id = reminder.id;
                id.random_id = randomIds.get(index);
                updates.add(id);
            }
            TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
            update.message = FoxMesTL.scheduledMessage(reminder, runtime);
            updates.add(update);
            index++;
        }
        ArrayList<TLRPC.User> users = runtime.tlUsers(Collections.singletonList(peer));
        r.replyLate(() -> runtime.updates.container(updates, users));
    }


    private static final class Prepared {
        long attachmentId;
        String poster;
        String kind;
        boolean forceFile;
        Long durationMs;
        String waveform;
        boolean spoiler;
        int ttl;

        FoxMesJson.Body meta() {
            return FoxMesJson.body()
                    .put("kind", kind)
                    .put("duration_ms", durationMs)
                    .put("waveform", waveform)
                    .put("spoiler", spoiler ? Boolean.TRUE : null);
        }
    }

    private static String[] typeAndKind(TLRPC.InputMedia media) {
        if (media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            return new String[]{"image", "photo"};
        }
        boolean round = false;
        boolean video = false;
        boolean animated = false;
        boolean voice = false;
        boolean audio = false;
        for (TLRPC.DocumentAttribute attribute : media.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                video = true;
                round = attribute.round_message;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                animated = true;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                audio = true;
                voice = attribute.voice;
            }
        }
        String mime = media.mime_type != null ? media.mime_type.toLowerCase(Locale.US) : "";
        if (media.force_file) {
            return new String[]{"file", "document"};
        }
        if (round) {
            return new String[]{"video", "video_note"};
        }
        if (animated || media.nosound_video || mime.equals("image/gif")) {
            return new String[]{mime.equals("image/gif") ? "gif" : "video", "animation"};
        }
        if (video) {
            return new String[]{"video", "video"};
        }
        if (voice) {
            return new String[]{"audio-message", "voice"};
        }
        if (audio) {
            return new String[]{"audio", "audio"};
        }
        return new String[]{"file", "document"};
    }

    private static String fileName(TLRPC.InputMedia media, String kind) {
        for (TLRPC.DocumentAttribute attribute : media.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename && !TextUtils.isEmpty(attribute.file_name)) {
                return attribute.file_name;
            }
        }
        if (media.file != null && !TextUtils.isEmpty(media.file.name)) {
            return media.file.name;
        }
        switch (kind) {
            case "photo":
                return "photo.jpg";
            case "voice":
                return "voice.ogg";
            case "video":
            case "video_note":
            case "animation":
                return "video.mp4";
            default:
                return "file";
        }
    }

    private static Prepared upload(FoxMesRuntime runtime, TLRPC.InputMedia media, long chatId) throws IOException {
        String[] typeKind = typeAndKind(media);
        Prepared prepared = new Prepared();
        prepared.kind = typeKind[1];
        prepared.forceFile = media.force_file || "document".equals(typeKind[1]);
        prepared.spoiler = media.spoiler;
        prepared.ttl = (media.flags & TLObject.FLAG_1) != 0 || media.ttl_seconds != 0 ? media.ttl_seconds : 0;
        for (TLRPC.DocumentAttribute attribute : media.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                prepared.durationMs = (long) (attribute.duration * 1000);
            } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                prepared.durationMs = (long) attribute.duration * 1000L;
                if (attribute.waveform != null && attribute.waveform.length > 0) {
                    prepared.waveform = Base64.encodeToString(attribute.waveform, Base64.NO_WRAP);
                }
            }
        }
        String mime = "photo".equals(prepared.kind) ? "image/jpeg" : (TextUtils.isEmpty(media.mime_type) ? "application/octet-stream" : media.mime_type);
        String name = fileName(media, prepared.kind);
        File file = FoxMesFiles.assemble(runtime.account, media.file);
        FoxMesFiles.discard(runtime.account, media.thumb);
        try {
            FoxMesApi.Uploaded uploaded = runtime.api.uploadAttachment(file, name, mime, typeKind[0], chatId, null);
            prepared.attachmentId = uploaded.id;
            prepared.poster = uploaded.poster;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        return prepared;
    }

    private static Prepared uploadSavedGif(FoxMesRuntime runtime, FoxMesFileRef ref, long chatId) throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "foxmes_up/" + runtime.account);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, "gif_" + UUID.randomUUID() + ".mp4");
        try {
            try (okhttp3.Response response = runtime.http.download(ref.url, 0, 0)) {
                okhttp3.ResponseBody content = response.body();
                if (!response.isSuccessful() || content == null) {
                    throw new IOException("saved GIF download: HTTP " + response.code());
                }
                try (InputStream in = content.byteStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            String mime = TextUtils.isEmpty(ref.mime) ? "video/mp4" : ref.mime;
            FoxMesApi.Uploaded uploaded = runtime.api.uploadAttachment(file, "animation.mp4", mime, "video", chatId, null);
            Prepared prepared = new Prepared();
            prepared.attachmentId = uploaded.id;
            prepared.poster = uploaded.poster;
            prepared.kind = "animation";
            prepared.durationMs = ref.durationMs > 0 ? ref.durationMs : null;
            return prepared;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static Prepared existing(TLRPC.InputMedia media) {
        byte[] reference = null;
        if (media instanceof TLRPC.TL_inputMediaPhoto && ((TLRPC.TL_inputMediaPhoto) media).id instanceof TLRPC.TL_inputPhoto) {
            reference = ((TLRPC.TL_inputMediaPhoto) media).id.file_reference;
        } else if (media instanceof TLRPC.TL_inputMediaDocument && ((TLRPC.TL_inputMediaDocument) media).id instanceof TLRPC.TL_inputDocument) {
            reference = ((TLRPC.TL_inputMediaDocument) media).id.file_reference;
        }
        FoxMesFileRef ref = FoxMesFileRef.decode(reference);
        if (ref == null || ref.attachmentId == 0 || TextUtils.isEmpty(ref.kind)) {
            return null;
        }
        Prepared prepared = new Prepared();
        prepared.attachmentId = ref.attachmentId;
        prepared.poster = TextUtils.isEmpty(ref.poster) ? null : ref.poster;
        prepared.kind = ref.kind;
        prepared.forceFile = "document".equals(ref.kind);
        prepared.durationMs = ref.durationMs > 0 ? ref.durationMs : null;
        prepared.waveform = TextUtils.isEmpty(ref.waveform) ? null : ref.waveform;
        prepared.spoiler = media.spoiler;
        prepared.ttl = media.ttl_seconds;
        return prepared;
    }

    private static void putMediaTtl(FoxMesJson.Body body, int ttl) {
        if (ttl == 0) {
            return;
        }
        if (ttl == FoxMesTL.TTL_ONCE) {
            body.put("media_ttl_mode", "once");
        } else {
            body.put("media_ttl_mode", "timer");
            body.put("media_ttl_seconds", ttl);
        }
    }

    private static void sendMedia(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_sendMedia req = (TLRPC.TL_messages_sendMedia) r.request;
        long peer = FoxMesHandlers.peerUserId(req.peer, runtime);
        long chatId = runtime.ensureChatId(peer);
        String text = req.message != null ? req.message : "";
        String nonce = nonce(runtime, req.random_id);
        FoxMesJson.Body body = FoxMesJson.body()
                .put("text", text)
                .putBodies("entities", FoxMesTL.entitiesOut(req.entities, text))
                .put("reply_to_id", replyTo(req.reply_to))
                .put("client_nonce", nonce)
                .put("silent", req.silent);
        TLRPC.InputMedia media = req.media;
        if (media instanceof TLRPC.TL_inputMediaGeoPoint || media instanceof TLRPC.TL_inputMediaVenue) {
            TLRPC.InputGeoPoint point = media.geo_point;
            if (!(point instanceof TLRPC.TL_inputGeoPoint)) {
                r.fail(400, "GEO_POINT_INVALID");
                return;
            }
            body.put("location", FoxMesJson.body()
                    .put("latitude", point.lat)
                    .put("longitude", point._long)
                    .put("title", media instanceof TLRPC.TL_inputMediaVenue && !TextUtils.isEmpty(media.title) ? media.title : null)
                    .put("address", media instanceof TLRPC.TL_inputMediaVenue && !TextUtils.isEmpty(media.address) ? media.address : null));
        } else if (media instanceof TLRPC.TL_inputMediaDocument && isCatalogSticker((TLRPC.TL_inputMediaDocument) media)) {
            long id = ((TLRPC.TL_inputMediaDocument) media).id.id & ~FoxMesTL.STICKER_BIT;
            FoxMesModels.ReactionCatalogItem item = runtime.catalogItem(id);
            String alt = item != null && !TextUtils.isEmpty(item.emoji) ? item.emoji : "🙂";
            ArrayList<FoxMesJson.Body> entities = new ArrayList<>();
            entities.add(FoxMesJson.body().put("type", "custom_emoji").put("offset", 0).put("length", alt.length()).put("emoji_id", id));
            body.put("text", alt).putBodies("entities", entities);
        } else if (media instanceof TLRPC.TL_inputMediaDocument && isSavedGif((TLRPC.TL_inputMediaDocument) media)) {
            FoxMesFileRef ref = FoxMesFileRef.decode(((TLRPC.TL_inputMediaDocument) media).id.file_reference);
            attach(body, Collections.singletonList(uploadSavedGif(runtime, ref, chatId)));
        } else if (media instanceof TLRPC.TL_inputMediaUploadedPhoto || media instanceof TLRPC.TL_inputMediaUploadedDocument) {
            Prepared prepared = upload(runtime, media, chatId);
            attach(body, Collections.singletonList(prepared));
            putMediaTtl(body, prepared.ttl);
        } else if (media instanceof TLRPC.TL_inputMediaPhoto || media instanceof TLRPC.TL_inputMediaDocument) {
            Prepared prepared = existing(media);
            if (prepared == null) {
                r.fail(400, "MEDIA_INVALID");
                return;
            }
            attach(body, Collections.singletonList(prepared));
            putMediaTtl(body, prepared.ttl);
        } else if (!(media instanceof TLRPC.TL_inputMediaEmpty) && !(media instanceof TLRPC.TL_inputMediaWebPage)) {
            r.fail(400, "MEDIA_INVALID");
            return;
        }
        if (req.schedule_date != 0) {
            sendReminder(r, runtime, chatId, peer, body, req.schedule_date, Collections.singletonList(req.random_id));
            return;
        }
        deliver(r, runtime, chatId, peer, body, Collections.singletonList(req.random_id), Collections.singletonList(nonce));
    }

    private static boolean isCatalogSticker(TLRPC.TL_inputMediaDocument media) {
        return media.id != null && (media.id.id & FoxMesTL.STICKER_BIT) != 0 && (media.id.id & FoxMesTL.GIF_BIT) == 0;
    }

    private static boolean isSavedGif(TLRPC.TL_inputMediaDocument media) {
        if (media.id == null) {
            return false;
        }
        FoxMesFileRef ref = FoxMesFileRef.decode(media.id.file_reference);
        return ref != null && ref.has(FoxMesFileRef.FLAG_GIF) && !TextUtils.isEmpty(ref.sha256);
    }

    private static void attach(FoxMesJson.Body body, List<Prepared> items) {
        ArrayList<Long> ids = new ArrayList<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> posters = new LinkedHashMap<>();
        boolean forceFile = false;
        for (Prepared item : items) {
            ids.add(item.attachmentId);
            meta.put(String.valueOf(item.attachmentId), item.meta());
            if (!TextUtils.isEmpty(item.poster)) {
                posters.put(String.valueOf(item.attachmentId), item.poster);
            }
            forceFile |= item.forceFile;
        }
        body.putNumbers("attachment_ids", ids)
                .put("force_file", forceFile)
                .putMap("attachment_meta", meta)
                .putMap("attachment_posters", posters);
    }

    private static void uploadMedia(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_uploadMedia req = (TLRPC.TL_messages_uploadMedia) r.request;
        TLRPC.InputMedia media = req.media;
        if (!(media instanceof TLRPC.TL_inputMediaUploadedPhoto) && !(media instanceof TLRPC.TL_inputMediaUploadedDocument)) {
            r.fail(400, "MEDIA_INVALID");
            return;
        }
        long peer = FoxMesHandlers.peerUserId(req.peer, runtime);
        long chatId = runtime.ensureChatId(peer != 0 ? peer : runtime.selfId());
        Prepared prepared = upload(runtime, media, chatId);
        FoxMesFileRef ref = new FoxMesFileRef();
        ref.attachmentId = prepared.attachmentId;
        ref.chatId = chatId;
        ref.kind = prepared.kind;
        ref.durationMs = prepared.durationMs != null ? prepared.durationMs : 0;
        ref.poster = prepared.poster != null ? prepared.poster : "";
        ref.waveform = prepared.waveform != null ? prepared.waveform : "";
        int date = FoxMesHandlers.now();
        if ("photo".equals(prepared.kind)) {
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = prepared.attachmentId;
            photo.access_hash = FoxMesTL.accessHash(photo.id);
            photo.file_reference = ref.encode();
            photo.date = date;
            photo.dc_id = FoxMesTL.DC;
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = "y";
            photo.sizes.add(size);
            TLRPC.TL_messageMediaPhoto result = new TLRPC.TL_messageMediaPhoto();
            result.photo = photo;
            result.flags |= TLObject.FLAG_0;
            result.spoiler = media.spoiler;
            r.reply(FoxMesTL.normalizeMedia(result));
            return;
        }
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = prepared.attachmentId;
        document.access_hash = FoxMesTL.accessHash(document.id);
        document.file_reference = ref.encode();
        document.date = date;
        document.mime_type = TextUtils.isEmpty(media.mime_type) ? "application/octet-stream" : media.mime_type;
        document.dc_id = FoxMesTL.DC;
        document.attributes.addAll(media.attributes);
        TLRPC.TL_messageMediaDocument result = new TLRPC.TL_messageMediaDocument();
        result.document = document;
        result.flags |= TLObject.FLAG_0;
        result.spoiler = media.spoiler;
        r.reply(FoxMesTL.normalizeMedia(result));
    }

    private static void sendMultiMedia(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_sendMultiMedia req = (TLRPC.TL_messages_sendMultiMedia) r.request;
        long peer = FoxMesHandlers.peerUserId(req.peer, runtime);
        long chatId = runtime.ensureChatId(peer);
        String caption = "";
        ArrayList<TLRPC.MessageEntity> captionEntities = new ArrayList<>();
        ArrayList<Long> randomIds = new ArrayList<>();
        ArrayList<String> nonces = new ArrayList<>();
        ArrayList<Prepared> prepared = new ArrayList<>();
        for (TLRPC.TL_inputSingleMedia single : req.multi_media) {
            Prepared item;
            if (single.media instanceof TLRPC.TL_inputMediaUploadedPhoto || single.media instanceof TLRPC.TL_inputMediaUploadedDocument) {
                item = upload(runtime, single.media, chatId);
            } else {
                item = existing(single.media);
            }
            if (item == null) {
                r.fail(400, "MEDIA_INVALID");
                return;
            }
            prepared.add(item);
            randomIds.add(single.random_id);
            nonces.add(nonce(runtime, single.random_id));
            if (caption.isEmpty() && !TextUtils.isEmpty(single.message)) {
                caption = single.message;
                captionEntities = single.entities;
            }
        }
        ArrayList<FoxMesJson.Body> items = new ArrayList<>();
        Map<String, Object> posters = new LinkedHashMap<>();
        boolean forceFile = false;
        for (int i = 0; i < prepared.size(); i++) {
            Prepared item = prepared.get(i);
            items.add(FoxMesJson.body()
                    .put("client_nonce", nonces.get(i))
                    .put("attachment_id", item.attachmentId)
                    .put("meta", item.meta()));
            if (!TextUtils.isEmpty(item.poster)) {
                posters.put(String.valueOf(item.attachmentId), item.poster);
            }
            forceFile |= item.forceFile;
        }
        FoxMesJson.Body body = FoxMesJson.body()
                .put("text", caption)
                .putBodies("entities", FoxMesTL.entitiesOut(captionEntities, caption))
                .put("reply_to_id", replyTo(req.reply_to))
                .put("silent", req.silent)
                .put("force_file", forceFile)
                .putBodies("items", items)
                .putMap("attachment_posters", posters);
        if (req.schedule_date != 0) {
            sendReminder(r, runtime, chatId, peer, body, req.schedule_date, randomIds);
            return;
        }
        deliver(r, runtime, chatId, peer, body, randomIds, nonces);
    }


    private static void forwardMessages(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_messages_forwardMessages req = (TLRPC.TL_messages_forwardMessages) r.request;
        long peer = FoxMesHandlers.peerUserId(req.to_peer, runtime);
        long chatId = runtime.ensureChatId(peer);
        long fromPeer = FoxMesHandlers.peerUserId(req.from_peer, runtime);
        LinkedHashMap<Long, ArrayList<Integer>> bySource = new LinkedHashMap<>();
        LinkedHashMap<Long, ArrayList<Long>> randomBySource = new LinkedHashMap<>();
        for (int i = 0; i < req.id.size(); i++) {
            int id = req.id.get(i);
            long source = runtime.chatOfMessage(id, fromPeer);
            if (source == 0) {
                r.fail(400, "MESSAGE_ID_INVALID");
                return;
            }
            ArrayList<Integer> ids = bySource.get(source);
            if (ids == null) {
                ids = new ArrayList<>();
                bySource.put(source, ids);
                randomBySource.put(source, new ArrayList<>());
            }
            ids.add(id);
            randomBySource.get(source).add(i < req.random_id.size() ? req.random_id.get(i) : 0L);
        }
        Integer videoTimestamp = (req.flags & 1048576) != 0 ? req.video_timestamp : null;
        ArrayList<Message> all = new ArrayList<>();
        ArrayList<Long> randomIds = new ArrayList<>();
        for (Map.Entry<Long, ArrayList<Integer>> entry : bySource.entrySet()) {
            ArrayList<Long> randoms = randomBySource.get(entry.getKey());
            String operationId = UUID.nameUUIDFromBytes(("fxl-forward:" + randoms).getBytes(StandardCharsets.UTF_8)).toString();
            ItemsResult result = runtime.api.forwardMessages(chatId, entry.getKey(), entry.getValue(), req.drop_author, videoTimestamp, operationId);
            List<Message> items = result.items();
            for (int i = 0; i < items.size() && i < randoms.size(); i++) {
                all.add(items.get(i));
                randomIds.add(randoms.get(i));
            }
        }
        ArrayList<String> nonces = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            nonces.add(null);
        }
        answer(r, runtime, peer, all, randomIds, nonces);
    }
}
