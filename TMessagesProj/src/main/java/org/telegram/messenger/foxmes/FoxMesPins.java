package org.telegram.messenger.foxmes;

import android.text.TextUtils;

import org.telegram.messenger.foxmes.FoxMesModels.PinnedState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class FoxMesPins {

    static final class Change {
        final ArrayList<Integer> added = new ArrayList<>();
        final ArrayList<Integer> removed = new ArrayList<>();
    }

    private FoxMesPins() {
    }

    static synchronized Change replace(FoxMesStore store, long chatId, PinnedState state) {
        String key = "pins_" + chatId;
        long knownRevision = -1;
        LinkedHashSet<Integer> previous = new LinkedHashSet<>();
        String saved = store.get(key);
        if (saved != null) {
            int split = saved.indexOf('|');
            try {
                knownRevision = Long.parseLong(split < 0 ? saved : saved.substring(0, split));
                if (split >= 0 && split + 1 < saved.length()) {
                    for (String id : saved.substring(split + 1).split(",")) {
                        previous.add(Integer.parseInt(id));
                    }
                }
            } catch (NumberFormatException e) {
                knownRevision = -1;
                previous.clear();
            }
        }
        if (state.pinRevision < knownRevision) {
            return null;
        }
        List<Integer> current = state.messageIds();
        Change change = new Change();
        for (Integer id : previous) {
            if (!current.contains(id)) {
                change.removed.add(id);
            }
        }
        for (Integer id : current) {
            if (!previous.contains(id)) {
                change.added.add(id);
            }
        }
        store.put(key, state.pinRevision + "|" + TextUtils.join(",", current));
        return change;
    }
}
