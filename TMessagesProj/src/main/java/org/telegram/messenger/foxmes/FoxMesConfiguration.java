package org.telegram.messenger.foxmes;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

import java.util.Locale;

public final class FoxMesConfiguration {
    public static final String productName = "FoxMes";
    public static final String uriScheme = "foxmes";
    public static final String shortUriScheme = "fm";
    public static final String internalLinksDomain = "foxtail.ing";

    public static final String productionBaseURL = "https://foxmes.foxtail.ing";
    public static final String localDevelopmentBaseURL = "http://127.0.0.1:7034";
    public static final String localDevelopmentWebURL = "http://127.0.0.1:5173";
    public static final String productionWebURL = "https://foxtail.ing";
    public static final String faqURL = "https://foxtail.ing/@info/983-chto-takoe-lisiy-khvost";

    public enum ApiEnvironment {
        DEV,
        PROD
    }

    public static final ApiEnvironment apiEnvironment = ApiEnvironment.DEV;

    public static final boolean trustsDevelopmentTestHosts = true;

    public static final String DEBUG_PREFERENCES = "foxmes_debug";
    public static final String KEY_ENV = "env";
    public static final String KEY_URL = "url";
    public static final String KEY_WEB_URL = "web_url";
    public static final String KEY_DISABLED = "disabled";

    private FoxMesConfiguration() {
    }

    private static SharedPreferences debugPreferences() {
        if (!BuildVars.DEBUG_VERSION) {
            return null;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(DEBUG_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String debugString(String key) {
        SharedPreferences preferences = debugPreferences();
        if (preferences == null) {
            return null;
        }
        String value = preferences.getString(key, null);
        return TextUtils.isEmpty(value) ? null : value.trim();
    }

    static boolean debugDisabled() {
        SharedPreferences preferences = debugPreferences();
        return preferences != null && preferences.getBoolean(KEY_DISABLED, false);
    }

    private static boolean debugProductionRequested() {
        return "prod".equalsIgnoreCase(debugString(KEY_ENV));
    }

    public static String baseURL() {
        if (!BuildVars.DEBUG_VERSION) {
            return productionBaseURL;
        }
        if (debugProductionRequested()) {
            return productionBaseURL;
        }
        String override = debugString(KEY_URL);
        if (override != null) {
            return trimTrailingSlashes(override);
        }
        if ("dev".equalsIgnoreCase(debugString(KEY_ENV))) {
            return localDevelopmentBaseURL;
        }
        return apiEnvironment == ApiEnvironment.PROD ? productionBaseURL : localDevelopmentBaseURL;
    }

    public static boolean isDevelopmentEndpoint() {
        return BuildVars.DEBUG_VERSION && !productionBaseURL.equals(baseURL());
    }

    public static String profileKey() {
        return isDevelopmentEndpoint() ? "development" : "production";
    }

    public static boolean allowsInsecureTLS() {
        return BuildVars.DEBUG_VERSION && isDevelopmentEndpoint() && baseURL().toLowerCase(Locale.US).startsWith("https:");
    }

    public static boolean allowsInsecureTLS(String host) {
        return trustsDevelopmentTestHosts && isDevelopmentTestHost(host);
    }

    public static boolean isDevelopmentTestHost(String host) {
        if (!BuildVars.DEBUG_VERSION || host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.equals("test") || lower.endsWith(".test");
    }

    public static boolean allowsBearer(String url) {
        if (url == null) {
            return false;
        }
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        if (host.equals("cdn.foxtail.ing")) {
            return true;
        }
        String apiHost = Uri.parse(baseURL()).getHost();
        if (apiHost != null && host.equals(apiHost.toLowerCase(Locale.US))) {
            return true;
        }
        return BuildVars.DEBUG_VERSION && host.equals("cdn.fxl.test");
    }

    public static String webURL() {
        if (BuildVars.DEBUG_VERSION) {
            String override = debugString(KEY_WEB_URL);
            if (override != null) {
                return trimTrailingSlashes(override);
            }
            if (localDevelopmentBaseURL.equals(baseURL())) {
                return localDevelopmentWebURL;
            }
        }
        return productionWebURL;
    }

    public static String profileURL(String username) {
        return webPath("@" + username);
    }

    public static String profileSettingsURL(String username) {
        return webPath("@" + username + "/settings");
    }

    public static String blockedUsersURL(String username) {
        return webPath("@" + username + "/settings/blocked");
    }

    public static String publicProfileLink(String username) {
        return "https://" + internalLinksDomain + "/@" + username;
    }

    private static String webPath(String path) {
        return trimTrailingSlashes(webURL()) + "/" + path;
    }

    public static String pairingURL(String request, String serverURL) {
        if (!BuildVars.DEBUG_VERSION || !isDevelopmentEndpoint()) {
            return serverURL;
        }
        String web = debugString(KEY_WEB_URL);
        if (web == null && localDevelopmentBaseURL.equals(baseURL())) {
            web = localDevelopmentWebURL;
        }
        if (web == null) {
            return serverURL;
        }
        Uri uri = Uri.parse(web);
        Uri.Builder builder = uri.buildUpon();
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            builder.path("/settings/foxMes");
        }
        builder.clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if (!"request".equals(name)) {
                for (String value : uri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value);
                }
            }
        }
        builder.appendQueryParameter("request", request);
        return builder.build().toString();
    }

    public static Uri normalizedIncomingUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return uri;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.US);
        if (scheme.equals(shortUriScheme) || scheme.equals(uriScheme)) {
            return uri.buildUpon().scheme("tg").build();
        }
        return uri;
    }

    static String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
