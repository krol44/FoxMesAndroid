package org.telegram.ui.foxmes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.foxmes.FoxMesAuthService;
import org.telegram.messenger.foxmes.FoxMesConfiguration;
import org.telegram.messenger.foxmes.FoxMesHttpException;
import org.telegram.messenger.foxmes.FoxMesModels;
import org.telegram.messenger.foxmes.FoxMesSession;
import org.telegram.messenger.foxmes.FoxMesTokenStore;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.List;

public class FoxMesLoginActivity extends BaseFragment {

    private static final int COLUMN_MAX_WIDTH = 340;
    private static final int COLUMN_INSET = 20;

    private final boolean addingAccount;
    private final FoxMesAuthService auth = new FoxMesAuthService();

    private TextView beginButton;
    private TextView copyButton;
    private EditText codeField;
    private TextView submitButton;
    private TextView statusLabel;

    private String pendingRequest;
    private String pendingUrl;
    private boolean destroyed;
    private boolean restoring;
    private int requestGeneration;

    public FoxMesLoginActivity() {
        this(-1);
    }

    public FoxMesLoginActivity(int account) {
        super();
        if (account >= 0) {
            currentAccount = account;
        }
        addingAccount = FoxMesSession.hasOtherAccounts(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        requestGeneration++;
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout holder = new FrameLayout(context);
        scroll.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout column = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = View.MeasureSpec.getSize(widthMeasureSpec);
                int width = Math.min(available - AndroidUtilities.dp(COLUMN_INSET * 2), AndroidUtilities.dp(COLUMN_MAX_WIDTH));
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY), heightMeasureSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        int top = Math.max((int) (AndroidUtilities.statusBarHeight / Math.max(1f, AndroidUtilities.density)) + 56, 88);
        columnParams.topMargin = AndroidUtilities.dp(top);
        columnParams.bottomMargin = AndroidUtilities.dp(24);
        holder.addView(column, columnParams);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.foxmes_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(logo, new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        TextView name = new TextView(context);
        name.setText(FoxMesConfiguration.productName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = AndroidUtilities.dp(12);
        header.addView(name, nameParams);
        column.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));

        column.addView(new StepDivider(context, 1), dividerParams());

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        beginButton = secondaryButton(context, "GET CODE");
        beginButton.setOnClickListener(v -> beginLogin());
        copyButton = secondaryButton(context, "COPY AUTH URL");
        copyButton.setOnClickListener(v -> copyAuthUrl());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(46), 1f);
        left.rightMargin = AndroidUtilities.dp(5);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, AndroidUtilities.dp(46), 1f);
        right.leftMargin = AndroidUtilities.dp(5);
        buttons.addView(beginButton, left);
        buttons.addView(copyButton, right);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(46));
        buttonsParams.topMargin = AndroidUtilities.dp(14);
        column.addView(buttons, buttonsParams);

        column.addView(new StepDivider(context, 2), dividerParams());

        codeField = new EditText(context);
        codeField.setHint("Code from foxtail.ing");
        codeField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        codeField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        codeField.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        codeField.setGravity(Gravity.CENTER);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeField.setSingleLine(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            codeField.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        }
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(AndroidUtilities.dp(10));
        border.setStroke(Math.max(1, AndroidUtilities.dp(1)), Theme.getColor(Theme.key_divider));
        border.setColor(0);
        codeField.setBackground(border);
        codeField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52));
        fieldParams.topMargin = AndroidUtilities.dp(14);
        column.addView(codeField, fieldParams);

        submitButton = new TextView(context);
        submitButton.setText("Connect");
        submitButton.setGravity(Gravity.CENTER);
        submitButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        submitButton.setTypeface(AndroidUtilities.bold());
        submitButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        submitButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10),
                Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        submitButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50));
        submitParams.topMargin = AndroidUtilities.dp(12);
        column.addView(submitButton, submitParams);

        statusLabel = new TextView(context);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setMinHeight(AndroidUtilities.dp(24));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = AndroidUtilities.dp(18);
        column.addView(statusLabel, statusParams);

        if (addingAccount) {
            TextView cancel = new TextView(context);
            cancel.setText(LocaleController.getString(R.string.Cancel));
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cancel.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            cancel.setGravity(Gravity.CENTER_VERTICAL);
            cancel.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            cancel.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.15f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            cancel.setOnClickListener(v -> finishFragment());
            FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44), Gravity.TOP | Gravity.LEFT);
            cancelParams.topMargin = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(6);
            cancelParams.leftMargin = AndroidUtilities.dp(4);
            root.addView(cancel, cancelParams);
        }

        fragmentView = root;
        if (!addingAccount) {
            restoreSession();
        }
        return fragmentView;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(26));
        params.topMargin = AndroidUtilities.dp(14);
        return params;
    }

    private TextView secondaryButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        int background = Theme.getColor(Theme.key_windowBackgroundGray);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), background,
                Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.15f)));
        return button;
    }

    private static final class StepDivider extends View {
        private final String number;
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StepDivider(Context context, int number) {
            super(context);
            this.number = String.valueOf(number);
            linePaint.setColor(Theme.getColor(Theme.key_divider));
            linePaint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.5f)));
            badgePaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            textPaint.setColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            textPaint.setTextSize(AndroidUtilities.dp(14));
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = AndroidUtilities.dp(13);
            float skip = AndroidUtilities.dp(14);
            canvas.drawLine(0, cy, cx - radius - skip, cy, linePaint);
            canvas.drawLine(cx + radius + skip, cy, getWidth(), cy, linePaint);
            canvas.drawCircle(cx, cy, radius, badgePaint);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            canvas.drawText(number, cx, cy - (metrics.ascent + metrics.descent) / 2f, textPaint);
        }
    }


    private void setStatus(String text, boolean busy) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || statusLabel == null) {
                return;
            }
            statusLabel.setText(text);
            beginButton.setEnabled(!busy);
            copyButton.setEnabled(!busy);
            submitButton.setEnabled(!busy);
            codeField.setEnabled(!busy);
            float alpha = busy ? 0.4f : 1f;
            beginButton.setAlpha(alpha);
            copyButton.setAlpha(alpha);
            submitButton.setAlpha(alpha);
        });
    }

    private void restoreSession() {
        final int generation = requestGeneration;
        Utilities.stageQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed && generation == requestGeneration) {
                restoreStoredSession();
            }
        }));
    }

    private void restoreStoredSession() {
        List<Long> users = FoxMesTokenStore.knownUserIds();
        if (users.isEmpty()) {
            return;
        }
        final long userId = users.get(0);
        final int generation = ++requestGeneration;
        restoring = true;
        setStatus("Restoring the session…", true);
        Utilities.globalQueue.postRunnable(new Runnable() {
            long delay = 2000;

            @Override
            public void run() {
                if (destroyed || generation != requestGeneration) {
                    return;
                }
                try {
                    FoxMesModels.Me me = auth.restore(userId);
                    if (me == null) {
                        restoring = false;
                        setStatus("FoxMes authorization required.", false);
                        return;
                    }
                    String token = FoxMesTokenStore.loadToken(userId);
                    AndroidUtilities.runOnUIThread(() -> completeAuthorization(me, token));
                } catch (FoxMesHttpException e) {
                    if (e.isSessionRejected()) {
                        restoring = false;
                        setStatus("The session has expired. Please log in again.", false);
                        return;
                    }
                    retry();
                } catch (Exception e) {
                    retry();
                }
            }

            private void retry() {
                setStatus("No connection to FoxMes. Retrying…", false);
                Utilities.globalQueue.postRunnable(this, delay);
                delay = Math.min(delay * 2, 30000);
            }
        });
    }

    private void requestPairing(Utilities.Callback<String> then) {
        final int generation = ++requestGeneration;
        restoring = false;
        setStatus("Creating the login request…", true);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                FoxMesModels.AuthStart start = auth.begin();
                String url = FoxMesConfiguration.pairingURL(start.request, start.url);
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || generation != requestGeneration) {
                        return;
                    }
                    pendingRequest = start.request;
                    pendingUrl = url;
                    codeField.setText("");
                    setStatus("Confirm the login in the browser, then enter the code.", false);
                    then.run(url);
                });
            } catch (Exception e) {
                setStatus("Could not start the login: " + describe(e), false);
            }
        });
    }

    private void beginLogin() {
        requestPairing(url -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getParentActivity().startActivity(intent);
            } catch (Exception e) {
                setStatus("Could not open the browser. Copy the auth url instead.", false);
            }
        });
    }

    private void copyAuthUrl() {
        if (pendingUrl != null) {
            copy(pendingUrl);
            return;
        }
        requestPairing(this::copy);
    }

    private void copy(String url) {
        AndroidUtilities.addToClipboard(url);
        setStatus("Auth url copied to clipboard.", false);
    }

    private void submit() {
        String code = codeField.getText() != null ? codeField.getText().toString().trim() : "";
        final String request = pendingRequest;
        if (request == null) {
            setStatus("Preparing the connection. Try again in a moment.", false);
            return;
        }
        if (code.length() != 6) {
            setStatus("Enter the code from foxtail.ing.", false);
            return;
        }
        AndroidUtilities.hideKeyboard(codeField);
        setStatus("Checking the code…", true);
        final int generation = ++requestGeneration;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                FoxMesModels.AuthExchange exchange = auth.exchange(request, code);
                FoxMesModels.Me me = auth.me(exchange.accessToken);
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || generation != requestGeneration) {
                        return;
                    }
                    if (FoxMesSession.isSignedIn(me.id)) {
                        setStatus("This account is already added.", false);
                        return;
                    }
                    completeAuthorization(me, exchange.accessToken);
                });
            } catch (Exception e) {
                setStatus("The code was rejected: " + describe(e), false);
            }
        });
    }

    private static String describe(Exception e) {
        if (e instanceof FoxMesHttpException) {
            FoxMesHttpException http = (FoxMesHttpException) e;
            return http.serverMessage != null ? http.serverMessage : (http.code != null ? http.code : "HTTP " + http.status);
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void completeAuthorization(FoxMesModels.User user, String token) {
        if (destroyed || token == null) {
            return;
        }
        setStatus("Done. Loading FoxMes…", true);
        FoxMesSession.activate(currentAccount, user, token);
        openChats();
    }

    private void openChats() {
        if (destroyed || !(getParentActivity() instanceof LaunchActivity)) {
            return;
        }
        if (parentLayout == null || parentLayout.checkTransitionAnimation()) {
            AndroidUtilities.runOnUIThread(this::openChats, 100);
            return;
        }
        if (addingAccount) {
            ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true, obj -> {
                MainTabsActivity tabs = new MainTabsActivity();
                tabs.prepareDialogsActivity(new Bundle());
                return tabs;
            });
            finishFragment();
        } else {
            MainTabsActivity tabs = new MainTabsActivity();
            tabs.prepareDialogsActivity(new Bundle());
            if (!presentFragment(tabs, true)) {
                AndroidUtilities.runOnUIThread(this::openChats, 100);
                return;
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            LocaleController.getInstance().loadRemoteLanguages(currentAccount);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(Theme.getColor(Theme.key_windowBackgroundWhite)) > 0.721f;
    }
}
