package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_chatlists;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class FoxMesFolders {
    static final int LIMIT = 30, CHATS_LIMIT = 200;
    static final class Capabilities {
        boolean enabled;
        int limit;
        @SerializedName("chats_limit") int chatsLimit;
        @SerializedName("excluded_chats_limit") int excludedChatsLimit;
        @SerializedName("sharing_enabled") boolean sharingEnabled;
    }
    static final class Folder {
        int id;
        String title, emoticon;
        Integer color;
        boolean contacts, groups, channels, bots;
        @SerializedName("non_contacts") boolean nonContacts;
        @SerializedName("exclude_read") boolean excludeRead;
        @SerializedName("exclude_muted") boolean excludeMuted;
        @SerializedName("exclude_archived") boolean excludeArchived;
        @SerializedName("static_title") boolean staticTitle;
        @SerializedName("title_entities") List<FoxMesModels.Entity> entities = new ArrayList<>();
        @SerializedName("include_chat_ids") List<Long> include = new ArrayList<>();
        @SerializedName("pinned_chat_ids") List<Long> pinned = new ArrayList<>();
        @SerializedName("exclude_chat_ids") List<Long> exclude = new ArrayList<>();
    }
    static final class State {
        long revision;
        @SerializedName("tags_enabled") boolean tagsEnabled;
        List<Integer> order;
        List<Folder> folders;
        Capabilities limits;
        boolean valid() {
            if (revision < 0 || order == null || folders == null || limits == null || !limits.enabled
                    || limits.limit != LIMIT || limits.chatsLimit != CHATS_LIMIT || limits.excludedChatsLimit != CHATS_LIMIT
                    || limits.sharingEnabled || folders.size() > LIMIT || order.size() != folders.size() + 1) return false;
            HashSet<Integer> ids = new HashSet<>(); ids.add(0);
            for (Folder f : folders) {
                if (f == null || f.id < 2 || !ids.add(f.id) || f.title == null || f.title.trim().isEmpty() || f.title.length() > 32
                        || f.include == null || f.pinned == null || f.exclude == null || f.entities == null
                        || f.include.size() + f.pinned.size() > CHATS_LIMIT || f.exclude.size() > CHATS_LIMIT
                        || (f.color != null && (f.color < 0 || f.color > 7)) || f.entities.size() > 32) return false;
                HashSet<Long> chats = new HashSet<>();
                for (List<Long> values : java.util.Arrays.asList(f.include, f.pinned, f.exclude))
                    for (Long id : values) if (id == null || id <= 0 || !chats.add(id)) return false;
                for (FoxMesModels.Entity entity : f.entities) {
                    if (entity == null || entity.offset < 0 || entity.length <= 0 || entity.offset > f.title.length() - entity.length) return false;
                    if ("custom_emoji".equals(entity.type)) { if (entity.emojiId == null || entity.emojiId <= 0) return false; }
                    else if (!java.util.Arrays.asList("bold", "italic", "underline", "strikethrough", "spoiler").contains(entity.type)) return false;
                }
            }
            return new HashSet<>(order).equals(ids) && new HashSet<>(order).size() == order.size();
        }
    }
    static final class Suggested { List<Suggestion> folders; }
    static final class Suggestion { Folder folder; String description; }
    private static final FoxMesFolders[] instances = new FoxMesFolders[UserConfig.MAX_ACCOUNT_COUNT];
    private final FoxMesRuntime runtime;
    private final long userId;
    private final String token, profile;
    private final FoxMesHttp http;
    private volatile State state;
    private final Map<MessagesController.DialogFilter, Long> editorRevisions = new WeakHashMap<>();
    private final Map<TLObject, Long> requestRevisions = new WeakHashMap<>();
    private boolean restoreConfirmed;
    private final Map<FoxMesRequest, Pending> pending = new IdentityHashMap<>();
    private static final class Pending { String path, method, operation; FoxMesJson.Body body; boolean uncertain; }

    private FoxMesFolders(FoxMesRuntime runtime) {
        this.runtime = runtime; userId = runtime.selfId(); token = FoxMesTokenStore.loadToken(userId);
        profile = FoxMesConfiguration.profileKey(); http = new FoxMesHttp(() -> token);
    }
    static synchronized FoxMesFolders of(FoxMesRuntime runtime) {
        FoxMesFolders value = instances[runtime.account];
        if (value == null || !value.active()) instances[runtime.account] = value = new FoxMesFolders(runtime);
        return value;
    }
    private boolean active() {
        return userId == runtime.selfId() && Objects.equals(token, FoxMesTokenStore.loadToken(userId)) && profile.equals(FoxMesConfiguration.profileKey());
    }
    static boolean supported(int account) {
        FoxMesModels.Me me = FoxMesRuntime.getInstance(account).cachedMe();
        return me != null && me.capabilities().contains("chat-folders-v1") && me.folders != null && me.folders.enabled && me.folders.limit == LIMIT && me.folders.chatsLimit == CHATS_LIMIT && me.folders.excludedChatsLimit == CHATS_LIMIT && !me.folders.sharingEnabled;
    }
    public static void beginEdit(int account, MessagesController.DialogFilter filter) {
        if (!FoxMesFeatureGate.enabled) return;
        FoxMesFolders bridge = of(FoxMesRuntime.getInstance(account));
        synchronized (bridge) { bridge.editorRevisions.put(filter, bridge.state != null ? bridge.state.revision : -1L); }
    }
    public static void prepareEdit(int account, MessagesController.DialogFilter filter, TLObject request) {
        if (!FoxMesFeatureGate.enabled) return;
        FoxMesFolders bridge = of(FoxMesRuntime.getInstance(account));
        synchronized (bridge) {
            bridge.requestRevisions.put(request, bridge.editorRevisions.getOrDefault(filter, bridge.state != null ? bridge.state.revision : -1L));
        }
    }
    private synchronized Long revisionFor(TLObject request) {
        Long revision = requestRevisions.get(request);
        if (revision == null && state != null) { revision = state.revision; requestRevisions.put(request, revision); }
        return revision;
    }
    private synchronized void forgetRevision(TLObject request) { requestRevisions.remove(request); }
    static void configure(FoxMesRuntime runtime) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!supported(runtime.account)) return;
            MessagesController c = MessagesController.getInstance(runtime.account);
            c.dialogFiltersLimitDefault = c.dialogFiltersLimitPremium = LIMIT;
            c.dialogFiltersChatsLimitDefault = c.dialogFiltersChatsLimitPremium = CHATS_LIMIT;
            c.dialogFiltersPinnedLimitDefault = c.dialogFiltersPinnedLimitPremium = CHATS_LIMIT;
        });
    }
    private void requireSupported() throws IOException {
        runtime.me();
        if (!active() || !supported(runtime.account)) throw new FoxMesHttpException(400,"FOLDERS_DISABLED","Folders are unavailable","");
    }
    synchronized boolean receive(State incoming) {
        if (!active() || incoming == null || !incoming.valid()) return false;
        if (state == null || incoming.revision >= state.revision) state = incoming;
        return true;
    }
    synchronized void reset() { state = null; }
    private State load() throws IOException {
        requireSupported();
        State incoming = http.request("GET","/me/folders",null,null,State.class);
        if (!receive(incoming)) throw new IOException("Invalid folders state");
        return state;
    }
    private Folder find(int id) { if (state != null) for (Folder folder : state.folders) if (folder.id == id) return folder; return null; }
    static void refreshNative(FoxMesRuntime runtime) {
        if (supported(runtime.account)) of(runtime).notifyNative();
    }
    private void notifyNative() {
        AndroidUtilities.runOnUIThread(() -> { if (active()) MessagesController.getInstance(runtime.account).loadRemoteFilters(true); });
    }
    private void mutate(FoxMesRequest request, String method, String path, FoxMesJson.Body changes) throws IOException {
        mutate(request, method, path, changes, null);
    }
    private void mutate(FoxMesRequest request, String method, String path, FoxMesJson.Body changes, Long revision) throws IOException {
        requireSupported();
        Pending item = pending.get(request);
        if (item == null) {
            State current = state != null ? state : load(); item = new Pending(); item.method = method; item.path = path; item.operation = FoxMesJson.operationId();
            item.body = changes.put("operation_id",item.operation).put("base_revision",revision != null ? revision : current.revision); pending.put(request,item);
        }
        try {
            State incoming = null;
            if (item.uncertain) {
                JsonObject operation = http.request("GET","/operations/"+item.operation,null,null,JsonObject.class);
                String status = FoxMesJson.optString(operation,"status");
                if ("done".equals(status)) incoming = FoxMesJson.parse(operation.get("result"),State.class);
                else if (!"unknown".equals(status)) throw new IOException("Invalid folder operation status");
            }
            if (incoming == null) incoming = http.request(item.method,item.path,null,item.body,State.class);
            if (!receive(incoming)) throw new IOException("Invalid folder mutation response");
            pending.remove(request); forgetRevision(request.request); request.reply(FoxMesHandlers.boolTrue()); notifyNative();
        } catch (IOException error) {
            if (FoxMesHttpException.isTransient(error)) { item.uncertain = true; throw error; }
            pending.remove(request); forgetRevision(request.request);
            if (error instanceof FoxMesHttpException && ((FoxMesHttpException)error).status == 409) {
                try { JsonObject conflict = FoxMesJson.parse(((FoxMesHttpException)error).body,JsonObject.class); receive(FoxMesJson.parse(conflict.get("state"),State.class)); } catch (RuntimeException ignored) { }
            }
            restoreConfirmed = state != null;
            notifyNative(); throw error;
        }
    }
    private void peers(List<Long> ids, List<TLRPC.InputPeer> output) {
        for (Long id : ids) { long peer = runtime.peerForChat(id); if (peer != 0) output.add(FoxMesTL.inputPeer(peer)); }
    }
    private TLRPC.TL_dialogFilter nativeFolder(Folder folder) {
        TLRPC.TL_dialogFilter filter = new TLRPC.TL_dialogFilter(); filter.id = folder.id;
        filter.title = new TLRPC.TL_textWithEntities(); filter.title.text = folder.title; filter.title.entities = FoxMesTL.entities(folder.entities);
        filter.contacts = folder.contacts; filter.non_contacts = folder.nonContacts; filter.groups = folder.groups; filter.broadcasts = folder.channels; filter.bots = folder.bots;
        filter.exclude_muted = folder.excludeMuted; filter.exclude_read = folder.excludeRead; filter.exclude_archived = folder.excludeArchived; filter.title_noanimate = folder.staticTitle;
        if (folder.emoticon != null && !folder.emoticon.isEmpty()) {filter.flags |= TLObject.FLAG_25; filter.emoticon = folder.emoticon;}
        if (folder.color != null) { filter.flags |= TLObject.FLAG_27; filter.color = folder.color; }
        peers(folder.include,filter.include_peers); peers(folder.pinned,filter.pinned_peers); peers(folder.exclude,filter.exclude_peers);
        return filter;
    }
    private List<Long> chatIDs(List<TLRPC.InputPeer> peers, List<Long> previous) throws IOException {
        ArrayList<Long> ids = new ArrayList<>();
        for (TLRPC.InputPeer peer : peers) {
            long id = runtime.chatForPeer(FoxMesHandlers.peerUserId(peer,runtime));
            if (id == 0) throw new FoxMesHttpException(400,"PEER_ID_INVALID","Chat is unavailable","");
            if (!ids.contains(id)) ids.add(id);
        }
        if (previous != null) for (Long id : previous) if (runtime.peerForChat(id) == 0 && !ids.contains(id)) ids.add(id);
        return ids;
    }
    private Folder fromNative(TLRPC.TL_dialogFilter filter) throws IOException {
        Folder previous = find(filter.id); Folder folder = new Folder(); folder.id = filter.id; folder.title = filter.title.text;
        folder.contacts = filter.contacts; folder.nonContacts = filter.non_contacts; folder.groups = filter.groups; folder.channels = filter.broadcasts; folder.bots = filter.bots;
        folder.excludeMuted = filter.exclude_muted; folder.excludeRead = filter.exclude_read; folder.excludeArchived = filter.exclude_archived; folder.staticTitle = filter.title_noanimate;
        folder.emoticon = filter.emoticon != null ? filter.emoticon : (previous != null ? previous.emoticon : ""); if ((filter.flags & TLObject.FLAG_27) != 0) folder.color = filter.color;
        for (TLRPC.MessageEntity value : filter.title.entities) {
            FoxMesModels.Entity entity = new FoxMesModels.Entity(); entity.offset=value.offset; entity.length=value.length;
            if (value instanceof TLRPC.TL_messageEntityCustomEmoji) {entity.type="custom_emoji";entity.emojiId=((TLRPC.TL_messageEntityCustomEmoji)value).document_id;}
            else if (value instanceof TLRPC.TL_messageEntityBold) entity.type="bold";
            else if (value instanceof TLRPC.TL_messageEntityItalic) entity.type="italic";
            else if (value instanceof TLRPC.TL_messageEntityUnderline) entity.type="underline";
            else if (value instanceof TLRPC.TL_messageEntityStrike) entity.type="strikethrough";
            else if (value instanceof TLRPC.TL_messageEntitySpoiler) entity.type="spoiler";
            else continue;
            folder.entities.add(entity);
        }
        folder.include=chatIDs(filter.include_peers,previous!=null?previous.include:null);folder.pinned=chatIDs(filter.pinned_peers,previous!=null?previous.pinned:null);folder.exclude=chatIDs(filter.exclude_peers,previous!=null?previous.exclude:null);
        folder.include.removeAll(folder.pinned); return folder;
    }
    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TL_chatlists.TL_chatlists_exportChatlistInvite.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_deleteExportedInvite.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_editExportedInvite.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_getExportedInvites.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_checkChatlistInvite.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_joinChatlistInvite.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_getChatlistUpdates.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_joinChatlistUpdates.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_hideChatlistUpdates.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_getLeaveChatlistSuggestions.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TL_chatlists.TL_chatlists_leaveChatlist.class, FoxMesTransport.Lane.INLINE, r -> r.fail(400, "FOLDER_SHARING_DISABLED"));
        transport.on(TLRPC.TL_messages_getDialogFilters.class,FoxMesTransport.Lane.SERIAL,r -> {
            FoxMesFolders bridge=of(runtime);
            State state;
            if (bridge.restoreConfirmed && bridge.state != null) { state=bridge.state; bridge.restoreConfirmed=false; }
            else { state=bridge.load(); runtime.snapshot(false); }
            configure(runtime);
            TLRPC.TL_messages_dialogFilters result=new TLRPC.TL_messages_dialogFilters();result.tags_enabled=state.tagsEnabled;
            for (int id:state.order) {if(id==0) result.filters.add(new TLRPC.TL_dialogFilterDefault());else result.filters.add(bridge.nativeFolder(bridge.find(id)));}r.reply(result);
        });
        transport.on(TLRPC.TL_messages_getSuggestedDialogFilters.class,FoxMesTransport.Lane.SERIAL,r -> {
            FoxMesFolders bridge=of(runtime);bridge.requireSupported();Suggested suggestions=bridge.http.request("GET","/me/folders/suggested",null,null,Suggested.class);
            Vector<TLRPC.TL_dialogFilterSuggested> result=new Vector<>(TLRPC.TL_dialogFilterSuggested::TLdeserialize);
            for(Suggestion value:suggestions.folders){TLRPC.TL_dialogFilterSuggested suggestion=new TLRPC.TL_dialogFilterSuggested();suggestion.filter=bridge.nativeFolder(value.folder);suggestion.description=value.description;result.objects.add(suggestion);}r.reply(result);
        });
        transport.on(TLRPC.TL_messages_updateDialogFilter.class,FoxMesTransport.Lane.SERIAL,r -> {
            FoxMesFolders bridge=of(runtime);TLRPC.TL_messages_updateDialogFilter req=(TLRPC.TL_messages_updateDialogFilter)r.request;
            Long revision=bridge.revisionFor(req);
            try {
                if(!bridge.pending.containsKey(r)) {bridge.load();runtime.snapshot(false);}
                if (revision != null && revision < 0) throw new FoxMesHttpException(400,"FOLDER_LOAD_FAILED","Reload folders before editing","");
                FoxMesJson.Body body=FoxMesJson.body();boolean remove=(req.flags & TLObject.FLAG_0)==0 || req.filter==null;
                if(!remove) body.put("folder",FoxMesJson.gson.toJsonTree(bridge.fromNative((TLRPC.TL_dialogFilter) req.filter)));
                bridge.mutate(r,remove?"DELETE":"PUT","/me/folders/"+req.id,body,revision);
            } catch (IOException error) {
                if (!FoxMesHttpException.isTransient(error)) bridge.forgetRevision(req);
                bridge.restoreConfirmed=bridge.state!=null;bridge.notifyNative();throw error;
            }
        });
        transport.on(TLRPC.TL_messages_updateDialogFiltersOrder.class,FoxMesTransport.Lane.SERIAL,r -> {
            ArrayList<Integer> order=new ArrayList<>(((TLRPC.TL_messages_updateDialogFiltersOrder)r.request).order);if(!order.contains(0))order.add(0,0);
            of(runtime).mutate(r,"PUT","/me/folders/order",FoxMesJson.body().putNumbers("order",order));
        });
        transport.on(TLRPC.TL_messages_toggleDialogFilterTags.class,FoxMesTransport.Lane.SERIAL,r -> of(runtime).mutate(r,"PUT","/me/folders/settings",FoxMesJson.body().put("tags_enabled",((TLRPC.TL_messages_toggleDialogFilterTags)r.request).enabled)));
    }
    static void togglePin(FoxMesRuntime runtime,FoxMesRequest request,int folderID,long peer,boolean pinned) throws IOException {
        FoxMesFolders bridge=of(runtime);bridge.load();Folder folder=bridge.find(folderID);if(folder==null)throw new FoxMesHttpException(400,"FILTER_ID_INVALID","Unknown folder","");
        folder=FoxMesJson.parse(FoxMesJson.gson.toJson(folder),Folder.class);long id=runtime.chatForPeer(peer);if(id==0)throw new FoxMesHttpException(400,"PEER_ID_INVALID","Unknown chat","");
        folder.pinned.remove(id);if(pinned){folder.include.remove(id);folder.pinned.add(0,id);}else if(!folder.include.contains(id))folder.include.add(id);
        bridge.mutate(request,"PUT","/me/folders/"+folderID,FoxMesJson.body().put("folder",FoxMesJson.gson.toJsonTree(folder)));
    }
    static void reorderPins(FoxMesRuntime runtime,FoxMesRequest request,int folderID,List<TLRPC.InputDialogPeer> peers) throws IOException {
        FoxMesFolders bridge=of(runtime);bridge.load();Folder folder=bridge.find(folderID);if(folder==null)throw new FoxMesHttpException(400,"FILTER_ID_INVALID","Unknown folder","");
        folder=FoxMesJson.parse(FoxMesJson.gson.toJson(folder),Folder.class);ArrayList<Long> order=new ArrayList<>();for(TLRPC.InputDialogPeer peer:peers){long id=runtime.chatForPeer(FoxMesHandlers.peerUserId(peer,runtime));if(id==0)throw new FoxMesHttpException(400,"PEER_ID_INVALID","Unknown chat","");order.add(id);}
        for(Long id:folder.pinned)if(!order.contains(id)&&!folder.include.contains(id))folder.include.add(id);folder.pinned=order;folder.include.removeAll(order);
        bridge.mutate(request,"PUT","/me/folders/"+folderID,FoxMesJson.body().put("folder",FoxMesJson.gson.toJsonTree(folder)));
    }
}
