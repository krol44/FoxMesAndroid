package org.telegram.messenger.foxmes;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class FoxMesFiles {

    private static final ExecutorService partWriter = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "foxmes-parts");
        thread.setDaemon(true);
        return thread;
    });

    private FoxMesFiles() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_upload_saveFilePart.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_upload_saveFilePart req = (TLRPC.TL_upload_saveFilePart) r.request;
            savePart(runtime, r, req.file_id, req.file_part, req.bytes);
            req.freeResources();
        });
        transport.on(TLRPC.TL_upload_saveBigFilePart.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_upload_saveBigFilePart req = (TLRPC.TL_upload_saveBigFilePart) r.request;
            savePart(runtime, r, req.file_id, req.file_part, req.bytes);
            req.freeResources();
        });
        transport.on(TLRPC.TL_upload_getFile.class, FoxMesTransport.Lane.DOWNLOAD, r -> getFile(r, runtime));
        cleanupOldParts(runtime.account);
    }


    private static File partsRoot(int account) {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), "foxmes_up/" + account);
    }

    private static File partsDir(int account, long fileId) {
        return new File(partsRoot(account), Long.toString(fileId));
    }

    private static void savePart(FoxMesRuntime runtime, FoxMesRequest r, long fileId, int part, NativeByteBuffer bytes) {
        byte[] data;
        if (bytes == null || bytes.buffer == null) {
            data = new byte[0];
        } else {
            ByteBuffer buffer = bytes.buffer;
            int length = bytes.limit();
            data = new byte[length];
            ByteBuffer copy = buffer.duplicate();
            copy.position(0);
            copy.limit(length);
            copy.get(data);
        }
        r.wroteToSocket();
        final int account = runtime.account;
        partWriter.execute(() -> {
            try {
                File dir = partsDir(account, fileId);
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    throw new IOException("cannot create " + dir);
                }
                try (OutputStream out = new FileOutputStream(new File(dir, part + ".bin"))) {
                    out.write(data);
                }
                r.reply(new TLRPC.TL_boolTrue());
            } catch (Exception e) {
                FoxMesLog.e("upload part", e);
                r.fail(500, "FOXMES_UPLOAD_PART");
            }
        });
    }

    static File assemble(int account, TLRPC.InputFile file) throws IOException {
        File dir = partsDir(account, file.id);
        File result = new File(partsRoot(account), file.id + ".bin");
        try (OutputStream out = new FileOutputStream(result)) {
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < file.parts; i++) {
                File part = new File(dir, i + ".bin");
                if (!part.exists()) {
                    throw new IOException("upload part " + i + " of " + file.id + " is missing");
                }
                try (InputStream in = new java.io.FileInputStream(part)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        deleteDir(dir);
        return result;
    }

    static void discard(int account, TLRPC.InputFile file) {
        if (file != null) {
            deleteDir(partsDir(account, file.id));
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void cleanupOldParts(int account) {
        partWriter.execute(() -> {
            File[] entries = partsRoot(account).listFiles();
            if (entries == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File entry : entries) {
                if (entry.lastModified() < cutoff) {
                    if (entry.isDirectory()) {
                        deleteDir(entry);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        entry.delete();
                    }
                }
            }
        });
    }


    static String urlFor(FoxMesRuntime runtime, TLRPC.InputFileLocation location) {
        if (location instanceof TLRPC.TL_inputPhotoFileLocation) {
            TLRPC.TL_inputPhotoFileLocation photo = (TLRPC.TL_inputPhotoFileLocation) location;
            FoxMesFileRef ref = FoxMesFileRef.decode(photo.file_reference);
            if (ref == null) {
                String url = runtime.store().photoUrl(photo.id);
                return url != null ? FoxMesFileRef.sizedURL(url, FoxMesFileRef.sideForType(photo.thumb_size), 0, 0, true) : null;
            }
            int side = FoxMesFileRef.sideForType(photo.thumb_size);
            return FoxMesFileRef.sizedURL(ref.url, side, ref.width, ref.height, ref.has(FoxMesFileRef.FLAG_CDN_RESIZABLE));
        }
        if (location instanceof TLRPC.TL_inputDocumentFileLocation) {
            TLRPC.TL_inputDocumentFileLocation document = (TLRPC.TL_inputDocumentFileLocation) location;
            FoxMesFileRef ref = FoxMesFileRef.decode(document.file_reference);
            if (ref == null) {
                return null;
            }
            if (document.thumb_size == null || document.thumb_size.isEmpty()) {
                return ref.url;
            }
            if (ref.posterUrl == null || ref.posterUrl.isEmpty()) {
                return null;
            }
            return FoxMesFileRef.sizedURL(ref.posterUrl, FoxMesFileRef.sideForType(document.thumb_size), ref.width, ref.height, true);
        }
        if (location instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            TLRPC.TL_inputPeerPhotoFileLocation peerPhoto = (TLRPC.TL_inputPeerPhotoFileLocation) location;
            String url = runtime.store().photoUrl(peerPhoto.photo_id);
            if (url == null) {
                return null;
            }
            return FoxMesFileRef.sizedURL(url, peerPhoto.big ? 640 : 160, 0, 0, true);
        }
        return null;
    }

    private static void getFile(FoxMesRequest r, FoxMesRuntime runtime) throws IOException {
        TLRPC.TL_upload_getFile req = (TLRPC.TL_upload_getFile) r.request;
        String url = urlFor(runtime, req.location);
        if (url == null) {
            r.fail(400, "FILE_ID_INVALID");
            return;
        }
        long offset = req.offset;
        int limit = req.limit > 0 ? req.limit : 128 * 1024;
        Call call = runtime.http.newDownloadCall(url, offset, limit);
        r.attachCall(call);
        byte[] data;
        try (Response response = call.execute()) {
            int status = response.code();
            if (status == 416) {
                data = new byte[0];
            } else if (status == 206 || status == 200) {
                ResponseBody body = response.body();
                data = body != null ? readRange(body.byteStream(), status == 200 ? offset : 0, limit) : new byte[0];
            } else if (status >= 500 || status == 408 || status == 429) {
                throw new FoxMesHttpException(status, null, null, "");
            } else {
                FoxMesLog.w("download " + status + " " + url);
                r.fail(400, "FILE_ID_INVALID");
                return;
            }
        }
        TLRPC.TL_upload_file file = new TLRPC.TL_upload_file();
        file.type = new TLRPC.TL_storage_filePartial();
        file.mtime = 0;
        try {
            NativeByteBuffer bytes = new NativeByteBuffer(data.length);
            if (bytes.buffer == null) {
                throw new IOException("no native buffer for " + data.length + " bytes");
            }
            bytes.buffer.put(data);
            bytes.buffer.position(0);
            file.bytes = bytes;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("buffer", e);
        }
        r.reply(file);
    }

    private static byte[] readRange(InputStream in, long skip, int limit) throws IOException {
        long skipped = 0;
        while (skipped < skip) {
            long step = in.skip(skip - skipped);
            if (step <= 0) {
                if (in.read() < 0) {
                    return new byte[0];
                }
                step = 1;
            }
            skipped += step;
        }
        byte[] buffer = new byte[limit];
        int total = 0;
        while (total < limit) {
            int read = in.read(buffer, total, limit - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        if (total == limit) {
            return buffer;
        }
        byte[] result = new byte[total];
        System.arraycopy(buffer, 0, result, 0, total);
        return result;
    }
}
