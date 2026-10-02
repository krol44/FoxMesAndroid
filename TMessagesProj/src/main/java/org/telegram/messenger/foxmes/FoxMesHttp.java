package org.telegram.messenger.foxmes;

import android.annotation.SuppressLint;

import org.telegram.messenger.BuildVars;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

public final class FoxMesHttp {

    public interface TokenProvider {
        String token();
    }

    public interface Progress {
        void onBytes(long sent);
    }

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    public static final String LANE_HEADER = "x-fxl-lane";

    private static OkHttpClient sharedClient;
    private static OkHttpClient sharedInsecureClient;
    private static OkHttpClient sharedDownloadClient;
    private static OkHttpClient sharedInsecureDownloadClient;

    private final TokenProvider tokenProvider;
    private volatile Runnable onUnauthorized;

    public FoxMesHttp(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public void setOnUnauthorized(Runnable onUnauthorized) {
        this.onUnauthorized = onUnauthorized;
    }

    public String token() {
        return tokenProvider != null ? tokenProvider.token() : null;
    }


    private static synchronized OkHttpClient client() {
        if (sharedClient == null) {
            sharedClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .pingInterval(25, TimeUnit.SECONDS)
                    .dns(FoxMesHttp::lookup)
                    .build();
        }
        return sharedClient;
    }

    private static List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (FoxMesConfiguration.isDevelopmentTestHost(hostname)) {
            return Collections.singletonList(InetAddress.getByAddress(hostname, new byte[]{127, 0, 0, 1}));
        }
        return Dns.SYSTEM.lookup(hostname);
    }

