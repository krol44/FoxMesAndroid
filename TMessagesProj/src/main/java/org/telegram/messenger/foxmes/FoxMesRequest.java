package org.telegram.messenger.foxmes;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.KeepAliveJob;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.QuickAckDelegate;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.WriteToSocketDelegate;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;

public final class FoxMesRequest {

    public interface Late {
        TLObject build();
    }

    public final int account;
    public final TLObject request;
    public final int flags;
    public final int connectionType;
    public final int token;
    private final RequestDelegate onComplete;
    private final RequestDelegateTimestamp onCompleteTimestamp;
    private final QuickAckDelegate onQuickAck;
    private final WriteToSocketDelegate onWriteToSocket;

    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Runnable onCancelled;
    private volatile Call call;
    int attempts;

    FoxMesRequest(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp,
                  QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int connectionType, int token) {
        this.account = account;
        this.request = request;
        this.onComplete = onComplete;
        this.onCompleteTimestamp = onCompleteTimestamp;
        this.onQuickAck = onQuickAck;
        this.onWriteToSocket = onWriteToSocket;
        this.flags = flags;
        this.connectionType = connectionType;
        this.token = token;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isDone() {
        return done.get();
    }

    public boolean failsOnServerErrors() {
        return (flags & (ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagFailOnServerErrorsExceptFloodWait)) != 0;
    }

    public void attachCall(Call call) {
        this.call = call;
        if (cancelled.get() && call != null) {
            call.cancel();
        }
    }

    public void quickAck() {
        QuickAckDelegate delegate = onQuickAck;
        if (delegate != null && !cancelled.get()) {
            Utilities.stageQueue.postRunnable(delegate::run);
        }
    }

    public void wroteToSocket() {
        WriteToSocketDelegate delegate = onWriteToSocket;
        if (delegate != null && !cancelled.get()) {
            Utilities.stageQueue.postRunnable(delegate::run);
        }
    }

    public void reply(TLObject response) {
        deliver(null, response, null);
    }

    public void replyLate(Late builder) {
        deliver(builder, null, null);
    }

    public void fail(int code, String text) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = code;
        error.text = text;
        deliver(null, null, error);
    }

    private void deliver(Late builder, TLObject response, TLRPC.TL_error error) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        FoxMesTransport.getInstance(account).finished(this);
        Utilities.stageQueue.postRunnable(() -> {
            if (cancelled.get()) {
                if (response != null) {
                    response.freeResources();
                }
                return;
            }
            TLObject result = response;
            TLRPC.TL_error resultError = error;
            if (builder != null) {
                try {
                    result = builder.build();
                } catch (Exception e) {
                    FoxMesLog.e("late response " + request.getClass().getSimpleName(), e);
                    resultError = new TLRPC.TL_error();
                    resultError.code = 400;
                    resultError.text = "FOXMES_INTERNAL";
                }
            }
            if (result != null) {
                result.networkType = ApplicationLoader.getCurrentNetworkType();
            }
            try {
                if (onComplete != null) {
                    onComplete.run(result, resultError);
                } else if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(result, resultError, System.currentTimeMillis());
                } else if (result instanceof TLRPC.Updates) {
                    KeepAliveJob.finishJob();
                    AccountInstance.getInstance(account).getMessagesController().processUpdates((TLRPC.Updates) result, false);
                }
            } catch (Exception e) {
                FoxMesLog.e("delivering " + request.getClass().getSimpleName(), e);
            }
            if (result != null) {
                result.freeResources();
            }
        });
    }

    void cancel(Runnable onCancelled) {
        this.onCancelled = onCancelled;
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Call current = call;
        if (current != null) {
            current.cancel();
        }
        if (done.compareAndSet(false, true)) {
            FoxMesTransport.getInstance(account).finished(this);
        }
        Runnable callback = onCancelled;
        if (callback != null) {
            Utilities.stageQueue.postRunnable(callback);
        }
    }
}
