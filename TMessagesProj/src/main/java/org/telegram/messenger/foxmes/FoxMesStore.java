package org.telegram.messenger.foxmes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FoxMesStore {

    private static final int VERSION = 2;
    private static final FoxMesStore[] instances = new FoxMesStore[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    private final Helper helper;

    public static FoxMesStore getInstance(int account) {
        synchronized (instances) {
            FoxMesStore store = instances[account];
            if (store == null) {
                store = new FoxMesStore(account);
                instances[account] = store;
            }
            return store;
        }
    }

    private FoxMesStore(int account) {
        helper = new Helper("foxmes_bridge_" + account + "_" + FoxMesConfiguration.profileKey() + ".db");
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(String name) {
            super(ApplicationLoader.applicationContext, name, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE chat_peer(chat_id INTEGER PRIMARY KEY, peer_user_id INTEGER NOT NULL, is_saved INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX chat_peer_peer ON chat_peer(peer_user_id)");
            db.execSQL("CREATE TABLE msg(id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, reaction_revision INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX msg_chat ON msg(chat_id)");
            db.execSQL("CREATE TABLE photo_url(photo_id INTEGER PRIMARY KEY, url TEXT NOT NULL)");
            db.execSQL("CREATE TABLE draft_rev(chat_id INTEGER PRIMARY KEY, rev INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
            createEphemeral(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                createEphemeral(db);
            }
        }

        private static void createEphemeral(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS ephemeral_msg(id INTEGER PRIMARY KEY)");
        }
    }

    private SQLiteDatabase db() {
        return helper.getWritableDatabase();
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            db.delete("chat_peer", null, null);
            db.delete("msg", null, null);
            db.delete("ephemeral_msg", null, null);
            db.delete("photo_url", null, null);
            db.delete("draft_rev", null, null);
            db.delete("kv", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    public synchronized void putChat(long chatId, long peerUserId, boolean isSaved) {
        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("peer_user_id", peerUserId);
        values.put("is_saved", isSaved ? 1 : 0);
        db().insertWithOnConflict("chat_peer", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void removeChat(long chatId) {
        db().delete("chat_peer", "chat_id = ?", new String[]{String.valueOf(chatId)});
    }

    public synchronized Map<Long, Long> chatToPeer() {
        HashMap<Long, Long> result = new HashMap<>();
        try (Cursor cursor = db().rawQuery("SELECT chat_id, peer_user_id FROM chat_peer", null)) {
            while (cursor.moveToNext()) {
                result.put(cursor.getLong(0), cursor.getLong(1));
            }
        }
        return result;
    }


    public synchronized void putMessage(int id, long chatId, int revision, long reactionRevision) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("chat_id", chatId);
        values.put("revision", revision);
        values.put("reaction_revision", reactionRevision);
        db().insertWithOnConflict("msg", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putMessageChat(int id, long chatId) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("chat_id", chatId);
        db().insertWithOnConflict("msg", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void putMessages(List<int[]> idRevision, List<Long> chats, List<Long> reactionRevisions) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (int i = 0; i < idRevision.size(); i++) {
                ContentValues values = new ContentValues();
                values.put("id", idRevision.get(i)[0]);
                values.put("chat_id", chats.get(i));
                values.put("revision", idRevision.get(i)[1]);
                values.put("reaction_revision", reactionRevisions.get(i));
                db.insertWithOnConflict("msg", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized long chatOfMessage(int id) {
        try (Cursor cursor = db().rawQuery("SELECT chat_id FROM msg WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public synchronized long[] revisions(int id) {
        try (Cursor cursor = db().rawQuery("SELECT revision, reaction_revision FROM msg WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? new long[]{cursor.getLong(0), cursor.getLong(1)} : null;
        }
    }

    public synchronized void setReactionRevision(int id, long reactionRevision) {
        ContentValues values = new ContentValues();
        values.put("reaction_revision", reactionRevision);
        db().update("msg", values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized List<Integer> messagesOfChat(long chatId) {
        ArrayList<Integer> result = new ArrayList<>();
        try (Cursor cursor = db().rawQuery("SELECT id FROM msg WHERE chat_id = ? ORDER BY id", new String[]{String.valueOf(chatId)})) {
            while (cursor.moveToNext()) {
                result.add(cursor.getInt(0));
            }
        }
        return result;
    }

    public synchronized void removeMessages(List<Integer> ids) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (Integer id : ids) {
                db.delete("msg", "id = ?", new String[]{String.valueOf(id)});
                db.delete("ephemeral_msg", "id = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void removeMessagesOfChat(long chatId) {
        db().delete("ephemeral_msg", "id IN (SELECT id FROM msg WHERE chat_id = ?)", new String[]{String.valueOf(chatId)});
        db().delete("msg", "chat_id = ?", new String[]{String.valueOf(chatId)});
    }


    public synchronized void putEphemeral(List<Integer> ids) {
        if (ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (Integer id : ids) {
                ContentValues values = new ContentValues();
                values.put("id", id);
                db.insertWithOnConflict("ephemeral_msg", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean isEphemeral(int id) {
        try (Cursor cursor = db().rawQuery("SELECT 1 FROM ephemeral_msg WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst();
        }
    }


    public synchronized void putPhotoUrl(long photoId, String url) {
        ContentValues values = new ContentValues();
        values.put("photo_id", photoId);
        values.put("url", url);
        db().insertWithOnConflict("photo_url", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String photoUrl(long photoId) {
        try (Cursor cursor = db().rawQuery("SELECT url FROM photo_url WHERE photo_id = ?", new String[]{String.valueOf(photoId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }


    public synchronized void putDraftRevision(long chatId, long revision) {
        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("rev", revision);
        db().insertWithOnConflict("draft_rev", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Long draftRevision(long chatId) {
        try (Cursor cursor = db().rawQuery("SELECT rev FROM draft_rev WHERE chat_id = ?", new String[]{String.valueOf(chatId)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : null;
        }
    }

    public synchronized void removeDraftRevision(long chatId) {
        db().delete("draft_rev", "chat_id = ?", new String[]{String.valueOf(chatId)});
    }


    public synchronized void put(String key, String value) {
        if (value == null) {
            db().delete("kv", "k = ?", new String[]{key});
            return;
        }
        ContentValues values = new ContentValues();
        values.put("k", key);
        values.put("v", value);
        db().insertWithOnConflict("kv", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String get(String key) {
        try (Cursor cursor = db().rawQuery("SELECT v FROM kv WHERE k = ?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    public long getLong(String key, long fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void putLong(String key, long value) {
        put(key, String.valueOf(value));
    }
}
