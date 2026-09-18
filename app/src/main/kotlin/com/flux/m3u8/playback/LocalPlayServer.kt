package com.flux.m3u8.playback

import com.flux.m3u8.crypto.HlsCrypto
import com.flux.m3u8.model.Segment
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * 边下边播用的**本地回环 HTTP 服务**。
 *
 * 原理：把下载任务的分片目录伪装成一个标准 HLS 源，播放器访问
 * `http://127.0.0.1:<随机端口>/<token>/<任务ID>/index.m3u8`，
 * 播放已经落盘的分片；尚未来得及下载的分片由本服务**按需回源拉取**并解密后再吐给播放器。
 *
 * 这样做的好处：
 *  · **不重复消耗带宽**：已下载的部分直接读本地文件，不再走网络
 *  · **可拖动**：拖到没下载的位置也能播，按需回源即可
 *  · **加密流自动处理**：本地分片是解密后的，本地播放列表里不再带 EXT-X-KEY
 *
 * 安全约束（三条都做了）：
 *  1. 只绑定 127.0.0.1，外部进程/局域网访问不到
 *  2. 路径里带一次性随机 token，猜不到就 404
 *  3. 分片索引只接受纯数字，不做任何文件路径拼接，杜绝目录穿越
 */
class LocalPlayServer(private val clientProvider: () -> OkHttpClient) {

    /** 一个可播放任务的会话。 */
    data class Session(
        val token: String,
        val taskId: String,
        val segments: List<Segment>,
        val headers: Map<String, String>,
        val keys: Map<String, ByteArray>,
        val tmpDir: File,
        val cacheDir: File,
        val targetDuration: Int
    )

    private data class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var executor: ExecutorService? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val fetchLocks = ConcurrentHashMap<String, Any>()
    private val threadIds = AtomicInteger(0)

    val isRunning: Boolean get() = serverSocket != null

    fun start() {
        synchronized(this) {
            if (serverSocket != null) return
            val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            ss.reuseAddress = true
            port = ss.localPort
            serverSocket = ss
            executor = Executors.newFixedThreadPool(8) { r ->
                Thread(r, "flux-play-${threadIds.incrementAndGet()}").apply { isDaemon = true }
            }
            thread(name = "flux-play-accept", isDaemon = true) { acceptLoop(ss) }
        }
    }

