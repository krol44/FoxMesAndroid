package org.telegram.messenger.foxmes;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.voip.VoIPPreNotificationService;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;

final class FoxMesCalls {

    private static final long RING_GRACE_MS = 5_000;

    private FoxMesCalls() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_messages_getDhConfig.class, FoxMesTransport.Lane.IO, r -> {
            TLRPC.TL_messages_getDhConfig req = (TLRPC.TL_messages_getDhConfig) r.request;
            JsonObject dto = runtime.api.request("GET", "/calls/dh-config", null, null, JsonObject.class);
            TLRPC.TL_messages_dhConfig config = new TLRPC.TL_messages_dhConfig();
            config.g = (int) FoxMesJson.getLong(dto, "g", 3);
            config.p = decode(FoxMesJson.optString(dto, "p"));
            config.version = (int) FoxMesJson.getLong(dto, "version", 0);
            byte[] random = decode(FoxMesJson.optString(dto, "random"));
            int length = Math.max(req.random_length, 0);
            if (random.length < length) {
                byte[] full = new byte[length];
                new SecureRandom().nextBytes(full);
                System.arraycopy(random, 0, full, 0, random.length);
                random = full;
            }
            config.random = random;
            r.reply(config);
        });
        transport.on(TL_phone.getCallConfig.class, FoxMesTransport.Lane.IO, r -> {
            JsonObject dto = runtime.api.request("GET", "/calls/config", null, null, JsonObject.class);
            TLRPC.TL_dataJSON data = new TLRPC.TL_dataJSON();
            String value = FoxMesJson.optString(dto, "data");
            data.data = value != null ? value : "{}";
            r.reply(data);
        });
        transport.on(TL_phone.requestCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.requestCall req = (TL_phone.requestCall) r.request;
            long peer = FoxMesHandlers.userId(req.user_id, runtime);
            long chatId = runtime.ensureChatId(peer);
            FoxMesJson.Body body = FoxMesJson.body()
                    .put("chat_id", chatId)
                    .put("g_a_hash", encode(req.g_a_hash))
                    .put("video", req.video)
                    .put("operation_id", FoxMesJson.operationId())
                    .put("protocol", protocolBody(req.protocol));
            JsonObject answer = callRequest(r, runtime, "POST", "/calls", body);
            if (answer != null) {
                r.reply(phoneCall(runtime, answer, peer));
            }
        });
        transport.on(TL_phone.receivedCall.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.receivedCall req = (TL_phone.receivedCall) r.request;
            runtime.api.requestString("POST", "/calls/" + req.peer.id + "/received", null, null);
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TL_phone.acceptCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.acceptCall req = (TL_phone.acceptCall) r.request;
            FoxMesJson.Body body = FoxMesJson.body().put("g_b", encode(req.g_b)).put("protocol", protocolBody(req.protocol));
            JsonObject answer = callRequest(r, runtime, "POST", "/calls/" + req.peer.id + "/accept", body);
            if (answer != null) {
                r.reply(phoneCall(runtime, answer, 0));
            }
        });
        transport.on(TL_phone.confirmCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.confirmCall req = (TL_phone.confirmCall) r.request;
            FoxMesJson.Body body = FoxMesJson.body()
                    .put("g_a", encode(req.g_a))
                    .put("key_fingerprint", req.key_fingerprint)
                    .put("protocol", protocolBody(req.protocol));
            JsonObject answer = callRequest(r, runtime, "POST", "/calls/" + req.peer.id + "/confirm", body);
            if (answer != null) {
                r.reply(phoneCall(runtime, answer, 0));
            }
        });
        transport.on(TL_phone.discardCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.discardCall req = (TL_phone.discardCall) r.request;
            String reason = "hangup";
            String slug = null;
            if (req.reason instanceof TLRPC.TL_phoneCallDiscardReasonBusy) {
                reason = "busy";
            } else if (req.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed) {
                reason = "missed";
            } else if (req.reason instanceof TLRPC.TL_phoneCallDiscardReasonDisconnect) {
                reason = "disconnect";
            } else if (req.reason instanceof TLRPC.TL_phoneCallDiscardReasonMigrateConferenceCall) {
                reason = "migrate_conference_call";
                slug = ((TLRPC.TL_phoneCallDiscardReasonMigrateConferenceCall) req.reason).slug;
            }
            try {
                runtime.api.requestString("POST", "/calls/" + req.peer.id + "/discard", null,
                        FoxMesJson.body().put("reason", reason).put("slug", slug).put("duration", req.duration));
            } catch (IOException e) {
                FoxMesLog.w("discard call: " + e.getMessage());
            }
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.sendSignalingData.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.sendSignalingData req = (TL_phone.sendSignalingData) r.request;
            runtime.api.requestString("POST", "/calls/" + req.peer.id + "/signaling", null, FoxMesJson.body().put("data", encode(req.data)));
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TL_phone.setCallRating.class, FoxMesTransport.Lane.INLINE, r -> r.reply(FoxMesHandlers.emptyUpdates()));
        transport.on(TL_phone.saveCallDebug.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_boolTrue()));
        transport.on(TL_phone.saveCallLog.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_boolTrue()));
    }

    private static JsonObject callRequest(FoxMesRequest r, FoxMesRuntime runtime, String method, String path, FoxMesJson.Body body) throws IOException {
        try {
            return runtime.api.request(method, path, null, body, JsonObject.class);
        } catch (FoxMesHttpException e) {
            if (e.isTransient()) {
                throw e;
            }
            String code = e.code != null ? e.code : "";
            switch (code) {
                case "call_peer_unsupported":
                case "call_version_mismatch":
                    r.fail(400, "PARTICIPANT_VERSION_OUTDATED");
                    break;
                case "call_peer_refuses":
                    r.fail(403, "USER_PRIVACY_RESTRICTED");
                    break;
                case "call_busy":
                    r.fail(403, "CALL_BUSY");
                    break;
                case "call_peer_offline":
                    r.fail(406, e.serverMessage != null ? e.serverMessage : "The user is offline.");
                    break;
                default:
                    r.fail(e.status, !code.isEmpty() ? code : (e.serverMessage != null ? e.serverMessage : "FOXMES_CALL_FAILED"));
                    break;
            }
            return null;
        }
    }

    private static TL_phone.TL_phone_phoneCall phoneCall(FoxMesRuntime runtime, JsonObject answer, long peer) {
        JsonObject dto = FoxMesJson.object(answer, "call");
        if (dto == null) {
            dto = answer;
        }
        TL_phone.TL_phone_phoneCall result = new TL_phone.TL_phone_phoneCall();
        result.phone_call = call(dto);
        ArrayList<Long> ids = new ArrayList<>();
        if (peer != 0) {
            ids.add(peer);
        }
        ids.add(FoxMesJson.getLong(dto, "admin_id", 0));
        ids.add(FoxMesJson.getLong(dto, "participant_id", 0));
        result.users.addAll(runtime.tlUsers(ids));
        return result;
    }


    static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static String encode(byte[] bytes) {
        return bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : "";
    }

    static FoxMesJson.Body protocolBody(TL_phone.PhoneCallProtocol protocol) {
        FoxMesJson.Body body = FoxMesJson.body();
        if (protocol == null) {
            return body;
        }
        return body.put("min_layer", protocol.min_layer)
                .put("max_layer", protocol.max_layer)
                .putStrings("library_versions", protocol.library_versions)
                .put("udp_p2p", true)
                .put("udp_reflector", true);
    }

    static TL_phone.TL_phoneCallProtocol protocol(JsonObject dto) {
        TL_phone.TL_phoneCallProtocol protocol = new TL_phone.TL_phoneCallProtocol();
        protocol.udp_p2p = dto == null || !Boolean.FALSE.equals(FoxMesJson.optBoolean(dto, "udp_p2p"));
        protocol.udp_reflector = dto == null || !Boolean.FALSE.equals(FoxMesJson.optBoolean(dto, "udp_reflector"));
        protocol.min_layer = (int) FoxMesJson.getLong(dto, "min_layer", 65);
        protocol.max_layer = (int) FoxMesJson.getLong(dto, "max_layer", 92);
        if (dto != null && dto.has("library_versions") && dto.get("library_versions").isJsonArray()) {
            for (JsonElement version : dto.getAsJsonArray("library_versions")) {
                protocol.library_versions.add(version.getAsString());
            }
        }
        return protocol;
    }

    static TL_phone.PhoneCall call(JsonObject dto) {
        String kind = FoxMesJson.optString(dto, "_");
        if (kind == null) {
            kind = "";
        }
        long id = FoxMesJson.getLong(dto, "id", 0);
        long accessHash = FoxMesJson.getLong(dto, "access_hash", 0);
        int date = (int) FoxMesJson.getLong(dto, "date", 0);
        long admin = FoxMesJson.getLong(dto, "admin_id", 0);
        long participant = FoxMesJson.getLong(dto, "participant_id", 0);
        boolean video = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "video"));
        JsonObject protocolDto = FoxMesJson.object(dto, "protocol");
        switch (kind) {
            case "phoneCallWaiting": {
                TL_phone.TL_phoneCallWaiting call = new TL_phone.TL_phoneCallWaiting();
                fill(call, id, accessHash, date, admin, participant, video);
                call.protocol = protocol(protocolDto);
                Long receive = FoxMesJson.optLong(dto, "receive_date");
                if (receive != null && receive > 0) {
                    call.receive_date = (int) (long) receive;
                    call.flags |= 1;
                }
                return call;
            }
            case "phoneCallRequested": {
                TL_phone.phoneCallRequested call = new TL_phone.phoneCallRequested();
                fill(call, id, accessHash, date, admin, participant, video);
                call.g_a_hash = decode(FoxMesJson.optString(dto, "g_a_hash"));
                call.protocol = protocol(protocolDto);
                return call;
            }
            case "phoneCallAccepted": {
                TL_phone.TL_phoneCallAccepted call = new TL_phone.TL_phoneCallAccepted();
                fill(call, id, accessHash, date, admin, participant, video);
                call.g_b = decode(FoxMesJson.optString(dto, "g_b"));
                call.protocol = protocol(protocolDto);
                return call;
            }
            case "phoneCall": {
                TL_phone.TL_phoneCall call = new TL_phone.TL_phoneCall();
                fill(call, id, accessHash, date, admin, participant, video);
                call.g_a_or_b = decode(FoxMesJson.optString(dto, "g_a_or_b"));
                call.key_fingerprint = FoxMesJson.getLong(dto, "key_fingerprint", 0);
                call.protocol = protocol(protocolDto);
                call.start_date = (int) FoxMesJson.getLong(dto, "start_date", 0);
                call.p2p_allowed = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "p2p_allowed"));
                call.conference_supported = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "conference_supported"));
                JsonArray connections = dto.has("connections") && dto.get("connections").isJsonArray() ? dto.getAsJsonArray("connections") : new JsonArray();
                for (JsonElement element : connections) {
                    JsonObject connectionDto = element.getAsJsonObject();
                    TLRPC.TL_phoneConnectionWebrtc connection = new TLRPC.TL_phoneConnectionWebrtc();
                    connection.id = FoxMesJson.getLong(connectionDto, "id", 1);
                    connection.ip = orEmpty(FoxMesJson.optString(connectionDto, "ip"));
                    connection.ipv6 = orEmpty(FoxMesJson.optString(connectionDto, "ipv6"));
                    connection.port = (int) FoxMesJson.getLong(connectionDto, "port", 0);
                    connection.username = orEmpty(FoxMesJson.optString(connectionDto, "username"));
                    connection.password = orEmpty(FoxMesJson.optString(connectionDto, "password"));
                    connection.turn = Boolean.TRUE.equals(FoxMesJson.optBoolean(connectionDto, "turn"));
                    connection.stun = Boolean.TRUE.equals(FoxMesJson.optBoolean(connectionDto, "stun"));
                    call.connections.add(connection);
                }
                if (call.connections.isEmpty()) {
                    FoxMesLog.e("phoneCall " + id + " without connections");
                }
                return call;
            }
            default: {
                TL_phone.TL_phoneCallDiscarded call = new TL_phone.TL_phoneCallDiscarded();
                call.id = id;
                call.video = video;
                String reasonName = FoxMesJson.optString(dto, "reason");
                TLRPC.PhoneCallDiscardReason reason;
                if ("migrate_conference_call".equals(reasonName)) {
                    String slug = FoxMesJson.optString(dto, "slug");
                    if (slug != null && !slug.isEmpty()) {
                        TLRPC.TL_phoneCallDiscardReasonMigrateConferenceCall migrate = new TLRPC.TL_phoneCallDiscardReasonMigrateConferenceCall();
                        migrate.slug = slug;
                        reason = migrate;
                    } else {
                        reason = new TLRPC.TL_phoneCallDiscardReasonHangup();
                    }
                } else {
                    reason = FoxMesTL.discardReason(reasonName);
                }
                if (reason != null) {
                    call.reason = reason;
                    call.flags |= 1;
                }
                Long duration = FoxMesJson.optLong(dto, "duration");
                if (duration != null && duration > 0) {
                    call.duration = (int) (long) duration;
                    call.flags |= 2;
                }
                call.need_rating = false;
                call.need_debug = false;
                return call;
            }
        }
    }

    private static void fill(TL_phone.PhoneCall call, long id, long accessHash, int date, long admin, long participant, boolean video) {
        call.id = id;
        call.access_hash = accessHash;
        call.date = date;
        call.admin_id = admin;
        call.participant_id = participant;
        call.video = video;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }


    static void handleEvent(FoxMesRuntime runtime, String type, JsonObject data) {
        if ("call.signaling".equals(type)) {
            TL_update.TL_updatePhoneCallSignalingData update = new TL_update.TL_updatePhoneCallSignalingData();
            update.phone_call_id = FoxMesJson.getLong(data, "call_id", 0);
            update.data = decode(FoxMesJson.optString(data, "data"));
            runtime.updates.dispatch(FoxMesHandlers.single(update), new ArrayList<>(), null);
            return;
        }
        JsonObject dto = FoxMesJson.object(data, "call");
        if (dto == null) {
            dto = data;
        }
        TL_update.TL_updatePhoneCall update = new TL_update.TL_updatePhoneCall();
        update.phone_call = call(dto);
        ArrayList<Long> ids = new ArrayList<>();
        ids.add(FoxMesJson.getLong(dto, "admin_id", 0));
        ids.add(FoxMesJson.getLong(dto, "participant_id", 0));
        ids.removeAll(Collections.singletonList(0L));
        ArrayList<TLRPC.User> users = runtime.tlUsers(ids);
        runtime.updates.dispatch(FoxMesHandlers.single(update), users, null);
        if (update.phone_call instanceof TL_phone.phoneCallRequested) {
            scheduleRingTimeout(runtime, update.phone_call);
        }
    }


    private static void scheduleRingTimeout(FoxMesRuntime runtime, TL_phone.PhoneCall call) {
        long timeout = MessagesController.getInstance(runtime.account).callRingTimeout + RING_GRACE_MS;
        long id = call.id;
        boolean video = call.video;
        AndroidUtilities.runOnUIThread(() -> {
            if (isRinging(runtime.account, id)) {
                FoxMesLog.w("call " + id + ": ring time is up without a word from the server");
                dispatchDiscarded(runtime, id, video, "missed");
            }
        }, timeout);
    }

    static void handleDiscardPush(FoxMesRuntime runtime, long id, boolean video, String reason) {
        AndroidUtilities.runOnUIThread(() -> {
            if (isShowing(runtime.account, id)) {
                dispatchDiscarded(runtime, id, video, reason);
            }
        });
    }

    static boolean isRinging(int account, long id) {
        TL_phone.PhoneCall pending = VoIPPreNotificationService.pendingCall;
        if (pending != null && pending.id == id) {
            return true;
        }
        TL_phone.PhoneCall starting = VoIPService.callIShouldHavePutIntoIntent;
        if (starting != null && starting.id == id) {
            return true;
        }
        VoIPService service = VoIPService.getSharedInstance();
        return service != null && service.getAccount() == account && service.getCallID() == id
                && service.getCallState() == VoIPService.STATE_WAITING_INCOMING;
    }

    private static boolean isShowing(int account, long id) {
        if (isRinging(account, id)) {
            return true;
        }
        VoIPService service = VoIPService.getSharedInstance();
        return service != null && service.getAccount() == account && service.getCallID() == id;
    }

    private static void dispatchDiscarded(FoxMesRuntime runtime, long id, boolean video, String reasonName) {
        TL_phone.TL_phoneCallDiscarded call = new TL_phone.TL_phoneCallDiscarded();
        call.id = id;
        call.video = video;
        TLRPC.PhoneCallDiscardReason reason = FoxMesTL.discardReason(reasonName);
        if (reason != null) {
            call.reason = reason;
            call.flags |= 1;
        }
        TL_update.TL_updatePhoneCall update = new TL_update.TL_updatePhoneCall();
        update.phone_call = call;
        runtime.updates.dispatch(FoxMesHandlers.single(update), new ArrayList<>(), null);
    }
}
