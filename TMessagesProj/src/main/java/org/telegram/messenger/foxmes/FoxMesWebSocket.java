package org.telegram.messenger.foxmes;

import org.telegram.messenger.foxmes.FoxMesModels.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class FoxMesWebSocket {

    public interface Listener {
        void onConnected();

        void onDisconnected();

        void onEvent(Event event);

        void onGap(long resume);
    }

    private static final long[] SHORT_DELAYS = {1, 2, 3};
    private static final long MAX_DELAY = 64_000;

    private final FoxMesHttp http;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;

    private WebSocket socket;
    private FoxMesSealedCrypto.SocketCodec codec;
    private int failuresSinceConnect;
    private ScheduledFuture<?> reconnect;
    private boolean running;
    private boolean connected;
    private int attempt;
    private long lastSequence;
    private int generation;
    private boolean background;

    public FoxMesWebSocket(int account, FoxMesHttp http, Listener listener) {
        this.http = http;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "foxmes-ws-" + account);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(long since) {
        lastSequence = Math.max(lastSequence, since);
        if (running) {
            return;
        }
        running = true;
        attempt = 0;
        connect();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        if (reconnect != null) {
            reconnect.cancel(false);
            reconnect = null;
        }
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
        if (connected) {
            connected = false;
            listener.onDisconnected();
        }
    }

    public void restart() {
        restart(-1);
    }

    private void restart(int expectedGeneration) {
        boolean notify;
        synchronized (this) {
            if (!running || (expectedGeneration >= 0 && expectedGeneration != generation)) {
                return;
            }
            generation++;
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            notify = connected;
            connected = false;
            attempt = 0;
            connect();
        }
        if (notify) {
            listener.onDisconnected();
        }
    }

    public void refreshPingInterval() {
        boolean stale;
        synchronized (this) {
            stale = running && socket != null && background == FoxMesAppState.isForeground();
        }
        if (stale) {
            restart();
        }
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }

    public synchronized void setLastSequence(long value) {
        lastSequence = value;
    }

    public synchronized boolean send(String text) {
        if (socket == null || !connected || codec == null) {
            return false;
        }
        try {
            return socket.send(ByteString.of(codec.seal(text.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    private HttpUrl socketUrl() {
        HttpUrl base = HttpUrl.parse(FoxMesConfiguration.baseURL());
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder().encodedPath("/ws");
        if (lastSequence > 0) {
            builder.addQueryParameter("since", String.valueOf(lastSequence));
        }
        return builder.build();
    }

    private void connect() {
        HttpUrl url = socketUrl();
        String token = http.token();
        if (url == null || token == null || token.isEmpty()) {
            scheduleReconnect();
            return;
        }
        final int current = ++generation;
        final boolean fresh = failuresSinceConnect >= 2;
        scheduler.execute(() -> open(current, url, token, fresh));
    }

    private void open(int current, HttpUrl url, String token, boolean fresh) {
        FoxMesSealed.SignedSocket signed;
        try {
            signed = FoxMesSealed.signSocket(new Request.Builder().url(url).build(), token, fresh);
        } catch (IOException e) {
            FoxMesLog.w("websocket cannot be sealed: " + e.getMessage());
            lost(current);
            return;
        }
        synchronized (this) {
            if (!running || current != generation) {
                return;
            }
            codec = signed.codec;
            background = !FoxMesAppState.isForeground();
            socket = FoxMesHttp.webSocketClient(url, background).newWebSocket(signed.request, listenerFor(current));
        }
    }

    private WebSocketListener listenerFor(int current) {
        return new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                synchronized (FoxMesWebSocket.this) {
                    if (current != generation) {
                        return;
                    }
                    attempt = 0;
                    failuresSinceConnect = 0;
                    connected = true;
                }
                listener.onConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                webSocket.cancel();
                lost(current);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                FoxMesSealedCrypto.SocketCodec current0;
                synchronized (FoxMesWebSocket.this) {
                    current0 = codec;
                }
                byte[] plain;
                try {
                    plain = current0 != null ? current0.open(bytes.toByteArray()) : null;
                } catch (java.security.GeneralSecurityException e) {
                    plain = null;
                }
                if (plain == null) {
                    webSocket.cancel();
                    lost(current);
                    return;
                }
                handle(current, new String(plain, StandardCharsets.UTF_8));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                lost(current);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (response != null && response.code() == 401) {
                    FoxMesLog.w("websocket handshake was rejected");
                }
                lost(current);
            }
        };
    }

    private void lost(int current) {
        boolean notify;
        synchronized (this) {
            if (current != generation) {
                return;
            }
            socket = null;
            notify = connected;
            if (!connected) {
                failuresSinceConnect++;
            }
            connected = false;
            if (running) {
                scheduleReconnect();
            }
        }
        if (notify) {
            listener.onDisconnected();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!running) {
            return;
        }
        long delay;
        if (attempt < SHORT_DELAYS.length) {
            delay = SHORT_DELAYS[attempt];
        } else {
            delay = Math.min(MAX_DELAY, 1000L << Math.min(6, attempt - SHORT_DELAYS.length));
        }
        attempt++;
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        reconnect = scheduler.schedule(() -> {
            synchronized (FoxMesWebSocket.this) {
                reconnect = null;
                if (running && socket == null) {
                    connect();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void handle(int current, String text) {
        Event event;
        try {
            event = FoxMesJson.parse(text, Event.class);
        } catch (Exception e) {
            FoxMesLog.e("websocket frame", e);
            return;
        }
        if (event == null || event.type == null) {
            return;
        }
        boolean sequenceGap = false;
        synchronized (this) {
            if (current != generation) {
                return;
            }
            if ("gap.detected".equals(event.type)) {
                com.google.gson.JsonObject data = event.dataObject();
                Long next = FoxMesJson.optLong(data, "next_seq");
                if (next == null) {
                    next = FoxMesJson.optLong(data, "resume_from");
                }
                long resume = next != null ? next : event.seq;
                lastSequence = Math.max(lastSequence, resume - 1);
            } else if (event.seq > 0 && lastSequence > 0 && event.seq > lastSequence + 1) {
                sequenceGap = true;
            } else if (event.seq > 0 && event.seq <= lastSequence) {
                return;
            } else if (event.seq > 0) {
                lastSequence = event.seq;
            }
        }
        if (sequenceGap) {
            restart(current);
            return;
        }
        if ("gap.detected".equals(event.type)) {
            long resume;
            synchronized (this) {
                resume = lastSequence + 1;
            }
            stop();
            listener.onGap(resume);
            return;
        }
        listener.onEvent(event);
    }
}
