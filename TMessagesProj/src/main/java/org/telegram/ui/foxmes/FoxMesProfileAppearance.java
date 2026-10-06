package org.telegram.ui.foxmes;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProfileActivity;

import java.util.WeakHashMap;

public final class FoxMesProfileAppearance {
    private static final WeakHashMap<View, Stroke> strokes = new WeakHashMap<>();

    public static void paintOutline(View owner, Canvas canvas, int account, long userId, boolean topic,
            View avatar, ProfileActivity.AvatarImageView image,
            float expanded, Runnable nativePaint) {
        nativePaint.run();
        if (topic || userId <= 0 || expanded >= .2f || image == null
                || image.getVisibility() != View.VISIBLE || !image.getImageReceiver().getVisible()) return;
        MessagesController controller = MessagesController.getInstance(account);
        if (controller.getStoriesController().hasStories(userId)
                || controller.getStoriesController().hasUploadingStories(userId)) return;
        TLRPC.User user = controller.getUser(userId);
        if (user == null || !(user.profile_color instanceof TLRPC.TL_peerColor)
                || (user.profile_color.flags & 1) == 0) return;
        MessagesController.PeerColors catalog = controller.profilePeerColors;
        MessagesController.PeerColor color = catalog != null ? catalog.getColor(UserObject.getProfileColorId(user)) : null;
        if (color == null) return;
        boolean dark = Theme.isCurrentThemeDark();
        int top = color.getStoryColor1(dark), bottom = color.getStoryColor2(dark);
        float cx = avatar.getX() + avatar.getWidth() * avatar.getScaleX() / 2f;
        float cy = avatar.getY() + avatar.getHeight() * avatar.getScaleY() / 2f;
        float radius = Math.min(avatar.getWidth() * avatar.getScaleX(), avatar.getHeight() * avatar.getScaleY()) / 2f;
        float alpha = Math.max(0f, Math.min(1f, 1f - expanded / .2f)) * avatar.getAlpha() * image.getAlpha();
        if (radius <= 0f || alpha <= 0f) return;
        Stroke stroke = strokes.get(owner);
        if (stroke == null) { stroke = new Stroke(); strokes.put(owner, stroke); }
        if (stroke.top != top || stroke.bottom != bottom || stroke.cy != cy || stroke.radius != radius) {
            stroke.paint.setShader(new LinearGradient(cx, cy - radius, cx, cy + radius, top, bottom, Shader.TileMode.CLAMP));
            stroke.top = top; stroke.bottom = bottom; stroke.cy = cy; stroke.radius = radius;
        }
        stroke.paint.setAlpha((int) (255 * alpha));
        float width = AndroidUtilities.dpf2(3f);
        stroke.paint.setStrokeWidth(width);
        canvas.drawCircle(cx, cy, radius + AndroidUtilities.dpf2(3f) + width / 2f, stroke.paint);
    }

    private static final class Stroke {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int top, bottom;
        float cy, radius;
        Stroke() { paint.setStyle(Paint.Style.STROKE); }
    }

    private FoxMesProfileAppearance() {}
}
