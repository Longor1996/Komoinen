package de.komoinen

import com.beust.klaxon.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal typealias CharStream = java.io.Reader
internal typealias ByteStream = java.io.InputStream
internal typealias Exec = java.util.concurrent.ExecutorService

// marker interface
interface AssetSource {
    fun size(assetPath: AssetPath, suppressedExceptions: Exception): Int?
    fun exists(assetPath: AssetPath, suppressedExceptions: Exception): Boolean
    fun asBytes(assetPath: AssetPath, suppressedExceptions: Exception): ByteBuffer?
    fun asString(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): String?
    fun asByteStream(assetPath: AssetPath, suppressedExceptions: Exception): ByteStream?
    fun asCharStream(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): CharStream?
}

@Suppress("UNUSED")
class AssetPath {
    val namespace: String
    val path: String

    @Suppress("UNUSED")
    constructor(namespace: String, path: String) {
        this.namespace = namespace
        this.path = path
    }

    @Suppress("UNUSED")
    constructor(assetPath: String) {
        if(assetPath.contains(':')) {
            this.namespace = assetPath.substringBefore(':')
            this.path = assetPath.substringAfter(':')
        } else {
            this.namespace = "default"
            this.path = assetPath
        }
    }

    @Suppress("UNUSED")
    constructor(assetPath: AssetPath) {
        this.namespace = assetPath.namespace
        this.path = assetPath.path
    }

    fun asMeta(): AssetPath {
        return if(path.endsWith(".meta")) {
            this
        } else {
            AssetPath(namespace, path.plus(".meta"))
        }
    }
}

interface AssetAccess {

    /**
     * Creates the default Exception for the collection of suppressed errors thrown by the various assetXXX-functions.
     **/
    fun newDefaultErr(msg: String): Exception

    /**
     * Returns the default [ExecutorService] to use for the async assetXXX-functions.
     **/
    fun newDefaultExecutor(): ExecutorService

    @Throws(Exception::class)
    fun assetFind      (terms: String, exceptions: Exception = newDefaultErr("Error while searching assets."), block:(AssetPath)->Boolean)

