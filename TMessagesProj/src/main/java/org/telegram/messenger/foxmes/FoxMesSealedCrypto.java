package org.telegram.messenger.foxmes;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class FoxMesSealedCrypto {

    public static final int KEY_SIZE = 32;
    public static final int NONCE_SIZE = 12;
    public static final int TAG_SIZE = 16;
    public static final int PUBLIC_KEY_SIZE = 65;
    public static final int FRAME_SIZE = 65536;
    public static final int FRAME_OVERHEAD = NONCE_SIZE + TAG_SIZE;
    public static final String DIR_C2S = "c2s";
    public static final String DIR_S2C = "s2c";

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static ECParameterSpec curve;

    private FoxMesSealedCrypto() {
    }

    public static final class Keys {
        public final byte[] c2s;
        public final byte[] s2c;

        public Keys(byte[] c2s, byte[] s2c) {
            this.c2s = c2s;
            this.s2c = s2c;
        }
    }

    public static final class FrameContext {
        public final byte[] nonce;
        public final String dir;
        public final int status;
        public final String type;
        public final String range;
        public final long offset;

        public FrameContext(byte[] nonce, String dir, int status, String type, String range, long offset) {
            this.nonce = nonce;
            this.dir = dir;
            this.status = status;
            this.type = type == null ? "" : type;
            this.range = range == null ? "" : range;
            this.offset = offset;
        }
    }

    public static final class SignedHeader {
        public final String name;
        public final String value;

        public SignedHeader(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String b64(byte[] data) {
        StringBuilder result = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            result.append(ALPHABET[(chunk >> 18) & 63]).append(ALPHABET[(chunk >> 12) & 63])
                    .append(ALPHABET[(chunk >> 6) & 63]).append(ALPHABET[chunk & 63]);
        }
        int rest = data.length - i;
        if (rest == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            result.append(ALPHABET[(chunk >> 18) & 63]).append(ALPHABET[(chunk >> 12) & 63]);
        } else if (rest == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            result.append(ALPHABET[(chunk >> 18) & 63]).append(ALPHABET[(chunk >> 12) & 63]).append(ALPHABET[(chunk >> 6) & 63]);
        }
        return result.toString();
    }

    public static byte[] unb64(String value) {
        if (value == null || value.length() % 4 == 1) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '-') v = 62;
            else if (c == '_') v = 63;
            else return null;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        if (bits > 0 && (buffer & ((1 << bits) - 1)) != 0) {
            return null;
        }
        return out.toByteArray();
    }

    public static byte[] random(int size) {
        byte[] result = new byte[size];
        RANDOM.nextBytes(result);
        return result;
    }

    public static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, String info) throws GeneralSecurityException {
        byte[] prk = hmac(salt == null || salt.length == 0 ? new byte[KEY_SIZE] : salt, ikm);
        byte[] infoBytes = utf8(info);
        byte[] input = new byte[infoBytes.length + 1];
        System.arraycopy(infoBytes, 0, input, 0, infoBytes.length);
        input[infoBytes.length] = 1;
        return hmac(prk, input);
    }

    public static Keys loginKeys(byte[] shared, String kid, String epk) throws GeneralSecurityException {
        byte[] salt = utf8("foxmes-login-v1");
        return new Keys(hkdf(shared, salt, "c2s|" + kid + "|" + epk), hkdf(shared, salt, "s2c|" + kid + "|" + epk));
    }

    public static Keys sessionKeys(byte[] secret) throws GeneralSecurityException {
        byte[] salt = utf8("foxmes-sealed-v1");
        return new Keys(hkdf(secret, salt, "c2s"), hkdf(secret, salt, "s2c"));
    }

    public static byte[] bindSalt(byte[] secret) throws GeneralSecurityException {
        return hkdf(secret, utf8("foxmes-sealed-v1"), "bind");
    }

    public static byte[] tempSecret(byte[] shared, byte[] bindSalt, long sid, String tid, String clientPublic, String serverPublic) throws GeneralSecurityException {
        return hkdf(shared, bindSalt, "foxmes-temp-v1|" + sid + "|" + tid + "|" + clientPublic + "|" + serverPublic);
    }

    public static Keys tempKeys(byte[] temp) throws GeneralSecurityException {
        byte[] salt = utf8("foxmes-temp-v1");
        return new Keys(hkdf(temp, salt, "c2s"), hkdf(temp, salt, "s2c"));
    }

    public static Keys wsKeys(Keys keys, byte[] nonce) throws GeneralSecurityException {
        byte[] salt = utf8("foxmes-ws-v1");
        String info = b64(nonce);
        return new Keys(hkdf(keys.c2s, salt, info), hkdf(keys.s2c, salt, info));
    }

    public static byte[] pushKeyV2(byte[] secret) throws GeneralSecurityException {
        return hkdf(secret, null, "fxl-push-v2");
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plain, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_SIZE * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] sealed, int offset, int length, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_SIZE * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, offset, length);
    }

    public static byte[] headerBlockAad(byte[] requestNonce) {
        return utf8("foxmes-headers-v1\n" + b64(requestNonce));
    }

    public static List<String[]> openHeaderBlock(byte[] key, byte[] requestNonce, String value) {
        byte[] raw = value != null ? unb64(value) : null;
        if (raw == null || raw.length < NONCE_SIZE + TAG_SIZE) {
            return null;
        }
        String text;
        try {
            byte[] nonce = Arrays.copyOf(raw, NONCE_SIZE);
            text = new String(open(key, nonce, raw, NONCE_SIZE, raw.length - NONCE_SIZE, headerBlockAad(requestNonce)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
        List<String[]> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            result.add(new String[]{line.substring(0, colon), line.substring(colon + 1)});
        }
        return result;
    }

    public static byte[] frameAad(FrameContext context, long offset, boolean last) {
        return utf8("foxmes-frame-v1\n" + b64(context.nonce) + "\n" + context.dir + "\n" + context.status + "\n"
                + context.type + "\n" + context.range + "\n" + offset + "\n" + (last ? "1" : "0"));
    }

    public static long sealedLength(long plain) {
        long frames = plain / FRAME_SIZE;
        if (plain % FRAME_SIZE != 0 || plain == 0) {
            frames++;
        }
        return plain + frames * FRAME_OVERHEAD;
    }

    public static long plainLength(long sealed) {
        if (sealed < FRAME_OVERHEAD) {
            return -1;
        }
        long full = FRAME_SIZE + FRAME_OVERHEAD;
        long frames = (sealed + full - 1) / full;
        long plain = sealed - frames * FRAME_OVERHEAD;
        return plain >= 0 && sealedLength(plain) == sealed ? plain : -1;
    }

    public static byte[] sealFrames(byte[] key, FrameContext context, byte[] plain, List<byte[]> nonces) throws GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) sealedLength(plain.length));
        int offset = 0;
        int index = 0;
        do {
            int size = Math.min(FRAME_SIZE, plain.length - offset);
            boolean last = offset + size == plain.length;
            byte[] nonce = nonces != null && index < nonces.size() ? nonces.get(index) : random(NONCE_SIZE);
            byte[] sealed = seal(key, nonce, Arrays.copyOfRange(plain, offset, offset + size), frameAad(context, context.offset + offset, last));
            out.write(nonce, 0, nonce.length);
            out.write(sealed, 0, sealed.length);
            offset += size;
            index++;
        } while (offset < plain.length);
        return out.toByteArray();
    }

    public static final class FrameDecoder {
        private final byte[] key;
        private final FrameContext context;
        private byte[] buffer = new byte[0];
        private long offset;
        private boolean failed;
        private boolean finished;

        public FrameDecoder(byte[] key, FrameContext context) {
            this.key = key;
            this.context = context;
            this.offset = context.offset;
        }

        private byte[] openFrame(int size, boolean last) throws GeneralSecurityException {
            byte[] nonce = Arrays.copyOfRange(buffer, 0, NONCE_SIZE);
            try {
                byte[] plain = open(key, nonce, buffer, NONCE_SIZE, size - NONCE_SIZE, frameAad(context, offset, last));
                buffer = Arrays.copyOfRange(buffer, size, buffer.length);
                offset += plain.length;
                return plain;
            } catch (GeneralSecurityException e) {
                failed = true;
                throw e;
            }
        }

        public byte[] feed(byte[] data, int length) throws GeneralSecurityException {
            if (failed || finished) {
                throw new GeneralSecurityException("frame stream is closed");
            }
            byte[] joined = Arrays.copyOf(buffer, buffer.length + length);
            System.arraycopy(data, 0, joined, buffer.length, length);
            buffer = joined;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int full = FRAME_SIZE + FRAME_OVERHEAD;
            while (buffer.length > full) {
                byte[] plain = openFrame(full, false);
                out.write(plain, 0, plain.length);
            }
            return out.toByteArray();
        }

        public byte[] finish() throws GeneralSecurityException {
            if (failed || finished) {
                throw new GeneralSecurityException("frame stream is closed");
            }
            finished = true;
            if (buffer.length < FRAME_OVERHEAD) {
                failed = true;
                throw new GeneralSecurityException("frame stream is truncated");
            }
            return openFrame(buffer.length, true);
        }
    }

    public static byte[] requestAad(String method, String host, String path, String query, long ts, String cred, String type, List<SignedHeader> headers) {
        StringBuilder text = new StringBuilder("foxmes-req-v1\n").append(method).append('\n')
                .append(host.toLowerCase(java.util.Locale.US)).append('\n').append(path).append('\n')
                .append(query).append('\n').append(ts).append('\n').append(cred).append('\n')
                .append(type).append('\n');
        for (SignedHeader header : headers) {
            text.append(header.name).append(':').append(header.value).append('\n');
        }
        return utf8(text.toString());
    }

    public static byte[] requestTag(byte[] key, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        return seal(key, nonce, new byte[0], aad);
    }

    public static final class SocketCodec {
        private final Keys keys;
        private long sent;
        private long received;

        public SocketCodec(Keys temp, byte[] nonce) throws GeneralSecurityException {
            this.keys = wsKeys(temp, nonce);
        }

        private static byte[] counter(long value) {
            byte[] result = new byte[NONCE_SIZE];
            for (int i = 0; i < 8; i++) {
                result[NONCE_SIZE - 1 - i] = (byte) (value >>> (8 * i));
            }
            return result;
        }

        public synchronized byte[] seal(byte[] plain) throws GeneralSecurityException {
            byte[] result = FoxMesSealedCrypto.seal(keys.c2s, counter(sent), plain, utf8("foxmes-ws-v1\n" + DIR_C2S));
            sent++;
            return result;
        }

        public synchronized byte[] open(byte[] message) throws GeneralSecurityException {
            byte[] result = FoxMesSealedCrypto.open(keys.s2c, counter(received), message, 0, message.length, utf8("foxmes-ws-v1\n" + DIR_S2C));
            received++;
            return result;
        }
    }

    private static synchronized ECParameterSpec curve() throws GeneralSecurityException {
        if (curve == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            curve = ((ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        }
        return curve;
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[KEY_SIZE];
        int length = Math.min(raw.length, KEY_SIZE);
        System.arraycopy(raw, raw.length - length, result, KEY_SIZE - length, length);
        return result;
    }

    public static byte[] encodePublic(PublicKey key) {
        ECPoint point = ((ECPublicKey) key).getW();
        byte[] result = new byte[PUBLIC_KEY_SIZE];
        result[0] = 4;
        System.arraycopy(fixed(point.getAffineX()), 0, result, 1, KEY_SIZE);
        System.arraycopy(fixed(point.getAffineY()), 0, result, 1 + KEY_SIZE, KEY_SIZE);
        return result;
    }

    public static PublicKey decodePublic(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != PUBLIC_KEY_SIZE || raw[0] != 4) {
            throw new GeneralSecurityException("bad public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + KEY_SIZE));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + KEY_SIZE, PUBLIC_KEY_SIZE));
        ECParameterSpec params = curve();
        BigInteger p = ((java.security.spec.ECFieldFp) params.getCurve().getField()).getP();
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.pow(3).add(params.getCurve().getA().multiply(x)).add(params.getCurve().getB()).mod(p);
        if (x.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !lhs.equals(rhs)) {
            throw new GeneralSecurityException("point is not on the curve");
        }
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    public static byte[] agree(java.security.PrivateKey key, byte[] peer) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(key);
        agreement.doPhase(decodePublic(peer), true);
        byte[] shared = agreement.generateSecret();
        if (shared.length != KEY_SIZE) {
            shared = fixed(new BigInteger(1, shared));
        }
        return shared;
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(decodePublic(publicKey));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] intermediateMessage(long serial, String pub, long notAfter) {
        return utf8("foxmes-intermediate-v1\n" + serial + "\n" + pub + "\n" + notAfter);
    }

    public static byte[] loginKeyMessage(String kid, String pub, long notAfter) {
        return utf8("foxmes-login-key-v1\n" + kid + "\n" + pub + "\n" + notAfter);
    }

    public static byte[] revocationMessage(long minSerial, long issuedAt) {
        return utf8("foxmes-revocation-v1\n" + minSerial + "\n" + issuedAt);
    }
}
