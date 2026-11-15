package com.dsh.collect;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 SQLite 数据库。
 *
 * <p>库文件放在 App 私有目录（/data/data/com.dsh.collect/databases/），不放在 /sdcard：
 * 外部存储走 FUSE，SQLite 的文件锁在上面不稳定。
 * 需要给 agent / Obsidian 用时，由 {@link Export} 导出 Markdown + JSONL 到共享目录。
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "collect.db";
    private static final int VERSION = 2;

    private static final String T_ARTICLES = "articles";

    private static Db INSTANCE;

    /** 进程内单例 */
    public static synchronized Db get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new Db(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    public Db(Context ctx) {
        super(ctx.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_ARTICLES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT UNIQUE NOT NULL,"
                + "title TEXT DEFAULT '',"
                + "account TEXT DEFAULT '',"
                + "source TEXT DEFAULT 'wechat',"
                + "collected_at INTEGER DEFAULT 0,"
                + "updated_at INTEGER DEFAULT 0,"
                + "content_html TEXT DEFAULT '',"
                + "content_text TEXT DEFAULT '',"
                + "summary TEXT DEFAULT '',"
                + "tags TEXT DEFAULT '',"
                + "status TEXT DEFAULT 'new',"
                + "starred INTEGER DEFAULT 0,"
                + "read_at INTEGER DEFAULT 0,"
                + "fetch_state TEXT DEFAULT 'pending',"
                + "notes TEXT DEFAULT ''"
                + ")");
        db.execSQL("CREATE INDEX idx_articles_collected ON " + T_ARTICLES + " (collected_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + T_ARTICLES + " ADD COLUMN export_name TEXT DEFAULT ''");
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 写入

    /** 插入或更新（按 url 去重）；返回记录 id */
    public synchronized long upsert(Article a) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        a.updatedAt = now;
        ContentValues v = new ContentValues();
        v.put("url", a.url);
        v.put("title", a.title);
        v.put("account", a.account);
        v.put("source", a.source);
        v.put("collected_at", a.collectedAt);
        v.put("updated_at", a.updatedAt);
        v.put("content_html", a.contentHtml);
        v.put("content_text", a.contentText);
        v.put("summary", a.summary);
        v.put("tags", a.tags);
        v.put("status", a.status);
        v.put("starred", a.starred ? 1 : 0);
        v.put("read_at", a.readAt);
        v.put("fetch_state", a.fetchState);
        v.put("notes", a.notes);
        v.put("export_name", a.exportName);
        // 空正文不覆盖已有内容（重复收集同一篇时保住抓到的正文）
        if (a.contentHtml == null || a.contentHtml.isEmpty()) {
            v.remove("content_html");
        }
        if (a.contentText == null || a.contentText.isEmpty()) {
            v.remove("content_text");
        }
        if (a.summary == null || a.summary.isEmpty()) {
            v.remove("summary");
        }

        long id = db.insertWithOnConflict(T_ARTICLES, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id > 0) {
            a.id = id;
            return id;
        }
        // 已存在则按 url 更新（保留 id）
        db.update(T_ARTICLES, v, "url=?", new String[]{a.url});
        Cursor c = db.rawQuery("SELECT id FROM " + T_ARTICLES + " WHERE url=?", new String[]{a.url});
        try {
            if (c.moveToFirst()) {
                a.id = c.getLong(0);
                return a.id;
            }
        } finally {
            c.close();
        }
        return -1L;
    }

    /** 只更新元信息（不动正文） */
    public synchronized void updateMeta(long id, String title, String account, String summary,
                                        String fetchState) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("account", account);
        v.put("summary", summary);
        v.put("fetch_state", fetchState);
        v.put("updated_at", System.currentTimeMillis());
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateContent(long id, String html, String text, String title,
                                           String account, String summary) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("content_html", html);
        v.put("content_text", text);
        if (title != null && !title.isEmpty()) {
            v.put("title", title);
        }
        if (account != null && !account.isEmpty()) {
            v.put("account", account);
        }
        if (summary != null) {
            v.put("summary", summary);
        }
        v.put("fetch_state", Article.FETCH_OK);
        v.put("updated_at", System.currentTimeMillis());
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateStatus(long id, String status) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", status);
        if (Article.STATUS_READ.equals(status)) {
            v.put("read_at", System.currentTimeMillis());
        }
        v.put("updated_at", System.currentTimeMillis());
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateStar(long id, boolean starred) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("starred", starred ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 记住导出文件名（标题变化时重命名而不是新建文件） */
    public synchronized void updateExportName(long id, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("export_name", name);
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateTags(long id, String tags) {        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("tags", tags);
        v.put("updated_at", System.currentTimeMillis());
        db.update(T_ARTICLES, v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void delete(long id) {
        getWritableDatabase().delete(T_ARTICLES, "id=?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------- 查询

    private static Article fromCursor(Cursor c) {
        Article a = new Article();
        a.id = c.getLong(c.getColumnIndexOrThrow("id"));
        a.url = str(c, "url");
        a.title = str(c, "title");
        a.account = str(c, "account");
        a.source = str(c, "source");
        a.collectedAt = c.getLong(c.getColumnIndexOrThrow("collected_at"));
        a.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        a.contentHtml = str(c, "content_html");
        a.contentText = str(c, "content_text");
        a.summary = str(c, "summary");
        a.tags = str(c, "tags");
        a.status = str(c, "status");
        a.starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1;
        a.readAt = c.getLong(c.getColumnIndexOrThrow("read_at"));
        a.fetchState = str(c, "fetch_state");
        a.notes = str(c, "notes");
        a.exportName = str(c, "export_name");
        return a;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) {
            return "";
        }
        return c.getString(i);
    }

    /** 按条件查询，倒序 */
    public synchronized List<Article> query(String keyword, String status, boolean onlyStarred,
                                           String tag) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + T_ARTICLES + " WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (onlyStarred) {
            sql.append(" AND starred=1");
        }
        if (tag != null && !tag.isEmpty()) {
            sql.append(" AND (tags LIKE ? OR tags LIKE ? OR tags LIKE ?)");
            args.add("%" + tag + "%");
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = "%" + keyword.trim() + "%";
            sql.append(" AND (title LIKE ? OR account LIKE ? OR tags LIKE ? OR summary LIKE ?"
                    + " OR content_text LIKE ?)");
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
        }
        sql.append(" ORDER BY starred DESC, collected_at DESC");

        List<Article> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized Article byId(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + T_ARTICLES + " WHERE id=?",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    public synchronized Article byUrl(String url) {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + T_ARTICLES + " WHERE url=?",
                new String[]{url});
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    public synchronized List<Article> all() {
        return query(null, null, false, null);
    }

    /** 待抓取正文的记录 */
    public synchronized List<Article> pendingFetch() {
        List<Article> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM " + T_ARTICLES + " WHERE fetch_state=? ORDER BY collected_at DESC",
                new String[]{Article.FETCH_PENDING});
        try {
            while (c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 全部用到的标签（去重、按使用次数倒序） */
    public synchronized List<String> allTags() {
        java.util.Map<String, Integer> counter = new java.util.LinkedHashMap<>();
        for (Article a : all()) {
            for (String t : a.tagList()) {
                Integer n = counter.get(t);
                counter.put(t, n == null ? 1 : n + 1);
            }
        }
        List<String> keys = new ArrayList<>(counter.keySet());
        java.util.Collections.sort(keys, new java.util.Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return counter.get(y) - counter.get(x);
            }
        });
        return keys;
    }

    public synchronized int count() {
        return scalar("SELECT COUNT(*) FROM " + T_ARTICLES);
    }

    public synchronized int countStatus(String status) {
        return scalar("SELECT COUNT(*) FROM " + T_ARTICLES + " WHERE status=?", status);
    }

    private int scalar(String sql, String... args) {
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }
}
