package org.telegram.messenger.foxmes;

import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.Message;
import org.telegram.messenger.foxmes.FoxMesModels.MessagesPage;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FoxMesGapRecovery {
    interface Completion {
        void applied(List<Integer> deleted);
    }

    static void reconcile(FoxMesRuntime runtime, FoxMesRuntime.Snapshot snapshot, Completion afterApplied) throws IOException {
        Set<Long> active = new HashSet<>();
        for (Chat chat : snapshot.chats) {
            active.add(chat.id);
        }
        ArrayList<Integer> deleted = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : runtime.store().chatToPeer().entrySet()) {
            if (!runtime.isStarted()) {
                throw new IOException("session stopped");
            }
            List<Integer> known = runtime.store().messagesOfChat(entry.getKey());
            if (known.isEmpty()) {
                continue;
            }
            Set<Integer> missing = new HashSet<>(known);
            if (active.contains(entry.getKey())) {
                int oldest = Collections.min(known);
                Integer before = null;
                while (true) {
                    if (!runtime.isStarted()) {
                        throw new IOException("session stopped");
                    }
                    MessagesPage page = runtime.api.history(entry.getKey(), 100, before, null, null);
                    if (page == null || page.items == null) {
                        throw new IOException("incomplete history page");
                    }
                    for (Message message : page.items) {
                        missing.remove(message.id);
                    }
                    if (!page.hasMoreBefore || (!page.items.isEmpty()
                            && page.items.get(0).id <= oldest)) {
                        break;
                    }
                    if (page.nextBefore <= 0 || (before != null && page.nextBefore >= before)) {
                        throw new IOException("invalid history cursor");
                    }
                    before = page.nextBefore;
                }
            }
            deleted.addAll(missing);
        }
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        for (int offset = 0; offset < deleted.size(); offset += 100) {
            TL_update.TL_updateDeleteMessages update = new TL_update.TL_updateDeleteMessages();
            update.messages.addAll(deleted.subList(offset, Math.min(deleted.size(), offset + 100)));
            updates.add(update);
        }
        FoxMesArchive.apply(runtime, snapshot.chats, updates, new ArrayList<>(), () -> afterApplied.applied(deleted));
    }
}
