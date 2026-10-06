package org.telegram.ui.foxmes;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.foxmes.FoxMesRuntime;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ImageUpdater;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.io.File;

// The edit form of a direct chat's person: a name, a photo and a note only
// this account sees, and blocking. FoxMes has no contacts - the chat is the
// contact - so this replaces the upstream contact editor.
public class FoxMesEditContactActivity extends BaseFragment implements ImageUpdater.ImageUpdaterDelegate {

    private static final int DONE = 1;
    private static final int MAX_NAME = 128;
    private static final int MAX_NOTE = 128;

    private long userId;
    private ImageUpdater imageUpdater;
    private EditTextCell nameField;
    private EditTextCell noteField;
    private TextCell resetPhotoCell;
    private TextCell blockCell;
    private BackupImageView avatarImage;
    private View avatarOverlay;
    private RadialProgressView avatarProgressView;
    private AnimatorSet avatarAnimation;
    private boolean uploadingPhoto;
    private TextView nameView;
    private boolean saving;

    public FoxMesEditContactActivity(Bundle args) {
        super(args);
    }

    public static FoxMesEditContactActivity of(long userId) {
        return of(userId, false);
    }

    public static FoxMesEditContactActivity of(long userId, boolean focusNote) {
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        args.putBoolean("focus_notes", focusNote);
        return new FoxMesEditContactActivity(args);
    }

