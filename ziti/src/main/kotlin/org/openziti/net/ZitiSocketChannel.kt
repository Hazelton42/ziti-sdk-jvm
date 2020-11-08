/*
 * Copyright (c) 2018-2020 NetFoundry, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openziti.net

import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.SessionPair
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.openziti.ZitiAddress
import org.openziti.ZitiConnection
import org.openziti.api.SessionType
import org.openziti.crypto.Crypto
import org.openziti.impl.ZitiContextImpl
import org.openziti.net.nio.FutureHandler
import org.openziti.net.nio.readSuspend
import org.openziti.net.nio.writeCompletely
import org.openziti.util.Logged
import org.openziti.util.ZitiLog
import org.openziti.util.transfer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketOption
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.CompletionHandler
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.channels.Channel as Chan

internal class ZitiSocketChannel(internal val ctx: ZitiContextImpl):
    AsynchronousSocketChannel(Provider),
    Channel.MessageReceiver,
    ZitiConnection,
    Logged by ZitiLog() {

    object Provider: AsynchronousChannelProvider() {
        override fun openAsynchronousSocketChannel(group: AsynchronousChannelGroup?): AsynchronousSocketChannel =
            TODO()

        override fun openAsynchronousServerSocketChannel(group: AsynchronousChannelGroup?): AsynchronousServerSocketChannel {
            TODO("Not yet implemented")
        }

        override fun openAsynchronousChannelGroup(nThreads: Int, threadFactory: ThreadFactory?): AsynchronousChannelGroup =
            TODO("Not yet implemented")

        override fun openAsynchronousChannelGroup(executor: ExecutorService?, initialSize: Int): AsynchronousChannelGroup =
            TODO("Not yet implemented")

    }

    internal enum class State {
        initial,
        connecting,
        connected,
        closed
    }
    val sentFin = AtomicBoolean(false)

    override var timeout: Long = 0

    val state = AtomicReference(State.initial)
    var connId: Int = 0
    lateinit var channel: Channel
    val seq = AtomicInteger(1)
    lateinit var serviceName: String
    var remote: SocketAddress? = null
    var local: ZitiAddress? = null
    val receiveQueue = Chan<ByteArray>(16)
    val receiveBuff: ByteBuffer = ByteBuffer.allocate(32 * 1024).apply { flip() }
    val crypto = CompletableDeferred<Crypto.SecretStream?>()

    override fun getLocalAddress(): SocketAddress? = local

    override fun getRemoteAddress(): SocketAddress? = remote

    override fun supportedOptions(): MutableSet<SocketOption<*>> = mutableSetOf()
    override fun <T : Any> getOption(name: SocketOption<T>?): T? = null
    override fun <T : Any?> setOption(name: SocketOption<T>?, value: T): AsynchronousSocketChannel = this

    override fun isClosed() = !isOpen

    override fun <A : Any?> connect(remote: SocketAddress?, attachment: A, handler: CompletionHandler<Void, in A>) {

        when (remote) {
            is InetSocketAddress -> {
                val s = ctx.getService(remote.hostName, remote.port)
                serviceName = s.name
                this.remote = remote
            }
            is ZitiAddress.Service -> {
                this.remote = remote
                this.serviceName = remote.name
            }
            else -> throw UnsupportedAddressTypeException()
        }

        state.getAndUpdate { st ->
            when(st) {
                State.initial -> {}
                State.connecting -> throw ConnectionPendingException()
                State.connected -> throw AlreadyConnectedException()
                State.closed -> throw ClosedChannelException()
                null -> error("not possible")
            }

            State.connecting
        }

        ctx.async {
            val kp = Crypto.newKeyPair()

            val ns = ctx.getNetworkSession(serviceName, SessionType.Dial)
            channel = ctx.getChannel(ns)
            channel.onClose {
                state.set(State.closed)
                receiveQueue.cancel()
            }
            connId = channel.registerReceiver(this@ZitiSocketChannel)

            val connectMsg = Message(ZitiProtocol.ContentType.Connect, ns.token.toByteArray(UTF_8))
                .setHeader(ZitiProtocol.Header.ConnId, connId)
                .setHeader(ZitiProtocol.Header.SeqHeader, 0)
                .setHeader(ZitiProtocol.Header.PublicKeyHeader, kp.publicKey.asBytes)

            d("starting network connection ${ns.id}/$connId")
            val reply = channel.SendAndWait(connectMsg)
            when (reply.content) {
                ZitiProtocol.ContentType.StateConnected -> {
                    val peerPk = reply.getHeader(ZitiProtocol.Header.PublicKeyHeader)
                    if (peerPk == null) {
                        d{"did not receive peer key, connection[$connId] will not be encrypted"}
                        crypto.complete(null)
                    } else {
                        setupCrypto(Crypto.kx(kp, Key.fromBytes(peerPk), false))
                        startCrypto()
                    }
                    local = ZitiAddress.Session(ns.id, connId, serviceName)
                    d("network connection established ${ns.id}/$connId")
                    state.set(State.connected)
                }
                ZitiProtocol.ContentType.StateClosed -> {
                    state.set(State.closed)
                    val err = reply.body.toString(UTF_8)
                    w("connection rejected: ${err}")
                    throw ConnectException(err)
                }
                else -> {
                    state.set(State.closed)
                    throw IOException("Invalid response type")
                }
            }
        }.invokeOnCompletion { exc ->
            if (exc != null) {
                handler.failed(exc, attachment)
                channel.deregisterReceiver(connId)
            }
            else handler.completed(null, attachment)
        }
    }

    override fun connect(remote: SocketAddress?): Future<Void> {
        val result = CompletableFuture<Void>()
        connect(remote, result, FutureHandler())
        return result
    }

    override fun isOpen(): Boolean = state.get() != State.closed

    override fun bind(local: SocketAddress?): AsynchronousSocketChannel = this // NOOP

    override fun shutdownInput(): AsynchronousSocketChannel {
        when(state.get()) {
            State.initial, State.connecting -> throw NotYetConnectedException()
            State.connected -> {}
            State.closed -> return this
            else -> return this
        }
        channel.deregisterReceiver(connId)
        receiveQueue.close()
        return this
    }

    override fun close() {
        shutdownOutput()
        shutdownInput()
        closeInternal()
    }

    override fun shutdownOutput(): AsynchronousSocketChannel {
        if (sentFin.compareAndSet(false, true)) {
            val finMsg = Message(ZitiProtocol.ContentType.Data).apply {
                setHeader(ZitiProtocol.Header.ConnId, connId)
                setHeader(ZitiProtocol.Header.FlagsHeader, ZitiProtocol.EdgeFlags.FIN)
                setHeader(ZitiProtocol.Header.SeqHeader, seq.getAndIncrement())

            }
            d{"sending FIN conn = ${this.connId}"}
            runBlocking { channel.SendSynch(finMsg) }
        }

        return this
    }

    internal fun closeInternal(): AsynchronousSocketChannel {
        synchronized(state) {
            when (state.get()) {
                State.initial ->
                    state.set(State.closed)
                State.connecting, State.connected -> {
                    val closeMsg = Message(ZitiProtocol.ContentType.StateClosed).apply {
                        setHeader(ZitiProtocol.Header.ConnId, connId)
                    }
                    d("closing conn = ${this.connId}")
                    runBlocking { channel.SendSynch(closeMsg) }
                    state.set(State.closed)
                }
                State.closed -> {}
                else -> {}
            }
        }
        return this
    }

    override
    fun <A : Any?> read(dst: ByteBuffer, timeout: Long, unit: TimeUnit,
        att: A, handler: CompletionHandler<Int, in A>
    ) {
        read(arrayOf(dst), 0, 1, timeout, unit, att, object : CompletionHandler<Long, A>{
            override fun completed(result: Long, a: A) = handler.completed(result.toInt(), a)
            override fun failed(exc: Throwable?, a: A) = handler.failed(exc, a)
        })
    }

    override fun read(dst: ByteBuffer?): Future<Int> {
        val result = CompletableFuture<Int>()
        read(dst, result, FutureHandler())
        return result
    }

    override fun <A : Any?> read(dsts: Array<out ByteBuffer>, offset: Int, length: Int,
        to: Long, unit: TimeUnit,
        att: A, handler: CompletionHandler<Long, in A>) {
        t{"reading state=$state"}
        val slice = dsts.sliceArray(offset until offset + length)
        val copied = receiveBuff.transfer(slice)
        if (copied > 0) {
            t{"reading completed[$copied]"}
            handler.completed(copied, att)
            return
        }

        ctx.launch {
            var count = 0L
            t { "waiting for data with $receiveBuff" }

            try {
                var data: ByteArray? = receiveQueue.receive()
                do {
                    val dataBuf = ByteBuffer.wrap(data)
                    count += dataBuf.transfer(slice)
                    if (dataBuf.hasRemaining()) {
                        t { "saving ${dataBuf.remaining()} for later" }
                        receiveBuff.compact()
                        receiveBuff.put(dataBuf)
                        receiveBuff.flip()
                        break
                    }
                    data = receiveQueue.poll()
                } while (data != null)

                t { "transferred $count" }
                handler.completed(count, att)

            } catch (e1: Exception) {
                if (count > 0) handler.completed(count, att)
                else if (e1 is ClosedReceiveChannelException) handler.completed(-1, att)
                else handler.failed(e1, att)
            }
        }
    }

    override
    fun <A : Any?> write(src: ByteBuffer, to: Long, unit: TimeUnit?, att: A, handler: CompletionHandler<Int, in A>) {
        write(arrayOf(src), 0, 1, to, unit, att, object : CompletionHandler<Long, A>{
            override fun completed(result: Long, a: A): Unit = handler.completed(result.toInt(), a)
            override fun failed(exc: Throwable, a: A) = handler.failed(exc, a)
        })
    }

    override fun write(src: ByteBuffer): Future<Int> {
        val result = CompletableFuture<Int>()
        write(src, result, FutureHandler())
        return result
    }

    override fun <A : Any?> write(
        _srcs: Array<out ByteBuffer>, offset: Int, length: Int,
        timeout: Long, unit: TimeUnit?,
        att: A, handler: CompletionHandler<Long, in A>
    ) {
        when (state.get()) {
            State.initial,
            State.connecting -> throw NotYetConnectedException()
            State.connected -> {}
            State.closed -> throw ClosedChannelException()
        }

        val srcs = _srcs.slice(offset until offset + length)

        ctx.async {
            var sent = 0L
            for (b in srcs) {
                var data = ByteArray(b.remaining())
                b.get(data)

                crypto.await()?.let {
                    data = it.encrypt(data)
                }

                val dataMessage = Message(ZitiProtocol.ContentType.Data, data)
                dataMessage.setHeader(ZitiProtocol.Header.ConnId, connId)
                dataMessage.setHeader(ZitiProtocol.Header.SeqHeader, seq.getAndIncrement())
                sent += data.size
                v("sending $dataMessage")
                channel.Send(dataMessage)
            }
            handler.completed(sent, att)
        }.invokeOnCompletion { ex ->
            if (ex is TimeoutCancellationException) {
                handler.failed(InterruptedByTimeoutException(), att)
            } else if (ex != null) {
                handler.failed(ex, att)
            }
        }
    }

    override suspend fun receive(msg: Message) {
        v{"conn[$connId] received message[${msg.content}] with seq[${msg.getIntHeader(ZitiProtocol.Header.SeqHeader)}]"}
        when (msg.content) {
            ZitiProtocol.ContentType.StateClosed -> {
                state.set(State.closed)
                t{"signaling EOF"}
                receiveQueue.close()
                channel.deregisterReceiver(connId)
            }
            ZitiProtocol.ContentType.Data -> {
                t{"received data(${msg.body.size} bytes) for conn[$connId]"}
                if (msg.body.size > 0) {
                    val crypt = crypto.await()
                    if (crypt != null) {
                        if (crypt.initialized()) {
                            receiveQueue.send(crypt.decrypt(msg.body))
                        } else {
                            crypt.init(msg.body)
                            d { "crypto init finished conn[$connId]" }
                        }
                    } else {
                        receiveQueue.send(msg.body)
                    }
                }
                msg.getIntHeader(ZitiProtocol.Header.FlagsHeader)?.let {
                    if (it and ZitiProtocol.EdgeFlags.FIN != 0 ) {
                        d("received FIN")
                        receiveQueue.close()
                    }
                }
            }
            else -> {
                e{"unexpected message type[${msg.content}] for conn[$connId]"}
                state.set(State.closed)
                receiveQueue.close(IllegalStateException())
                channel.deregisterReceiver(connId)
            }
        }
    }

    override suspend fun send(data: ByteArray) = send(data, 0, data.size)

    suspend fun send(data: ByteArray, offset: Int, len: Int) {
        writeCompletely(ByteBuffer.wrap(data, offset, len))
    }

    override suspend fun receive(out: ByteArray, off: Int, len: Int): Int {
        val dst = ByteBuffer.wrap(out, off, len)
        return try {
            readSuspend(dst, timeout, TimeUnit.MILLISECONDS)
        } catch (ex: TimeoutCancellationException) {
            0
        }
    }

    override fun getInputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                val b = byteArrayOf(-1)
                if (read(b, 0, 1) == -1) {
                    return -1
                }

                return (b[0].toInt() and 0xff)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = runBlocking {
                receive(b, off, len)
            }
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(b: ByteArray, off: Int, len: Int) = runBlocking {
            send(b, off, len)
        }
    }

    internal fun setupCrypto(keys: SessionPair?) {
        crypto.complete(keys?.let { Crypto.newStream(it) })
    }

    internal suspend fun startCrypto() {
        crypto.await()?.let {
            val header = it.header()
            val headerMessage = Message(ZitiProtocol.ContentType.Data, header)
                .setHeader(ZitiProtocol.Header.ConnId, connId)
                .setHeader(ZitiProtocol.Header.SeqHeader, seq.getAndIncrement())
            channel.Send(headerMessage)
        }
    }

    override fun toString(): String {
        val s = StringBuilder(this::class.java.simpleName)
        s.append("[$state]")
        when(state.get()) {
            State.connecting -> s.append("(remote=$remote)")
            State.connected -> s.append("($local -> $remote)")
            else -> {}
        }
        return s.toString()
    }
}