package net.game5048.baobao.data.network

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.game5048.baobao.BuildConfig
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.ConnectionState
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.random.Random

/**
 * TCP 长连接客户端（单例）。
 *
 * 职责边界：
 *  - 建立/维护 TCP 连接，断线指数退避自动重连
 *  - 帧的读（[Protocol] 的 4 字节长度头 + JSON 体）与写
 *  - 30 秒心跳 ping，靠 pong 测 RTT
 *  - 把收到的原始信封通过 [incoming] 抛给 Repository，**不做业务解析**
 *
 * 线程模型：所有网络操作都在 [Dispatchers.IO] 的单线程协程作用域内串行执行，
 * 写操作再用 [writeLock] 保护，避免心跳与上报并发写导致的帧交错。
 *
 * 使用方式：
 * ```
 * TcpClient.getInstance(context).start()          // 开始连接（内部自动重连）
 * TcpClient.getInstance(context).send("battery", json)  // 任意线程调用，内部切协程
 * TcpClient.getInstance(context).stop()           // 停止
 * ```
 */
class TcpClient private constructor(private val prefs: PreferencesManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 防止心跳与数据帧并发写同一 socket 造成半包交错 */
    private val writeLock = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: DataInputStream? = null

    @Volatile
    private var output: DataOutputStream? = null

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)

    /** 链路状态，UI 用来显示“已连接 / 重连中” */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * 收到的原始信封流（未解密）。
     * extraBufferCapacity 给足，防止突发消息把上游协程挂起。
     */
    private val _incoming = MutableSharedFlow<Protocol.Envelope>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming: SharedFlow<Protocol.Envelope> = _incoming.asSharedFlow()

    /** 往返延迟（毫秒），-1 表示尚未测到 */
    private val _rttMs = MutableStateFlow(-1L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    /** 服务端返回的错误（如配对码不一致），UI 用 Snackbar 提示 */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 上一次 ping 的发送时刻，用于算 RTT */
    @Volatile
    private var pingSentAt = 0L

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 启动连接循环。重复调用安全。 */
    fun start() {
        if (connectJob?.isActive == true) return
        Log.i(TAG, "启动 TCP 客户端")
        connectJob = scope.launch { connectLoop() }
    }

    /** 停止连接（会先礼貌地发一个 bye，然后关闭 socket） */
    fun stop() {
        Log.i(TAG, "停止 TCP 客户端")
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        // bye 是可选的礼貌帧，发不出去也无所谓
        scope.launch { runCatching { sendNow(Protocol.TYPE_BYE, null) } }
        closeSocket()
        _connectionState.value = ConnectionState.STOPPED
    }

    /**
     * 强制重连（服务器地址 / 端口 / 配对码变更后调用）。
     * 关闭当前 socket 会让阻塞中的读循环立刻抛异常，连接循环随即用新配置重连。
     */
    fun reconnectNow() {
        closeSocket()
        start()
    }

    /** 是否已认证。用于采集器判断“现在发消息有没有意义”。 */
    val isAuthenticated: Boolean get() = _connectionState.value == ConnectionState.AUTHENTICATED

    // ==================================================================
    // 连接循环
    // ==================================================================

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            val settings = prefs.settings.value

            if (!settings.readyToConnect) {
                // 还没填服务器地址或配对码，安静等待用户在设置页补全
                _connectionState.value = ConnectionState.IDLE
                delay(3_000)
                continue
            }

            try {
                _connectionState.value =
                    if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

                openSocket(settings.serverHost, settings.serverPort)
                Log.i(TAG, "TCP 已连接 ${settings.serverHost}:${settings.serverPort}")

                sendAuth(settings)
                startHeartbeat()

                attempt = 0
                readLoop() // 阻塞直到链路断开
            } catch (c: CancellationException) {
                throw c // 主动 stop()，不当作错误
            } catch (t: Throwable) {
                Log.w(TAG, "连接中断：${t.javaClass.simpleName} - ${t.message}")
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                closeSocket()
                if (_connectionState.value != ConnectionState.STOPPED) {
                    _connectionState.value = ConnectionState.RECONNECTING
                }
            }

            // 指数退避：2s → 4s → 8s … 最多 60s，加 0~1s 抖动避免两端同时重连撞车
            attempt++
            val backoff = min(60_000L, 2_000L * (1L shl min(attempt - 1, 5))) +
                Random.nextLong(0, 1_000)
            Log.i(TAG, "${backoff}ms 后重连")
            delay(backoff)
        }
    }

    private fun openSocket(host: String, port: Int) {
        val s = Socket()
        // 小包实时性：禁用 Nagle 算法，否则上报帧可能被攒 200ms 才发出
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), Protocol.CONNECT_TIMEOUT_MS)
        // 读超时当作“链路已死”的兜底：正常情况下每 30 秒 pong 会刷新它
        s.soTimeout = Protocol.READ_TIMEOUT_MS

        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
    }

    private fun closeSocket() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    // ==================================================================
    // 读
    // ==================================================================

    private suspend fun readLoop() {
        val ins = input ?: throw IOException("socket 未初始化")
        while (true) {
            // readInt() 读 4 字节大端序，正好对应协议的长度头
            val length = try {
                ins.readInt()
            } catch (e: EOFException) {
                throw IOException("服务端关闭了连接", e)
            }
            if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
                throw IOException("非法帧长度 $length")
            }
            val body = ByteArray(length)
            ins.readFully(body) // 保证读满，否则会拿到半包
            val json = String(body, Charsets.UTF_8)

            // 注意：这里不能写成 `?: run { continue }`。
            // Kotlin 2.0 起「在 inline lambda 里 break/continue」还是实验特性，
            // 必须加 -Xfeature 才能编译，所以老老实实用 if。
            val envelope = Protocol.parseEnvelope(json)
            if (envelope == null) {
                Log.w(TAG, "无法解析的信封：${json.take(120)}")
                continue
            }
            handleEnvelope(envelope)
        }
    }

    private suspend fun handleEnvelope(envelope: Protocol.Envelope) {
        when (envelope.type) {
            Protocol.TYPE_AUTH_OK -> {
                Log.i(TAG, "认证成功，partner=${envelope.raw.optString("partner_device_id")}")
                _connectionState.value = ConnectionState.AUTHENTICATED
            }

            Protocol.TYPE_PONG -> {
                if (pingSentAt > 0) {
                    _rttMs.value = System.currentTimeMillis() - pingSentAt
                }
            }

            Protocol.TYPE_ERROR -> {
                val message = envelope.raw.optString("message").ifBlank { "服务端返回未知错误" }
                Log.w(TAG, "服务端错误：$message")
                _errors.emit(message)
            }
        }
        // 无论什么类型都抛给上层，由 Repository 决定是否关心
        _incoming.emit(envelope)
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 发送业务消息（非阻塞，任意线程可调用）。
     * payload 会在写锁内加密，保证 IV 与密文一一对应。
     */
    fun send(type: String, payload: JSONObject? = null) {
        scope.launch { sendNow(type, payload) }
    }

    /** 立即发送并返回是否成功（供连接循环内部使用） */
    private suspend fun sendNow(
        type: String,
        payload: JSONObject?,
        plainExtra: JSONObject? = null
    ): Boolean = writeLock.withLock {
        val out = output ?: return@withLock false
        val settings = prefs.settings.value
        return@withLock try {
            val encrypted = payload?.let { body ->
                // 每次加密都用新的随机 IV；密钥按配对码派生（内部有缓存）
                CryptoUtils.encrypt(body.toString(), CryptoUtils.deriveKey(settings.pairCode))
            }
            val frame = Protocol.encodeFrame(
                Protocol.buildEnvelope(type, settings.deviceId, encrypted, plainExtra = plainExtra)
            )
            out.write(frame)
            out.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "发送 $type 失败：${t.javaClass.simpleName} - ${t.message}")
            closeSocket() // 触发上层重连
            false
        }
    }

    private suspend fun sendAuth(settings: PreferencesManager.Settings) {
        val payload = JSONObject().apply {
            put("pair_code", settings.pairCode)   // 密文里再放一份：解密成功即证明我方持有密钥
            put("device_id", settings.deviceId)
            put("app_version", BuildConfig.VERSION_NAME)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        // 配对码必须明文放在信封里：密钥由它派生，服务端拿到之前无法解密 payload。
        // 这是协议里唯一一处明文业务字段，原因见 docs/PROTOCOL.md。
        val plainExtra = JSONObject().apply {
            put("pair_code", settings.pairCode)
        }
        if (sendNow(Protocol.TYPE_AUTH, payload, plainExtra)) {
            Log.i(TAG, "已发送 auth，device_id=${settings.deviceId}")
        }
    }

    // ==================================================================
    // 心跳
    // ==================================================================

    /**
     * 每 30 秒发一个 ping。
     * 写失败说明链路已经不可用，主动关 socket 让连接循环立即重连，
     * 而不是干等 90 秒读超时。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(Protocol.HEARTBEAT_INTERVAL_MS)
                pingSentAt = System.currentTimeMillis()
                if (!sendNow(Protocol.TYPE_PING, null)) {
                    Log.w(TAG, "心跳发送失败，主动断开重连")
                    closeSocket()
                    return@launch
                }
            }
        }
    }

    companion object {
        private const val TAG = "TcpClient"

        @Volatile
        private var instance: TcpClient? = null

        fun getInstance(context: Context): TcpClient =
            instance ?: synchronized(this) {
                instance ?: TcpClient(PreferencesManager.getInstance(context)).also { instance = it }
            }
    }
}