    private static synchronized OkHttpClient downloadClient() {
        if (sharedDownloadClient == null) {
            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequestsPerHost(4);
            dispatcher.setMaxRequests(16);
            sharedDownloadClient = client().newBuilder().dispatcher(dispatcher).build();
        }
        return sharedDownloadClient;
    }

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier"})
    private static synchronized OkHttpClient insecure(OkHttpClient base) {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
            SSLSocketFactory factory = context.getSocketFactory();
            return base.newBuilder()
                    .sslSocketFactory(factory, trustAll)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            FoxMesLog.e("insecure client", e);
            return base;
        }
    }

    static OkHttpClient clientFor(HttpUrl url, boolean download) {
        boolean insecure = BuildVars.DEBUG_VERSION && url != null && url.isHttps()
                && (FoxMesConfiguration.allowsInsecureTLS(url.host())
                || (FoxMesConfiguration.allowsInsecureTLS() && url.host().equalsIgnoreCase(HttpUrl.get(FoxMesConfiguration.baseURL()).host())));
        if (!insecure) {
            return download ? downloadClient() : client();
        }
        synchronized (FoxMesHttp.class) {
            if (download) {
                if (sharedInsecureDownloadClient == null) {
                    sharedInsecureDownloadClient = insecure(downloadClient());
                }
                return sharedInsecureDownloadClient;
            }
            if (sharedInsecureClient == null) {
                sharedInsecureClient = insecure(client());
            }
            return sharedInsecureClient;
        }
    }

    public static OkHttpClient webSocketClient(HttpUrl url) {
        return clientFor(url, false);
    }


    public static HttpUrl url(String path, Map<String, String> query) throws IOException {
        String base = FoxMesConfiguration.trimTrailingSlashes(FoxMesConfiguration.baseURL());
        String trimmed = path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        HttpUrl parsed = HttpUrl.parse(base + "/" + trimmed);
        if (parsed == null) {
            throw new IOException("Invalid FoxMes URL: " + path);
        }
        if (query == null || query.isEmpty()) {
            return parsed;
        }
        HttpUrl.Builder builder = parsed.newBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() != null) {
                builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    public static String absolute(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        HttpUrl base = HttpUrl.parse(FoxMesConfiguration.baseURL());
        if (base == null) {
            return url;
        }
        HttpUrl resolved = base.resolve(url);
        return resolved != null ? resolved.toString() : url;
    }


    public <T> T request(String method, String path, Map<String, String> query, FoxMesJson.Body body, Type type) throws IOException {
        return request(method, path, query, body, true, type);
    }

    public <T> T request(String method, String path, Map<String, String> query, FoxMesJson.Body body, boolean authenticated, Type type) throws IOException {
        String text = requestString(method, path, query, body, authenticated);
        if (type == null || type == Void.class) {
            return null;
        }
        try {
            return FoxMesJson.parse(text, type);
        } catch (Exception e) {
            throw new IOException("FoxMes decoding error for " + path + ": " + e.getMessage(), e);
        }
    }

    public String requestString(String method, String path, Map<String, String> query, FoxMesJson.Body body, boolean authenticated) throws IOException {
        return requestString(method, path, query, body, authenticated ? token() : null);
    }

    public String requestString(String method, String path, Map<String, String> query, FoxMesJson.Body body, String bearer) throws IOException {
        HttpUrl url = url(path, query);
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(body.toString(), JSON);
        } else if (requiresBody(method)) {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method(method, requestBody)
                .header("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return execute(clientFor(url, false), builder.build(), null);
    }

    private static boolean requiresBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }


    public String upload(String method, String path, Map<String, String> query, RequestBody body, Map<String, String> headers,
                         String[] lane, long timeoutSeconds, Progress progress) throws IOException {
        HttpUrl url = url(path, query);
        RequestBody requestBody = body;
        if (requestBody != null && progress != null) {
            requestBody = new ProgressBody(requestBody, progress);
        }
        if (requestBody == null && requiresBody(method)) {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method(method, requestBody)
                .header("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        if (lane != null && lane[0] != null) {
            builder.header(LANE_HEADER, lane[0]);
        }
        String token = token();
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        OkHttpClient client = clientFor(url, false).newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return execute(client, builder.build(), lane);
    }

    private String execute(OkHttpClient client, Request request, String[] lane) throws IOException {
        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            if (lane != null) {
                String value = response.header(LANE_HEADER);
                if (value != null && !value.trim().isEmpty()) {
                    lane[0] = value.trim();
                }
            }
            ResponseBody responseBody = response.body();
            String text = responseBody != null ? responseBody.string() : "";
            int status = response.code();
            if (status == 401 && sentCurrentBearer(request)) {
                Runnable callback = onUnauthorized;
                if (callback != null) {
                    callback.run();
                }
            }
            if (status < 200 || status >= 300) {
                throw errorFor(status, text);
            }
            return text;
        }
    }

    private boolean sentCurrentBearer(Request request) {
        String sent = request.header("Authorization");
        String token = token();
        return sent != null && token != null && !token.isEmpty() && sent.equals("Bearer " + token);
    }

    static FoxMesHttpException errorFor(int status, String text) {
        String code = null;
        String message = null;
        try {
            com.google.gson.JsonObject envelope = FoxMesJson.parse(text, com.google.gson.JsonObject.class);
            if (envelope != null) {
                code = FoxMesJson.optString(envelope, "code");
                message = FoxMesJson.optString(envelope, "message");
                if (message == null) {
                    message = FoxMesJson.optString(envelope, "error");
                }
            }
        } catch (Exception ignore) {
        }
        return new FoxMesHttpException(status, code, message, text);
    }


    public Response download(String url, long offset, long length) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("Invalid media URL");
        }
        Request.Builder builder = new Request.Builder().url(parsed).get();
        if (length > 0) {
            builder.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
        } else if (offset > 0) {
            builder.header("Range", "bytes=" + offset + "-");
        }
        if (FoxMesConfiguration.allowsBearer(url)) {
            String token = token();
            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        return clientFor(parsed, true).newCall(builder.build()).execute();
    }

    public Call newDownloadCall(String url, long offset, long length) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("Invalid media URL");
        }
        Request.Builder builder = new Request.Builder().url(parsed).get();
        if (length > 0) {
            builder.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
        } else if (offset > 0) {
            builder.header("Range", "bytes=" + offset + "-");
        }
        if (FoxMesConfiguration.allowsBearer(url)) {
            String token = token();
            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        return clientFor(parsed, true).newCall(builder.build());
    }

    private static final class ProgressBody extends RequestBody {
        private final RequestBody delegate;
        private final Progress progress;

        ProgressBody(RequestBody delegate, Progress progress) {
            this.delegate = delegate;
            this.progress = progress;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            final long[] written = {0};
            Sink counting = new ForwardingSink(sink) {
                @Override
                public void write(Buffer source, long byteCount) throws IOException {
                    super.write(source, byteCount);
                    written[0] += byteCount;
                    progress.onBytes(written[0]);
                }
            };
            BufferedSink buffered = Okio.buffer(counting);
            delegate.writeTo(buffered);
            buffered.flush();
        }
    }
}
