package audiolens.pycharm.audio

import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PeakLevel(val blockSize: Int, val min: List<FloatArray>, val max: List<FloatArray>)

class StreamedAudioCache private constructor(
    val directory: Path,
    val wavePath: Path,
    val dataOffset: Long,
    val sampleRate: Int,
    val numberOfChannels: Int,
    val length: Long,
    val channelPeaks: List<Double>,
    val channelRms: List<Double>,
    val peakLevels: List<PeakLevel>,
) : Closeable {
    val duration: Double = length.toDouble() / sampleRate

    override fun close() {
        directory.toFile().deleteRecursively()
    }

    fun readWaveformPeaks(channel: Int, startSample: Long, endSample: Long, width: Int): Pair<FloatArray, FloatArray> {
        val safeChannel = channel.coerceIn(0, numberOfChannels - 1)
        val safeStart = startSample.coerceIn(0, length)
        val safeEnd = endSample.coerceIn(safeStart, length)
        val safeWidth = width.coerceIn(1, 8192)
        val samplesPerPixel = max(1.0, (safeEnd - safeStart).toDouble() / safeWidth)
        var level = peakLevels.first()
        for (candidate in peakLevels) {
            if (candidate.blockSize > samplesPerPixel) break
            level = candidate
        }
        if (samplesPerPixel < level.blockSize) {
            return readFinePeaks(safeChannel, safeStart, safeEnd, safeWidth)
        }
        val sourceMin = level.min[safeChannel]
        val sourceMax = level.max[safeChannel]
        val resultMin = FloatArray(safeWidth)
        val resultMax = FloatArray(safeWidth)
        for (pixel in 0 until safeWidth) {
            val pixelStart = safeStart + ((safeEnd - safeStart) * pixel.toDouble()) / safeWidth
            val pixelEnd = safeStart + ((safeEnd - safeStart) * (pixel + 1).toDouble()) / safeWidth
            val firstBin = max(0, floor(pixelStart / level.blockSize).toInt())
            val lastBin = min(sourceMin.lastIndex, max(firstBin, ceil(pixelEnd / level.blockSize).toInt() - 1))
            var low = 1f
            var high = -1f
            for (bin in firstBin..lastBin) {
                low = min(low, sourceMin.getOrElse(bin) { 0f })
                high = max(high, sourceMax.getOrElse(bin) { 0f })
            }
            resultMin[pixel] = if (low <= high) low else 0f
            resultMax[pixel] = if (low <= high) high else 0f
        }
        return resultMin to resultMax
    }

    fun readChannelSamples(channel: Int, startSample: Long, endSample: Long, maxOutputBytes: Int): FloatArray {
        val safeChannel = channel.coerceIn(0, numberOfChannels - 1)
        val safeStart = startSample.coerceIn(0, length)
        val safeEnd = endSample.coerceIn(safeStart, length)
        val count = safeEnd - safeStart
        require(count <= maxOutputBytes / Float.SIZE_BYTES) { "Requested PCM range is too large." }
        require(count <= Int.MAX_VALUE) { "Requested PCM range is too large." }
        val result = FloatArray(count.toInt())
        if (result.isEmpty()) return result
        val bytesPerFrame = numberOfChannels * 2
        val input = ByteBuffer.allocate(result.size * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(wavePath, StandardOpenOption.READ).use { channelFile ->
            readFullyOrZero(channelFile, input, dataOffset + safeStart * bytesPerFrame)
        }
        input.flip()
        for (frame in result.indices) {
            result[frame] = input.getShort((frame * numberOfChannels + safeChannel) * 2) / 32768f
        }
        return result
    }

    data class PackedWindows(val samples: FloatArray, val frameCount: Int, val windowSize: Int)

    fun readPackedWindows(
        channel: Int,
        startSample: Long,
        endSample: Long,
        windowSize: Int,
        hopSize: Int,
        maxFrames: Int,
        maxOutputBytes: Int,
    ): PackedWindows {
        val safeChannel = channel.coerceIn(0, numberOfChannels - 1)
        val start = startSample.coerceIn(0, length)
        val end = endSample.coerceIn(start, length)
        val window = windowSize.coerceIn(8, 32768)
        val hop = hopSize.coerceIn(1, max(1L, length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val available = max(0, end - start)
        val naturalFrames = if (available <= window) 1 else ((available - window) / hop + 1).toInt()
        val frames = min(maxFrames.coerceIn(1, 8192), max(1, naturalFrames))
        val outputBytes = frames.toLong() * window * Float.SIZE_BYTES
        require(outputBytes <= maxOutputBytes) { "Requested FFT windows are too large." }
        val samples = FloatArray(frames * window)
        FileChannel.open(wavePath, StandardOpenOption.READ).use { file ->
            for (frame in 0 until frames) {
                val frameStart = start + frame.toLong() * hop
                val readable = min(window.toLong(), max(0, length - frameStart)).toInt()
                if (readable == 0) continue
                val bytes = ByteBuffer.allocate(readable * numberOfChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
                readFullyOrZero(file, bytes, dataOffset + frameStart * numberOfChannels * 2L)
                bytes.flip()
                for (sample in 0 until readable) {
                    samples[frame * window + sample] = bytes.getShort((sample * numberOfChannels + safeChannel) * 2) / 32768f
                }
            }
        }
        return PackedWindows(samples, frames, window)
    }

    private fun readFinePeaks(channel: Int, start: Long, end: Long, width: Int): Pair<FloatArray, FloatArray> {
        val resultMin = FloatArray(width)
        val resultMax = FloatArray(width)
        val count = end - start
        if (count <= 0) return resultMin to resultMax
        FileChannel.open(wavePath, StandardOpenOption.READ).use { file ->
            for (pixel in 0 until width) {
                val from = min(end - 1, start + floor(pixel.toDouble() * count / width).toLong())
                val to = min(end, max(from + 1, start + ceil((pixel + 1).toDouble() * count / width).toLong()))
                val samples = readChannelSamplesFrom(file, channel, from, to)
                resultMin[pixel] = samples.minOrNull() ?: 0f
                resultMax[pixel] = samples.maxOrNull() ?: 0f
            }
        }
        return resultMin to resultMax
    }

    private fun readChannelSamplesFrom(file: FileChannel, channel: Int, start: Long, end: Long): FloatArray {
        val count = (end - start).toInt()
        val input = ByteBuffer.allocate(count * numberOfChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
        readFullyOrZero(file, input, dataOffset + start * numberOfChannels * 2L)
        input.flip()
        return FloatArray(count) { frame -> input.getShort((frame * numberOfChannels + channel) * 2) / 32768f }
    }

    companion object {
        private const val BASE_BLOCK_SIZE = 512
        private const val READ_CHUNK_BYTES = 4 * 1024 * 1024

        fun create(input: Path, maxCacheBytes: Long): StreamedAudioCache {
            val directory = Files.createTempDirectory("audiolens-cache-")
            val output = directory.resolve("decoded.wav")
            try {
                FfmpegService.transcodeToPcm16Wave(input, output, maxCacheBytes)
                return buildIndex(directory, output)
            } catch (error: Throwable) {
                directory.toFile().deleteRecursively()
                throw error
            }
        }

        private fun buildIndex(directory: Path, wavePath: Path): StreamedAudioCache {
            FileChannel.open(wavePath, StandardOpenOption.READ).use { file ->
                val format = parsePcm16Wave(file)
                val bytesPerFrame = format.channels * 2
                val length = format.dataSize / bytesPerFrame
                require(length <= Int.MAX_VALUE.toLong() * BASE_BLOCK_SIZE) { "The decoded audio is too long to index safely." }
                val bins = ceil(length.toDouble() / BASE_BLOCK_SIZE).toInt().coerceAtLeast(1)
                val baseMin = List(format.channels) { FloatArray(bins) { 1f } }
                val baseMax = List(format.channels) { FloatArray(bins) { -1f } }
                val peaks = DoubleArray(format.channels)
                val sums = DoubleArray(format.channels)
                val framesPerChunk = max(1, READ_CHUNK_BYTES / bytesPerFrame)
                var frameOffset = 0L
                while (frameOffset < length) {
                    val frames = min(framesPerChunk.toLong(), length - frameOffset).toInt()
                    val input = ByteBuffer.allocate(frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
                    readFullyOrZero(file, input, format.dataOffset + frameOffset * bytesPerFrame)
                    input.flip()
                    for (frame in 0 until frames) {
                        val bin = ((frameOffset + frame) / BASE_BLOCK_SIZE).toInt()
                        for (channel in 0 until format.channels) {
                            val sample = input.short / 32768f
                            if (sample < baseMin[channel][bin]) baseMin[channel][bin] = sample
                            if (sample > baseMax[channel][bin]) baseMax[channel][bin] = sample
                            peaks[channel] = max(peaks[channel], abs(sample.toDouble()))
                            sums[channel] += sample * sample
                        }
                    }
                    frameOffset += frames
                }
                val levels = mutableListOf(PeakLevel(BASE_BLOCK_SIZE, baseMin, baseMax))
                while (levels.last().min.first().size > 1) {
                    val previous = levels.last()
                    val nextBins = ceil(previous.min.first().size / 2.0).toInt()
                    val nextMin = List(format.channels) { FloatArray(nextBins) }
                    val nextMax = List(format.channels) { FloatArray(nextBins) }
                    for (channel in 0 until format.channels) {
                        for (bin in 0 until nextBins) {
                            val left = bin * 2
                            val right = min(left + 1, previous.min[channel].lastIndex)
                            nextMin[channel][bin] = min(previous.min[channel][left], previous.min[channel][right])
                            nextMax[channel][bin] = max(previous.max[channel][left], previous.max[channel][right])
                        }
                    }
                    levels += PeakLevel(previous.blockSize * 2, nextMin, nextMax)
                }
                return StreamedAudioCache(
                    directory,
                    wavePath,
                    format.dataOffset,
                    format.sampleRate,
                    format.channels,
                    length,
                    peaks.toList(),
                    sums.map { sqrt(it / max(1L, length)) },
                    levels,
                )
            }
        }

        private data class WaveFormat(val dataOffset: Long, val dataSize: Long, val sampleRate: Int, val channels: Int)

        private fun parsePcm16Wave(file: FileChannel): WaveFormat {
            val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            readFully(file, head, 0)
            val bytes = head.array()
            require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "FFmpeg did not produce a supported RIFF/WAVE cache."
            }
            var offset = 12L
            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            while (offset + 8 <= file.size() && offset < 16L * 1024 * 1024) {
                val chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                readFully(file, chunk, offset)
                val id = String(chunk.array(), 0, 4, Charsets.US_ASCII)
                val size = Integer.toUnsignedLong(chunk.getInt(4))
                val payload = offset + 8
                if (id == "fmt " && size >= 16) {
                    val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    readFully(file, format, payload)
                    audioFormat = format.getShort(0).toInt() and 0xffff
                    channels = format.getShort(2).toInt() and 0xffff
                    sampleRate = format.getInt(4)
                    bitsPerSample = format.getShort(14).toInt() and 0xffff
                } else if (id == "data") {
                    require(audioFormat == 1 && bitsPerSample == 16 && channels > 0 && sampleRate > 0) {
                        "FFmpeg PCM cache must be 16-bit little-endian PCM WAV."
                    }
                    return WaveFormat(payload, min(size, file.size() - payload), sampleRate, channels)
                }
                offset = payload + size + (size and 1)
            }
            throw IllegalArgumentException("Cannot find the PCM data chunk in the FFmpeg WAV cache.")
        }

        private fun readFully(file: FileChannel, buffer: ByteBuffer, position: Long) {
            var cursor = position
            while (buffer.hasRemaining()) {
                val count = file.read(buffer, cursor)
                if (count < 0) throw EOFException("Unexpected end of WAV file.")
                cursor += count
            }
        }

        private fun readFullyOrZero(file: FileChannel, buffer: ByteBuffer, position: Long) {
            var cursor = position
            while (buffer.hasRemaining()) {
                val count = file.read(buffer, cursor)
                if (count <= 0) break
                cursor += count
            }
            while (buffer.hasRemaining()) buffer.put(0)
        }
    }
}
