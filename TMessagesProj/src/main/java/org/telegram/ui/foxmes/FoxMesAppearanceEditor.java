package org.telegram.ui.foxmes;

import android.widget.Toast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.foxmes.FoxMesAppearanceRequest;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.PeerColorActivity;
import java.util.WeakHashMap;

public final class FoxMesAppearanceEditor {
    private static final WeakHashMap<PeerColorActivity, Boolean> saving = new WeakHashMap<>();

    public static void apply(PeerColorActivity screen, int account, boolean channel,
            int nameColor, long nameIcon, int profileColor, long profileIcon, Runnable nativeApply) {
        if (channel) { nativeApply.run(); return; }
        if (Boolean.TRUE.equals(saving.get(screen))) return;
        saving.put(screen, true);
        FoxMesAppearanceRequest request = new FoxMesAppearanceRequest(nameColor, nameIcon, profileColor, profileIcon);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    saving.remove(screen);
                    if (error == null) screen.finishFragment();
                    else Toast.makeText(ApplicationLoader.applicationContext,
                            LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_LONG).show();
                }));
    }

    private FoxMesAppearanceEditor() {}
}