    @Throws(Exception::class)
    fun assetSize      (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while getting asset size.")): Int

    @Throws(Exception::class)
    fun assetCheck     (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while checking if asset exists.")): Boolean

    @Throws(Exception::class)
    fun assetSource    (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while getting asset source")): String

    @Suppress("UNUSED")
    @Throws(Exception::class)
    fun assetHash      (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while getting asset hash."), algorithm: String = "MD5"): ByteArray {
        return assetStream_AsBytes(asset) {
            val digest: MessageDigest = MessageDigest.getInstance(algorithm)
            val bucket = ByteArray(4096)
            pipeLoop@while(true) {
                val count: Int = it.read(bucket)
                if(count == -1) break@pipeLoop
                if(count == 0) continue@pipeLoop
                digest.update(bucket, 0, count)
            } // digest update loop
            digest.digest()
        } // asset stream as bytes
    }

    @Throws(Exception::class)
    fun assetMetaCheck (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while checking if asset metadata exists.")): Boolean

    @Throws(Exception::class)
    fun assetMetaLoad  (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while loading asset metadata.")): JsonObject

    @Throws(Exception::class)
    fun assetLoad_AsBytes              (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while loading asset as ByteBuffer.")): ByteBuffer

    @Throws(Exception::class)
    fun <RET> assetStream_AsBytes      (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while streaming asset as bytes."), block: (ByteStream)->RET): RET

    @Throws(Exception::class)
    fun <RET> assetStream_AsChars      (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while streaming asset as chars."), encoding: Charset = Charsets.UTF_8, block: (CharStream)->RET): RET

    @Throws(Exception::class)
    fun assetLoad_AsChars              (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while loading asset as string."), encoding: Charset = Charsets.UTF_8): String

    @Throws(Exception::class)
    fun assetLoadAsync_AsBytes         (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while async-loading asset as ByteBuffer."), exec: Exec = newDefaultExecutor()): Future<ByteBuffer>

    @Throws(Exception::class)
    fun <RET> assetStreamAsync_AsBytes (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while async-streaming asset as bytes."), exec: Exec = newDefaultExecutor(), block: (ByteStream)->RET): Future<RET>

    @Throws(Exception::class)
    fun <RET> assetStreamAsync_AsChars (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while async-streaming asset as chars."), encoding: Charset = Charsets.UTF_8, exec: Exec = newDefaultExecutor(), block: (CharStream)->RET): Future<RET>

    @Throws(Exception::class)
    fun assetLoadAsync_AsChars         (asset: AssetPath, exceptions: Exception = newDefaultErr("Error while async-loading asset as string."), encoding: Charset = Charsets.UTF_8, exec: Exec = newDefaultExecutor()): Future<String>

}

class AssetManager: AssetAccess {
    internal lateinit var komoinen: Komoinen
    internal lateinit var modManager: ModuleManager

    override fun newDefaultErr(msg: String): Exception = komoinen.KomoinenAssetException(msg)
    override fun newDefaultExecutor(): ExecutorService = komoinen.executor

    /**
     * This makes the async-asset functions interruptible.
     **/
    private fun checkInterrupt(exceptions: Exception) {
        if(Thread.interrupted()) {
            exceptions.initCause(InterruptedException("Thread was interrupted."))
            throw exceptions
        }
    }

    override fun assetFind(terms: String, exceptions: Exception, block: (AssetPath) -> Boolean) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun assetSize(asset: AssetPath, exceptions: Exception): Int {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            return it.size(asset, exceptions) ?: return@forEach
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetSize(asset, exceptions)
        }
        throw exceptions
    }

    override fun assetCheck(asset: AssetPath, exceptions: Exception): Boolean {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            if(it.exists(asset, exceptions)) return true
        }
        if(komoinen.parent != null) {
            if(komoinen.parent!!.assetManager.assetCheck(asset, exceptions)) {
                return true
            }
        }
        if(exceptions.suppressed.isNotEmpty())
            throw exceptions
        return false
    }

    override fun assetSource(asset: AssetPath, exceptions: Exception): String {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            if(it.exists(asset, exceptions)) return it.toString()
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetSource(asset, exceptions)
        }
        throw exceptions
    }

    override fun assetMetaCheck(asset: AssetPath, exceptions: Exception): Boolean {
        return assetCheck(asset.asMeta(), exceptions)
    }

    override fun assetMetaLoad(asset: AssetPath, exceptions: Exception): JsonObject {
        return assetStream_AsChars(asset, exceptions) {
            val thing = com.beust.klaxon.Parser().parse(it)
            thing as? JsonObject ?: throw komoinen.KomoinenAssetException("Metadata file for: $asset, is not a JSON file with a object as root.")
        }
    }

    override fun assetLoad_AsChars(asset: AssetPath, exceptions: Exception, encoding: Charset): String {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            val input = it.asString(asset, exceptions, encoding)
            if(input != null) {
                return input
            }
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetLoad_AsChars(asset, exceptions)
        }
        throw exceptions
    }

    override fun assetLoad_AsBytes(asset: AssetPath, exceptions: Exception): ByteBuffer {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            val input = it.asBytes(asset, exceptions)
            if(input != null) {
                return input
            }
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetLoad_AsBytes(asset, exceptions)
        }
        throw exceptions
    }

    override fun <RET> assetStream_AsBytes(asset: AssetPath, exceptions: Exception, block: (ByteStream) -> RET): RET {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            val input = it.asByteStream(asset, exceptions)
            if(input != null) {
                return tryWith(input) { block(input) }
            }
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetStream_AsBytes(asset, exceptions, block)
        }
        throw exceptions
    }

    override fun <RET> assetStream_AsChars(asset: AssetPath, exceptions: Exception, encoding: Charset, block: (CharStream) -> RET): RET {
        modManager.assetProviders.forEach {
            checkInterrupt(exceptions)
            val input = it.asCharStream(asset, exceptions, encoding)
            if(input != null) {
                return tryWith(input) { block(input) }
            }
        }
        if(komoinen.parent != null) {
            return komoinen.parent!!.assetManager.assetStream_AsChars(asset, exceptions, encoding, block)
        }
        throw exceptions
    }

    override fun assetLoadAsync_AsBytes(asset: AssetPath, exceptions: Exception, exec: Exec): Future<ByteBuffer> {
        return exec.submit(Callable<ByteBuffer> { assetLoad_AsBytes(asset, exceptions) })
    }

    override fun assetLoadAsync_AsChars(asset: AssetPath, exceptions: Exception, encoding: Charset, exec: Exec): Future<String> {
        return exec.submit(Callable<String> { assetLoad_AsChars(asset, exceptions) })
    }

    override fun <RET> assetStreamAsync_AsBytes(asset: AssetPath, exceptions: Exception, exec: Exec, block: (ByteStream) -> RET): Future<RET> {
        return exec.submit(Callable<RET> { assetStream_AsBytes(asset, exceptions, block) })
    }

    override fun <RET> assetStreamAsync_AsChars(asset: AssetPath, exceptions: Exception, encoding: Charset, exec: Exec, block: (CharStream) -> RET): Future<RET> {
        return exec.submit(Callable<RET> { assetStream_AsChars(asset, exceptions, encoding, block) })
    }

}