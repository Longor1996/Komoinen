package de.komoinen

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class BindingsManager {
    internal lateinit var komoinen: Komoinen
    private val bindings = HashMap<BindKey<*, *>, Binding>(16)

    fun getBindingsCount() = bindings.size

    private fun getBinding(binding:BindKey<*,*>): Binding? {
        return bindings[binding] ?: komoinen.parent?.bindingsManager?.getBinding(binding)
    }

    /**
     * Given a complete Binding, this method will register it with this injection context.
     *
     * @throws KomoinenException If the binding is not complete (eg: its bindable is missing).
     * @throws KomoinenException If a existing binding that is not overridable is attempted to be overwritten by the new one.
     **/
    fun addBinding(binding: Binding) {
        if(komoinen.initialized)
            throw komoinen.KomoinenException("The injection context is already initialized; no further changes can be made.")

        binding.binding ?: throw komoinen.KomoinenException("Attempted to register binding $binding without a bindable.")
        val existing = getBinding(binding)
        if (existing != null)
            if (!existing.overridable)
                throw komoinen.KomoinenException("Attempted to override $existing, which is non-overridable, with $binding.")
            else
                println("  [$IDEN] Overriding binding $existing with $binding")
        else
            println("  [$IDEN] Registering binding $binding")
        bindings.put(binding, binding)
    }

    @Suppress("UNCHECKED_CAST")
    fun <RETURN, PARAM> internal_getValue(clazzPrimary: Class<*>, clazzSecondary: Class<*>, tag: Any = Unit, param: PARAM, elevate: Boolean = true): RETURN {
        // Create binding-key and fetch
        val key = BindTempKey(clazzPrimary, clazzSecondary, tag)
        val binding = bindings[key]

        // No binding found, ask parent context... if we have one.
        if (binding == null) {
            return (if (elevate) komoinen.parent?.bindingsManager!!.internal_getValue(clazzPrimary, clazzSecondary, tag, param) else null)
                    ?: throw komoinen.KomoinenException("No value for $clazzPrimary&$clazzSecondary#$tag")
        } else {
            // Found a binding! Cast and return
            return (binding.binding as Bindable<RETURN, PARAM>).get(param)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <RETURN, PARAM> internal_getValueAsync(clazzPrimary: Class<*>, clazzSecondary: Class<*>, tag: Any, param: PARAM, elevate: Boolean, executor: ExecutorService): Future<RETURN> {
        // Create binding-key and fetch
        val key = BindTempKey(clazzPrimary, clazzSecondary, tag)
        val binding = bindings[key]

        // No binding found, ask parent context... if we have one.
        if (binding == null) {
            return (if (elevate) komoinen.parent?.bindingsManager!!.internal_getValueAsync(clazzPrimary, clazzSecondary, tag, param, true, executor) else null)
                    ?: throw komoinen.KomoinenException("No value for $clazzPrimary&$clazzSecondary#$tag")
        } else {
            // Found a binding! Cast and return
            return (binding.binding as Bindable<RETURN, PARAM>).getAsync(executor, param)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun internal_exists(clazzPrimary: Class<*>, clazzSecondary: Class<*>, tag: Any = Unit, elevate: Boolean = true): Boolean {
        val binding = bindings[BindTempKey(clazzPrimary, clazzSecondary, tag)]
        return (binding ?: if(elevate)komoinen.parent?.bindingsManager!!.internal_exists(clazzPrimary, clazzSecondary, tag)else null) != null
    }

}

sealed class Bindable<RETURN, in PARAM> {
    abstract fun get(param: PARAM): RETURN
    abstract fun getAsync(executorService: ExecutorService, param: PARAM): Future<RETURN>
}

/**
 * A value-binding is the same as a [Singleton]-Binding, with the difference that the value-binding is already created/instanced and thus immediately available.
 **/
data class Value<T>(private val data: T) : Bindable<T, Unit>() {
    override fun get(param: Unit): T = data

    override fun getAsync(executorService: ExecutorService, param: Unit): Future<T> {
        return java.util.concurrent.CompletableFuture.completedFuture(data)
    }
}

/**
 * A lambda-binding is a simple function that can receive anything as parameter and return an instance of some type.
 **/
data class Lambda<in PARAM>(private val block: (PARAM) -> Unit) : Bindable<Unit, PARAM>() {
    override fun get(param: PARAM) {
        block(param)
        return Unit
    }

    override fun getAsync(executorService: ExecutorService, param: PARAM): Future<Unit> {
        return executorService.submit<Unit> {block(param)}
    }
}

/**
 * A provider-binding returns a new instance of its bound type, every time it is injected.
 **/
data class Provider<T>(private val block: () -> T) : Bindable<T, Unit>() {
    override fun get(param: Unit): T = block()

    override fun getAsync(executorService: ExecutorService, param: Unit): Future<T> {
        return executorService.submit<T> {block()}
    }
}

/**
 * A factory-binding returns a new parameterized instance of its bound type, every time it is injected.
 **/
data class Factory<T, in P>(private val block: (P) -> T) : Bindable<T, P>() {
    override fun get(param: P): T = block(param)

    override fun getAsync(executorService: ExecutorService, param: P): Future<T> {
        return executorService.submit<T> {block(param)}
    }
}

/**
 * A singleton-binding is a global, static instance that is created the first time it is injected. For any injection afterwards the first created instance is reused.
 **/
data class Singleton<T>(private val block: () -> T) : Bindable<T, Unit>() {
    var value: T? = null

    override fun get(param: Unit): T {
        if (value == null) {
            synchronized(this) {
                if (value == null) {
                    value = block()
                }
            }
        }

        return value!!
    }

    // WARNING: Untested synchronization code.
    override fun getAsync(executorService: ExecutorService, param: Unit): Future<T> {
        return executorService.submit<T> {
            if (value == null) {
                synchronized(this) {
                    if (value == null) {
                        value = block()
                    }
                }
            }; value
        }
    }
}

/**
 * A multiton-binding is exactly the same as a [Singleton], except that it accepts a parameter-object when it is first created.
 * This requires that at every point of injection, the parameter-object be available. It is recommended to only use simple objects.
 *
 * The parameter-object must correctly implement [equals] and [hashCode] for this to work.
 **/
data class Multiton<T, in P>(private val block: (P) -> T) : Bindable<T, P>() {
    private var values = HashMap<P, T>()

    override fun get(param: P): T {
        if ( !values.contains(param)) {
            synchronized(this) {
                if ( !values.contains(param)) {
                    val value = block(param)
                    values.put(param, value)
                }
            }
        }
        return values[param] ?: throw RuntimeException("Multiton value $this with parameter $param is null after initialization.")
    }

    // WARNING: Untested synchronization code.
    override fun getAsync(executorService: ExecutorService, param: P): Future<T> {
        return executorService.submit<T> {
            if ( !values.contains(param)) {
                synchronized(this) {
                    if ( !values.contains(param)) {
                        val value = block(param)
                        values.put(param, value)
                    }
                }
            }
            values[param] ?: throw RuntimeException("Multiton value $this with parameter $param is null after asynchronous initialization.")
        }
    }
}

@Suppress("unused")
interface BindKey<T, P> {
    fun isReal() = false
}

/**
 * @see Komoinen.bind
 **/
data class Binding(
        private val clazzPrimary: Class<*>,
        private val clazzSecondary: Class<*>,
        val tag: Any = Unit,
        val overridable: Boolean = false,
        val immediate: Boolean = false,
        val threaded: Boolean = false,
        val binding: Bindable<*, *>?,
        val context: Komoinen
) : BindKey<Any, Any> {
    override fun isReal() = true
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Binding) return false
        return this.tag == other.tag
    }

    override fun hashCode(): Int {
        return clazzPrimary.hashCode() * clazzSecondary.hashCode() * tag.hashCode()
    }

    override fun toString(): String {
        return "(primary= ${clazzPrimary.canonicalName}${
            if(clazzSecondary==Unit.javaClass){""}else{", secondary= ${clazzSecondary.canonicalName}"}
        }${
            if(tag==Unit){""}else{", tag= $tag"}
        }, binding= $binding, flags= (override= $overridable, immediate= $immediate, threaded= $threaded)"
    }
}

/**
 * Internal class, used for instance injection resolution.
 *
 * @see Komoinen.get
 **/
internal data class BindTempKey<T, P>(
        private val clazzPrimary: Class<T>,
        private val clazzSecondary: Class<P>,
        private val tag: Any = Unit
) : BindKey<T, P> {
    override fun isReal() = false
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Binding) return false
        return this.tag == other.tag
    }

    override fun hashCode(): Int {
        return clazzPrimary.hashCode() * clazzSecondary.hashCode() * tag.hashCode()
    }
}