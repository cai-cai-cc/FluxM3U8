package com.flux.m3u8.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.util.SecretBox

/**
 * 任务持久化。
 *
 * 用原生 SQLite 而不是 Room：少一个注解处理器，构建更快、更不容易出错，
 * 对新手改代码也更友好。
 *
 * 注意：分片级的断点续传**不依赖数据库**，而是靠临时目录里已存在的 .ts 文件判断。
 * 这样即使数据库损坏，已下载的内容也不会作废。
 */
class TaskDb(context: Context) :
    SQLiteOpenHelper(context, "flux_tasks.db", null, DB_VERSION) {

    /**
     * 进程内所有读写都串行化。
     *
     * 下载时多个 ticker 每 400ms 写一次进度，还有 UI 线程/合并阶段的插入，
     * 若并发直冲裸 SQLite，rollback journal 模式下会互相抢文件锁：
     * 轻则 SQLiteDatabaseLockedException，重则阻塞等待连接池——UI 因此卡死、
     * 任务停在「解析中」不推进。统一加锁后进程内同一时刻只有一个线程碰数据库，
     * 从根上消掉锁竞争（数据量小，串行化开销可忽略）。
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> locked(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val DB_VERSION = 1
        private const val TABLE = "tasks"

        private const val C_ID = "id"
        private const val C_URL = "url"
        private const val C_NAME = "name"
        private const val C_DIR = "save_dir"
        private const val C_STATUS = "status"
        private const val C_VARIANT = "variant"
        private const val C_TOTAL_SEG = "total_seg"
        private const val C_DONE_SEG = "done_seg"
        private const val C_TOTAL_BYTE = "total_byte"
        private const val C_DONE_BYTE = "done_byte"
        private const val C_ESTIMATED = "estimated"
        private const val C_LIVE = "live"
        private const val C_ENCRYPTED = "encrypted"
        private const val C_ERROR = "error"
        private const val C_OUTPUT_URI = "output_uri"
        private const val C_OUTPUT_NAME = "output_name"
        private const val C_CREATED = "created_at"
        private const val C_FINISHED = "finished_at"
        // 运行时选项（重启后续传需要原样恢复）
        private const val C_CONN = "conn"
        private const val C_TO_MP4 = "to_mp4"
        private const val C_REFERER = "referer"
        private const val C_UA = "ua"
        private const val C_COOKIE = "cookie"
        private const val C_VARIANT_IDX = "variant_idx"

        /**
         * 可用于增量迁移的列（主键 id 之外的全部列）。
         * 新增列时只要在 CREATE 语句里加一行、再往这里加一项即可，
         * 老用户升级时会自动 ALTER TABLE 补上，**不会丢数据**。
         */
        private val MIGRATABLE_COLUMNS = listOf(
            C_URL to "TEXT",
            C_NAME to "TEXT",
            C_DIR to "TEXT",
            C_STATUS to "TEXT",
            C_VARIANT to "TEXT",
            C_TOTAL_SEG to "INTEGER DEFAULT 0",
            C_DONE_SEG to "INTEGER DEFAULT 0",
            C_TOTAL_BYTE to "INTEGER DEFAULT 0",
            C_DONE_BYTE to "INTEGER DEFAULT 0",
            C_ESTIMATED to "INTEGER DEFAULT 0",
            C_LIVE to "INTEGER DEFAULT 0",
            C_ENCRYPTED to "INTEGER DEFAULT 0",
            C_ERROR to "TEXT",
            C_OUTPUT_URI to "TEXT",
            C_OUTPUT_NAME to "TEXT",
            C_CREATED to "INTEGER",
            C_FINISHED to "INTEGER",
            C_CONN to "INTEGER DEFAULT 8",
            C_TO_MP4 to "INTEGER DEFAULT 0",
            C_REFERER to "TEXT",
            C_UA to "TEXT",
            C_COOKIE to "TEXT",
            C_VARIANT_IDX to "INTEGER DEFAULT -1"
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $C_ID TEXT PRIMARY KEY,
                $C_URL TEXT NOT NULL,
                $C_NAME TEXT NOT NULL,
                $C_DIR TEXT NOT NULL,
                $C_STATUS TEXT NOT NULL,
                $C_VARIANT TEXT,
                $C_TOTAL_SEG INTEGER DEFAULT 0,
                $C_DONE_SEG INTEGER DEFAULT 0,
                $C_TOTAL_BYTE INTEGER DEFAULT 0,
                $C_DONE_BYTE INTEGER DEFAULT 0,
                $C_ESTIMATED INTEGER DEFAULT 0,
                $C_LIVE INTEGER DEFAULT 0,
                $C_ENCRYPTED INTEGER DEFAULT 0,
                $C_ERROR TEXT,
                $C_OUTPUT_URI TEXT,
                $C_OUTPUT_NAME TEXT,
                $C_CREATED INTEGER,
                $C_FINISHED INTEGER,
                $C_CONN INTEGER DEFAULT 8,
                $C_TO_MP4 INTEGER DEFAULT 0,
                $C_REFERER TEXT,
                $C_UA TEXT,
                $C_COOKIE TEXT,
                $C_VARIANT_IDX INTEGER DEFAULT -1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status ON $TABLE($C_STATUS)")
    }

    /**
     * 升级策略：**只加列，绝不删表**。
     *
     * 旧实现是 `DROP TABLE + recreate`——用户一升级，任务列表、下载记录、
     * 输出文件位置全部清空，已下载的文件也随之变成没人认领的孤儿。
     * 这里改成按列补齐，老数据原样保留。
     */
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        ensureColumns(db)
    }

    /** 补齐缺失列（表不存在时顺手建表）。 */
    fun ensureColumns(db: SQLiteDatabase) {
        val existing = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) existing += c.getString(idx)
        }
        if (existing.isEmpty()) {
            onCreate(db)
            return
        }
        MIGRATABLE_COLUMNS.forEach { (name, definition) ->
            if (name !in existing) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $name $definition")
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status ON $TABLE($C_STATUS)")
    }

    fun insert(task: DownloadTask) = locked {
        val v = ContentValues().apply {
            put(C_ID, task.id)
            put(C_URL, task.url)
            put(C_NAME, task.name)
            put(C_DIR, task.saveDirUri)
            put(C_STATUS, task.status.name)
            put(C_VARIANT, task.variantLabel)
            put(C_TOTAL_SEG, task.totalSegments)
            put(C_DONE_SEG, task.doneSegments)
            put(C_TOTAL_BYTE, task.totalBytes)
            put(C_DONE_BYTE, task.doneBytes)
            put(C_ESTIMATED, if (task.estimated) 1 else 0)
            put(C_LIVE, if (task.isLive) 1 else 0)
            put(C_ENCRYPTED, if (task.encrypted) 1 else 0)
            put(C_ERROR, task.error)
            put(C_OUTPUT_URI, task.outputUri)
            put(C_OUTPUT_NAME, task.outputName)
            put(C_CREATED, task.createdAt)
            put(C_FINISHED, task.finishedAt)
            put(C_CONN, task.connections)
            put(C_TO_MP4, if (task.toMp4) 1 else 0)
            put(C_REFERER, task.referer)
            put(C_UA, task.userAgent)
            // Cookie 带登录态，加密后再落盘（失败时 SecretBox 会降级为明文）
            put(C_COOKIE, SecretBox.encrypt(task.cookie))
            put(C_VARIANT_IDX, task.variantIndex ?: -1)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 只更新进度相关字段（高频调用，避免整行覆盖）。 */
    fun updateProgress(id: String, doneSegments: Int, doneBytes: Long,
                       totalSegments: Int, totalBytes: Long, estimated: Boolean) = locked {
        val v = ContentValues().apply {
            put(C_DONE_SEG, doneSegments)
            put(C_DONE_BYTE, doneBytes)
            put(C_TOTAL_SEG, totalSegments)
            put(C_TOTAL_BYTE, totalBytes)
            put(C_ESTIMATED, if (estimated) 1 else 0)
        }
        writableDatabase.update(TABLE, v, "$C_ID=?", arrayOf(id))
    }

    fun updateStatus(id: String, status: TaskStatus, error: String? = null) = locked {
        val v = ContentValues().apply {
            put(C_STATUS, status.name)
            put(C_ERROR, error)
            if (status == TaskStatus.Completed) put(C_FINISHED, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, v, "$C_ID=?", arrayOf(id))
    }

    fun updateOutput(id: String, uri: String?, name: String?) = locked {
        val v = ContentValues().apply {
            put(C_OUTPUT_URI, uri)
            put(C_OUTPUT_NAME, name)
        }
        writableDatabase.update(TABLE, v, "$C_ID=?", arrayOf(id))
    }

    fun all(): List<DownloadTask> = locked {
        val out = mutableListOf<DownloadTask>()
        readableDatabase.query(TABLE, null, null, null, null, null, "$C_CREATED DESC").use { c ->
            while (c.moveToNext()) {
                out += DownloadTask(
                    id = c.getString(c.getColumnIndexOrThrow(C_ID)),
                    url = c.getString(c.getColumnIndexOrThrow(C_URL)),
                    name = c.getString(c.getColumnIndexOrThrow(C_NAME)),
                    saveDirUri = c.getString(c.getColumnIndexOrThrow(C_DIR)),
                    status = TaskStatus.from(c.getString(c.getColumnIndexOrThrow(C_STATUS))),
                    variantLabel = c.getString(c.getColumnIndexOrThrow(C_VARIANT)) ?: "",
                    totalSegments = c.getInt(c.getColumnIndexOrThrow(C_TOTAL_SEG)),
                    doneSegments = c.getInt(c.getColumnIndexOrThrow(C_DONE_SEG)),
                    totalBytes = c.getLong(c.getColumnIndexOrThrow(C_TOTAL_BYTE)),
                    doneBytes = c.getLong(c.getColumnIndexOrThrow(C_DONE_BYTE)),
                    estimated = c.getInt(c.getColumnIndexOrThrow(C_ESTIMATED)) == 1,
                    isLive = c.getInt(c.getColumnIndexOrThrow(C_LIVE)) == 1,
                    encrypted = c.getInt(c.getColumnIndexOrThrow(C_ENCRYPTED)) == 1,
                    error = c.getString(c.getColumnIndexOrThrow(C_ERROR)),
                    outputUri = c.getString(c.getColumnIndexOrThrow(C_OUTPUT_URI)),
                    outputName = c.getString(c.getColumnIndexOrThrow(C_OUTPUT_NAME)),
                    createdAt = c.getLong(c.getColumnIndexOrThrow(C_CREATED)),
                    finishedAt = c.getLong(c.getColumnIndexOrThrow(C_FINISHED)).let { if (it == 0L) null else it },
                    connections = c.getInt(c.getColumnIndexOrThrow(C_CONN)),
                    toMp4 = c.getInt(c.getColumnIndexOrThrow(C_TO_MP4)) == 1,
                    referer = c.getString(c.getColumnIndexOrThrow(C_REFERER)) ?: "",
                    userAgent = c.getString(c.getColumnIndexOrThrow(C_UA)) ?: "",
                    cookie = SecretBox.decrypt(c.getString(c.getColumnIndexOrThrow(C_COOKIE)) ?: ""),
                    variantIndex = c.getInt(c.getColumnIndexOrThrow(C_VARIANT_IDX)).let { if (it < 0) null else it }
                )
            }
        }
        out
    }

    /** 取出重启前仍在下载中的任务，用于自动续跑。 */
    fun resumable(): List<DownloadTask> =
        all().filter { it.status in listOf(TaskStatus.Downloading, TaskStatus.Queued, TaskStatus.Parsing, TaskStatus.Merging) }

    fun get(id: String): DownloadTask? = all().firstOrNull { it.id == id }

    fun delete(id: String) = locked {
        writableDatabase.delete(TABLE, "$C_ID=?", arrayOf(id))
    }

    fun clearFinished() = locked {
        val done = listOf(TaskStatus.Completed.name, TaskStatus.Error.name, TaskStatus.Canceled.name)
        writableDatabase.delete(TABLE, "$C_STATUS IN (?,?,?)", done.toTypedArray())
    }
}
