/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.atomicfu.locks.*
import kotlinx.coroutines.channels.BufferOverflow.*
import kotlinx.coroutines.channels.ChannelResult.Companion.success
import kotlinx.coroutines.internal.callUndeliveredElement
import kotlinx.coroutines.internal.OnUndeliveredElement
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*

/**
 * Channel with array buffer of a fixed capacity.
 * Sender suspends only when buffer is full and receiver suspends only when buffer is empty.
 *
 * This channel is created by `Channel(capacity)` factory function invocation.
 *
 * This implementation is blocking and uses a lock to protect send and receive operations.
 * Removing a cancelled sender or receiver from a list of waiters is lock-free.
 **/
internal open class ConflatedBufferedChannel<E>(
    /**
     * Buffer capacity.
     */
    private val capacity: Int,
    private val onBufferOverflow: BufferOverflow,
    onUndeliveredElement: OnUndeliveredElement<E>? = null
) : BufferedChannel<E>(capacity = capacity, onUndeliveredElement = onUndeliveredElement) {
    private val lock = reentrantLock()

    init {
        require(onBufferOverflow !== SUSPEND) {
            "This implementation does not support suspension for senders, use ${BufferedChannel::class.simpleName} instead"
        }
        require(capacity >= 1) {
            "Buffered channel capacity must be at least 1, but $capacity was specified"
        }
    }

    override suspend fun receive(): E {
        lock.lock()
        return super.receive()
    }

    override suspend fun receiveCatching(): ChannelResult<E> {
        lock.lock()
        return super.receiveCatching()
    }

    override fun tryReceive(): ChannelResult<E> {
        lock.lock()
        return super.tryReceive()
    }

    override fun registerSelectForReceive(select: SelectInstance<*>, ignoredParam: Any?) {
        lock.lock()
        super.registerSelectForReceive(select, ignoredParam)
    }

    override fun iterator(): ChannelIterator<E> = ConflatedChannelIterator()

    internal open inner class ConflatedChannelIterator : BufferedChannelIterator() {
        override suspend fun hasNext(): Boolean {
            lock.lock()
            return super.hasNext()
        }
    }

    override fun onReceiveSynchronizationCompletion() {
        lock.unlock()
    }

    /*
     * Modification for send operations: all of them should be protected by lock
     * and never suspend; the [onBufferOverflow] strategy is used instead of suspension.
     *
     */

    override suspend fun send(element: E) {
        val attempt = trySend(element)
        if (attempt.isClosed) {
            onUndeliveredElement?.callUndeliveredElement(element, coroutineContext)
            throw sendException(attempt.exceptionOrNull())
        }
    }

    override fun trySend(element: E): ChannelResult<Unit> = lock.withLock {
        while (true) {
            if (!shouldSendSuspend()) {
                val trySendResult = super.trySend(element)
                if (trySendResult.isClosed || trySendResult.isSuccess) return trySendResult
                continue
            }
            if (onBufferOverflow === DROP_LATEST) {
                onUndeliveredElement?.invoke(element)
            } else { // DROP_OLDEST
                val tryReceiveResult = tryReceiveInternal()
                if (tryReceiveResult.isFailure) continue
                check(!shouldSendSuspend())
                super.trySend(element)
                onUndeliveredElement?.invoke(tryReceiveResult.getOrThrow())
            }
            return success(Unit)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable") // cannot remove this line due to type inference
    }

    override suspend fun sendBroadcast(element: E): Boolean {
        trySend(element)
            .onSuccess { return true }
            .onClosed { return false }
        error("unreachable")
    }

    @Suppress("UNCHECKED_CAST")
    override fun registerSelectForSend(select: SelectInstance<*>, element: Any?) {
        trySend(element as E).let {
            it.onSuccess {
                select.selectInRegistrationPhase(Unit)
                return
            }.onClosed {
                select.selectInRegistrationPhase(CHANNEL_CLOSED)
                return
            }
        }
        error("unreachable")
    }

    override fun close(cause: Throwable?) = lock.withLock {
        super.close(cause)
    }

    override fun cancelImpl(cause: Throwable?) = lock.withLock {
        super.cancelImpl(cause)
    }

    override val isClosedForSend: Boolean
        get() = lock.withLock { super.isClosedForSend }

    override val isClosedForReceive: Boolean
        get() = lock.withLock { super.isClosedForReceive }

    override val isEmpty: Boolean
        get() = lock.withLock { super.isEmpty }
}