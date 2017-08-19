package de.komoinen

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLConnection
import java.nio.ByteBuffer


fun <T: Closeable, R> tryWith(closable: T, block:(closeable:T)->R):R {
    try {
        return block(closable)
    } catch (ex : Exception) {
        throw ex
    } finally {
        closable.close()
    }
}

inline fun <reified T> T.asArray(): Array<T> = arrayOf(this)

fun Int.toHexStr() = "0x"+this.toString(16)

fun <T> JsonObject.anyList(fieldName: String): List<T>? {
    val field = this[fieldName] ?: return null
    if(field is JsonArray<*>) {
        return field.value as List<T>
    }
    return listOf(field) as List<T>
}

fun URL.getAsByteArray(defBufferSize: Int = 16384, defBucketSize: Int = 4096): ByteArray {
    val con = this.openConnection()
    val input = con.getInputStream()
    val length = con.contentLength

    val output = if(length != -1) {
        ByteArrayOutputStream(length)
    } else {
        ByteArrayOutputStream(defBufferSize)
    }

    val buf = ByteArray(defBucketSize)
    while (true) {
        val len = input.read(buf)
        if (len == -1) {
            break
        }
        output.write(buf, 0, len)
    }

    input.close()
    output.close()

    return output.toByteArray()
}

fun URL.getAsByteBuffer(): ByteBuffer {
    return ByteBuffer.wrap(this.getAsByteArray())
}