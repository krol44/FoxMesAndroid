package org.telegram.ui.foxmes;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.foxmes.FoxMesConfiguration;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.theme.ThemeKey;
import org.telegram.ui.ChatActivity;

public final class FoxMesChatThemeUpdater {
    public static void apply(int account, long dialogId, TLRPC.ChatTheme theme,
            ChatActivity.ThemeDelegate delegate, boolean animated) {
        ChatThemeController controller = ChatThemeController.getInstance(account);
        controller.setDialogTheme(dialogId, theme, false);
        controller.putThemeIfNeeded(theme);
        ThemeKey key = ThemeKey.of(theme);
        String server = FoxMesConfiguration.baseURL();
        long owner = UserConfig.getInstance(account).getClientUserId();
        controller.requestChatTheme(key, result -> AndroidUtilities.runOnUIThread(() -> {
            if (!server.equals(FoxMesConfiguration.baseURL()) || owner != UserConfig.getInstance(account).getClientUserId()) return;
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(dialogId);
            if (full != null && !ThemeKey.equals(key, ThemeKey.of(full.theme))) return;
            delegate.setCurrentTheme(result, controller.getDialogWallpaper(dialogId), animated, null);
        }));
    }

    private FoxMesChatThemeUpdater() {}
}
