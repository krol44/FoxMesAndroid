package org.telegram.ui.foxmes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import org.telegram.messenger.foxmes.FoxMesLog;
import org.telegram.messenger.foxmes.FoxMesModels;
import org.telegram.messenger.foxmes.FoxMesSession;
import org.telegram.messenger.foxmes.FoxMesTokenStore;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class FoxMesLoginActivity extends BaseFragment {

    private static final int COLUMN_MAX_WIDTH = 340;
    private static final int COLUMN_INSET = 20;

    private final boolean addingAccount;
    private final FoxMesAuthService auth = new FoxMesAuthService();

    private static final String DEFAULT_LINK = "foxtail.ing/fm";
    private static final String COPY_CODE = "COPY CODE";
    private static final String CODE_COPIED = "Code copied to clipboard.";
    private static final String GETTING_CODE = "Getting a code…";
    private static final String WAITING = "Waiting for confirmation in the browser…";
    private static final String CODE_RENEWED = "The code has expired. Here is a new one.";
    private static final String DECLINED = "The login was declined in the browser. Here is a new code.";
    private static final String NO_CONNECTION = "No connection to FoxMes. Retrying…";
    private static final String ALREADY_ADDED = "This account is already added. Here is a new code.";
    private static final String STORAGE_UNAVAILABLE = "This device cannot keep the sign-in secure. Sign-in is not saved.";
    private static final long MAX_START_DELAY_MS = 30000;

    private TextView aboutLabel;
    private TextView codeLabel;
    private TextView openButton;
    private TextView copyButton;
    private TextView statusLabel;

    private String verificationUrl;
    private String userCode;
    private volatile boolean destroyed;
    private volatile int requestGeneration;

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

        aboutLabel = new TextView(context);
        aboutLabel.setText(aboutText(DEFAULT_LINK));
        aboutLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        aboutLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        aboutLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = AndroidUtilities.dp(14);
        column.addView(aboutLabel, aboutParams);

        codeLabel = new TextView(context);
        codeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        codeLabel.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        codeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeLabel.setGravity(Gravity.CENTER);
        codeLabel.setOnClickListener(v -> copyCode());
        column.addView(codeLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(64)));

        openButton = new TextView(context);
        openButton.setGravity(Gravity.CENTER);
        openButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        openButton.setTypeface(AndroidUtilities.bold());
        openButton.setSingleLine(true);
        openButton.setEllipsize(TextUtils.TruncateAt.END);
        openButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        openButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10),
                Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        openButton.setOnClickListener(v -> openVerificationPage());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50));
        openParams.topMargin = AndroidUtilities.dp(8);
        column.addView(openButton, openParams);

        copyButton = secondaryButton(context, COPY_CODE);
        copyButton.setOnClickListener(v -> copyCode());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(46));
        copyParams.topMargin = AndroidUtilities.dp(10);
        column.addView(copyButton, copyParams);

        statusLabel = new TextView(context);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setMinHeight(AndroidUtilities.dp(24));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = AndroidUtilities.dp(18);
        column.addView(statusLabel, statusParams);
        showCode(null, null, GETTING_CODE);

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
        if (addingAccount) {
            startLogin(null);
        } else {
            restoreSession();
        }
        return fragmentView;
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

    private static String aboutText(String link) {
        return "Open " + link + " in a browser where you are signed in to Foxtail, and enter this code:";
    }

    private static String linkLabel(String url) {
        Uri uri = url != null ? Uri.parse(url) : null;
        if (uri == null || TextUtils.isEmpty(uri.getHost())) {
            return DEFAULT_LINK;
        }
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String path = uri.getPath() != null ? uri.getPath() : "";
        return uri.getHost() + port + path;
    }

    private static String grouped(String code) {
        if (code.length() <= 3) {
            return code;
        }
        int middle = code.length() / 2;
        return code.substring(0, middle) + " " + code.substring(middle);
    }

    private static long parseExpiry(String value) {
        if (value == null) {
            return 0;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(value);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void showCode(String code, String url, String status) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || statusLabel == null) {
                return;
            }
            userCode = code;
            verificationUrl = url;
            String link = url != null ? linkLabel(url) : DEFAULT_LINK;
            aboutLabel.setText(aboutText(link));
            openButton.setText("Open " + link);
            codeLabel.setText(code != null ? grouped(code) : "––– –––");
            boolean ready = code != null;
            for (View view : new View[]{codeLabel, openButton, copyButton}) {
                view.setEnabled(ready);
            }
            openButton.setAlpha(ready ? 1f : 0.4f);
            copyButton.setAlpha(ready ? 1f : 0.4f);
            statusLabel.setText(status);
        });
    }

    private void setStatus(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || statusLabel == null) {
                return;
            }
            statusLabel.setText(text);
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
            startLogin(null);
            return;
        }
        final long userId = users.get(0);
        final int generation = ++requestGeneration;
        showCode(null, null, "Restoring the session…");
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
                        AndroidUtilities.runOnUIThread(() -> startLogin(null));
                        return;
                    }
                    String token = FoxMesTokenStore.loadToken(userId);
                    AndroidUtilities.runOnUIThread(() -> completeAuthorization(me, token));
                } catch (FoxMesHttpException e) {
                    if (e.isSessionRejected()) {
                        AndroidUtilities.runOnUIThread(() -> startLogin("The session has expired. Please log in again."));
                        return;
                    }
                    retry();
                } catch (Exception e) {
                    retry();
                }
            }

            private void retry() {
                setStatus(NO_CONNECTION);
                Utilities.globalQueue.postRunnable(this, delay);
                delay = Math.min(delay * 2, 30000);
            }
        });
    }

    private void startLogin(String status) {
        if (destroyed) {
            return;
        }
        final int generation = ++requestGeneration;
        showCode(null, null, status != null ? status : GETTING_CODE);
        Utilities.globalQueue.postRunnable(() -> requestCode(generation, status, 2000));
    }

    private boolean stale(int generation) {
        return destroyed || generation != requestGeneration;
    }

    private void requestCode(int generation, String status, long retryDelay) {
        if (stale(generation)) {
            return;
        }
        try {
            FoxMesModels.AuthStart start = auth.begin();
            if (start == null || TextUtils.isEmpty(start.request) || TextUtils.isEmpty(start.userCode)) {
                throw new IllegalStateException("invalid device start response");
            }
            String url = FoxMesConfiguration.verificationURL(start.verificationUrl);
            long expiresAt = parseExpiry(start.expiresAt);
            int interval = Math.max(start.interval, 1);
            showCode(start.userCode, url, status != null ? status : WAITING);
            schedulePoll(generation, start.request, expiresAt, interval);
        } catch (Exception e) {
            if (!(e instanceof FoxMesHttpException) || ((FoxMesHttpException) e).status != 429) {
                setStatus(NO_CONNECTION);
            }
            long next = Math.min(retryDelay * 2, MAX_START_DELAY_MS);
            Utilities.globalQueue.postRunnable(() -> requestCode(generation, status, next), retryDelay);
        }
    }

    private void schedulePoll(int generation, String request, long expiresAt, int interval) {
        Utilities.globalQueue.postRunnable(() -> poll(generation, request, expiresAt, interval), interval * 1000L);
    }

    private void renew(int generation, String status) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!stale(generation)) {
                startLogin(status);
            }
        });
    }

    private void poll(int generation, String request, long expiresAt, int interval) {
        if (stale(generation)) {
            return;
        }
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            renew(generation, CODE_RENEWED);
            return;
        }
        try {
            FoxMesModels.AuthPoll result = auth.poll(request);
            String sealedToken = result != null ? result.sealedToken() : null;
            if (sealedToken != null && result.user != null) {
                FoxMesModels.Me me = auth.me(sealedToken);
                AndroidUtilities.runOnUIThread(() -> {
                    if (stale(generation)) {
                        return;
                    }
                    if (FoxMesSession.isSignedIn(me.id)) {
                        startLogin(ALREADY_ADDED);
                        return;
                    }
                    completeAuthorization(me, sealedToken);
                });
                return;
            }
            int next = result != null && result.interval != null ? Math.max(result.interval, 1) : interval;
            setStatus(WAITING);
            schedulePoll(generation, request, expiresAt, next);
        } catch (FoxMesHttpException e) {
            if (e.status == 403) {
                renew(generation, DECLINED);
                return;
            }
            if (e.status == 410) {
                renew(generation, CODE_RENEWED);
                return;
            }
            if (e.status != 429) {
                setStatus(NO_CONNECTION);
            }
            schedulePoll(generation, request, expiresAt, interval);
        } catch (Exception e) {
            setStatus(NO_CONNECTION);
            schedulePoll(generation, request, expiresAt, interval);
        }
    }

    private void openVerificationPage() {
        if (verificationUrl == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getParentActivity().startActivity(intent);
        } catch (Exception e) {
            setStatus("Could not open the browser. Open " + linkLabel(verificationUrl) + " manually.");
        }
    }

    private void copyCode() {
        if (userCode == null) {
            return;
        }
        AndroidUtilities.addToClipboard(userCode);
        setStatus(CODE_COPIED);
    }

    private void completeAuthorization(FoxMesModels.User user, String token) {
        if (destroyed || token == null) {
            return;
        }
        ++requestGeneration;
        try {
            FoxMesSession.activate(currentAccount, user, token);
        } catch (IllegalStateException e) {
            FoxMesLog.e("sign-in storage", e);
            setStatus(STORAGE_UNAVAILABLE);
            return;
        }
        showCode(null, null, "Done. Loading FoxMes…");
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
