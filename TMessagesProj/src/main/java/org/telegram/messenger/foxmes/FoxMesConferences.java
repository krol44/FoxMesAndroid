package org.telegram.messenger.foxmes;

import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class FoxMesConferences {

    private static final ConcurrentHashMap<String, long[]> slugs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> accessHashes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, String> inviteLinks = new ConcurrentHashMap<>();

    private FoxMesConferences() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TL_phone.createConferenceCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            JsonObject answer = runtime.api.request("POST", "/conferences", null, FoxMesJson.body(), JsonObject.class);
            rememberUsers(runtime, answer);
            TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
            update.call = call(FoxMesJson.object(answer, "call"));
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            updates.updates.add(update);
            r.reply(updates);
        });
        transport.on(TL_phone.getGroupCall.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.getGroupCall req = (TL_phone.getGroupCall) r.request;
            JsonObject answer;
            try {
                answer = fetch(runtime, req.call);
            } catch (FoxMesHttpException e) {
                if (e.status == 404) {
                    TL_phone.groupCall result = new TL_phone.groupCall();
                    TLRPC.TL_groupCallDiscarded discarded = new TLRPC.TL_groupCallDiscarded();
                    discarded.id = req.call instanceof TLRPC.TL_inputGroupCall ? req.call.id : 0;
                    discarded.access_hash = req.call instanceof TLRPC.TL_inputGroupCall ? req.call.access_hash : 0;
                    result.call = discarded;
                    result.participants_next_offset = "";
                    r.reply(result);
                    return;
                }
                throw e;
            }
            r.reply(phoneGroupCall(runtime, answer));
        });
        transport.on(TL_phone.joinGroupCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.joinGroupCall req = (TL_phone.joinGroupCall) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/join", null, FoxMesJson.body()
                    .put("access_hash", String.valueOf(call[1]))
                    .put("muted", req.muted)
                    .put("video_stopped", req.video_stopped)
                    .put("public_key", req.public_key != null ? FoxMesCalls.encode(req.public_key) : null)
                    .put("block", req.block != null ? FoxMesCalls.encode(req.block) : null)
                    .put("params", req.params != null ? req.params.data : null), JsonObject.class);
            r.reply(joinUpdates(runtime, answer));
        });
        transport.on(TL_phone.getGroupParticipants.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.getGroupParticipants req = (TL_phone.getGroupParticipants) r.request;
            long[] call = resolve(runtime, req.call);
            ArrayList<Long> ids = new ArrayList<>();
            for (TLRPC.InputPeer peer : req.ids) {
                ids.add(FoxMesHandlers.peerUserId(peer, runtime));
            }
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/participants", null, FoxMesJson.body()
                    .putNumbers("ids", ids)
                    .putNumbers("sources", req.sources)
                    .put("offset", req.offset != null ? req.offset : "")
                    .put("limit", req.limit), JsonObject.class);
            ArrayList<TLRPC.User> users = rememberUsers(runtime, answer);
            TL_phone.groupParticipants result = new TL_phone.groupParticipants();
            result.participants.addAll(participants(answer));
            result.count = (int) FoxMesJson.getLong(answer, "count", result.participants.size());
            result.next_offset = "";
            result.version = (int) FoxMesJson.getLong(answer, "version", 0);
            result.users.addAll(users);
            r.reply(result);
        });
        transport.on(TL_phone.editGroupCallParticipant.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.editGroupCallParticipant req = (TL_phone.editGroupCallParticipant) r.request;
            long[] call = resolve(runtime, req.call);
            FoxMesJson.Body body = FoxMesJson.body().put("user_id", FoxMesHandlers.peerUserId(req.participant, runtime));
            if ((req.flags & 1) != 0) {
                body.put("muted", req.muted);
            }
            if ((req.flags & 2) != 0) {
                body.put("volume", req.volume);
            }
            if ((req.flags & 4) != 0) {
                body.put("raise_hand", req.raise_hand);
            }
            if ((req.flags & 8) != 0) {
                body.put("video_stopped", req.video_stopped);
            }
            if ((req.flags & 16) != 0) {
                body.put("video_paused", req.video_paused);
            }
            if ((req.flags & 32) != 0) {
                body.put("presentation_paused", req.presentation_paused);
            }
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/participants/edit", null, body, JsonObject.class);
            r.reply(participantsUpdates(runtime, call, answer));
        });
        transport.on(TL_phone.toggleGroupCallSettings.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.toggleGroupCallSettings req = (TL_phone.toggleGroupCallSettings) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/settings", null, FoxMesJson.body()
                    .put("join_muted", req.join_muted)
                    .put("messages_enabled", req.messages_enabled), JsonObject.class);
            r.reply(callUpdates(answer));
        });
        transport.on(TL_phone.leaveGroupCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.leaveGroupCall req = (TL_phone.leaveGroupCall) r.request;
            long[] call = resolve(runtime, req.call);
            runtime.api.requestString("POST", "/conferences/" + call[0] + "/leave", null, FoxMesJson.body().put("source", req.source));
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.discardGroupCall.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.discardGroupCall req = (TL_phone.discardGroupCall) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/discard", null, FoxMesJson.body(), JsonObject.class);
            r.reply(callUpdates(answer));
        });
        transport.on(TL_phone.checkGroupCall.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.checkGroupCall req = (TL_phone.checkGroupCall) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/check", null, FoxMesJson.body().putNumbers("sources", req.sources), JsonObject.class);
            Vector<Vector.Int> result = new Vector<>(Vector.Int::TLDeserialize);
            if (answer != null && answer.has("sources") && answer.get("sources").isJsonArray()) {
                for (JsonElement element : answer.getAsJsonArray("sources")) {
                    result.objects.add(new Vector.Int(element.getAsInt()));
                }
            }
            r.reply(result);
        });
        transport.on(TL_phone.joinGroupCallPresentation.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.joinGroupCallPresentation req = (TL_phone.joinGroupCallPresentation) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/presentation", null,
                    FoxMesJson.body().put("params", req.params != null ? req.params.data : ""), JsonObject.class);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            TL_update.TL_updateGroupCallConnection connection = new TL_update.TL_updateGroupCallConnection();
            connection.presentation = true;
            TLRPC.TL_dataJSON params = new TLRPC.TL_dataJSON();
            String value = FoxMesJson.optString(answer, "params");
            params.data = value != null ? value : "{}";
            connection.params = params;
            updates.updates.add(connection);
            if (answer != null && answer.has("participants")) {
                updates.updates.add(participantsUpdate(call, answer));
                updates.users.addAll(rememberUsers(runtime, answer));
            }
            r.reply(updates);
        });
        transport.on(TL_phone.leaveGroupCallPresentation.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.leaveGroupCallPresentation req = (TL_phone.leaveGroupCallPresentation) r.request;
            long[] call = resolve(runtime, req.call);
            runtime.api.requestString("DELETE", "/conferences/" + call[0] + "/presentation", null, null);
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.getGroupCallChainBlocks.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.getGroupCallChainBlocks req = (TL_phone.getGroupCallChainBlocks) r.request;
            long[] call = resolve(runtime, req.call);
            JsonObject answer = runtime.api.request("GET", "/conferences/" + call[0] + "/chain/" + req.sub_chain_id,
                    FoxMesApi.q("offset", req.offset, "limit", req.limit), null, JsonObject.class);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            updates.updates.add(chainUpdate(call, answer, req.sub_chain_id));
            r.reply(updates);
        });
        transport.on(TL_phone.sendConferenceCallBroadcast.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.sendConferenceCallBroadcast req = (TL_phone.sendConferenceCallBroadcast) r.request;
            long[] call = resolve(runtime, req.call);
            runtime.api.requestString("POST", "/conferences/" + call[0] + "/chain/1", null, FoxMesJson.body().put("block", FoxMesCalls.encode(req.block)));
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.sendGroupCallEncryptedMessage.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.sendGroupCallEncryptedMessage req = (TL_phone.sendGroupCallEncryptedMessage) r.request;
            long[] call = resolve(runtime, req.call);
            runtime.api.requestString("POST", "/conferences/" + call[0] + "/messages", null, FoxMesJson.body().put("data", FoxMesCalls.encode(req.encrypted_message)));
            r.reply(new TLRPC.TL_boolTrue());
        });
        transport.on(TL_phone.inviteConferenceCallParticipant.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.inviteConferenceCallParticipant req = (TL_phone.inviteConferenceCallParticipant) r.request;
            long[] call = resolve(runtime, req.call);
            long user = FoxMesHandlers.userId(req.user_id, runtime);
            JsonObject answer = runtime.api.request("POST", "/conferences/" + call[0] + "/invite", null,
                    FoxMesJson.body().put("user_id", user).put("video", req.video), JsonObject.class);
            TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
            JsonObject messageDto = FoxMesJson.object(answer, "message");
            if (messageDto != null) {
                Message message = FoxMesJson.parse(messageDto, Message.class);
                runtime.store().putMessage(message.id, message.chatId, message.revision, message.reactionRevision);
                TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID();
                id.id = message.id;
                id.random_id = 0;
                updates.updates.add(id);
            }
            r.reply(updates);
        });
        transport.on(TL_phone.deleteConferenceCallParticipants.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.deleteConferenceCallParticipants req = (TL_phone.deleteConferenceCallParticipants) r.request;
            long[] call = resolve(runtime, req.call);
            runtime.api.requestString("POST", "/conferences/" + call[0] + "/participants/delete", null, FoxMesJson.body()
                    .putNumbers("ids", req.ids)
                    .put("only_left", req.only_left)
                    .put("kick", req.kick)
                    .put("block", req.block != null ? FoxMesCalls.encode(req.block) : ""));
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.declineConferenceCallInvite.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_phone.declineConferenceCallInvite req = (TL_phone.declineConferenceCallInvite) r.request;
            runtime.api.requestString("POST", "/conferences/invites/" + req.msg_id + "/decline", null, FoxMesJson.body());
            r.reply(FoxMesHandlers.emptyUpdates());
        });
        transport.on(TL_phone.exportGroupCallInvite.class, FoxMesTransport.Lane.IO, r -> {
            TL_phone.exportGroupCallInvite req = (TL_phone.exportGroupCallInvite) r.request;
            long[] call = resolve(runtime, req.call);
            String link = inviteLinks.get(call[0]);
            if (link == null) {
                r.fail(400, "GROUPCALL_INVALID");
                return;
            }
            TL_phone.exportedGroupCallInvite result = new TL_phone.exportedGroupCallInvite();
            result.link = link;
            r.reply(result);
        });
    }


    private static long[] resolve(FoxMesRuntime runtime, TLRPC.InputGroupCall input) throws IOException {
        if (input instanceof TLRPC.TL_inputGroupCall) {
            return new long[]{input.id, input.access_hash};
        }
        if (input instanceof TLRPC.TL_inputGroupCallSlug) {
            long[] cached = slugs.get(input.slug);
            if (cached != null) {
                return cached;
            }
        }
        JsonObject answer = fetch(runtime, input);
        JsonObject call = FoxMesJson.object(answer, "call");
        return new long[]{FoxMesJson.getLong(call, "id", 0), FoxMesJson.getLong(call, "access_hash", 0)};
    }

    private static JsonObject fetch(FoxMesRuntime runtime, TLRPC.InputGroupCall input) throws IOException {
        JsonObject answer;
        if (input instanceof TLRPC.TL_inputGroupCallSlug) {
            answer = runtime.api.request("GET", "/conferences/by-slug/" + Uri.encode(input.slug), null, null, JsonObject.class);
            JsonObject call = FoxMesJson.object(answer, "call");
            slugs.put(input.slug, new long[]{FoxMesJson.getLong(call, "id", 0), FoxMesJson.getLong(call, "access_hash", 0)});
        } else if (input instanceof TLRPC.TL_inputGroupCallInviteMessage) {
            answer = runtime.api.request("GET", "/conferences/by-invite/" + input.msg_id, null, null, JsonObject.class);
        } else {
            answer = runtime.api.request("GET", "/conferences/" + input.id, null, null, JsonObject.class);
        }
        return answer;
    }


    private static ArrayList<TLRPC.User> rememberUsers(FoxMesRuntime runtime, JsonObject answer) {
        ArrayList<Long> ids = new ArrayList<>();
        if (answer != null && answer.has("users") && answer.get("users").isJsonArray()) {
            for (JsonElement element : answer.getAsJsonArray("users")) {
                User user = FoxMesJson.parse(element, User.class);
                runtime.rememberUser(user);
                ids.add(user.id);
            }
        }
        if (answer != null && answer.has("participants") && answer.get("participants").isJsonArray()) {
            for (JsonElement element : answer.getAsJsonArray("participants")) {
                ids.add(FoxMesJson.getLong(element.getAsJsonObject(), "user_id", 0));
            }
        }
        ids.removeAll(Collections.singletonList(0L));
        return ids.isEmpty() ? new ArrayList<>() : runtime.tlUsers(ids);
    }

    private static String shareLink(String inviteLink) {
        if (TextUtils.isEmpty(inviteLink)) {
            return null;
        }
        String slug = null;
        try {
            Uri uri = Uri.parse(inviteLink);
            slug = uri.getQueryParameter("slug");
            if (slug == null) {
                List<String> segments = uri.getPathSegments();
                slug = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            }
        } catch (Exception ignore) {
        }
        return TextUtils.isEmpty(slug) ? null : "https://" + FoxMesConfiguration.internalLinksDomain + "/call/" + slug;
    }

    static TLRPC.GroupCall call(JsonObject dto) {
        long id = FoxMesJson.getLong(dto, "id", 0);
        long accessHash = FoxMesJson.getLong(dto, "access_hash", 0);
        accessHashes.put(id, accessHash);
        if (Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "discarded"))) {
            TLRPC.TL_groupCallDiscarded discarded = new TLRPC.TL_groupCallDiscarded();
            discarded.id = id;
            discarded.access_hash = accessHash;
            discarded.duration = (int) FoxMesJson.getLong(dto, "duration", 0);
            return discarded;
        }
        TLRPC.TL_groupCall call = new TLRPC.TL_groupCall();
        call.id = id;
        call.access_hash = accessHash;
        call.conference = true;
        call.join_muted = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "join_muted"));
        call.can_change_join_muted = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "can_change_join_muted"));
        call.join_date_asc = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "join_date_asc"));
        call.can_start_video = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "can_start_video"));
        call.creator = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "creator"));
        call.messages_enabled = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "messages_enabled"));
        call.can_change_messages_enabled = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "can_change_messages_enabled"));
        call.participants_count = (int) FoxMesJson.getLong(dto, "participants_count", 0);
        call.unmuted_video_limit = (int) FoxMesJson.getLong(dto, "unmuted_video_limit", 0);
        call.version = (int) FoxMesJson.getLong(dto, "version", 0);
        String link = shareLink(FoxMesJson.optString(dto, "invite_link"));
        if (link != null) {
            call.invite_link = link;
            call.flags |= TLObject.FLAG_16;
            inviteLinks.put(id, link);
        }
        return call;
    }

    private static TLRPC.TL_groupCallParticipantVideo video(JsonObject dto) {
        TLRPC.TL_groupCallParticipantVideo video = new TLRPC.TL_groupCallParticipantVideo();
        video.paused = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "paused"));
        String endpoint = FoxMesJson.optString(dto, "endpoint");
        video.endpoint = endpoint != null ? endpoint : "";
        Long audio = FoxMesJson.optLong(dto, "audio_source");
        if (audio != null) {
            video.audio_source = (int) (long) audio;
            video.flags |= 2;
        }
        if (video.paused) {
            video.flags |= 1;
        }
        if (dto.has("source_groups") && dto.get("source_groups").isJsonArray()) {
            for (JsonElement element : dto.getAsJsonArray("source_groups")) {
                JsonObject groupDto = element.getAsJsonObject();
                TLRPC.TL_groupCallParticipantVideoSourceGroup group = new TLRPC.TL_groupCallParticipantVideoSourceGroup();
                String semantics = FoxMesJson.optString(groupDto, "semantics");
                group.semantics = semantics != null ? semantics : "";
                if (groupDto.has("sources") && groupDto.get("sources").isJsonArray()) {
                    for (JsonElement source : groupDto.getAsJsonArray("sources")) {
                        group.sources.add(source.getAsInt());
                    }
                }
                video.source_groups.add(group);
            }
        }
        return video;
    }

    static TLRPC.TL_groupCallParticipant participant(JsonObject dto) {
        TLRPC.TL_groupCallParticipant participant = new TLRPC.TL_groupCallParticipant();
        participant.peer = FoxMesTL.peer(FoxMesJson.getLong(dto, "user_id", 0));
        participant.date = (int) FoxMesJson.getLong(dto, "date", 0);
        participant.source = (int) FoxMesJson.getLong(dto, "source", 0);
        participant.muted = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "muted"));
        participant.left = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "left"));
        participant.can_self_unmute = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "can_self_unmute"));
        participant.just_joined = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "just_joined"));
        participant.versioned = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "versioned"));
        participant.self = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "self"));
        participant.video_joined = Boolean.TRUE.equals(FoxMesJson.optBoolean(dto, "video_joined"));
        long activeDate = FoxMesJson.getLong(dto, "active_date", 0);
        if (activeDate != 0) {
            participant.active_date = (int) activeDate;
            participant.flags |= TLObject.FLAG_3;
        }
        long volume = FoxMesJson.getLong(dto, "volume", 0);
        if (volume != 0) {
            participant.volume = (int) volume;
            participant.flags |= TLObject.FLAG_7;
        }
        long raiseHand = FoxMesJson.getLong(dto, "raise_hand_rating", 0);
        if (raiseHand != 0) {
            participant.raise_hand_rating = raiseHand;
            participant.flags |= TLObject.FLAG_13;
        }
        JsonObject videoDto = FoxMesJson.object(dto, "video");
        if (videoDto != null) {
            participant.video = video(videoDto);
            participant.flags |= TLObject.FLAG_6;
        }
        JsonObject presentationDto = FoxMesJson.object(dto, "presentation");
        if (presentationDto != null) {
            participant.presentation = video(presentationDto);
            participant.flags |= TLObject.FLAG_14;
        }
        return participant;
    }

    private static ArrayList<TLRPC.GroupCallParticipant> participants(JsonObject answer) {
        ArrayList<TLRPC.GroupCallParticipant> result = new ArrayList<>();
        if (answer != null && answer.has("participants") && answer.get("participants").isJsonArray()) {
            for (JsonElement element : answer.getAsJsonArray("participants")) {
                result.add(participant(element.getAsJsonObject()));
            }
        }
        return result;
    }

    private static TLRPC.TL_inputGroupCall inputCall(long id, long accessHash) {
        TLRPC.TL_inputGroupCall input = new TLRPC.TL_inputGroupCall();
        input.id = id;
        input.access_hash = accessHash;
        return input;
    }

    private static TL_update.TL_updateGroupCallParticipants participantsUpdate(long[] call, JsonObject answer) {
        TL_update.TL_updateGroupCallParticipants update = new TL_update.TL_updateGroupCallParticipants();
        update.call = inputCall(call[0], call[1]);
        update.participants.addAll(participants(answer));
        update.version = (int) FoxMesJson.getLong(answer, "version", 0);
        return update;
    }

    private static TLRPC.TL_updates participantsUpdates(FoxMesRuntime runtime, long[] call, JsonObject answer) {
        TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
        if (answer != null && answer.has("participants")) {
            updates.users.addAll(rememberUsers(runtime, answer));
            updates.updates.add(participantsUpdate(call, answer));
        }
        return updates;
    }

    private static TLRPC.TL_updates callUpdates(JsonObject answer) {
        TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
        JsonObject callDto = FoxMesJson.object(answer, "call");
        if (callDto != null) {
            TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
            update.call = call(callDto);
            updates.updates.add(update);
        }
        return updates;
    }

    private static TL_update.TL_updateGroupCallChainBlocks chainUpdate(long[] call, JsonObject chain, int defaultSubChain) {
        TL_update.TL_updateGroupCallChainBlocks update = new TL_update.TL_updateGroupCallChainBlocks();
        update.call = inputCall(call[0], call[1]);
        update.sub_chain_id = (int) FoxMesJson.getLong(chain, "sub_chain_id", defaultSubChain);
        update.next_offset = (int) FoxMesJson.getLong(chain, "next_offset", 0);
        if (chain != null && chain.has("blocks") && chain.get("blocks").isJsonArray()) {
            JsonArray blocks = chain.getAsJsonArray("blocks");
            for (JsonElement block : blocks) {
                update.blocks.add(FoxMesCalls.decode(block.getAsString()));
            }
        }
        return update;
    }

    private static TL_phone.groupCall phoneGroupCall(FoxMesRuntime runtime, JsonObject answer) {
        TL_phone.groupCall result = new TL_phone.groupCall();
        result.call = call(FoxMesJson.object(answer, "call"));
        result.participants.addAll(participants(answer));
        result.participants_next_offset = "";
        result.users.addAll(rememberUsers(runtime, answer));
        return result;
    }

    private static TLRPC.TL_updates joinUpdates(FoxMesRuntime runtime, JsonObject answer) {
        JsonObject callDto = FoxMesJson.object(answer, "call");
        long[] call = {FoxMesJson.getLong(callDto, "id", 0), FoxMesJson.getLong(callDto, "access_hash", 0)};
        TLRPC.TL_updates updates = FoxMesHandlers.emptyUpdates();
        updates.users.addAll(rememberUsers(runtime, answer));
        TL_update.TL_updateGroupCall callUpdate = new TL_update.TL_updateGroupCall();
        callUpdate.call = call(callDto);
        updates.updates.add(callUpdate);
        TL_update.TL_updateGroupCallConnection connection = new TL_update.TL_updateGroupCallConnection();
        TLRPC.TL_dataJSON params = new TLRPC.TL_dataJSON();
        String value = FoxMesJson.optString(answer, "params");
        params.data = value != null ? value : "{}";
        connection.params = params;
        updates.updates.add(connection);
        updates.updates.add(participantsUpdate(call, answer));
        JsonObject chain = FoxMesJson.object(answer, "chain_blocks");
        if (chain != null) {
            updates.updates.add(chainUpdate(call, chain, 0));
        }
        JsonObject broadcast = FoxMesJson.object(answer, "broadcast_blocks");
        updates.updates.add(chainUpdate(call, broadcast != null ? broadcast : new JsonObject(), 1));
        return updates;
    }


    static void handleEvent(FoxMesRuntime runtime, String type, JsonObject data) {
        ArrayList<TLRPC.User> users = rememberUsers(runtime, data);
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        switch (type) {
            case "conference.participants": {
                long id = FoxMesJson.getLong(data, "call_id", 0);
                Long hash = accessHashes.get(id);
                updates.add(participantsUpdate(new long[]{id, hash != null ? hash : 0}, data));
                break;
            }
            case "conference.updated": {
                JsonObject callDto = FoxMesJson.object(data, "call");
                if (callDto != null) {
                    TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
                    update.call = call(callDto);
                    updates.add(update);
                }
                break;
            }
            case "conference.chain_blocks": {
                long id = FoxMesJson.getLong(data, "call_id", 0);
                Long hash = accessHashes.get(id);
                updates.add(chainUpdate(new long[]{id, hash != null ? hash : 0}, data, 0));
                break;
            }
            case "conference.encrypted_message": {
                long id = FoxMesJson.getLong(data, "call_id", 0);
                Long hash = accessHashes.get(id);
                TL_update.TL_updateGroupCallEncryptedMessage update = new TL_update.TL_updateGroupCallEncryptedMessage();
                update.call = inputCall(id, hash != null ? hash : 0);
                update.from_id = FoxMesTL.peer(FoxMesJson.getLong(data, "from_id", 0));
                update.encrypted_message = FoxMesCalls.decode(FoxMesJson.optString(data, "data"));
                updates.add(update);
                break;
            }
            default:
                return;
        }
        if (!updates.isEmpty()) {
            runtime.updates.dispatch(updates, users, null);
        }
    }
}
