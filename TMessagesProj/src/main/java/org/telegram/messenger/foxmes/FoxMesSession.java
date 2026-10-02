package org.telegram.messenger.foxmes;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.foxmes.FoxMesModels.User;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class FoxMesSession {

    private FoxMesSession() {
    }

    public static void activate(int account, User source, String token) {
        FoxMesTokenStore.save(token, source.id);
        FoxMesStore store = FoxMesStore.getInstance(account);
        store.clearAll();
        FoxMesTL.MapContext context = new FoxMesTL.MapContext() {
            @Override
            public long selfId() {
                return source.id;
            }

            @Override
            public long peerForChat(long chatId) {
                return 0;
            }

            @Override
            public boolean isCatalogReaction(int emojiId) {
                return true;
            }

            @Override
            public void recordPhotoUrl(long photoId, String url) {
                store.putPhotoUrl(photoId, url);
            }

            @Override
            public boolean isOnline(long userId) {
                return false;
            }
        };
        TLRPC.User user = FoxMesTL.user(source, context);

        MessagesController.getInstance(account).cleanup();
        UserConfig config = UserConfig.getInstance(account);
        config.clearConfig();
        MessagesController.getInstance(account).cleanup();
        config.syncContacts = false;
        config.setCurrentUser(user);
        config.saveConfig(true);
        MessagesStorage.getInstance(account).cleanup(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(account).putUser(user, false);
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.rememberUser(source);
        runtime.start();
        MessagesController.getInstance(account).loadAppConfig();
    }

    public static boolean isSignedIn(long userId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == userId) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOtherAccounts(int account) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != account && UserConfig.getInstance(a).isClientActivated()) {
                return true;
            }
        }
        return false;
    }
}
