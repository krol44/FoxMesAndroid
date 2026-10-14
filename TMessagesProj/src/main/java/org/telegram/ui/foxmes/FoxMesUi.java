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
import org.telegram.messenger.foxmes.FoxMesChatRequests;
import org.telegram.messenger.foxmes.FoxMesConfiguration;
import org.telegram.messenger.foxmes.FoxMesRuntime;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.UnreadCounterTextView;

public final class FoxMesUi {

    public static final int CREATE_MEET = 0x0F0E0001;
    public static final int EDIT_CONTACT = 0x0F0E0002;

    public static final int STATUS_PICKER = 600301;

    public static void addStatusMenu(ActionBarMenuItem menu, int account) {
        if (menu == null || !org.telegram.messenger.foxmes.FoxMesPeerStatusHandlers.available(account)) return;
        menu.addSubItem(STATUS_PICKER, R.drawable.msg_emoji_smiles, LocaleController.getString(R.string.SetEmojiStatus));
    }
    public static void statusPicker(BaseFragment fragment) {
        if (fragment instanceof org.telegram.ui.ProfileActivity) ((org.telegram.ui.ProfileActivity) fragment).showStatusSelect();
    }

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

    public static boolean applyChatRequestOverlay(int account, TLRPC.User user, UnreadCounterTextView view) {
        FoxMesChatRequests.State state = FoxMesChatRequests.state(account, user);
        String text;
        switch (state.kind) {
            case INCOMING:
                text = "ACCEPT";
                break;
            case OUTGOING:
                text = "REQUEST SENT";
                break;
            case DECLINED:
                text = state.canRequest() ? "REQUEST CHAT" : "REQUEST DECLINED";
                break;
            default:
                text = "REQUEST CHAT";
                break;
        }
        view.setText(text);
        view.setVisibility(android.view.View.VISIBLE);
        return state.kind == FoxMesChatRequests.Kind.INCOMING || state.canRequest();
    }

    public static void chatRequestOverlayClicked(BaseFragment fragment, int account, TLRPC.User user) {
        FoxMesChatRequests.State state = FoxMesChatRequests.state(account, user);
        if (state.kind == FoxMesChatRequests.Kind.INCOMING) {
            FoxMesChatRequests.answer(account, user, true, ok -> chatRequestFailed(fragment, ok));
        } else if (state.canRequest()) {
            FoxMesChatRequests.request(account, user, ok -> chatRequestFailed(fragment, ok));
        } else if (state.kind == FoxMesChatRequests.Kind.OUTGOING) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, UserObject.getFirstName(user) + " will be able to chat with you after accepting the request.").show();
        } else if (state.kind == FoxMesChatRequests.Kind.DECLINED) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, "You can request a chat again on " + LocaleController.formatDateTime(state.retryAt, true) + ".").show();
        }
    }

    public static boolean answerChatRequest(BaseFragment fragment, int account, TLRPC.User user, boolean accept) {
        if (FoxMesChatRequests.state(account, user).kind != FoxMesChatRequests.Kind.INCOMING) {
            return false;
        }
        FoxMesChatRequests.answer(account, user, accept, ok -> chatRequestFailed(fragment, ok));
        return true;
    }

    public static void decorateChatRequestBar(int account, TLRPC.User user, android.widget.TextView accept, android.widget.TextView decline) {
        if (FoxMesChatRequests.state(account, user).kind != FoxMesChatRequests.Kind.INCOMING) {
            return;
        }
        if (accept != null) {
            accept.setText("ACCEPT");
        }
        if (decline != null) {
            decline.setText("DECLINE");
        }
    }

    public static Text chatRequestFooter(int account, long dialogId) {
        TLRPC.User user = dialogId > 0 ? org.telegram.messenger.MessagesController.getInstance(account).getUser(dialogId) : null;
        FoxMesChatRequests.State state = FoxMesChatRequests.state(account, user);
        if (user == null || !state.gated()) {
            return null;
        }
        String name = UserObject.getFirstName(user);
        String text;
        switch (state.kind) {
            case INCOMING:
                text = name + " wants to chat with you. You haven't talked before — accept to start chatting or decline to hide this chat.";
                break;
            case OUTGOING:
                text = "Chat request sent. You can write once " + name + " accepts it.";
                break;
            case DECLINED:
                text = state.canRequest()
                        ? name + " declined your chat request. You can send a new one."
                        : name + " declined your chat request. You can try again on " + LocaleController.formatDateTime(state.retryAt, true) + ".";
                break;
            default:
                text = "You haven't talked with " + name + " before. Send a chat request — you can write once it's accepted.";
                break;
        }
        return new Text(text, 12)
                .align(android.text.Layout.Alignment.ALIGN_CENTER)
                .multiline(6)
                .setMaxWidth(Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * .6f);
    }

    /** Chat list text of a request chat without messages; null when there is no request. */
    public static String chatRequestListPreview(int account, TLRPC.User user) {
        if (user == null) {
            return null;
        }
        switch (FoxMesChatRequests.state(account, user).kind) {
            case INCOMING:
                return "Wants to chat with you";
            case OUTGOING:
                return "Chat request sent";
            case DECLINED:
                return "Chat request declined";
            default:
                return null;
        }
    }

    private static void chatRequestFailed(BaseFragment fragment, boolean ok) {
        if (!ok && fragment != null && fragment.getParentActivity() != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, "Couldn't send the request. Please try again.").show();
        }
    }
}
