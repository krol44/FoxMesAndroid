package org.telegram.messenger.foxmes;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.Okio;
import okio.Source;
import okio.Timeout;

public final class FoxMesSealed {

    public static final String CONTENT_TYPE = "application/vnd.foxmes.sealed";
    private static final MediaType SEALED = MediaType.get(CONTENT_TYPE);
    private static final String[] PROTECTED_HEADERS = {"range", "x-upload-id", "x-chunk-index", "x-total-chunks"};
    private static final String REJECTED_HEADER = "X-FoxMes-Rejected";
    private static final String SERVER_TIME_HEADER = "X-FoxMes-Server-Time";
    private static final String BLOCK_HEADER = "X-FoxMes-Headers";
    private static final String OPEN_PREFIX = "open_";
    private static final Set<String> OPEN_REJECTIONS = new HashSet<>(Arrays.asList("sealed_invalid", "sealed_temp_expired", "unauthorized"));
    private static final long RENEW_BEFORE = 3600;
    private static final long RENEW_RETRY = 30;
    private static final Executor RENEWAL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "foxmes-temp-key");
        thread.setDaemon(true);
        return thread;
    });
    private static final long EXPIRY_MARGIN = 60;
    private static final long MINIMAL_SERIAL = 1;

    private static final Map<String, String> RELEASE_ROOTS = new HashMap<>();
    private static final Map<String, String> STAGE_ROOTS = new HashMap<>();

    static {
        RELEASE_ROOTS.put("main", "BH2lkBMPeHXjXbYfb3LXCmqlTSpwauxFBFeGa5c5wpcvfRy5EMazkLO8rJJn5x0l9Mwof2be0IgFQEt87qu41Pc");
        RELEASE_ROOTS.put("backup", "BJCTSzIuUbMQkEmXT8GKbPDVLY-GKecfl9CorLWUK6vxKqssYNGx1LAnC4jZgpKUHEWTUliIO-bxo6w26Je7pzY");
        STAGE_ROOTS.put("stage", "BGaLTyW3v-B9KgdSD87VxACK1pX5omaD_Mq8pjttI6lVnj2QH0EF_gN5NqZ_okrP04lBu2TznNOMwjGRrKdkSKw");
    }

    private static volatile long clockOffset;

    private FoxMesSealed() {
    }

    public static long now() {
        return System.currentTimeMillis() / 1000 + clockOffset;
    }

    public static void clockSkew(long serverTime) {
        if (serverTime > 0) {
            clockOffset = serverTime - System.currentTimeMillis() / 1000;
        }
    }

    public static final class Credential {
        static final int TEMP = 1;
        static final int SESSION = 2;
        static final int LOGIN = 3;

        final int kind;
        final String tid;
        final long sid;
        final String kid;
        final String epk;
        final FoxMesSealedCrypto.Keys keys;

        Credential(int kind, String tid, long sid, String kid, String epk, FoxMesSealedCrypto.Keys keys) {
            this.kind = kind;
            this.tid = tid;
            this.sid = sid;
            this.kid = kid;
            this.epk = epk;
            this.keys = keys;
        }

        String header() {
            switch (kind) {
                case TEMP: return "tid=" + tid;
                case SESSION: return "sid=" + sid;
                default: return "kid=" + kid + ", epk=" + epk;
            }
        }

        String aad() {
            switch (kind) {
                case TEMP: return "tid=" + tid;
                case SESSION: return "sid=" + sid;
                default: return "kid=" + kid + ";epk=" + epk;
            }
        }
    }

    public static final class Tag {
        final String token;
        final Credential fixed;
        volatile Credential current;

        private Tag(String token, Credential fixed) {
            this.token = token;
            this.fixed = fixed;
        }

        public static Tag session(String token) {
            return new Tag(token, null);
        }

        public static Tag login(Credential credential) {
            return new Tag(null, credential);
        }

        public String token() {
            return token;
        }
    }

    public static final class Token {
        public final long sid;
        public final byte[] secret;

        private Token(long sid, byte[] secret) {
            this.sid = sid;
            this.secret = secret;
        }

        public static String encode(long sid, byte[] secret) {
            return "v1." + sid + "." + FoxMesSealedCrypto.b64(secret);
        }

        public static Token parse(String token) {
            if (token == null) {
                return null;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                return null;
            }
            try {
                long sid = Long.parseLong(parts[1]);
                byte[] secret = FoxMesSealedCrypto.unb64(parts[2]);
                if (sid <= 0 || secret == null || secret.length != FoxMesSealedCrypto.KEY_SIZE) {
                    return null;
                }
                return new Token(sid, secret);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static final class UnauthorizedException extends IOException {
        UnauthorizedException() {
            super("unauthorized");
        }
    }

    static final class Session {
        private static final Map<String, Session> registry = new HashMap<>();

        private final String base;
        private final Token token;
        private Credential temp;
        private long expires;
        private long renewAt;
        private long renewRetryAt;
        private boolean renewing;
        private boolean rejected;

        private Session(String base, Token token) {
            this.base = base;
            this.token = token;
        }

        static Session forToken(String token) {
            Token parsed = Token.parse(token);
            if (parsed == null) {
                return null;
            }
            String base = FoxMesConfiguration.trimTrailingSlashes(FoxMesConfiguration.baseURL());
            String key = base + "|" + token;
            synchronized (registry) {
                Session session = registry.get(key);
                if (session == null) {
                    session = new Session(base, parsed);
                    registry.put(key, session);
                }
                return session;
            }
        }

        synchronized Credential credential() throws IOException {
            long current = now();
            if (temp != null && current < expires - EXPIRY_MARGIN) {
                if (current >= renewAt && current >= renewRetryAt && !renewing) {
                    renewing = true;
                    RENEWAL.execute(this::renew);
                }
                return temp;
            }
            apply(bind(false));
            return temp;
        }

        private void renew() {
            try {
                Bound bound = bind(false);
                synchronized (this) {
                    apply(bound);
                    renewing = false;
                }
            } catch (IOException e) {
                FoxMesLog.w("temp key renewal failed: " + e.getMessage());
                synchronized (this) {
                    renewing = false;
                    renewRetryAt = now() + RENEW_RETRY;
                }
            }
        }

        private void apply(Bound bound) {
            temp = bound.credential;
            expires = bound.expires;
            renewAt = bound.expires - Math.min(RENEW_BEFORE, Math.max(0, bound.expires - now()) / 4);
            renewRetryAt = 0;
            rejected = false;
        }

        synchronized void tempExpired(String tid) {
            if (temp != null && tid != null && tid.equals(temp.tid)) {
                temp = null;
                expires = 0;
            }
        }

        synchronized void dropTemp() {
            temp = null;
            expires = 0;
        }

        synchronized boolean isRejected() {
            return rejected;
        }

        private Bound bind(boolean clockRetried) throws IOException {
            try {
                KeyPair ephemeral = FoxMesSealedCrypto.generateKeyPair();
                String clientPublic = FoxMesSealedCrypto.b64(FoxMesSealedCrypto.encodePublic(ephemeral.getPublic()));
                HttpUrl url = HttpUrl.get(base + "/auth/temp-key");
                Request request = new Request.Builder().url(url).header("Accept", "application/json").build();
                Credential session = new Credential(Credential.SESSION, null, token.sid, null, null, FoxMesSealedCrypto.sessionKeys(token.secret));
                byte[] body = FoxMesSealedCrypto.utf8("{\"pub\":\"" + clientPublic + "\"}");
                Signed signed = sign(request, "POST", session, body, "application/json");
                OkHttpClient client = FoxMesHttp.plainClientFor(url);
                try (Response response = client.newCall(signed.request).execute()) {
                    Opened opened = openFully(response, signed, session);
                    if (opened.rejection != null) {
                        if ("sealed_clock".equals(opened.rejection) && opened.serverTime > 0 && !clockRetried) {
                            clockSkew(opened.serverTime);
                            return bind(true);
                        }
                        throw new IOException("temp key bind rejected: " + opened.rejection);
                    }
                    if (opened.status == 401) {
                        synchronized (this) {
                            rejected = true;
                        }
                        throw new UnauthorizedException();
                    }
                    if (opened.status != 200) {
                        throw new IOException("temp key bind failed: " + opened.status);
                    }
                    JsonObject object = FoxMesJson.parse(new String(opened.body, "UTF-8"), JsonObject.class);
                    String tid = object.get("tid").getAsString();
                    String serverPublic = object.get("pub").getAsString();
                    long expiresAt = object.get("expires_at").getAsLong();
                    byte[] shared = FoxMesSealedCrypto.agree(ephemeral.getPrivate(), FoxMesSealedCrypto.unb64(serverPublic));
                    byte[] secret = FoxMesSealedCrypto.tempSecret(shared, FoxMesSealedCrypto.bindSalt(token.secret), token.sid, tid, clientPublic, serverPublic);
                    return new Bound(new Credential(Credential.TEMP, tid, 0, null, null, FoxMesSealedCrypto.tempKeys(secret)), expiresAt);
                }
            } catch (GeneralSecurityException | RuntimeException e) {
                throw new IOException("temp key bind failed", e);
            }
        }
    }

    private static final class Bound {
        final Credential credential;
        final long expires;

        Bound(Credential credential, long expires) {
            this.credential = credential;
            this.expires = expires;
        }
    }

    static final class Signed {
        final Request request;
        final byte[] nonce;

        Signed(Request request, byte[] nonce) {
            this.request = request;
            this.nonce = nonce;
        }
    }

    static Signed signHeaders(Request request, String method, Credential credential, String type) throws GeneralSecurityException {
        HttpUrl url = request.url();
        List<FoxMesSealedCrypto.SignedHeader> headers = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (String name : PROTECTED_HEADERS) {
            String value = request.header(name);
            if (value == null) {
                continue;
            }
            headers.add(new FoxMesSealedCrypto.SignedHeader(name, value.trim()));
            if (names.length() > 0) {
                names.append(';');
            }
            names.append(name);
        }
        long ts = now();
        byte[] nonce = FoxMesSealedCrypto.random(FoxMesSealedCrypto.NONCE_SIZE);
        String query = url.encodedQuery();
        byte[] aad = FoxMesSealedCrypto.requestAad(method, url.host(), url.encodedPath(), query != null ? query : "", ts,
                credential.aad(), type != null ? type : "", headers);
        byte[] tag = FoxMesSealedCrypto.requestTag(credential.keys.c2s, nonce, aad);
        String authorization = "FoxMes v=1, " + credential.header() + ", ts=" + ts + ", n=" + FoxMesSealedCrypto.b64(nonce)
                + ", t=" + FoxMesSealedCrypto.b64(tag) + (names.length() > 0 ? ", h=" + names : "");
        Request.Builder builder = request.newBuilder().header("Authorization", authorization);
        if (type == null || type.isEmpty()) {
            builder.removeHeader("Content-Type").removeHeader("X-FoxMes-Type");
        } else {
            builder.header("Content-Type", CONTENT_TYPE).header("X-FoxMes-Type", type);
        }
        return new Signed(builder.build(), nonce);
    }

    static Signed sign(Request request, String method, Credential credential, byte[] plain, String type) throws GeneralSecurityException {
        Signed headers = signHeaders(request, method, credential, type);
        if (type == null || type.isEmpty()) {
            return new Signed(headers.request.newBuilder().method(method, "GET".equals(method) ? null : RequestBody.create(new byte[0], null)).build(), headers.nonce);
        }
        byte[] sealed = FoxMesSealedCrypto.sealFrames(credential.keys.c2s,
                new FoxMesSealedCrypto.FrameContext(headers.nonce, FoxMesSealedCrypto.DIR_C2S, 0, type, "", 0), plain, null);
        Request result = headers.request.newBuilder()
                .method(method, RequestBody.create(sealed, SEALED))
                .header("Content-Length", String.valueOf(sealed.length))
                .build();
        return new Signed(result, headers.nonce);
    }

    static final class Opened {
        int status;
        byte[] body;
        String rejection;
        long serverTime;
    }

    private static FoxMesSealedCrypto.FrameContext responseContext(Response response, byte[] nonce, int status) {
        String range = response.header("X-FoxMes-Content-Range", "");
        long offset = 0;
        if (range.startsWith("bytes ")) {
            int dash = range.indexOf('-', 6);
            if (dash > 6) {
                try {
                    offset = Long.parseLong(range.substring(6, dash));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return new FoxMesSealedCrypto.FrameContext(nonce, FoxMesSealedCrypto.DIR_S2C, status,
                response.header("X-FoxMes-Type", ""), range, offset);
    }

    private static boolean isSealed(Response response) {
        String type = response.header("Content-Type");
        return type != null && type.startsWith(CONTENT_TYPE);
    }

    private static int sealedStatus(Response response) {
        try {
            return Integer.parseInt(response.header("X-FoxMes-Status", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JsonObject errorObject(byte[] body) {
        try {
            return FoxMesJson.parse(new String(body, "UTF-8"), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorCode(JsonObject object) {
        return object != null ? FoxMesJson.optString(object, "error") : null;
    }

    private static long errorTime(JsonObject object) {
        try {
            return object != null && object.has("time") ? object.get("time").getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String[]> headerBlock(Response response, byte[] key, byte[] nonce) {
        return FoxMesSealedCrypto.openHeaderBlock(key, nonce, response.header(BLOCK_HEADER));
    }

    private static byte[] openAll(FoxMesSealedCrypto.FrameDecoder decoder, byte[] raw) throws GeneralSecurityException {
        Buffer plain = new Buffer();
        plain.write(decoder.feed(raw, raw.length));
        plain.write(decoder.finish());
        return plain.readByteArray();
    }

    static Opened openFully(Response response, Signed signed, Credential credential) throws IOException, GeneralSecurityException {
        Opened result = new Opened();
        ResponseBody body = response.body();
        byte[] raw = body != null ? body.bytes() : new byte[0];
        if (isSealed(response)) {
            result.status = sealedStatus(response);
            if (result.status < 0 || headerBlock(response, credential.keys.s2c, signed.nonce) == null) {
                throw new IOException("sealed response is damaged");
            }
            result.body = openAll(new FoxMesSealedCrypto.FrameDecoder(credential.keys.s2c, responseContext(response, signed.nonce, result.status)), raw);
            if (result.status == 401) {
                JsonObject object = errorObject(result.body);
                String code = errorCode(object);
                if ("sealed_clock".equals(code) || "sealed_replay".equals(code)) {
                    result.rejection = code;
                    result.serverTime = errorTime(object);
                }
            }
            return result;
        }
        String code = response.code() == 401 ? errorCode(errorObject(raw)) : null;
        if (code == null || !OPEN_REJECTIONS.contains(code)) {
            throw new IOException("response is not sealed");
        }
        result.body = raw;
        result.rejection = OPEN_PREFIX + code;
        return result;
    }

    private static final class FrameSink extends ForwardingSink {
        private final byte[] key;
        private final FoxMesSealedCrypto.FrameContext context;
        private final Buffer pending = new Buffer();
        private long offset;

        FrameSink(BufferedSink delegate, byte[] key, FoxMesSealedCrypto.FrameContext context) {
            super(delegate);
            this.key = key;
            this.context = context;
        }

        private void emit(boolean last) throws IOException {
            long size = Math.min(FoxMesSealedCrypto.FRAME_SIZE, pending.size());
            byte[] chunk = pending.readByteArray(size);
            byte[] nonce = FoxMesSealedCrypto.random(FoxMesSealedCrypto.NONCE_SIZE);
            try {
                byte[] sealed = FoxMesSealedCrypto.seal(key, nonce, chunk, FoxMesSealedCrypto.frameAad(context, offset, last));
                Buffer frame = new Buffer().write(nonce).write(sealed);
                super.write(frame, frame.size());
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            offset += chunk.length;
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            pending.write(source, byteCount);
            while (pending.size() > FoxMesSealedCrypto.FRAME_SIZE) {
                emit(false);
            }
        }

        void finishFrames() throws IOException {
            emit(true);
        }
    }

    private static final class SealedRequestBody extends RequestBody {
        private final RequestBody delegate;
        private final byte[] key;
        private final FoxMesSealedCrypto.FrameContext context;

        SealedRequestBody(RequestBody delegate, byte[] key, FoxMesSealedCrypto.FrameContext context) {
            this.delegate = delegate;
            this.key = key;
            this.context = context;
        }

        @Override
        public MediaType contentType() {
            return SEALED;
        }

        @Override
        public long contentLength() throws IOException {
            long plain = delegate.contentLength();
            return plain >= 0 ? FoxMesSealedCrypto.sealedLength(plain) : -1;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            FrameSink frames = new FrameSink(sink, key, context);
            BufferedSink buffered = Okio.buffer(frames);
            delegate.writeTo(buffered);
            buffered.flush();
            frames.finishFrames();
            sink.flush();
        }
    }

    private static final class SealedSource implements Source {
        private final BufferedSource upstream;
        private final FoxMesSealedCrypto.FrameDecoder decoder;
        private final Buffer plain = new Buffer();
        private final byte[] chunk = new byte[16 * 1024];
        private boolean done;

        SealedSource(BufferedSource upstream, FoxMesSealedCrypto.FrameDecoder decoder) {
            this.upstream = upstream;
            this.decoder = decoder;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            while (plain.size() == 0 && !done) {
                int read = upstream.read(chunk);
                try {
                    if (read < 0) {
                        plain.write(decoder.finish());
                        done = true;
                    } else if (read > 0) {
                        plain.write(decoder.feed(chunk, read));
                    }
                } catch (GeneralSecurityException e) {
                    throw new IOException("sealed response is damaged", e);
                }
            }
            if (plain.size() == 0) {
                return -1;
            }
            return plain.read(sink, Math.min(byteCount, plain.size()));
        }

        @Override
        public Timeout timeout() {
            return upstream.timeout();
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }
    }

    private static final class SealedResponseBody extends ResponseBody {
        private final ResponseBody delegate;
        private final MediaType type;
        private final long length;
        private final BufferedSource source;

        SealedResponseBody(ResponseBody delegate, MediaType type, long length, FoxMesSealedCrypto.FrameDecoder decoder) {
            this.delegate = delegate;
            this.type = type;
            this.length = length;
            this.source = Okio.buffer(new SealedSource(delegate.source(), decoder));
        }

        @Override
        public MediaType contentType() {
            return type;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public BufferedSource source() {
            return source;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static Response unauthorized(Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .header(REJECTED_HEADER, "unauthorized")
                .body(ResponseBody.create("{\"error\":\"unauthorized\",\"code\":\"UNAUTHORIZED\"}", MediaType.get("application/json")))
                .build();
    }

    public static final class NetworkInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Tag tag = request.tag(Tag.class);
            if (tag == null) {
                return chain.proceed(request);
            }
            Credential credential = tag.fixed != null ? tag.fixed : tag.current;
            if (credential == null) {
                throw new IOException("sealed request has no credential");
            }
            RequestBody body = request.body();
            String type = null;
            if (body != null && body.contentLength() != 0) {
                MediaType mediaType = body.contentType();
                type = mediaType != null ? mediaType.toString() : "application/octet-stream";
            }
            byte[] nonce;
            Request sealed;
            try {
                Signed headers = signHeaders(request, request.method(), credential, type);
                nonce = headers.nonce;
                Request.Builder builder = headers.request.newBuilder();
                if (type != null) {
                    SealedRequestBody sealedBody = new SealedRequestBody(body, credential.keys.c2s,
                            new FoxMesSealedCrypto.FrameContext(nonce, FoxMesSealedCrypto.DIR_C2S, 0, type, "", 0));
                    builder.method(request.method(), sealedBody);
                    long length = sealedBody.contentLength();
                    if (length >= 0) {
                        builder.header("Content-Length", String.valueOf(length));
                    } else {
                        builder.removeHeader("Content-Length");
                    }
                }
                sealed = builder.build();
            } catch (GeneralSecurityException e) {
                throw new IOException("cannot seal the request", e);
            }
            Response response = chain.proceed(sealed);
            if (isSealed(response)) {
                return openResponse(response, credential, nonce);
            }
            if (response.code() == 401) {
                ResponseBody rejection = response.body();
                byte[] raw = rejection != null ? rejection.bytes() : new byte[0];
                String code = errorCode(errorObject(raw));
                if (code != null && OPEN_REJECTIONS.contains(code)) {
                    return response.newBuilder()
                            .headers(new Headers.Builder().set(REJECTED_HEADER, OPEN_PREFIX + code).build())
                            .body(ResponseBody.create(new byte[0], null))
                            .build();
                }
            }
            response.close();
            throw new IOException("response is not sealed");
        }

        private static Response openResponse(Response response, Credential credential, byte[] nonce) throws IOException {
            int status = sealedStatus(response);
            List<String[]> block = status < 0 ? null : headerBlock(response, credential.keys.s2c, nonce);
            if (block == null) {
                response.close();
                throw new IOException("sealed response is damaged");
            }
            Headers.Builder headers = new Headers.Builder();
            for (String[] line : block) {
                if (!line[0].toLowerCase(Locale.US).startsWith("x-foxmes-")) {
                    headers.addUnsafeNonAscii(line[0], line[1]);
                }
            }
            String lane = response.header(FoxMesHttp.LANE_HEADER);
            if (lane != null && !lane.isEmpty()) {
                headers.set(FoxMesHttp.LANE_HEADER, lane);
            }
            String plainType = response.header("X-FoxMes-Type", "");
            if (!plainType.isEmpty()) {
                headers.set("Content-Type", plainType);
            }
            String range = response.header("X-FoxMes-Content-Range");
            if (range != null && !range.isEmpty()) {
                headers.set("Content-Range", range);
            }
            ResponseBody responseBody = response.body();
            long length = responseBody != null ? FoxMesSealedCrypto.plainLength(responseBody.contentLength()) : -1;
            if (length >= 0) {
                headers.set("Content-Length", String.valueOf(length));
            }
            FoxMesSealedCrypto.FrameDecoder decoder = new FoxMesSealedCrypto.FrameDecoder(credential.keys.s2c, responseContext(response, nonce, status));
            MediaType mediaType = plainType.isEmpty() ? null : MediaType.parse(plainType);
            if (status == 401) {
                byte[] plain;
                try {
                    plain = openAll(decoder, responseBody != null ? responseBody.bytes() : new byte[0]);
                } catch (GeneralSecurityException e) {
                    throw new IOException("sealed response is damaged", e);
                }
                JsonObject object = errorObject(plain);
                String code = errorCode(object);
                if ("sealed_clock".equals(code) || "sealed_replay".equals(code)) {
                    headers.set(REJECTED_HEADER, code);
                    long time = errorTime(object);
                    if (time > 0) {
                        headers.set(SERVER_TIME_HEADER, String.valueOf(time));
                    }
                }
                return response.newBuilder().code(status).headers(headers.build()).body(ResponseBody.create(plain, mediaType)).build();
            }
            Response.Builder builder = response.newBuilder().code(status).headers(headers.build());
            if (responseBody != null) {
                builder.body(new SealedResponseBody(responseBody, mediaType, length, decoder));
            }
            return builder.build();
        }
    }

    public static final class RetryInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Tag tag = request.tag(Tag.class);
            if (tag == null) {
                return chain.proceed(request);
            }
            Response response = proceed(chain, request, tag);
            String rejected = response.header(REJECTED_HEADER);
            if (rejected == null) {
                return response;
            }
            if ("sealed_clock".equals(rejected)) {
                long serverTime = 0;
                try {
                    serverTime = Long.parseLong(response.header(SERVER_TIME_HEADER, "0"));
                } catch (NumberFormatException ignore) {
                }
                if (serverTime > 0) {
                    response.close();
                    clockSkew(serverTime);
                    return mapRejection(proceed(chain, request, tag));
                }
            } else if ((OPEN_PREFIX + "sealed_temp_expired").equals(rejected) && tag.fixed == null) {
                Session session = Session.forToken(tag.token);
                Credential used = tag.current;
                if (session != null) {
                    response.close();
                    session.tempExpired(used != null ? used.tid : null);
                    return mapRejection(proceed(chain, request, tag));
                }
            }
            return mapRejection(response);
        }

        private static Response proceed(Chain chain, Request request, Tag tag) throws IOException {
            if (tag.fixed == null) {
                Session session = Session.forToken(tag.token);
                if (session == null) {
                    return unauthorized(request);
                }
                try {
                    tag.current = session.credential();
                } catch (UnauthorizedException e) {
                    return unauthorized(request);
                }
            }
            return chain.proceed(request);
        }

        private static Response mapRejection(Response response) {
            String rejected = response.header(REJECTED_HEADER);
            if (rejected == null || "unauthorized".equals(rejected)) {
                return response;
            }
            return response.newBuilder().code(0).message(rejected).build();
        }
    }

    public static final class LoginKey {
        final String kid;
        final String pub;
        final long notAfter;

        LoginKey(String kid, String pub, long notAfter) {
            this.kid = kid;
            this.pub = pub;
            this.notAfter = notAfter;
        }
    }

    private static SharedPreferences settings() {
        return ApplicationLoader.applicationContext.getSharedPreferences("foxmes_sealed", Context.MODE_PRIVATE);
    }

    private static byte[] rootKey(JsonObject object) {
        JsonElement name = object.get("root");
        if (name == null) {
            return null;
        }
        String pub = RELEASE_ROOTS.get(name.getAsString());
        if (pub == null && BuildVars.DEBUG_VERSION) {
            pub = STAGE_ROOTS.get(name.getAsString());
        }
        return pub != null ? FoxMesSealedCrypto.unb64(pub) : null;
    }

    public static LoginKey verifyLoginDocument(String text, String host) {
        try {
            JsonObject document = FoxMesJson.parse(text, JsonObject.class);
            JsonObject intermediate = document.getAsJsonObject("intermediate");
            JsonObject revocation = document.getAsJsonObject("revocation");
            JsonArray keys = document.getAsJsonArray("keys");
            long current = now();
            long serial = intermediate.get("serial").getAsLong();
            String intermediatePub = intermediate.get("pub").getAsString();
            long notAfter = intermediate.get("not_after").getAsLong();
            byte[] root = rootKey(intermediate);
            byte[] sig = FoxMesSealedCrypto.unb64(intermediate.get("sig").getAsString());
            if (root == null || sig == null || notAfter <= current
                    || !FoxMesSealedCrypto.verify(root, FoxMesSealedCrypto.intermediateMessage(serial, intermediatePub, notAfter), sig)) {
                return null;
            }
            long minSerial = revocation.get("min_serial").getAsLong();
            byte[] revocationRoot = rootKey(revocation);
            byte[] revocationSig = FoxMesSealedCrypto.unb64(revocation.get("sig").getAsString());
            if (revocationRoot == null || revocationSig == null
                    || !FoxMesSealedCrypto.verify(revocationRoot, FoxMesSealedCrypto.revocationMessage(minSerial, revocation.get("issued_at").getAsLong()), revocationSig)) {
                return null;
            }
            String key = "min_serial_" + host.toLowerCase(Locale.US);
            long remembered = Math.max(settings().getLong(key, 0), MINIMAL_SERIAL);
            if (serial < Math.max(remembered, minSerial)) {
                return null;
            }
            if (minSerial > remembered) {
                settings().edit().putLong(key, minSerial).apply();
            }
            byte[] signer = FoxMesSealedCrypto.unb64(intermediatePub);
            LoginKey best = null;
            for (JsonElement element : keys) {
                JsonObject object = element.getAsJsonObject();
                String kid = object.get("kid").getAsString();
                String pub = object.get("pub").getAsString();
                long expires = object.get("not_after").getAsLong();
                byte[] raw = FoxMesSealedCrypto.unb64(pub);
                byte[] keySig = FoxMesSealedCrypto.unb64(object.get("sig").getAsString());
                if (kid.isEmpty() || raw == null || raw.length != FoxMesSealedCrypto.PUBLIC_KEY_SIZE || keySig == null || expires <= current
                        || !FoxMesSealedCrypto.verify(signer, FoxMesSealedCrypto.loginKeyMessage(kid, pub, expires), keySig)) {
                    continue;
                }
                if (best == null || expires > best.notAfter) {
                    best = new LoginKey(kid, pub, expires);
                }
            }
            return best;
        } catch (Exception e) {
            FoxMesLog.e("login key document", e);
            return null;
        }
    }

    public static Credential startLogin(LoginKey key) throws GeneralSecurityException {
        KeyPair ephemeral = FoxMesSealedCrypto.generateKeyPair();
        String epk = FoxMesSealedCrypto.b64(FoxMesSealedCrypto.encodePublic(ephemeral.getPublic()));
        byte[] shared = FoxMesSealedCrypto.agree(ephemeral.getPrivate(), FoxMesSealedCrypto.unb64(key.pub));
        return new Credential(Credential.LOGIN, null, 0, key.kid, epk, FoxMesSealedCrypto.loginKeys(shared, key.kid, epk));
    }

    public static final class SignedSocket {
        public final Request request;
        public final FoxMesSealedCrypto.SocketCodec codec;

        SignedSocket(Request request, FoxMesSealedCrypto.SocketCodec codec) {
            this.request = request;
            this.codec = codec;
        }
    }

    public static SignedSocket signSocket(Request request, String token, boolean fresh) throws IOException {
        Session session = Session.forToken(token);
        if (session == null) {
            throw new UnauthorizedException();
        }
        if (fresh) {
            session.dropTemp();
        }
        Credential credential = session.credential();
        try {
            Signed signed = sign(request, "GET", credential, null, null);
            return new SignedSocket(signed.request, new FoxMesSealedCrypto.SocketCodec(credential.keys, signed.nonce));
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot seal the socket request", e);
        }
    }

    public static byte[] pushKeyV2(String token) {
        Token parsed = Token.parse(token);
        if (parsed == null) {
            return null;
        }
        try {
            return FoxMesSealedCrypto.pushKeyV2(parsed.secret);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
