package org.telegram.messenger.foxmes;

import android.net.Uri;

import org.telegram.tgnet.SerializedData;

import java.util.Locale;

public final class FoxMesFileRef {

    private static final int MAGIC = 0x31525846;
    private static final int VERSION = 1;

    public static final int FLAG_CDN_RESIZABLE = 1;
    public static final int FLAG_GIF = 2;
    public static final int FLAG_AVATAR = 4;
    public static final int FLAG_CATALOG = 8;

    public int flags;
    public String url = "";
    public String posterUrl = "";
    public String sha256 = "";
    public String fileUniqueId = "";
    public long chatId;
    public long attachmentId;
    public int width;
    public int height;
    public String kind = "";
    public long durationMs;
    public String name = "";
    public String mime = "";
    public String poster = "";
    public String waveform = "";

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public byte[] encode() {
        SerializedData data = new SerializedData(96 + len(url) + len(posterUrl) + len(sha256) + len(fileUniqueId)
                + len(kind) + len(name) + len(mime) + len(poster) + len(waveform));
        data.writeInt32(MAGIC);
        data.writeInt32(VERSION);
        data.writeInt32(flags);
        data.writeString(nonNull(url));
        data.writeString(nonNull(posterUrl));
        data.writeString(nonNull(sha256));
        data.writeString(nonNull(fileUniqueId));
        data.writeInt64(chatId);
        data.writeInt64(attachmentId);
        data.writeInt32(width);
        data.writeInt32(height);
        data.writeString(nonNull(kind));
        data.writeInt64(durationMs);
        data.writeString(nonNull(name));
        data.writeString(nonNull(mime));
        data.writeString(nonNull(poster));
        data.writeString(nonNull(waveform));
        byte[] result = data.toByteArray();
        data.cleanup();
        return result;
    }

    public static FoxMesFileRef decode(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        SerializedData data = new SerializedData(bytes);
        try {
            if (data.readInt32(false) != MAGIC) {
                return null;
            }
            int version = data.readInt32(false);
            if (version < 1) {
                return null;
            }
            FoxMesFileRef ref = new FoxMesFileRef();
            ref.flags = data.readInt32(false);
            ref.url = data.readString(false);
            ref.posterUrl = data.readString(false);
            ref.sha256 = data.readString(false);
            ref.fileUniqueId = data.readString(false);
            ref.chatId = data.readInt64(false);
            ref.attachmentId = data.readInt64(false);
            ref.width = data.readInt32(false);
            ref.height = data.readInt32(false);
            ref.kind = data.readString(false);
            ref.durationMs = data.readInt64(false);
            ref.name = data.readString(false);
            ref.mime = data.readString(false);
            ref.poster = data.readString(false);
            ref.waveform = data.readString(false);
            return ref;
        } catch (Exception e) {
            return null;
        } finally {
            data.cleanup();
        }
    }

    private static int len(String value) {
        return value != null ? value.length() * 4 + 8 : 8;
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }


    public static final int[] CDN_PREVIEW_SIDES = {100, 320, 1280};
    public static final String[] CDN_PREVIEW_TYPES = {"s", "m", "y"};

    public static boolean isCdnOriginalURL(String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getQuery() != null) {
                return false;
            }
            String path = uri.getPath();
            if (path == null) {
                return false;
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.length() != 64) {
                return false;
            }
            for (int i = 0; i < path.length(); i++) {
                if (Character.digit(path.charAt(i), 16) < 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean mimeHasCdnPreview(String mime) {
        if (mime == null) {
            return false;
        }
        String lower = mime.toLowerCase(Locale.US);
        return lower.equals("image/jpeg") || lower.equals("image/jpg") || lower.equals("image/png") || lower.equals("image/webp");
    }

    public static String cdnPreviewURL(String url, int side) {
        return FoxMesConfiguration.trimTrailingSlashes(url) + "/-/preview/" + side + "x" + side + "/quality/smart/format/webp";
    }

    public static int sideForType(String type) {
        if (type == null || type.isEmpty()) {
            return 0;
        }
        switch (type.charAt(0)) {
            case 'a':
                return 160;
            case 'b':
                return 320;
            case 'c':
                return 640;
            case 's':
                return 100;
            case 'm':
                return 320;
            case 'x':
                return 800;
            case 'y':
                return 1280;
            default:
                return 0;
        }
    }

    public static String sizedURL(String url, int side, int width, int height, boolean resizable) {
        if (side <= 0 || !resizable || !isCdnOriginalURL(url)) {
            return url;
        }
        if (width > 0 && height > 0 && Math.max(width, height) <= side) {
            return url;
        }
        return cdnPreviewURL(url, side);
    }

    public static int[] previewDimensions(int width, int height, int side) {
        if (width <= 0 || height <= 0 || Math.max(width, height) <= side) {
            return new int[]{width, height};
        }
        double scale = side / (double) Math.max(width, height);
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }
}
