package org.telegram.ui.foxmes;

import android.text.TextUtils;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.foxmes.FoxMesModels;
import org.telegram.messenger.foxmes.FoxMesRuntime;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ReplyMessageLine;

public final class FoxMesReplyAppearance {
    public interface NativeCheck {
        int color(boolean animate);
    }

    public static int check(ReplyMessageLine line, MessageObject message,
            Theme.ResourcesProvider resourcesProvider, int type, NativeCheck nativeCheck) {
        int[] colors = colors(message, resourcesProvider, type);
        if (colors == null) return nativeCheck.color(true);
        nativeCheck.color(false);
        boolean dark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        line.setSimpleColor(colors[0], dark);
        line.color2 = colors[1];
        line.color3 = colors[2];
        line.hasColor2 = line.color2 != line.color1;
        line.hasColor3 = line.color3 != line.color1;
        if (line.hasColor3) {
            int secondary = line.color2;
            line.color2 = line.color3;
            line.color3 = secondary;
        }
        line.nameColor = line.emojiColor = line.color1;
        line.backgroundColor = Theme.multAlpha(line.color1, 0.10f);
        return line.nameColorAnimated.set(line.nameColor);
    }

    private static int[] colors(MessageObject message, Theme.ResourcesProvider provider, int type) {
        if (message == null || type != ReplyMessageLine.TYPE_REPLY || !message.isOutOwner()
                || message.shouldDrawWithoutBackground() || message.overrideLinkPeerColor != null
                || message.replyMessageObject == null || message.messageOwner == null
                || message.messageOwner.reply_to == null
                || message.replyMessageObject.messageOwner == null
                || message.replyMessageObject.messageOwner.from_id == null
                || !message.replyMessageObject.isFromUser()) return null;
        if (message.messageOwner.reply_to.reply_from != null
                && !TextUtils.isEmpty(message.messageOwner.reply_to.reply_from.from_name)) return null;
        int colorId = message.overrideLinkColor;
        if (colorId < 0) {
            long authorId = message.replyMessageObject.messageOwner.from_id.user_id;
            FoxMesModels.User author = FoxMesRuntime.getInstance(message.currentAccount).knownUser(authorId);
            if (author == null || author.appearance == null || author.appearance.nameColorId == null) return null;
            colorId = author.appearance.nameColorId;
        }
        if (colorId < 0 || colorId >= 21) return null;
        if (colorId < 7) {
            int color = Theme.getColor(Theme.keys_avatar_nameInMessage[colorId], provider);
            return new int[] {color, color, color};
        }
        MessagesController.PeerColors catalog = MessagesController.getInstance(message.currentAccount).peerColors;
        MessagesController.PeerColor color = catalog != null ? catalog.getColor(colorId) : null;
        if (color == null) return null;
        return new int[] {color.getColor(0, provider), color.getColor(1, provider), color.getColor(2, provider)};
    }

    private FoxMesReplyAppearance() {}
}
