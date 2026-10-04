package org.telegram.ui.foxmes;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.foxmes.FoxMesConfiguration;
import org.telegram.messenger.foxmes.FoxMesRuntime;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

public final class FoxMesUi {

    public static final int CREATE_MEET = 0x0F0E0001;
    public static final int EDIT_CONTACT = 0x0F0E0002;

    private FoxMesUi() {
    }

    public static void openExternal(Context context, String url) {
        if (context == null || TextUtils.isEmpty(url)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignore) {
        }
    }

    private static String username(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null && !TextUtils.isEmpty(user.username) ? user.username : null;
    }

    public static void openProfileSettings(Context context, int account) {
        String username = username(account);
        openExternal(context, username != null ? FoxMesConfiguration.profileSettingsURL(username) : FoxMesConfiguration.webURL());
    }

    public static void openFaq(Context context) {
        openExternal(context, FoxMesConfiguration.faqURL);
    }

    public static void shareProfileLink(Context context, int account) {
        String username = username(account);
        shareText(context, username != null ? FoxMesConfiguration.publicProfileLink(username) : FoxMesConfiguration.webURL());
    }

    public static void shareText(Context context, String text) {
        if (context == null || TextUtils.isEmpty(text)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(intent, null);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception ignore) {
        }
    }

    public static void copyProfileLink(TLRPC.User user) {
        String username = UserObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) {
            AndroidUtilities.addToClipboard(FoxMesConfiguration.publicProfileLink(username));
        }
    }

    public static void addCreateMeet(ActionBarMenuItem menu, TLRPC.User user) {
        if (menu == null || user == null || user.bot || UserObject.isDeleted(user) || UserObject.isUserSelf(user)) {
            return;
        }
        menu.addSubItem(EDIT_CONTACT, R.drawable.msg_edit, LocaleController.getString(R.string.EditContact));
        menu.addSubItem(CREATE_MEET, R.drawable.msg_calls, "Create Meet");
    }

    public static void editContact(BaseFragment fragment, long userId) {
        editContact(fragment, userId, false);
    }

    public static void editContact(BaseFragment fragment, long userId, boolean focusNote) {
        if (fragment != null && userId != 0) {
            fragment.presentFragment(FoxMesEditContactActivity.of(userId, focusNote));
        }
    }

    public static void createMeet(BaseFragment fragment, long userId) {
        if (fragment == null || userId == 0) {
            return;
        }
        int account = fragment.getCurrentAccount();
        FoxMesRuntime.getInstance(account).createMeetLink(userId, url -> {
            if (TextUtils.isEmpty(url)) {
                if (fragment.getParentActivity() != null) {
                    BulletinFactory.of(fragment).createErrorBulletin("Could not create a meet.").show();
                }
                return;
            }
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(url, userId, null, null, null, true, null, null, null, true, 0, 0, null, false));
            openExternal(fragment.getParentActivity() != null ? fragment.getParentActivity() : fragment.getContext(), url);
        });
    }
}