    @Override
    public boolean onFragmentCreate() {
        userId = getArguments().getLong("user_id", 0);
        if (userId == 0 || getMessagesController().getUser(userId) == null) {
            return false;
        }
        imageUpdater = new ImageUpdater(false, ImageUpdater.FOR_TYPE_USER, false);
        imageUpdater.parentFragment = this;
        imageUpdater.setDelegate(this);
        imageUpdater.setUploadAfterSelect(false);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (imageUpdater != null) {
            imageUpdater.clear();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (imageUpdater != null) {
            imageUpdater.onResume();
        }
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (imageUpdater != null) {
            imageUpdater.onPause();
        }
    }

    private TLRPC.User user() {
        return getMessagesController().getUser(userId);
    }

    private FoxMesRuntime runtime() {
        return FoxMesRuntime.getInstance(currentAccount);
    }

    @Override
    public View createView(Context context) {
        TLRPC.User user = user();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EditContact));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE) {
                    save();
                }
            }
        });
        actionBar.createMenu().addItem(DONE, LocaleController.getString(R.string.Done).toUpperCase());

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        avatarImage = new BackupImageView(context);
        avatarImage.setRoundRadius(dp(32));
        header.addView(avatarImage, LayoutHelper.createFrame(64, 64, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 16, 13, 16, 13));
        Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        overlayPaint.setColor(0x55000000);
        avatarOverlay = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (avatarImage != null && avatarImage.getImageReceiver().hasNotThumb()) {
                    overlayPaint.setAlpha((int) (0x55 * avatarImage.getImageReceiver().getCurrentAlpha()));
                    canvas.drawCircle(getMeasuredWidth() / 2.0f, getMeasuredHeight() / 2.0f, getMeasuredWidth() / 2.0f, overlayPaint);
                }
            }
        };
        header.addView(avatarOverlay, LayoutHelper.createFrame(64, 64, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 16, 13, 16, 13));
        avatarProgressView = new RadialProgressView(context);
        avatarProgressView.setSize(dp(30));
        avatarProgressView.setProgressColor(0xffffffff);
        avatarProgressView.setNoProgress(false);
        header.addView(avatarProgressView, LayoutHelper.createFrame(64, 64, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 16, 13, 16, 13));
        showAvatarProgress(uploadingPhoto, false);
        nameView = new TextView(context);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setTypeface(AndroidUtilities.bold());
        header.addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 16 : 94, 25.66f, LocaleController.isRTL ? 94 : 16, 0));
        TextView usernameView = new TextView(context);
        usernameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        usernameView.setSingleLine(true);
        String username = UserObject.getPublicUsername(user);
        usernameView.setText(TextUtils.isEmpty(username) ? "" : "@" + username);
        header.addView(usernameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 16 : 94, 49.66f, LocaleController.isRTL ? 94 : 16, 0));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 90));

        nameField = new EditTextCell(context, "Name", false, false, MAX_NAME, null);
        nameField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        nameField.setText(UserObject.getUserName(user));
        layout.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        noteField = new EditTextCell(context, LocaleController.getString(R.string.ContactNote), true, false, MAX_NOTE, null);
        noteField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        noteField.setText(runtime().contactNote(userId));
        layout.addView(noteField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextInfoPrivacyCell noteInfo = new TextInfoPrivacyCell(context);
        noteInfo.setText(LocaleController.getString(R.string.AddNotesInfo));
        layout.addView(noteInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCell setPhotoCell = new TextCell(context);
        setPhotoCell.setTextAndIcon(LocaleController.formatString(R.string.UserSetPhoto, UserObject.getFirstName(user)), R.drawable.msg_addphoto, true);
        setPhotoCell.setBackground(Theme.getSelectorDrawable(true));
        setPhotoCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
        setPhotoCell.setOnClickListener(v -> {
            imageUpdater.setUser(user());
            imageUpdater.openMenu(false, null, null, ImageUpdater.TYPE_SET_PHOTO_FOR_USER);
        });
        layout.addView(setPhotoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        resetPhotoCell = new TextCell(context);
        resetPhotoCell.setTextAndIcon(LocaleController.getString(R.string.ResetToOriginalPhoto), R.drawable.msg_photo_switch2, false);
        resetPhotoCell.setBackground(Theme.getSelectorDrawable(true));
        resetPhotoCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
        resetPhotoCell.setOnClickListener(v -> AlertsCreator.createSimpleAlert(context,
                LocaleController.getString(R.string.ResetToOriginalPhotoTitle),
                LocaleController.formatString(R.string.ResetToOriginalPhotoMessage, UserObject.getFirstName(user())),
                LocaleController.getString(R.string.Reset), () -> runtime().resetContactPhoto(userId, ok -> {
                    if (!ok) {
                        showError("Could not reset the photo.");
                    }
                    refresh();
                }), null).show());
        layout.addView(resetPhotoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText("You can replace " + UserObject.getFirstName(user) + "'s photo with another photo that only you will see.");
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        blockCell = new TextCell(context);
        blockCell.setBackground(Theme.getSelectorDrawable(true));
        blockCell.setColors(-1, Theme.key_text_RedRegular);
        blockCell.setOnClickListener(v -> toggleBlocked());
        layout.addView(blockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scrollView;
        refresh();
        if (getArguments().getBoolean("focus_notes", false)) {
            noteField.editText.requestFocus();
            noteField.editText.setSelection(noteField.editText.length());
            AndroidUtilities.showKeyboard(noteField.editText);
        }
        return fragmentView;
    }

    private void refresh() {
        TLRPC.User user = user();
        if (user == null || fragmentView == null) {
            return;
        }
        nameView.setText(UserObject.getUserName(user));
        if (!uploadingPhoto) {
            avatarImage.setForUserOrChat(user, new AvatarDrawable(user));
        }
        resetPhotoCell.setVisibility(runtime().hasContactPhoto(userId) ? View.VISIBLE : View.GONE);
        boolean blocked = getMessagesController().blockePeers.indexOfKey(userId) >= 0;
        blockCell.setText(LocaleController.getString(blocked ? R.string.Unblock : R.string.BlockUser), false);
    }

    private void save() {
        if (saving) {
            return;
        }
        String name = nameField.getText().toString().trim();
        String original = runtime().originalUserName(userId);
        if (TextUtils.equals(name, original)) {
            name = "";
        }
        String note = noteField.getText().toString().trim();
        String nameChange = TextUtils.equals(name, runtime().contactName(userId)) ? null : name;
        String noteChange = TextUtils.equals(note, runtime().contactNote(userId)) ? null : note;
        if (nameChange == null && noteChange == null) {
            finishFragment();
            return;
        }
        saving = true;
        runtime().setContactFields(userId, nameChange, noteChange, ok -> {
            saving = false;
            if (ok) {
                finishFragment();
            } else {
                showError("Could not save the contact.");
            }
        });
    }

    private void toggleBlocked() {
        TLRPC.User user = user();
        if (user == null || getParentActivity() == null) {
            return;
        }
        boolean blocked = getMessagesController().blockePeers.indexOfKey(userId) >= 0;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(blocked ? R.string.Unblock : R.string.BlockUser));
        builder.setMessage(blocked
                ? LocaleController.getString(R.string.AreYouSureUnblockContact)
                : AndroidUtilities.replaceTags(LocaleController.formatString(R.string.AreYouSureBlockContact2, UserObject.getFirstName(user))));
        builder.setPositiveButton(LocaleController.getString(blocked ? R.string.Unblock : R.string.BlockContact), (dialog, which) -> {
            if (blocked) {
                getMessagesController().unblockPeer(userId);
            } else {
                getMessagesController().blockPeer(userId);
            }
            AndroidUtilities.runOnUIThread(this::refresh, 300);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        if (!blocked) {
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button != null) {
                button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
        }
    }

    private void showError(String text) {
        if (getParentActivity() != null) {
            BulletinFactory.of(this).createErrorBulletin(text).show();
        }
    }

    @Override
    public void didUploadPhoto(TLRPC.InputFile photo, TLRPC.InputFile video, double videoStartTimestamp, String videoPath, TLRPC.PhotoSize bigSize, TLRPC.PhotoSize smallSize, boolean isVideo, TLRPC.VideoSize emojiMarkup) {
        AndroidUtilities.runOnUIThread(() -> {
            if (bigSize == null || imageUpdater.isCanceled()) {
                return;
            }
            uploadingPhoto = true;
            if (smallSize != null) {
                avatarImage.setImage(ImageLocation.getForLocal(smallSize.location), "50_50", new AvatarDrawable(user()), user());
            }
            showAvatarProgress(true, false);
            File file = FileLoader.getInstance(currentAccount).getPathToAttach(bigSize, true);
            runtime().setContactPhoto(userId, file, ok -> {
                uploadingPhoto = false;
                showAvatarProgress(false, true);
                if (!ok) {
                    showError("Could not set the photo.");
                }
                refresh();
            });
        });
    }

    // The same spinner over the avatar as the upstream contact editor.
    private void showAvatarProgress(boolean show, boolean animated) {
        if (avatarProgressView == null) {
            return;
        }
        if (avatarAnimation != null) {
            avatarAnimation.cancel();
            avatarAnimation = null;
        }
        if (animated) {
            avatarAnimation = new AnimatorSet();
            if (show) {
                avatarProgressView.setVisibility(View.VISIBLE);
                avatarOverlay.setVisibility(View.VISIBLE);
                avatarAnimation.playTogether(
                    ObjectAnimator.ofFloat(avatarProgressView, View.ALPHA, 1.0f),
                    ObjectAnimator.ofFloat(avatarOverlay, View.ALPHA, 1.0f)
                );
            } else {
                avatarAnimation.playTogether(
                    ObjectAnimator.ofFloat(avatarProgressView, View.ALPHA, 0.0f),
                    ObjectAnimator.ofFloat(avatarOverlay, View.ALPHA, 0.0f)
                );
            }
            avatarAnimation.setDuration(180);
            avatarAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (avatarAnimation == null || avatarProgressView == null) {
                        return;
                    }
                    if (!show) {
                        avatarProgressView.setVisibility(View.INVISIBLE);
                        avatarOverlay.setVisibility(View.INVISIBLE);
                    }
                    avatarAnimation = null;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    avatarAnimation = null;
                }
            });
            avatarAnimation.start();
        } else {
            avatarProgressView.setAlpha(show ? 1.0f : 0.0f);
            avatarProgressView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            avatarOverlay.setAlpha(show ? 1.0f : 0.0f);
            avatarOverlay.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
    }
}
