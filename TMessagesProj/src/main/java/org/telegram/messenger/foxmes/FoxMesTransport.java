package org.telegram.messenger.foxmes;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.QuickAckDelegate;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.WriteToSocketDelegate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class FoxMesTransport {

    public interface Handler {
        void handle(FoxMesRequest request) throws Exception;
    }

    public enum Lane {
        SERIAL, IO, DOWNLOAD, INLINE
    }

    private static final class Route {
        final Lane lane;
        final Handler handler;

        Route(Lane lane, Handler handler) {
            this.lane = lane;
            this.handler = handler;
        }
    }

    private static final FoxMesTransport[] instances = new FoxMesTransport[UserConfig.MAX_ACCOUNT_COUNT];

    public static FoxMesTransport getInstance(int account) {
        FoxMesTransport transport = instances[account];
        if (transport == null) {
            synchronized (instances) {
                transport = instances[account];
                if (transport == null) {
                    transport = new FoxMesTransport(account);
                    instances[account] = transport;
                }
            }
        }
        return transport;
    }

    public final int account;
    private final Map<Class<?>, Route> routes = new HashMap<>();
    private final ConcurrentHashMap<Integer, FoxMesRequest> active = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> guids = new HashMap<>();
    private static final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "foxmes-retry");
        thread.setDaemon(true);
        return thread;
    });

    private FoxMesTransport(int account) {
        this.account = account;
        FoxMesHandlers.register(this, FoxMesRuntime.getInstance(account));
    }

    public void on(Class<? extends TLObject> type, Lane lane, Handler handler) {
        routes.put(type, new Route(lane, handler));
    }

    public boolean handles(Class<?> type) {
        return routes.containsKey(type);
    }


    public void onConnectionsManagerCreated() {
        FoxMesRuntime.getInstance(account).onStartup();
    }

    public void handle(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck,
                       WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, int requestToken) {
        FoxMesRequest request = new FoxMesRequest(account, object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, connectionType, requestToken);
        Route route = object != null ? routes.get(object.getClass()) : null;
        if (route == null) {
            if (BuildVars.LOGS_ENABLED || BuildVars.DEBUG_VERSION) {
                FoxMesLog.w("mtproto leak " + (object != null ? object.getClass().getName() : "null"));
            }
            request.fail(406, "FOXMES_MTPROTO_DISABLED");
            return;
        }
        active.put(requestToken, request);
        dispatch(request, route);
    }

    public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) {
        FoxMesRequest request = active.remove(token);
        if (request != null) {
            request.cancel(onCancelled);
        } else if (onCancelled != null) {
            Utilities.stageQueue.postRunnable(onCancelled);
        }
    }

    public void cancelRequestsForGuid(int guid) {
        Set<Integer> tokens;
        synchronized (guids) {
            tokens = guids.remove(guid);
        }
        if (tokens == null) {
            return;
        }
        for (Integer token : tokens) {
            FoxMesRequest request = active.remove(token);
            if (request != null) {
                request.cancel(null);
            }
        }
    }

    public void bindRequestToGuid(int token, int guid) {
        if (guid == 0) {
            return;
        }
        synchronized (guids) {
            Set<Integer> tokens = guids.get(guid);
            if (tokens == null) {
                tokens = new HashSet<>();
                guids.put(guid, tokens);
            }
            tokens.add(token);
        }
    }

    public void failNotRunningRequest(int token) {
    }

    void finished(FoxMesRequest request) {
        active.remove(request.token);
    }


    private void dispatch(FoxMesRequest request, Route route) {
        Runnable task = () -> run(request, route);
        if (route.lane == Lane.INLINE) {
            task.run();
            return;
        }
        executor(route.lane).execute(task);
    }

    private Executor executor(Lane lane) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        switch (lane) {
            case SERIAL:
                return runtime.serial;
            case DOWNLOAD:
                return runtime.downloads;
            default:
                return runtime.io;
        }
    }

    private void run(FoxMesRequest request, Route route) {
        if (request.isCancelled() || request.isDone()) {
            return;
        }
        try {
            route.handler.handle(request);
        } catch (FoxMesHttpException e) {
            if (e.isTransient()) {
                retry(request, route, e);
            } else {
                FoxMesLog.w(request.request.getClass().getSimpleName() + ": " + e.getMessage());
                request.fail(e.status, errorText(e));
            }
        } catch (InterruptedIOException e) {
            if (!request.isCancelled()) {
                retry(request, route, e);
            }
        } catch (IOException e) {
            retry(request, route, e);
        } catch (Exception e) {
            FoxMesLog.e("handler " + request.request.getClass().getSimpleName(), e);
            request.fail(400, "FOXMES_INTERNAL");
        }
    }

    private static String errorText(FoxMesHttpException e) {
        if (e.code != null && !e.code.isEmpty()) {
            return e.code;
        }
        return "FOXMES_HTTP_" + e.status;
    }

    private final ConcurrentHashMap<Integer, Runnable> waiting = new ConcurrentHashMap<>();

    public void retryWaitingNow() {
        for (Integer token : new java.util.ArrayList<>(waiting.keySet())) {
            Runnable runnable = waiting.remove(token);
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    private void retry(FoxMesRequest request, Route route, Exception error) {
        if (request.isCancelled() || request.isDone()) {
            return;
        }
        if (request.failsOnServerErrors()) {
            request.fail(500, "FOXMES_NETWORK");
            return;
        }
        request.attempts++;
        long delay = Math.min(30_000L, 1000L << Math.min(5, request.attempts - 1));
        FoxMesLog.w(request.request.getClass().getSimpleName() + " retry in " + delay + " ms: " + error.getMessage());
        Runnable again = () -> {
            if (!request.isCancelled() && !request.isDone()) {
                executor(route.lane == Lane.INLINE ? Lane.IO : route.lane).execute(() -> run(request, route));
            }
        };
        waiting.put(request.token, again);
        retries.schedule(() -> {
            if (waiting.remove(request.token) != null) {
                again.run();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
