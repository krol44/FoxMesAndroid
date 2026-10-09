package org.telegram.ui.foxmes;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;

import androidx.annotation.NonNull;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserInfoCell;
import org.telegram.ui.Components.AvatarDrawable;

public class FoxMesUserInfoCell extends UserInfoCell {

    private static final int AVATAR_SIZE = 72;
    private static final int AVATAR_GAP = 10;

    private final int currentAccount;
    private final ImageReceiver avatarImage = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();

    public FoxMesUserInfoCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context, currentAccount, resourcesProvider);
        this.currentAccount = currentAccount;
        avatarImage.setRoundRadius(dp(AVATAR_SIZE / 2));
    }

    @Override
    public void set(long dialogId, TLRPC.PeerSettings settings) {
        super.set(dialogId, settings);
        TLRPC.User user = dialogId > 0 ? MessagesController.getInstance(currentAccount).getUser(dialogId) : null;
        if (user != null) {
            avatarDrawable.setInfo(currentAccount, user);
            avatarImage.setForUserOrChat(user, avatarDrawable);
        }
    }

    private int avatarSpace() {
        return dp(AVATAR_SIZE + AVATAR_GAP);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + avatarSpace());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int size = dp(AVATAR_SIZE);
        avatarImage.setImageCoords((getWidth() - size) / 2f, dp(8), size, size);
        avatarImage.draw(canvas);
        canvas.save();
        canvas.translate(0, avatarSpace() / 2f);
        super.onDraw(canvas);
        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        avatarImage.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        avatarImage.onDetachedFromWindow();
    }
}
