package brain.desktop

import java.io.RandomAccessFile
import java.nio.file.*

/** Дописываемый WAV: заголовок и данные переживают аварийный выход без загрузки всей записи в RAM. */
class WavJournal(val path: Path, val sampleRate: Int = 16000) : AutoCloseable {
    private val file: RandomAccessFile
    private var bytes: Long = 0
    init {
        require(sampleRate in setOf(16000, 44100, 48000))
        Files.createDirectories(path.parent)
        Files.createFile(path)
        file = RandomAccessFile(path.toFile(), "rw")
        writeHeader(file, sampleRate, 0)
    }
    @Synchronized fun append(data: ByteArray, count: Int) {
        require(count >= 0 && count <= data.size && count % 2 == 0)
        require(bytes + count <= MAX_BYTES) { "Достигнут лимит этой записи. Сохраните её и начните следующую." }
        file.seek(44 + bytes); file.write(data, 0, count); bytes += count
        writeHeader(file, sampleRate, bytes)
    }
    @Synchronized fun flush() { file.fd.sync() }
    @Synchronized override fun close() { writeHeader(file, sampleRate, bytes); file.fd.sync(); file.close() }
    companion object {
        const val MAX_BYTES = 60L * 1024 * 1024
        fun repair(path: Path): Long = RandomAccessFile(path.toFile(), "rw").use { file ->
            require(file.length() >= 44) { "Неполный журнал записи" }
            require(file.readInt() == 0x52494646) { "Некорректный журнал записи" }
            file.seek(24); val rate = Integer.reverseBytes(file.readInt())
            require(rate in setOf(16000, 44100, 48000)) { "Неизвестная частота записи" }
            val length = (file.length() - 44) / 2 * 2
            require(length <= MAX_BYTES) { "Запись превышает допустимый размер" }
            file.setLength(44 + length); writeHeader(file, rate, length); file.fd.sync(); length
        }
        private fun writeHeader(file: RandomAccessFile, rate: Int, bytes: Long) {
            file.seek(0); file.writeBytes("RIFF"); file.writeInt(Integer.reverseBytes((bytes + 36).toInt())); file.writeBytes("WAVEfmt ")
            file.writeInt(Integer.reverseBytes(16)); file.writeShort(0x0100); file.writeShort(0x0100)
            file.writeInt(Integer.reverseBytes(rate)); file.writeInt(Integer.reverseBytes(rate * 2))
            file.writeShort(0x0200); file.writeShort(0x1000); file.writeBytes("data"); file.writeInt(Integer.reverseBytes(bytes.toInt()))
        }
    }
}
