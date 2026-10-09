package org.telegram.ui.foxmes;

import android.view.Gravity;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.foxmes.FoxMesQuickReaction;
import org.telegram.messenger.foxmes.FoxMesQuickReactionChoice;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import java.util.ArrayList;
import java.util.function.Consumer;

public final class FoxMesDefaultReactionPicker {
    public static void show(BaseFragment fragment, int account, View anchor,
                            AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable drawable, Runnable changed,
                            SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow existing,
                            Consumer<SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow> owner) {
        if (existing != null || anchor == null || fragment.getContext() == null) return;
        int xoff = 0, yoff = 0;
        if (drawable != null) {
            drawable.play();
            AndroidUtilities.rectTmp2.set(drawable.getBounds());
            yoff = -(anchor.getHeight() - AndroidUtilities.rectTmp2.centerY()) - AndroidUtilities.dp(16);
            int width = (int) Math.min(AndroidUtilities.dp(340 - 16), AndroidUtilities.displaySize.x * .95f);
            xoff = AndroidUtilities.rectTmp2.centerX() - (AndroidUtilities.displaySize.x - AndroidUtilities.dp(12) - width);
        }
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        SelectAnimatedEmojiDialog layout = new SelectAnimatedEmojiDialog(fragment, fragment.getContext(), false, xoff, SelectAnimatedEmojiDialog.TYPE_SET_DEFAULT_REACTION, null) {
            private void choose(Long id) {
                if (id == null) return;
                FoxMesQuickReaction.set(account, "animated_" + id);
                changed.run();
                if (popup[0] != null) popup[0].dismiss();
            }
            @Override
            protected void onEmojiSelected(View view, Long id, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) { choose(id); }
            @Override
            protected void onReactionClick(ImageViewEmoji emoji, ReactionsLayoutInBubble.VisibleReaction reaction) { choose(reaction.documentId); }
        };
        Long selected = FoxMesQuickReactionChoice.id(FoxMesQuickReaction.get(account));
        if (selected != null) layout.setSelected(selected);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> reactions = new ArrayList<>();
        for (FoxMesQuickReactionChoice.Entry entry : FoxMesQuickReaction.catalog(account)) reactions.add(ReactionsLayoutInBubble.VisibleReaction.fromCustomEmoji((long) entry.id));
        layout.setRecentReactions(reactions);
        layout.setSaveState(3);
        layout.setScrimDrawable(drawable, anchor);
        popup[0] = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() { super.dismiss(); owner.accept(null); }
        };
        owner.accept(popup[0]);
        popup[0].showAsDropDown(anchor, 0, yoff, Gravity.TOP | Gravity.RIGHT);
        popup[0].dimBehind();
    }
}
