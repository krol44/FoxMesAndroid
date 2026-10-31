package org.telegram.messenger.foxmes;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class FoxMesCallNotifications {

    private FoxMesCallNotifications() {
    }

    public static ArrayList<MessageObject> filterMessages(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return null;
        }
        ArrayList<MessageObject> result = new ArrayList<>();
        for (MessageObject message : messages) {
            if (message == null || shouldNotify(message.messageOwner)) {
                result.add(message);
            }
        }
        return result;
    }

    public static ArrayList<TLRPC.Message> filterUnread(ArrayList<TLRPC.Message> messages) {
        if (messages == null) {
            return null;
        }
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            if (shouldNotify(message)) {
                result.add(message);
            }
        }
        return result;
    }

    private static boolean shouldNotify(TLRPC.Message message) {
        if (message == null || !(message.action instanceof TLRPC.TL_messageActionPhoneCall)) {
            return true;
        }
        return !message.out && message.action.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed;
    }
}
