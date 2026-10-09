package org.telegram.messenger.foxmes;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;

// Notification about an incoming chat request. Upstream builds notifications
// only from messages, and a request chat has none, so the bridge posts its own
// one, outside NotificationsController: its push messages are stored and would
// come back after a restart. Shown on the chat.request event (the push wakes the
// app and syncs it); removed when the request stops being an unseen incoming
// one: answered here or elsewhere, or the chat was opened on any device.
final class FoxMesChatRequestNotification {

    private static final String CHANNEL = "foxmes_chat_requests";

    private FoxMesChatRequestNotification() {
    }

    static void show(FoxMesRuntime runtime, long userId) {
        ArrayList<TLRPC.User> users = runtime.tlUsers(Collections.singletonList(userId));
        TLRPC.User user = users.isEmpty() ? null : users.get(0);
        int account = runtime.account;
        AndroidUtilities.runOnUIThread(() -> {
            if (MessagesController.getInstance(account).isDialogMuted(userId, 0)
                    || !NotificationsController.getInstance(account).isGlobalNotificationsEnabled(userId, false, false)) {
                return;
            }
            Context context = ApplicationLoader.applicationContext;
            createChannel(context);
            Intent intent = new Intent(context, LaunchActivity.class)
                    .setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("userId", userId)
                    .putExtra("currentAccount", account);
            PendingIntent open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(0xff11acfa)
                    .setContentTitle(user != null ? UserObject.getUserName(user) : null)
                    .setContentText("Wants to chat with you")
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(open);
            try {
                NotificationManagerCompat.from(context).notify(tag(account), (int) userId, builder.build());
            } catch (SecurityException e) {
                FoxMesLog.w("chat request notification: " + e.getMessage());
            }
        });
    }

    static void hide(int account, long userId) {
        AndroidUtilities.runOnUIThread(() -> NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(tag(account), (int) userId));
    }

    private static String tag(int account) {
        return CHANNEL + ":" + account;
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Chat requests", NotificationManager.IMPORTANCE_HIGH));
        }
    }
}
