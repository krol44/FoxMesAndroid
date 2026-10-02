package org.telegram.messenger.foxmes;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class FoxMesUpdateEngine {

    private static final int JOURNAL_SIZE = 1000;

    private final int account;
    private int issued;
    private final ArrayDeque<Entry> journal = new ArrayDeque<>();
    private final ArrayList<Runnable> waiting = new ArrayList<>();

    private static final class Entry {
        final int pts;
        final TLRPC.Update update;
        final ArrayList<TLRPC.User> users;

        Entry(int pts, TLRPC.Update update, ArrayList<TLRPC.User> users) {
            this.pts = pts;
            this.update = update;
            this.users = users;
        }
    }

    FoxMesUpdateEngine(int account) {
        this.account = account;
    }

    private MessagesStorage storage() {
        return AccountInstance.getInstance(account).getMessagesStorage();
    }

    public boolean isReady() {
        return storage().getLastPtsValue() > 0;
    }

    public int currentPts() {
        return Math.max(Math.max(issued, storage().getLastPtsValue()), 1);
    }

    private int nextPts() {
        issued = Math.max(issued, storage().getLastPtsValue()) + 1;
        return issued;
    }

    public void reset() {
        issued = 0;
        journal.clear();
        waiting.clear();
    }

    public static boolean isPtsUpdate(TLRPC.Update update) {
        return update instanceof TL_update.TL_updateNewMessage
                || update instanceof TL_update.TL_updateEditMessage
                || update instanceof TL_update.TL_updateDeleteMessages
                || update instanceof TL_update.TL_updateReadHistoryInbox
                || update instanceof TL_update.TL_updateReadHistoryOutbox
                || update instanceof TL_update.TL_updateReadMessagesContents
                || update instanceof TL_update.TL_updatePinnedMessages
                || update instanceof TL_update.TL_updateFolderPeers;
    }

    public void assignPts(TLRPC.Update update, ArrayList<TLRPC.User> users) {
        if (!isPtsUpdate(update)) {
            return;
        }
        int pts = nextPts();
        if (update instanceof TL_update.TL_updateNewMessage) {
            ((TL_update.TL_updateNewMessage) update).pts = pts;
            ((TL_update.TL_updateNewMessage) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateEditMessage) {
            ((TL_update.TL_updateEditMessage) update).pts = pts;
            ((TL_update.TL_updateEditMessage) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateDeleteMessages) {
            ((TL_update.TL_updateDeleteMessages) update).pts = pts;
            ((TL_update.TL_updateDeleteMessages) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateReadHistoryInbox) {
            ((TL_update.TL_updateReadHistoryInbox) update).pts = pts;
            ((TL_update.TL_updateReadHistoryInbox) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateReadHistoryOutbox) {
            ((TL_update.TL_updateReadHistoryOutbox) update).pts = pts;
            ((TL_update.TL_updateReadHistoryOutbox) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateReadMessagesContents) {
            ((TL_update.TL_updateReadMessagesContents) update).pts = pts;
            ((TL_update.TL_updateReadMessagesContents) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updatePinnedMessages) {
            ((TL_update.TL_updatePinnedMessages) update).pts = pts;
            ((TL_update.TL_updatePinnedMessages) update).pts_count = 1;
        } else if (update instanceof TL_update.TL_updateFolderPeers) {
            ((TL_update.TL_updateFolderPeers) update).pts = pts;
            ((TL_update.TL_updateFolderPeers) update).pts_count = 1;
        }
        journal.addLast(new Entry(pts, update, users));
        while (journal.size() > JOURNAL_SIZE) {
            journal.removeFirst();
        }
    }

    public TLRPC.TL_updates container(List<TLRPC.Update> updates, ArrayList<TLRPC.User> users) {
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.seq = 0;
        result.users.addAll(users);
        for (TLRPC.Update update : updates) {
            assignPts(update, users);
            result.updates.add(update);
        }
        return result;
    }

    public void dispatch(List<TLRPC.Update> updates, ArrayList<TLRPC.User> users, Runnable afterApplied) {
        Utilities.stageQueue.postRunnable(() -> {
            Runnable apply = () -> {
                TLRPC.TL_updates container = container(updates, users);
                AccountInstance.getInstance(account).getMessagesController().processUpdates(container, false);
                if (afterApplied != null) {
                    storage().getStorageQueue().postRunnable(afterApplied);
                }
            };
            if (!isReady()) {
                waiting.add(apply);
                return;
            }
            flushWaiting();
            apply.run();
        });
    }

    public void flushWaiting() {
        if (waiting.isEmpty() || !isReady()) {
            return;
        }
        ArrayList<Runnable> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable runnable : pending) {
            runnable.run();
        }
    }

    public TLRPC.TL_updates_state state() {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = currentPts();
        issued = Math.max(issued, state.pts);
        state.qts = 0;
        state.seq = 0;
        state.date = (int) (System.currentTimeMillis() / 1000);
        state.unread_count = 0;
        return state;
    }

    public TLObject difference(int fromPts) {
        int current = currentPts();
        int date = (int) (System.currentTimeMillis() / 1000);
        if (fromPts >= current) {
            TLRPC.TL_updates_differenceEmpty empty = new TLRPC.TL_updates_differenceEmpty();
            empty.date = date;
            empty.seq = 0;
            return empty;
        }
        Entry first = journal.peekFirst();
        if (first == null || first.pts > fromPts + 1) {
            TLRPC.TL_updates_differenceTooLong tooLong = new TLRPC.TL_updates_differenceTooLong();
            tooLong.pts = current;
            return tooLong;
        }
        TLRPC.TL_updates_difference difference = new TLRPC.TL_updates_difference();
        ArrayList<Long> seenUsers = new ArrayList<>();
        Iterator<Entry> iterator = journal.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.pts <= fromPts) {
                continue;
            }
            if (entry.update instanceof TL_update.TL_updateNewMessage) {
                difference.new_messages.add(((TL_update.TL_updateNewMessage) entry.update).message);
            } else {
                difference.other_updates.add(entry.update);
            }
            for (TLRPC.User user : entry.users) {
                if (!seenUsers.contains(user.id)) {
                    seenUsers.add(user.id);
                    difference.users.add(user);
                }
            }
        }
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = current;
        state.qts = 0;
        state.seq = 0;
        state.date = date;
        difference.state = state;
        return difference;
    }
}
