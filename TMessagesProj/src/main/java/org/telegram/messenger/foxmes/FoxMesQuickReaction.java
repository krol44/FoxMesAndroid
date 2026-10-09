package org.telegram.messenger.foxmes;

import android.content.SharedPreferences;
import org.telegram.messenger.MessagesController;
import java.util.ArrayList;
import java.util.List;

public final class FoxMesQuickReaction {
    private static final String KEY = "reaction_on_double_tap";

    public static List<FoxMesQuickReactionChoice.Entry> catalog(int account) {
        ArrayList<FoxMesQuickReactionChoice.Entry> entries = new ArrayList<>();
        FoxMesModels.ReactionCatalog catalog = FoxMesRuntime.getInstance(account).catalog();
        if (catalog != null) {
            for (FoxMesModels.ReactionCatalogItem item : catalog.availableReactions()) {
                entries.add(new FoxMesQuickReactionChoice.Entry(item.id, item.emoji, item.order));
            }
        }
        return FoxMesQuickReactionChoice.ordered(entries);
    }

    public static synchronized String get(int account) {
        SharedPreferences prefs = MessagesController.getEmojiSettings(account);
        List<FoxMesQuickReactionChoice.Entry> catalog = catalog(account);
        String saved = prefs.getString(KEY, null);
        if (!catalog.isEmpty() && saved != null && !saved.startsWith("animated_")) {
            saved = FoxMesQuickReactionChoice.migrate(saved, catalog);
            prefs.edit().putString(KEY, saved).apply();
        }
        String effective = FoxMesQuickReactionChoice.effective(saved, catalog);
        // ChatActivity reads again after hasDoubleTap; a catalog refresh may occur between those reads.
        return effective != null ? effective : "";
    }

    public static synchronized void set(int account, String value) {
        List<FoxMesQuickReactionChoice.Entry> catalog = catalog(account);
        String migrated = FoxMesQuickReactionChoice.migrate(value, catalog);
        Long id = FoxMesQuickReactionChoice.id(migrated);
        if (id == null) return;
        for (FoxMesQuickReactionChoice.Entry entry : catalog) {
            if (entry.id == id) {
                MessagesController.getEmojiSettings(account).edit().putString(KEY, FoxMesQuickReactionChoice.encode(entry.id)).apply();
                return;
            }
        }
    }
}
