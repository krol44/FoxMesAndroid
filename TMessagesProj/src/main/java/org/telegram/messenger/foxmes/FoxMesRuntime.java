package org.telegram.messenger.foxmes;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.gson.JsonObject;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.Event;
import org.telegram.messenger.foxmes.FoxMesModels.Me;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalog;
import org.telegram.messenger.foxmes.FoxMesModels.ReactionCatalogItem;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FoxMesRuntime implements FoxMesTL.MapContext {

    private static final FoxMesRuntime[] instances = new FoxMesRuntime[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long TRANSPORT_RESTART_INTERVAL_MS = 10_000;

    public static FoxMesRuntime getInstance(int account) {
        FoxMesRuntime runtime = instances[account];
        if (runtime == null) {
            synchronized (instances) {
                runtime = instances[account];
                if (runtime == null) {
                    runtime = new FoxMesRuntime(account);
                    instances[account] = runtime;
                }
            }
        }
        return runtime;
    }

    public final int account;
    public final FoxMesHttp http;
    public final FoxMesApi api;
    public final FoxMesUpdateEngine updates;
    final FoxMesCallDeduplicator callRequests = new FoxMesCallDeduplicator();
    public final FoxMesWebSocket socket;
    public final ExecutorService serial;
    public final ExecutorService io;
    public final ExecutorService downloads;
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> tick;

    private final ConcurrentHashMap<Long, Long> chatToPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> peerToChat = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Chat> chats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, User> users = new ConcurrentHashMap<>();
    private final Set<Long> missingUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public final Set<String> pendingNonces = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public final Set<Integer> openedHere = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean handlingUnauthorized = new AtomicBoolean();

    private volatile Me me;
    volatile boolean statusesSupported;
    private volatile ReactionCatalog catalog;
    private volatile FoxMesModels.StickerSetsCatalog stickerSets;
    private volatile Map<Integer, FoxMesModels.EmojiDocument> stickerSetDocuments = Collections.emptyMap();
    private final ConcurrentHashMap<Integer, FoxMesModels.EmojiDocument> foreignDocuments = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot;
    private volatile boolean started;
    private volatile FoxMesStickerFavorites stickerFavorites;
    private volatile long sessionUserId;
    private volatile boolean realtimePaused;
    private volatile long lastTransportRestartAt;
    private volatile long lastRealtimeAt;
    private volatile long openedChatId;
    private final ConcurrentHashMap<Long, long[]> presence = new ConcurrentHashMap<>();
    private int presenceTicks;

    public static final class Snapshot {
        public final Me me;
        public final List<Chat> chats;
        final long fetchedAt;

        Snapshot(Me me, List<Chat> chats) {
            this.me = me;
            this.chats = chats;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    private FoxMesRuntime(int account) {
        this.account = account;
        this.http = new FoxMesHttp(() -> FoxMesTokenStore.loadToken(selfId()));
        this.api = new FoxMesApi(http);
        this.updates = new FoxMesUpdateEngine(account);
        this.serial = Executors.newSingleThreadExecutor(named("foxmes-serial-" + account));
        this.io = Executors.newFixedThreadPool(4, named("foxmes-io-" + account));
        this.downloads = Executors.newFixedThreadPool(6, named("foxmes-dl-" + account));
        this.timer = Executors.newSingleThreadScheduledExecutor(named("foxmes-timer-" + account));
        this.socket = new FoxMesWebSocket(account, http, new FoxMesWebSocket.Listener() {
            @Override
            public void onConnected() {
                lastRealtimeAt = SystemClock.elapsedRealtime();
                setConnectionState(ConnectionsManager.ConnectionStateConnected);
                sendPresence();
                FoxMesSender.getInstance(account).flushQueued();
            }

            @Override
            public void onDisconnected() {
                clearPresence();
                setConnectionState(ApplicationLoader.isNetworkOnline() ? ConnectionsManager.ConnectionStateConnecting : ConnectionsManager.ConnectionStateWaitingForNetwork);
            }

            @Override
            public void onEvent(Event event) {
                lastRealtimeAt = SystemClock.elapsedRealtime();
                serial.execute(() -> FoxMesEvents.getInstance(account).apply(event));
            }

            @Override
            public void onGap(long resume) {
                serial.execute(() -> handleGap(resume));
            }
        });
        http.setOnUnauthorized(this::handleUnauthorized);
        http.setOnTransportFailure(this::onTransportFailure);
        chatToPeer.putAll(store().chatToPeer());
        for (Map.Entry<Long, Long> entry : chatToPeer.entrySet()) {
            peerToChat.put(entry.getValue(), entry.getKey());
        }
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public FoxMesStore store() {
        return FoxMesStore.getInstance(account);
    }


    @Override
    public long selfId() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    @Override
    public long peerForChat(long chatId) {
        Long peer = chatToPeer.get(chatId);
        return peer != null ? peer : 0;
    }

    @Override
    public void recordPhotoUrl(long photoId, String url) {
        store().putPhotoUrl(photoId, url);
    }


    public boolean isStarted() {
        return started;
    }

    public void onStartup() {
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated()) {
            return;
        }
        if (!FoxMesTokenStore.hasToken(config.getClientUserId())) {
            FoxMesLog.w("account " + account + " has no FoxMes token, logging out");
            ConnectionsManager.onLogout(account);
            return;
        }
        start();
    }

    synchronized FoxMesStickerFavorites stickerFavorites() {
        if (stickerFavorites != null && !stickerFavorites.active()) { stickerFavorites.stop(); stickerFavorites = null; }
        if (stickerFavorites == null) stickerFavorites = new FoxMesStickerFavorites(this);
        return stickerFavorites;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        sessionUserId = selfId();
        stickerFavorites().refresh(false);
        handlingUnauthorized.set(false);
        setConnectionState(ConnectionsManager.ConnectionStateConnecting);
        tick = timer.scheduleAtFixedRate(() -> {
            ConnectionsManager.onUpdate(account);
            Utilities.stageQueue.postRunnable(updates::flushWaiting);
            AndroidUtilities.runOnUIThread(this::followOpenedChat);
            renewPresence();
        }, 1, 1, TimeUnit.SECONDS);
        ConnectionsManager.onSessionCreated(account);
        long seq = store().getLong("event_seq", 0);
        if (seq > 0) {
            socket.start(seq);
        }
        serial.execute(() -> {
            refreshCatalogQuietly();
            FoxMesChatAppearance.refreshThemes(this);
        });
        FoxMesAppState.watch();
        TL_update.TL_updatePinnedDialogs pinned = new TL_update.TL_updatePinnedDialogs();
        pinned.folder_id = 0;
        updates.dispatch(Collections.singletonList(pinned), new ArrayList<>(), null);
    }

    static void forEachStarted(Utilities.Callback<FoxMesRuntime> action) {
        for (FoxMesRuntime runtime : instances) {
            if (runtime != null && runtime.started) {
                action.run(runtime);
            }
        }
    }

    void pauseRealtime() {
        if (!started || realtimePaused) {
            return;
        }
        FoxMesLog.d("account " + account + ": realtime paused in the background");
        realtimePaused = true;
        socket.stop();
    }

    void resumeRealtime() {
        if (!realtimePaused) {
            return;
        }
        realtimePaused = false;
        FoxMesLog.d("account " + account + ": realtime resumed");
        long seq = store().getLong("event_seq", 0);
        if (started && seq > 0) {
            socket.start(seq);
        }
    }

    void refreshSocketPing() {
        if (started && !realtimePaused) {
            socket.refreshPingInterval();
        }
    }

    void onNetworkChanged() {
        if (started && !realtimePaused) {
            socket.restart();
        }
    }

    private void onTransportFailure() {
        if (!started || realtimePaused || !socket.isConnected()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastTransportRestartAt < TRANSPORT_RESTART_INTERVAL_MS) {
            return;
        }
        lastTransportRestartAt = now;
        socket.restart();
    }

    void syncForPush(long timeoutMs, long quietMs) {
        if (!started) {
            return;
        }
        FoxMesAppState.holdForPush(true);
        try {
            if (!FoxMesAppState.isForeground()) {
                if (realtimePaused) {
                    resumeRealtime();
                } else {
                    socket.restart();
                }
            }
            long start = SystemClock.elapsedRealtime();
            long deadline = start + timeoutMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                long now = SystemClock.elapsedRealtime();
                if (socket.isConnected() && lastRealtimeAt >= start && now - lastRealtimeAt >= quietMs) {
                    return;
                }
                SystemClock.sleep(200);
            }
            FoxMesLog.w("account " + account + ": push sync timed out");
        } finally {
            FoxMesAppState.holdForPush(false);
        }
    }

    public synchronized void stop() {
        started = false;
        if (stickerFavorites != null) { stickerFavorites.stop(); stickerFavorites = null; }
        realtimePaused = false;
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        socket.stop();
        serial.execute(() -> {
            callRequests.reset();
            FoxMesEvents.getInstance(account).reset();
        });
        snapshot = null;
        me = null;
        statusesSupported = false;
        chats.clear();
        users.clear();
        missingUsers.clear();
        pendingNonces.clear();
        presence.clear();
        openedChatId = 0;
        Utilities.stageQueue.postRunnable(updates::reset);
    }

    public void logout() {
        long self = sessionUserId != 0 ? sessionUserId : selfId();
        String token = self != 0 ? FoxMesTokenStore.loadToken(self) : null;
        FoxMesLog.d("logout account " + account + " user " + self + (token != null ? " with" : " without") + " a token");
        if (self != 0) {
            FoxMesTokenStore.clear(self);
        }
        sessionUserId = 0;
        serial.execute(() -> {
            if (token != null) {
                try {
                    api.logout(token);
                } catch (Exception e) {
                    FoxMesLog.w("logout: " + e.getMessage());
                }
            }
            stop();
            chatToPeer.clear();
            peerToChat.clear();
            store().clearAll();
        });
    }

    private void handleUnauthorized() {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        if (!handlingUnauthorized.compareAndSet(false, true)) {
            return;
        }
        FoxMesLog.w("401 received, logging out account " + account);
        long self = selfId();
        if (self != 0) {
            FoxMesTokenStore.clear(self);
        }
        stop();
        ConnectionsManager.onLogout(account);
    }

    private void setConnectionState(int state) {
        ConnectionsManager.onConnectionStateChanged(state, account);
    }

    void startRealtimeIfNeeded(long eventSeq) {
        if (!started) {
            return;
        }
        if (realtimePaused) {
            if (eventSeq > 0 && store().getLong("event_seq", 0) <= 0) {
                store().putLong("event_seq", eventSeq);
            }
            return;
        }
        long stored = store().getLong("event_seq", 0);
        if (stored <= 0 && eventSeq > 0) {
            store().putLong("event_seq", eventSeq);
            stored = eventSeq;
        }
        if (stored > 0) {
            socket.start(stored);
        }
    }

    void saveEventSeq(long seq) {
        if (seq > store().getLong("event_seq", 0)) {
            store().putLong("event_seq", seq);
        }
    }

    private long lastGapResume;

    private void handleGap(long resume) {
        if (resume == lastGapResume) {
            FoxMesLog.e("gap.detected repeats at " + resume + ", realtime stopped");
            return;
        }
        lastGapResume = resume;
        stickerFavorites().refresh(true);
        store().putLong("event_seq", Math.max(0, resume - 1));
        snapshot = null;
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updatesTooLong tooLong = new TLRPC.TL_updatesTooLong();
            AccountInstance.getInstance(account).getMessagesController().processUpdates(tooLong, false);
        });
        try {
            snapshot(true);
            lastGapResume = 0;
        } catch (IOException e) {
            FoxMesLog.e("gap resync", e);
        }
        socket.setLastSequence(Math.max(0, resume - 1));
        socket.start(Math.max(0, resume - 1));
    }


    private void followOpenedChat() {
        long chatId = 0;
        if (started) {
            MessagesController controller = MessagesController.getInstance(account);
            for (Map.Entry<Long, Long> entry : peerToChat.entrySet()) {
                if (controller.isDialogVisible(entry.getKey(), false)) {
                    chatId = entry.getValue();
                    break;
                }
            }
        }
        if (chatId != openedChatId) {
            openedChatId = chatId;
            sendPresence();
        }
    }

    private void sendPresence() {
        long chatId = openedChatId;
        socket.send(FoxMesJson.body().put("type", "presence.set").put("chat_id", chatId).put("active", chatId > 0).toString());
    }

    @Override
    public boolean isOnline(long userId) {
        long[] state = presence.get(userId);
        return state != null && state[2] != 0;
    }


    public boolean applyPresence(long userId, long chatId, boolean online, long revision) {
        if (userId == 0 || userId == selfId() || peerForChat(chatId) != userId) {
            return false;
        }
        synchronized (presence) {
            long[] state = presence.get(userId);
            if (state != null && state[0] == chatId && revision > 0 && state[1] > revision) {
                return false;
            }
            boolean was = state != null && state[2] != 0;
            presence.put(userId, new long[]{chatId, revision, online ? 1 : 0});
            return was != online;
        }
    }

    private void renewPresence() {
        if (++presenceTicks < FoxMesTL.ONLINE_WINDOW / 2) {
            return;
        }
        presenceTicks = 0;
        ArrayList<Long> online = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : presence.entrySet()) {
            if (entry.getValue()[2] != 0) {
                online.add(entry.getKey());
            }
        }
        dispatchStatuses(online);
    }

    private void clearPresence() {
        ArrayList<Long> online = new ArrayList<>();
        synchronized (presence) {
            for (Map.Entry<Long, long[]> entry : presence.entrySet()) {
                if (entry.getValue()[2] != 0) {
                    online.add(entry.getKey());
                }
            }
            presence.clear();
        }
        dispatchStatuses(online);
    }

    public void dispatchStatuses(Collection<Long> userIds) {
        if (userIds.isEmpty() || !started) {
            return;
        }
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        for (Long userId : userIds) {
            TL_update.TL_updateUserStatus update = new TL_update.TL_updateUserStatus();
            update.user_id = userId;
            update.status = FoxMesTL.status(isOnline(userId));
            list.add(update);
        }
        updates.dispatch(list, new ArrayList<>(), null);
    }


    public Me me() throws IOException {
        Me current = me;
        if (current == null) {
            current = api.me();
            me = current;
            statusesSupported = current.capabilities().contains("emoji-status-v1");
            rememberUser(current);
            FoxMesFolders.configure(this);
            FoxMesFeatureGate.setEphemeralMedia(current.capabilities().contains(FoxMesModels.CAPABILITY_EPHEMERAL_MEDIA));
        }
        return current;
    }

    public Me cachedMe() {
        return me;
    }

    public synchronized Snapshot snapshot(boolean force) throws IOException {
        Snapshot current = snapshot;
        if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < 5000) {
            return current;
        }
        setUpdating(true);
        try {
            if (force) FoxMesFolders.of(this).reset();
            me = null;
            statusesSupported = false;
            Me self = me();
            startRealtimeIfNeeded(self.eventSeq != null ? self.eventSeq : 0);
            refreshCatalogQuietly();
            if (force) FoxMesChatAppearance.refreshThemes(this);
            FoxMesChatAppearance.refreshDefault(this);
            FoxMesAppearanceHandlers.refresh(this);
            List<Chat> list = new ArrayList<>(api.chats(true));
            try {
                Chat saved = api.savedChat();
                boolean present = false;
                for (Chat chat : list) {
                    if (chat.id == saved.id) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    list.add(saved);
                }
            } catch (IOException e) {
                FoxMesLog.w("saved chat: " + e.getMessage());
            }
            ArrayList<Chat> supported = new ArrayList<>();
            for (Chat chat : list) {
                if (FoxMesFeatureGate.supportsChat(chat.type, chat.isSavedChat()) && registerChat(chat) != 0) {
                    supported.add(chat);
                }
            }
            current = new Snapshot(self, supported);
            boolean refreshFolders = force || snapshot == null;
            snapshot = current;
            if (refreshFolders) FoxMesFolders.refreshNative(this);
            return current;
        } finally {
            setUpdating(false);
        }
    }

    public Snapshot cachedSnapshot() {
        return snapshot;
    }

    public void invalidateSnapshot() {
        snapshot = null;
    }

    private void setUpdating(boolean updating) {
        AndroidUtilities.runOnUIThread(() -> {
            ConnectionsManager manager = ConnectionsManager.getInstance(account);
            manager.setIsUpdating(updating);
        });
    }


    public long peerOf(Chat chat) {
        long self = selfId();
        if (chat.isSavedChat()) {
            return self;
        }
        if (chat.peer != null && chat.peer.id != 0) {
            return chat.peer.id;
        }
        for (User member : chat.members()) {
            if (member.id != self) {
                return member.id;
            }
        }
        for (Long id : chat.memberIds()) {
            if (id != self) {
                return id;
            }
        }
        if ("direct".equals(chat.type)) {
            return 0;
        }
        List<Long> ids = chat.memberIds();
        return ids.size() == 1 ? ids.get(0) : 0;
    }

    public long registerChat(Chat chat) {
        long peer = peerOf(chat);
        if (peer == 0) {
            return 0;
        }
        Long previous = chatToPeer.put(chat.id, peer);
        peerToChat.put(peer, chat.id);
        if (previous == null || previous != peer) {
            store().putChat(chat.id, peer, chat.isSavedChat());
        }
        if (chat.appearance == null) {
            chat.appearance = new FoxMesChatAppearance.Appearance();chat.appearance.theme = new FoxMesChatAppearance.ThemeState();chat.appearance.theme.theme_emoticon = chat.themeEmoticon;chat.appearance.wallpaper = chat.wallpaper;
        }
        chat.appearance = FoxMesChatAppearance.merge(this, chat.id, chat.appearance);
        chats.put(chat.id, chat);
        FoxMesChatAppearance.apply(this, chat.id, chat.appearance, null);
        if (chat.peer != null) {
            rememberUser(chat.peer);
        }
        for (User member : chat.members()) {
            rememberUser(member);
        }
        return peer;
    }

    public void forgetChat(long chatId) {
        Long peer = chatToPeer.remove(chatId);
        if (peer != null) {
            peerToChat.remove(peer);
        }
        chats.remove(chatId);
        store().removeChat(chatId);
    }

    public Chat chat(long chatId) {
        return chats.get(chatId);
    }

    public long chatForPeer(long peerUserId) {
        Long chatId = peerToChat.get(peerUserId);
        return chatId != null ? chatId : 0;
    }

    public Collection<Chat> knownChats() {
        return chats.values();
    }

    private final Map<Long, Object> ensureLocks = new HashMap<>();

    public long ensureChatId(long peerUserId) throws IOException {
        long existing = chatForPeer(peerUserId);
        if (existing != 0) {
            return existing;
        }
        Object lock;
        synchronized (ensureLocks) {
            lock = ensureLocks.get(peerUserId);
            if (lock == null) {
                lock = new Object();
                ensureLocks.put(peerUserId, lock);
            }
        }
        synchronized (lock) {
            existing = chatForPeer(peerUserId);
            if (existing != 0) {
                return existing;
            }
            Chat chat = peerUserId == selfId() ? api.savedChat() : api.directChat(peerUserId);
            long peer = registerChat(chat);
            if (peer != peerUserId && !(chat.isSavedChat() && peerUserId == selfId())) {
                chatToPeer.put(chat.id, peerUserId);
                peerToChat.put(peerUserId, chat.id);
                store().putChat(chat.id, peerUserId, chat.isSavedChat());
            }
            return chat.id;
        }
    }

    private final Set<Long> pendingMeets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void createMeetLink(long peerUserId, Utilities.Callback<String> done) {
        io.execute(() -> {
            String url = null;
            long started = 0;
            try {
                long chatId = ensureChatId(peerUserId);
                if (pendingMeets.add(chatId)) {
                    started = chatId;
                    url = api.createMeet(chatId).url;
                }
            } catch (Exception e) {
                FoxMesLog.e("meet failed", e);
            } finally {
                if (started != 0) {
                    pendingMeets.remove(started);
                }
            }
            final String result = url;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }


    public User knownUser(long id) {
        return users.get(id);
    }

    public synchronized void rememberUser(User user) {
        if (user != null && user.id != 0) {
            User previous = users.get(user.id);
            if (previous != null && (user.verified == null || (previous.verificationRevision != null && (user.verificationRevision == null || user.verificationRevision < previous.verificationRevision)))) {
                user.verified = previous.verified;
                user.verificationRevision = previous.verificationRevision;
            }
            if (previous != null && (user.emojiStatus == null || (previous.emojiStatus != null && user.emojiStatus.revision < previous.emojiStatus.revision))) user.emojiStatus = previous.emojiStatus;
            FoxMesPeerStatusHandlers.remember(this, user.emojiStatus);
            if (previous != null && previous.photosRevision != null && (user.photosRevision == null || user.photosRevision < previous.photosRevision)) {
                user.avatarUrl = previous.avatarUrl;
                user.avatarId = previous.avatarId;
                user.currentProfilePhotoId = previous.currentProfilePhotoId;
                user.photosRevision = previous.photosRevision;
            }
            if (previous != null && (user.appearance == null || (previous.appearance != null && previous.appearance.revision > user.appearance.revision))) {
                user.appearance = previous.appearance;
            }
            if (user.blocked == null && previous != null) {
                user.blocked = previous.blocked;
                user.contactName = previous.contactName;
                user.contactPhotoUrl = previous.contactPhotoUrl;
                user.originalDisplayName = previous.originalDisplayName;
                user.contactNote = previous.contactNote;
            }
            users.put(user.id, user);
            missingUsers.remove(user.id);
            if (user.blocked != null && previous != null
                    && !TextUtils.equals(nonNull(previous.contactNote), nonNull(user.contactNote))) {
                refreshNote(user.id, user.contactNote);
            }
        }
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    // The profile reads the note from the cached full user, which is loaded
    // once; a note changed here or on another device is written into it.
    private void refreshNote(long userId, String note) {
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(userId);
            if (full == null) {
                return;
            }
            applyNote(full, note);
            MessagesStorage.getInstance(account).updateUserInfo(full, true);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, userId, full);
        });
    }

    static void applyNote(TLRPC.UserFull full, String note) {
        if (TextUtils.isEmpty(note)) {
            full.note = null;
            full.flags2 &= ~TLObject.FLAG_22;
        } else {
            TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
            text.text = note;
            full.note = text;
            full.flags2 |= TLObject.FLAG_22;
        }
    }

    // The contact edits below change only this account's view of the person;
    // the server answers with that view and also sends it as user.updated to
    // every device of the account, which is what refreshes the upstream user.
    public boolean hasContactPhoto(long userId) {
        User user = users.get(userId);
        return user != null && !TextUtils.isEmpty(user.contactPhotoUrl);
    }

    public String contactName(long userId) {
        User user = users.get(userId);
        return user != null && user.contactName != null ? user.contactName : "";
    }

    public String originalUserName(long userId) {
        User user = users.get(userId);
        if (user == null) {
            return "";
        }
        return !TextUtils.isEmpty(user.originalDisplayName) ? user.originalDisplayName : (user.displayName != null ? user.displayName : "");
    }

    public interface ContactEdit {
        User run() throws IOException;
    }

    private void editContact(ContactEdit edit, Utilities.Callback<Boolean> done) {
        io.execute(() -> {
            boolean ok = false;
            try {
                rememberUser(edit.run());
                ok = true;
            } catch (Exception e) {
                FoxMesLog.e("contact edit failed", e);
            }
            final boolean result = ok;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public String contactNote(long userId) {
        User user = users.get(userId);
        return user != null ? nonNull(user.contactNote) : "";
    }

    public void setContactFields(long userId, String name, String note, Utilities.Callback<Boolean> done) {
        editContact(() -> api.setContactFields(userId, name, note), done);
    }

    public void setContactPhoto(long userId, File jpeg, Utilities.Callback<Boolean> done) {
        editContact(() -> api.setContactPhoto(userId, api.uploadContactPhoto(jpeg)), done);
    }

    public void resetContactPhoto(long userId, Utilities.Callback<Boolean> done) {
        editContact(() -> api.setContactPhoto(userId, 0), done);
    }

    public ArrayList<Long> blockedUserIds() {
        ArrayList<Long> result = new ArrayList<>();
        for (User user : users.values()) {
            if (Boolean.TRUE.equals(user.blocked)) {
                result.add(user.id);
            }
        }
        return result;
    }

    public ArrayList<TLRPC.User> tlUsers(Collection<Long> ids) {
        LinkedHashSet<Long> unique = new LinkedHashSet<>(ids);
        unique.add(selfId());
        ArrayList<TLRPC.User> result = new ArrayList<>();
        for (Long id : unique) {
            if (id == null || id == 0) {
                continue;
            }
            User user = users.get(id);
            if (user == null && id == selfId()) {
                user = me;
            }
            if (user == null && !missingUsers.contains(id)) {
                try {
                    user = api.user(id);
                    rememberUser(user);
                } catch (IOException e) {
                    missingUsers.add(id);
                }
            }
            result.add(user != null ? FoxMesTL.user(user, this) : FoxMesTL.placeholderUser(id, null, this));
        }
        return result;
    }

    public static void collectUserIds(Message message, Collection<Long> into) {
        if (message == null) {
            return;
        }
        into.add(message.senderId);
        if (message.forwardedFrom != null && message.forwardedFrom.authorId != null && message.forwardedFrom.authorId > 0) {
            into.add(message.forwardedFrom.authorId);
        }
        if (message.replyTo != null && message.replyTo.authorId != null && message.replyTo.authorId > 0) {
            into.add(message.replyTo.authorId);
        }
        for (FoxMesModels.Reaction reaction : message.reactions()) {
            into.add(reaction.userId);
        }
        if (message.call != null) {
            into.addAll(message.call.participants());
        }
    }


    public TLRPC.Message tlMessage(Message message) {
        store().putMessage(message.id, message.chatId, message.revision, message.reactionRevision);
        if (message.ephemeral != null) {
            store().putEphemeral(Collections.singletonList(message.id));
        }
        if (message.replyTo != null && message.replyTo.chatId != null && message.replyToId != null) {
            store().putMessageChat(message.replyToId, message.replyTo.chatId);
        }
        return FoxMesTL.message(message, this);
    }

    public ArrayList<TLRPC.Message> tlMessages(List<Message> messages) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        ArrayList<int[]> idRevision = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        ArrayList<Long> reactionRevisions = new ArrayList<>();
        ArrayList<Integer> ephemeral = new ArrayList<>();
        for (Message message : messages) {
            if (message.ephemeral != null) {
                ephemeral.add(message.id);
            }
            idRevision.add(new int[]{message.id, message.revision});
            chatIds.add(message.chatId);
            reactionRevisions.add(message.reactionRevision);
            if (message.replyTo != null && message.replyTo.chatId != null && message.replyToId != null) {
                store().putMessageChat(message.replyToId, message.replyTo.chatId);
            }
            result.add(FoxMesTL.message(message, this));
        }
        store().putMessages(idRevision, chatIds, reactionRevisions);
        store().putEphemeral(ephemeral);
        return result;
    }

    public long chatOfMessage(int messageId, long dialogPeer) {
        long chatId = store().chatOfMessage(messageId);
        if (chatId == 0 && dialogPeer != 0) {
            chatId = chatForPeer(dialogPeer);
        }
        return chatId;
    }


    public ReactionCatalog catalog() {
        return catalog;
    }

    public ReactionCatalogItem catalogItemForEmoji(String emoji) {
        ReactionCatalog current = catalog;
        if (current == null || emoji == null) {
            return null;
        }
        for (ReactionCatalogItem item : current.availableReactions()) {
            if (emoji.equals(item.emoji)) {
                return item;
            }
        }
        return null;
    }

    public ReactionCatalog ensureCatalog() throws IOException {
        ReactionCatalog current = catalog;
        if (current == null) {
            current = api.reactionCatalog();
            setCatalog(current);
        }
        return current;
    }

    void refreshCatalogQuietly() {
        try {
            setCatalog(api.reactionCatalog());
        } catch (Exception e) {
            FoxMesLog.w("reaction catalog: " + e.getMessage());
        }
        try {
            setStickerSets(api.stickerSets());
        } catch (Exception e) {
            FoxMesLog.w("sticker sets: " + e.getMessage());
        }
    }

    private void setCatalog(ReactionCatalog value) {
        catalog = value;
    }

    public FoxMesModels.StickerSetsCatalog ensureStickerSets() throws IOException {
        FoxMesModels.StickerSetsCatalog current = stickerSets;
        if (current == null) {
            current = api.stickerSets();
            setStickerSets(current);
        }
        return current;
    }

    private void setStickerSets(FoxMesModels.StickerSetsCatalog value) {
        if (value == null) {
            return;
        }
        HashMap<Integer, FoxMesModels.EmojiDocument> documents = new HashMap<>();
        for (FoxMesModels.StickerSet set : value.sets()) {
            for (FoxMesModels.EmojiDocument item : set.items()) {
                documents.put(item.id, item);
            }
        }
        synchronized (this) {
            FoxMesModels.StickerSetsCatalog previous = stickerSets;
            if (previous != null && previous.revision != null && value.revision != null && value.revision < previous.revision) {
                value.installed = previous.installed;
                value.revision = previous.revision;
            }
            stickerSetDocuments = documents;
            stickerSets = value;
        }
        FoxMesStickerFavorites favorites = stickerFavorites;
        if (favorites != null) favorites.catalogChanged();
    }

    synchronized boolean receiveStickerSets(FoxMesModels.StickerSetsState state) {
        FoxMesModels.StickerSetsCatalog current = stickerSets;
        if (state == null || !state.valid() || current == null) {
            return state != null && state.valid();
        }
        if (current.revision != null && state.revision < current.revision) {
            return false;
        }
        FoxMesModels.StickerSetsCatalog next = new FoxMesModels.StickerSetsCatalog();
        next.sets = current.sets;
        next.installed = state.installed;
        next.revision = state.revision;
        stickerSets = next;
        return true;
    }

    void stickerSetsChanged() {
        updates.dispatch(Collections.singletonList(new TL_update.TL_updateStickerSets()), new ArrayList<>(), null);
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.MediaDataController.getInstance(account)
                .loadStickers(org.telegram.messenger.MediaDataController.TYPE_EMOJIPACKS, false, true));
    }

    void setStickerSetInstalled(int setId, boolean installed) throws IOException {
        receiveStickerSets(api.setStickerSetInstalled(setId, installed));
    }

    void orderStickerSets(boolean emoji, List<Long> order) throws IOException {
        ArrayList<Integer> ids = new ArrayList<>();
        for (Long id : order) {
            if (id != null && id > 0 && id <= Integer.MAX_VALUE) {
                ids.add(id.intValue());
            }
        }
        receiveStickerSets(api.orderStickerSets(emoji, ids));
    }

    public FoxMesModels.EmojiDocument emojiDocument(long id) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            return null;
        }
        FoxMesModels.EmojiDocument item = stickerSetDocuments.get((int) id);
        return item != null ? item : foreignDocuments.get((int) id);
    }

    public Map<Integer, FoxMesModels.EmojiDocument> emojiDocuments(List<Integer> ids) throws IOException {
        ensureStickerSets();
        HashMap<Integer, FoxMesModels.EmojiDocument> result = new HashMap<>();
        ArrayList<Integer> missing = new ArrayList<>();
        for (Integer id : ids) {
            FoxMesModels.EmojiDocument item = emojiDocument(id);
            if (item != null) {
                result.put(id, item);
            } else if (!missing.contains(id)) {
                missing.add(id);
            }
        }
        for (int from = 0; from < missing.size(); from += 200) {
            for (FoxMesModels.EmojiDocument item : api.emojiDocuments(missing.subList(from, Math.min(missing.size(), from + 200)))) {
                foreignDocuments.put(item.id, item);
                result.put(item.id, item);
            }
        }
        return result;
    }

    private final ConcurrentHashMap<Long, long[]> outboxRead = new ConcurrentHashMap<>();

    public void rememberOutboxRead(long chatId, int readThroughId, String readAt) {
        long at = FoxMesJson.unixMillis(readAt);
        long[] current = outboxRead.get(chatId);
        if (current != null && current[0] > readThroughId) {
            return;
        }
        outboxRead.put(chatId, new long[]{readThroughId, at});
    }

    public int outboxReadDate(long chatId, int messageId) {
        long[] state = outboxRead.get(chatId);
        if (state == null || state[1] <= 0 || state[0] < messageId) {
            return 0;
        }
        return (int) (state[1] / 1000);
    }

    public static JsonObject readStateObject(JsonObject data) {
        JsonObject state = FoxMesJson.object(data, "state");
        return state != null ? state : data;
    }
}
