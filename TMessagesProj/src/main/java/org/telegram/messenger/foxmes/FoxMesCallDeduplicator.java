package org.telegram.messenger.foxmes;

import java.util.LinkedHashSet;

final class FoxMesCallDeduplicator {

    private static final int MAX_REQUESTS = 128;
    private final LinkedHashSet<Long> requests = new LinkedHashSet<>();

    synchronized boolean firstRequest(long callId) {
        if (!requests.add(callId)) {
            return false;
        }
        if (requests.size() > MAX_REQUESTS) {
            requests.remove(requests.iterator().next());
        }
        return true;
    }

    synchronized void reset() {
        requests.clear();
    }
}