    fun stop() {
        var ex: ExecutorService? = null
        synchronized(this) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            port = 0
            ex = executor
            executor = null
            sessions.clear()
            fetchLocks.clear()
        }
        ex?.shutdownNow()
    }

    /** 注册会话并返回可播放地址。 */
    fun register(session: Session): String {
        start()
        sessions[session.token] = session
        return "http://127.0.0.1:$port/${session.token}/${session.taskId}/index.m3u8"
    }

    fun unregister(token: String) {
        sessions.remove(token)
    }

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    // ──────────────── 连接处理 ────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                if (ss.isClosed) break else continue
            }
            // 双保险：理论上只有回环地址能连进来
            if (socket.inetAddress?.isLoopbackAddress != true) {
                runCatching { socket.close() }
                continue
            }
            val pool = executor
            if (pool == null || pool.isShutdown) {
                runCatching { socket.close() }
                continue
            }
            runCatching { pool.execute { handle(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val out = socket.getOutputStream()
            val req = readRequest(input)
            if (req == null) {
                send(out, null, 400, "Bad Request", "bad request")
                return
            }
            when (req.method) {
                "GET", "HEAD" -> route(out, req)
                else -> send(out, req, 405, "Method Not Allowed", "unsupported")
            }
            out.flush()
        } catch (e: Exception) {
            // 客户端（播放器）半途断开是常态，不记日志
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 读请求行与请求头（读到空行即止）。 */
    private fun readRequest(input: BufferedInputStream): Req? {
        val lines = ArrayList<String>(16)
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (lines.size < 64) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code && prev == '\r'.code) {
                val line = buf.toString("UTF-8").trim()
                buf.reset()
                if (line.isEmpty()) break
                lines.add(line)
            } else if (c != '\r'.code) {
                buf.write(c)
            }
            prev = c
        }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>(lines.size)
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx <= 0) continue
            headers[lines[i].substring(0, idx).trim().lowercase(Locale.US)] =
                lines[i].substring(idx + 1).trim()
        }
        return Req(parts[0].uppercase(Locale.US), parts[1], headers)
    }

    private fun route(out: OutputStream, req: Req) {
        val segs = req.path.trim('/').split('/')
        if (segs.size < 3) {
            send(out, req, 404, "Not Found", "not found")
            return
        }
        val session = sessions[segs[0]]
        if (session == null || session.taskId != segs[1]) {
            send(out, req, 404, "Not Found", "not found")
            return
        }
        when (segs[2]) {
            "index.m3u8" -> servePlaylist(out, req, session)
            "s" -> {
                val idx = segs.getOrNull(3)?.substringBefore('.')?.toIntOrNull()
                if (idx == null) {
                    send(out, req, 404, "Not Found", "not found")
                    return
                }
                serveSegment(out, req, session, idx)
            }
            else -> send(out, req, 404, "Not Found", "not found")
        }
    }

    // ──────────────── 播放列表 ────────────────

    private fun servePlaylist(out: OutputStream, req: Req, session: Session) {
        val body = PlaylistBuilder.build(session.segments, session.targetDuration)
            .toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/vnd.apple.mpegurl",
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-store",
            "Connection" to "close"
        )
        sendHead(out, 200, "OK", headers)
        if (req.method != "HEAD") out.write(body)
    }

    // ──────────────── 分片 ────────────────

    private fun serveSegment(out: OutputStream, req: Req, session: Session, index: Int) {
        val seg = session.segments.getOrNull(index)
        if (seg == null) {
            send(out, req, 404, "Not Found", "segment out of range")
            return
        }

        // ① 已下载完成的本地分片
        val local = File(session.tmpDir, seg.fileName)
        if (local.exists() && local.length() > 0) {
            serveFile(out, req, local)
            return
        }

        // ② 之前按需拉过、存在播放缓存里的
        val cached = File(session.cacheDir, seg.fileName)
        if (!isReadable(cached)) {
            val ok = synchronized(fetchLocks.computeIfAbsent(session.token + "#" + index) { Any() }) {
                if (isReadable(cached)) true
                else runCatching { fetchOnDemand(session, seg, cached) }.isSuccess
            }
            if (!ok) {
                send(out, req, 502, "Bad Gateway", "upstream segment unavailable")
                return
            }
        }
        serveFile(out, req, cached)
    }

    /** 按需回源拉取并解密一个尚未下载的分片。 */
    private fun fetchOnDemand(session: Session, seg: Segment, target: File) {
        val builder = Request.Builder().url(seg.uri)
        session.headers.forEach { (k, v) -> builder.header(k, v) }
        seg.byteRange?.let { (len, off) -> builder.header("Range", "bytes=$off-${off + len - 1}") }

        clientProvider().newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            var data = resp.body?.bytes() ?: throw IOException("响应为空")
            if (data.isEmpty()) throw IOException("分片内容为空")

            val key = seg.key
            if (key != null && key.encrypted) {
                val raw = session.keys[key.uri]
                    ?: throw IOException("缺少解密密钥，无法回源播放")
                val iv = if (!key.ivHex.isNullOrBlank()) HlsCrypto.ivFromHex(key.ivHex!!)
                else HlsCrypto.ivFromSequence(seg.mediaSequence)
                data = HlsCrypto.decrypt(data, raw, iv)
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun isReadable(f: File): Boolean = f.exists() && f.isFile && f.length() > 0

    /** 带 Range 支持的文件输出。 */
    private fun serveFile(out: OutputStream, req: Req, file: File) {
        val total = file.length()
        val range = HttpRange.parse(req.headers["range"], total)

        var start = 0L
        var end = total - 1
        when (range) {
            HttpRange.Result.Unsatisfiable -> {
                sendHead(out, 416, "Range Not Satisfiable", linkedMapOf(
                    "Content-Range" to "bytes */$total",
                    "Content-Length" to "0",
                    "Connection" to "close"
                ))
                return
            }
            HttpRange.Result.Full -> { start = 0; end = total - 1 }
            is HttpRange.Result.Part -> { start = range.start; end = range.endInclusive }
        }

        val partial = start > 0 || end < total - 1
        val len = (end - start + 1).coerceAtLeast(0)

        val headers = linkedMapOf(
            "Content-Type" to "video/mp2ts",
            "Accept-Ranges" to "bytes",
            "Content-Length" to len.toString(),
            "Cache-Control" to "no-store",
            "Connection" to "close"
        )
        if (partial) headers["Content-Range"] = HttpRange.contentRange(start, end, total)

        sendHead(out, if (partial) 206 else 200,
            if (partial) "Partial Content" else "OK", headers)
        if (req.method == "HEAD") return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(BUFFER)
            var remain = len
            while (remain > 0) {
                val n = raf.read(buf, 0, min(BUFFER.toLong(), remain).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                remain -= n
            }
        }
    }

    // ──────────────── 响应写出 ────────────────

    private fun sendHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun send(out: OutputStream, req: Req?, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendHead(out, code, reason, linkedMapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Connection" to "close"
        ))
        if (req?.method != "HEAD") out.write(bytes)
    }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}
