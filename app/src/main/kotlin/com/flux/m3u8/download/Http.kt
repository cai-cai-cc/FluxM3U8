package com.flux.m3u8.download

import com.flux.m3u8.data.Settings
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp 客户端工厂。
 *
 * 单独抽出来的原因：代理、超时等设置改了之后需要重建连接池才能生效，
 * 集中在一处管理，避免到处 new 导致连接泄漏。
 */
object HttpFactory {

    @Volatile
    private var cached: OkHttpClient? = null

    /** 获取（或按当前设置重建）共享客户端。 */
    fun get(settings: Settings): OkHttpClient {
        val existing = cached
        if (existing != null) return existing
        return synchronized(this) {
            cached ?: build(settings).also { cached = it }
        }
    }

    /**
     * 设置变更后调用，下次 get() 会拿到新客户端。
     *
     * 注意：仅仅把引用置空会**泄漏旧客户端**——OkHttp 的 Dispatcher 线程池与
     * 连接池会一直存活到连接自然超时（默认 5 分钟 keep-alive），改几次代理
     * 就堆出几十个空转线程。这里必须显式关闭。
     */
    fun invalidate() {
        val old = synchronized(this) {
            val o = cached
            cached = null
            o
        }
        if (old != null) {
            runCatching { old.dispatcher.executorService.shutdown() }
            runCatching { old.connectionPool.evictAll() }
            runCatching { old.cache?.close() }
        }
    }

    /**
     * 实际并发上限：任务并发 × 同时下载任务数 × 分片分块线程数。
     * 再预留一点余量给播放列表、密钥等辅助请求。
     */
    private fun limits(settings: Settings): Int =
        (settings.connections * settings.maxRunning * settings.chunkThreads + 16)
            .coerceIn(16, 256)

    private fun poolSize(settings: Settings): Int = limits(settings)

    private fun build(settings: Settings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(poolSize(settings), 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            // ★ 关键：OkHttp 默认 maxRequestsPerHost = 5，
            //   不覆盖的话无论界面上设多少并发，同域名最多只有 5 条连接，速度直接被锁死。
            .dispatcher(Dispatcher().apply {
                maxRequests = limits(settings)
                maxRequestsPerHost = limits(settings)
            })

        // 代理（HTTP 类型，覆盖绝大多数本地代理工具）
        val host = settings.proxyHost
        if (host.isNotBlank() && settings.proxyPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, settings.proxyPort)))
        }

        // 只有在用户明确打开「跳过证书校验」时才放宽。
        // 默认走系统校验：个别源站证书链不完整会握手失败，但那属于源站问题，
        // 不该由所有用户的传输安全来买单。
        if (settings.insecureTls) {
            try {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val ssl = SSLContext.getInstance("TLS")
                ssl.init(null, trustAll, SecureRandom())
                builder.sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                // 极端情况下退回默认配置，不阻塞使用
            }
        }

        return builder.build()
    }

    /** 合成请求头：任务自定义 > 全局设置 > 自动推导。 */
    fun buildHeaders(
        url: String,
        settings: Settings,
        referer: String = "",
        userAgent: String = "",
        cookie: String = ""
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = userAgent.ifBlank { settings.userAgent }
        headers["Accept"] = "*/*"
        headers["Connection"] = "keep-alive"

        val origin = try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}" + if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
        } catch (e: Exception) { "" }

        val ref = referer.ifBlank { settings.referer }.ifBlank { origin }
        if (ref.isNotBlank()) {
            headers["Referer"] = ref
            headers["Origin"] = origin
        }
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }
}
