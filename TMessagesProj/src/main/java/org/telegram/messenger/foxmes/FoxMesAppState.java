package org.telegram.messenger.foxmes;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.voip.VoIPPreNotificationService;
import org.telegram.messenger.voip.VoIPService;

import java.util.concurrent.atomic.AtomicInteger;

final class FoxMesAppState {

    private static final long PAUSE_DELAY_MS = 10_000;

    private static boolean registered;
    private static volatile int startedActivities;
    private static final AtomicInteger pushHolds = new AtomicInteger();
    private static final Runnable pause = FoxMesAppState::pauseIfAllowed;

    private FoxMesAppState() {
    }

    static void watch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!registered) {
                registered = true;
                if (!ApplicationLoader.mainInterfaceStopped) {
                    startedActivities = 1;
                }
                Application application = (Application) ApplicationLoader.applicationContext.getApplicationContext();
                application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityStarted(Activity activity) {
                        startedActivities++;
                        if (startedActivities == 1) {
                            AndroidUtilities.cancelRunOnUIThread(pause);
                            FoxMesRuntime.forEachStarted(FoxMesRuntime::resumeRealtime);
                        }
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                        startedActivities = Math.max(0, startedActivities - 1);
                        if (startedActivities == 0) {
                            schedulePause();
                        }
                    }

                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                });
            }
            if (startedActivities == 0) {
                schedulePause();
            }
        });
    }

    private static void schedulePause() {
        AndroidUtilities.cancelRunOnUIThread(pause);
        AndroidUtilities.runOnUIThread(pause, PAUSE_DELAY_MS);
    }

    static boolean isForeground() {
        return startedActivities > 0;
    }

    static void holdForPush(boolean hold) {
        if (hold) {
            pushHolds.incrementAndGet();
        } else if (pushHolds.decrementAndGet() == 0) {
            AndroidUtilities.runOnUIThread(() -> {
                if (startedActivities == 0) {
                    schedulePause();
                }
            });
        }
    }

    private static void pauseIfAllowed() {
        if (startedActivities > 0 || pushHolds.get() > 0) {
            return;
        }
        if (callInProgress()) {
            schedulePause();
            return;
        }
        FoxMesRuntime.forEachStarted(runtime -> {
            if (!backgroundConnection(runtime.account)) {
                runtime.pauseRealtime();
            }
        });
    }

    private static boolean callInProgress() {
        return VoIPPreNotificationService.pendingCall != null
                || VoIPService.callIShouldHavePutIntoIntent != null
                || VoIPService.getSharedInstance() != null;
    }

    static boolean backgroundConnection(int account) {
        return MessagesController.getNotificationsSettings(account)
                .getBoolean("pushConnection", MessagesController.getInstance(account).backgroundConnection);
    }
}
