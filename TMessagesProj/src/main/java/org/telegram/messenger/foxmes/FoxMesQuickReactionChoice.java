package org.telegram.messenger.foxmes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Account preference policy, independent of UI and asset availability. */
public final class FoxMesQuickReactionChoice {
    public static final class Entry {
        public final int id;
        public final String emoji;
        public final int order;
        public Entry(int id, String emoji, int order) { this.id = id; this.emoji = emoji; this.order = order; }
    }

    public static List<Entry> ordered(List<Entry> catalog) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : catalog) {
            if (entry.id > 0 && entry.emoji != null && !entry.emoji.isEmpty()) result.add(entry);
        }
        result.sort(Comparator.comparingInt(entry -> entry.order));
        return result;
    }

    public static String encode(int id) { return "animated_" + id; }

    public static Long id(String value) {
        if (value == null || !value.startsWith("animated_")) return null;
        try {
            long id = Long.parseLong(value.substring(9));
            return id > 0 && id <= Integer.MAX_VALUE ? id : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    public static String migrate(String saved, List<Entry> catalog) {
        if (saved == null || saved.startsWith("animated_")) return saved;
        for (Entry entry : ordered(catalog)) {
            if (saved.equals(entry.emoji)) return encode(entry.id);
        }
        return effective(null, catalog);
    }

    public static String effective(String saved, List<Entry> catalog) {
        List<Entry> entries = ordered(catalog);
        Long selected = id(saved);
        for (Entry entry : entries) if (selected != null && entry.id == selected) return encode(entry.id);
        for (Entry entry : entries) if ("👍".equals(entry.emoji) || "👍️".equals(entry.emoji)) return encode(entry.id);
        return entries.isEmpty() ? null : encode(entries.get(0).id);
    }
}
