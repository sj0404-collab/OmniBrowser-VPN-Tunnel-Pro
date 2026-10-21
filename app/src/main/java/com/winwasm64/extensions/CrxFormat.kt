package com.winwasm64.extensions

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Работа с настоящими пакетами Chrome Web Store.
 *
 * CRX3: "Cr24" | version=3 (LE uint32) | header_size (LE uint32) | protobuf header | ZIP
 * CRX2: "Cr24" | version=2 | pubkey_len | sig_len | pubkey | signature | ZIP
 * Также принимается обычный ZIP (распакованное расширение, упакованное архиватором).
 */
object CrxFormat {
    const val MAX_UNPACKED_BYTES: Long = 200L * 1024 * 1024
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Смещение начала ZIP внутри CRX (0 — если это уже чистый ZIP). */
    fun zipOffset(data: ByteArray): Int {
        if (data.size < 4) throw IOException("Файл слишком мал (${data.size} байт)")
        if (startsWith(data, ZIP_MAGIC, 0)) return 0
        val isCrx = data[0] == 'C'.code.toByte() && data[1] == 'r'.code.toByte() &&
            data[2] == '2'.code.toByte() && data[3] == '4'.code.toByte()
        if (!isCrx) {
            val head = String(data, 0, minOf(data.size, 256), Charsets.ISO_8859_1)
            if (head.contains("<html", ignoreCase = true) || head.contains("<!doctype", ignoreCase = true)) {
                throw IOException("Сервер вернул HTML-страницу вместо пакета расширения")
            }
            throw IOException("Неизвестный формат файла: нет подписи Cr24 или PK")
        }
        if (data.size < 16) throw IOException("Повреждённый заголовок CRX")
        val version = readLeInt(data, 4)
        val offset: Long = when (version) {
            3 -> 12L + readLeUInt(data, 8)
            2 -> 16L + readLeUInt(data, 8) + readLeUInt(data, 12)
            else -> throw IOException("Неподдерживаемая версия CRX: $version")
        }
        if (offset <= 0L || offset >= data.size.toLong()) throw IOException("Повреждённый заголовок CRX$version")
        if (!startsWith(data, ZIP_MAGIC, offset.toInt())) throw IOException("После заголовка CRX$version нет ZIP-данных")
        return offset.toInt()
    }

    /** Распаковывает CRX/ZIP в каталог (с защитой от zip-slip). Возвращает число файлов. */
    fun unpack(data: ByteArray, destDir: File): Int {
        val offset = zipOffset(data)
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Не удалось создать каталог ${destDir.absolutePath}")
        val root = destDir.canonicalFile
        var count = 0
        var total = 0L
        ZipInputStream(ByteArrayInputStream(data, offset, data.size - offset)).use { zis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty()) continue
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    throw IOException("Недопустимый путь в архиве: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) throw IOException("Расширение слишком большое (больше 200 МБ)")
                        os.write(buf, 0, n)
                    }
                }
                count++
            }
        }
        if (count == 0) throw IOException("Архив расширения пуст")
        return count
    }

    /**
     * ID расширения так, как его считает Chrome: SHA-256 от публичного ключа (DER),
     * первые 16 байт -> 32 hex-символа, где 0..f заменены на a..p.
     */
    fun extensionIdFromPublicKey(der: ByteArray): String = idFromDigest(MessageDigest.getInstance("SHA-256").digest(der))

    /** Детерминированный ID для расширения без ключа (например, ZIP из файла). */
    fun extensionIdFromBytes(bytes: ByteArray): String = idFromDigest(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun idFromDigest(digest: ByteArray): String {
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            sb.append('a' + (b shr 4))
            sb.append('a' + (b and 0x0F))
        }
        return sb.toString()
    }

    private fun startsWith(data: ByteArray, magic: ByteArray, at: Int): Boolean {
        if (at < 0 || at + magic.size > data.size) return false
        for (i in magic.indices) if (data[at + i] != magic[i]) return false
        return true
    }

    private fun readLeInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun readLeUInt(b: ByteArray, at: Int): Long = readLeInt(b, at).toLong() and 0xFFFFFFFFL
}

/** Ссылки и идентификаторы Chrome Web Store. */
object ChromeWebStore {
    private val ID_REGEX = Regex("(?<![a-z0-9])([a-p]{32})(?![a-z0-9])")
    private const val PROD_VERSION = "131.0.6778.200"

    /** Достаёт 32-символьный ID расширения из ссылки магазина или из самого ID. */
    fun extractId(input: String): String? = ID_REGEX.find(input.trim().lowercase())?.groupValues?.getOrNull(1)

    /** Официальный адрес выдачи CRX с серверов обновлений Google (тот же, что использует Chrome). */
    fun crxDownloadUrl(id: String): String =
        "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=$PROD_VERSION" +
            "&acceptformat=crx2,crx3&x=id%3D$id%26installsource%3Dondemand%26uc"

    fun detailPageUrl(id: String): String = "https://chromewebstore.google.com/detail/$id"

    /** true, если это страница конкретного расширения в магазине (новый или старый адрес). */
    fun isDetailPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        val onStore = u.startsWith("https://chromewebstore.google.com/detail/") ||
            u.startsWith("https://chrome.google.com/webstore/detail/")
        return onStore && extractId(u) != null
    }
}
