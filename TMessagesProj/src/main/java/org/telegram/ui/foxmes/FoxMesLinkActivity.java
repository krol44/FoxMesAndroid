package org.telegram.ui.foxmes;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.telegram.messenger.foxmes.FoxMesConfiguration;
import org.telegram.ui.LaunchActivity;

public class FoxMesLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, FoxMesConfiguration.normalizedIncomingUri(data), this, LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }
}
