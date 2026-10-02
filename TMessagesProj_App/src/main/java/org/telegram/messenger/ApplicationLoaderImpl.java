package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

import org.telegram.messenger.foxmes.FoxMesUpdater;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

import java.io.File;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public boolean isCustomUpdate() {
        return FoxMesUpdater.isSupported();
    }

    @Override
    public BetaUpdate getUpdate() {
        if (!isCustomUpdate()) return null;
        return FoxMesUpdater.getInstance().getUpdate();
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        if (!isCustomUpdate()) return;
        FoxMesUpdater.getInstance().checkForUpdate(force, whenDone);
    }

    @Override
    public void downloadUpdate() {
        if (!isCustomUpdate()) return;
        FoxMesUpdater.getInstance().downloadUpdate();
    }

    @Override
    public void cancelDownloadingUpdate() {
        if (!isCustomUpdate()) return;
        FoxMesUpdater.getInstance().cancelDownloadingUpdate();
    }

    @Override
    public boolean isDownloadingUpdate() {
        if (!isCustomUpdate()) return false;
        return FoxMesUpdater.getInstance().isDownloading();
    }

    @Override
    public float getDownloadingUpdateProgress() {
        if (!isCustomUpdate()) return 0;
        return FoxMesUpdater.getInstance().getDownloadingProgress();
    }

    @Override
    public File getDownloadedUpdateFile() {
        if (!isCustomUpdate()) return null;
        return FoxMesUpdater.getInstance().getDownloadedFile();
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        if (!isCustomUpdate()) return null;
        return new UpdateLayout(activity, sideMenuContainer);
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }
}
