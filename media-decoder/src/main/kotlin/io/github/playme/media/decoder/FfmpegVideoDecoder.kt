package io.github.playme.media.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An output buffer holding one decoded FFmpeg frame, drawn by [PlayMeVideoRenderer]. */
internal class FrameBuffer(owner: DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer>) : VideoDecoderOutputBuffer(owner) {
    /** The native frame, or 0. Freed when the buffer goes back to the decoder. */
    @Volatile
    var frame: Long = 0

    fun takeFrame(): Long = frame.also { frame = 0 }
}

/**
 * Decodes video with FFmpeg on a thread of its own.
 *
 * Unlike Media3's SimpleDecoder, which pairs every input sample with one output, this hands
 * out frames as FFmpeg finishes them. Multi-threaded decoding holds several frames back, and
 * reordered codecs (B-frames) release them late, so a sample can yield no frame or several,
 * and the stream's last frames only arrive once it ends.
 */
internal class FfmpegVideoDecoder(
    format: Format,
    threads: Int,
) : Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, PlayMeDecoderException> {

    private val handle: Long = try {
        VideoNative.create(format.sampleMimeType.orEmpty(), extraDataOf(format), format.width, format.height, threads)
    } catch (error: IllegalStateException) {
        throw PlayMeDecoderException(error.message ?: "Couldn't open the FFmpeg video decoder", error)
    }
    private val name = "playmedia-${VideoNative.name(handle)}"

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val freeInputs = ArrayDeque<DecoderInputBuffer>()
    private val queuedInputs = ArrayDeque<DecoderInputBuffer>()
    private val freeOutputs = ArrayDeque<FrameBuffer>()
    private val readyOutputs = ArrayDeque<FrameBuffer>()
    private var dequeuedInput: DecoderInputBuffer? = null
    private var failure: PlayMeDecoderException? = null
    private var released = false
    private var flushPending = false

    /** Bumped by every flush: work started before it is thrown away. */
    private var generation = 0
    private var outputStartTimeUs = C.TIME_UNSET
    private var skippedFrames = 0
    private var callback: Decoder.Callback? = null
    private var callbackExecutor: Executor? = null

    private val thread = Thread(::decodeLoop, "PlayMedia:VideoDecoder")

    init {
        val inputSize = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_SIZE
        repeat(INPUT_BUFFERS) {
            freeInputs += DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT).apply { ensureSpaceForWrite(inputSize) }
        }
        repeat(OUTPUT_BUFFERS) {
            freeOutputs += FrameBuffer { buffer -> recycleOutput(buffer as FrameBuffer) }
        }
        thread.start()
    }

    override fun getName(): String = name

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        lock.withLock { this.outputStartTimeUs = outputStartTimeUs }
    }

    override fun setCallback(callback: Decoder.Callback, executor: Executor) {
        lock.withLock {
            this.callback = callback
            callbackExecutor = executor
        }
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? = lock.withLock {
        failure?.let { throw it }
        check(dequeuedInput == null) { "An input buffer is already dequeued" }
        freeInputs.removeFirstOrNull()?.also { dequeuedInput = it }
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        lock.withLock {
            failure?.let { throw it }
            require(inputBuffer === dequeuedInput) { "Not the dequeued input buffer" }
            dequeuedInput = null
            queuedInputs += inputBuffer
            changed.signalAll()
        }
    }

    override fun dequeueOutputBuffer(): VideoDecoderOutputBuffer? = lock.withLock {
        failure?.let { throw it }
        readyOutputs.removeFirstOrNull()
    }

    override fun flush() {
        lock.withLock {
            generation++
            flushPending = true
            skippedFrames = 0
            dequeuedInput?.let(::recycleInputLocked)
            dequeuedInput = null
            while (queuedInputs.isNotEmpty()) recycleInputLocked(queuedInputs.removeFirst())
            while (readyOutputs.isNotEmpty()) recycleOutputLocked(readyOutputs.removeFirst())
            changed.signalAll()
        }
    }

    override fun release() {
        lock.withLock {
            released = true
            changed.signalAll()
        }
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        lock.withLock {
            while (readyOutputs.isNotEmpty()) recycleOutputLocked(readyOutputs.removeFirst())
        }
        VideoNative.destroy(handle)
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun decodeLoop() {
        val info = LongArray(3)
        while (true) {
            val input: DecoderInputBuffer
            val work: Int
            val flushFirst: Boolean
            lock.withLock {
                while (!released && (queuedInputs.isEmpty() || failure != null)) changed.await()
                if (released) return
                input = queuedInputs.removeFirst()
                work = generation
                flushFirst = flushPending
                flushPending = false
            }
            if (flushFirst) VideoNative.flush(handle)
            val error = try {
                decode(input, work, info)
            } catch (error: RuntimeException) {
                PlayMeDecoderException("Unexpected video decoding failure", error)
            }
            lock.withLock {
                if (error != null && work == generation) failure = error
                recycleInputLocked(input)
            }
            notifyCallback { it.onInputBufferAvailable() }
            if (error != null) notifyCallback { it.onOutputBufferAvailable() }
        }
    }

    private fun decode(input: DecoderInputBuffer, work: Int, info: LongArray): PlayMeDecoderException? {
        if (input.isEndOfStream) {
            val ready = VideoNative.drain(handle)
            if (ready < 0) return PlayMeDecoderException("Video decoding failed at the end of the stream ($ready)")
            emitFrames(work, info)
            val end = awaitFreeOutput(work) ?: return null
            end.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            publish(end, work)
            return null
        }
        val data = input.data ?: return null
        val ready = VideoNative.send(handle, data, data.position(), data.remaining(), input.timeUs, input.isKeyFrame)
        if (ready < 0) return PlayMeDecoderException("Video decoding failed ($ready)")
        emitFrames(work, info)
        return null
    }

    private fun emitFrames(work: Int, info: LongArray) {
        while (true) {
            val frame = VideoNative.nextFrame(handle, info)
            if (frame == 0L) return
            val timeUs = info[0]
            val beforeStart = lock.withLock {
                (outputStartTimeUs != C.TIME_UNSET && timeUs < outputStartTimeUs).also { if (it && work == generation) skippedFrames++ }
            }
            val buffer = if (beforeStart) null else awaitFreeOutput(work)
            if (buffer == null) {
                VideoNative.freeFrame(frame)
                continue
            }
            buffer.init(timeUs, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null)
            buffer.initForPrivateFrame(info[1].toInt(), info[2].toInt())
            buffer.frame = frame
            publish(buffer, work)
        }
    }

    /** A free output buffer; null once flushed or released, when the work is moot. */
    private fun awaitFreeOutput(work: Int): FrameBuffer? = lock.withLock {
        while (freeOutputs.isEmpty() && !released && work == generation) changed.await()
        if (released || work != generation) null else freeOutputs.removeFirst()
    }

    private fun publish(buffer: FrameBuffer, work: Int) {
        lock.withLock {
            if (released || work != generation) {
                recycleOutputLocked(buffer)
                return
            }
            buffer.skippedOutputBufferCount = skippedFrames
            skippedFrames = 0
            readyOutputs += buffer
        }
        notifyCallback { it.onOutputBufferAvailable() }
    }

    private fun recycleOutput(buffer: FrameBuffer) {
        lock.withLock { recycleOutputLocked(buffer) }
    }

    private fun recycleOutputLocked(buffer: FrameBuffer) {
        val frame = buffer.takeFrame()
        if (frame != 0L) VideoNative.freeFrame(frame)
        buffer.clear()
        freeOutputs += buffer
        changed.signalAll()
    }

    private fun recycleInputLocked(buffer: DecoderInputBuffer) {
        buffer.clear()
        freeInputs += buffer
    }

    private fun notifyCallback(event: (Decoder.Callback) -> Unit) {
        val (target, executor) = lock.withLock { callback to callbackExecutor }
        if (target != null && executor != null) executor.execute { event(target) }
    }

    private companion object {
        const val INPUT_BUFFERS = 8
        const val OUTPUT_BUFFERS = 8
        const val DEFAULT_INPUT_SIZE = 768 * 1024

        /** Codec configuration (SPS/PPS and the like): Media3 keeps it split, FFmpeg wants it whole. */
        fun extraDataOf(format: Format): ByteArray? {
            val parts = format.initializationData
            if (parts.isEmpty()) return null
            val whole = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(whole, offset)
                offset += part.size
            }
            return whole
        }
    }
}
