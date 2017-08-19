/**
 * Kotlin Module Injection Engine
 * @author Lars Longor K
 **/
package de.komoinen

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Predicate

const val NAME = "Komoinen"
const val VERS = "0.0.1"
const val IDEN = "$NAME-$VERS"

@KomoinenDsl
class Komoinen private constructor(
        @Suppress("UNUSED") val loopFix: Unit,
        val name : String,
        val parent: Komoinen? = null,
        val executor: ExecutorService,
        val bindingsManager: BindingsManager = BindingsManager(),
        val moduleManager: ModuleManager = ModuleManager(),
        val eventManager: EventManager = EventManager(),
        val assetManager: AssetManager = AssetManager(),
        val objectRegistry: ObjectRegistry = ObjectRegistry()
):// delegates
        AssetAccess by assetManager,
        EventAccess by eventManager,
        ModuleAccess by moduleManager,
        ObjectRegistryAccess by objectRegistry
{// body start
constructor(
        name: String,
        parent: Komoinen? = null,
        executor: ExecutorService = Executors.newFixedThreadPool(1, { Thread(it, "$IDEN.WORKER") })
) : this(Unit, name, parent, executor)
    init {
        objectRegistry.komoinen = this
        bindingsManager.komoinen = this
        moduleManager.komoinen = this
        eventManager.komoinen = this
        assetManager.komoinen = this
        assetManager.modManager = moduleManager
        eventManager.init()
    }

    private var init: Boolean = false
    val initialized: Boolean get() = init

    override fun toString(): String {
        return "$IDEN#${super.hashCode()}{name= $name, parent= $parent, bindings(count)= ${bindingsManager.getBindingsCount()}, eventTypes(count)= ${eventManager.eventTypes.size}}"
    }

    /**
     * Given an initializer block as parameter, this function will create, initialize and validate any bindings or modules created in the initializer block.
     *
     * **Warning:** This method is *not* thread-safe and should only be called once (during initialization).
     **/
    @Suppress("unused")
    operator fun invoke(block: Komoinen.() -> Unit) {
        println()
        println("[$IDEN] Building injection-context from block: $block")
        this.block()
        println("[$IDEN] Injection-Context built. Validating...")
        validate()
        println("[$IDEN] Added ${bindingsManager.getBindingsCount()} binding(s).")
        println("[$IDEN] Added ${eventManager.eventTypes.size} event-type(s).")
        println("[$IDEN] Loaded ${moduleManager.modules.size} module(s).")
        println()
    }

    /**
     * Validates all bindings and modules, then loads the modules.
     **/
    private fun validate() {
        moduleManager.modulesCheckAndLoad()
        init = true
    }

    /**
     * Creates a new Komoinen injection context that has the current one as its parent.
     **/
    @Suppress("unused")
    fun sub(name:String = "<unnamed>", block: Komoinen.() -> Unit): Komoinen {
        return Komoinen(name, this).also { it(block) }
    }

    /**
     * Creates a half-[Binding] for use as the left-hand parameter of the [with] operator function.
     *
     * @param tag An object to mark the binding using [hashCode].
     * @param immediate Construct object immediately after scope initialization if possible.
     * @param overridable Allow sub-scopes to override the binding.
     * @param threaded The instance is created once per thread.
     **/
    @Suppress("unused")
    inline fun <reified RETURN : Any> bind(
            tag: Any = Unit,
            overridable: Boolean = false,
            immediate: Boolean = false,
            threaded: Boolean = false
    ) = Binding(RETURN::class.java, Unit.javaClass, tag, overridable, immediate, threaded, null, this@Komoinen)

    /**
     * Creates a parameterized half-[Binding] for use as the left-hand parameter of the [with] operator function.
     *
     * @param tag An object to mark the binding using [hashCode].
     * @param immediate Construct object immediately after scope initialization if possible.
     * @param overridable Allow sub-scopes to override the binding.
     * @param threaded The instance is created once per thread.
     **/
    @Suppress("unused")
    inline fun <reified RETURN : Any, reified PARAM : Any> bindp(
            tag: Any = Unit,
            overridable: Boolean = false,
            immediate: Boolean = false,
            threaded: Boolean = false
    ) = Binding(RETURN::class.java, PARAM::class.java, tag, overridable, immediate, threaded, null, this@Komoinen)

    /** Given a half-[Binding] and a [Bindable], creates a full Binding and immediately registers it. **/
    @Suppress("unused")
    inline infix fun <reified RETURN, reified PARAM> Binding.with(bindable: Bindable<RETURN, PARAM>) {
        if(this.binding != null) throw KomoinenException("Can not bind a already bound Binding ($this) with a new Bindable ($bindable).")
        val b = Binding(RETURN::class.java, PARAM::class.java, tag, overridable, immediate, threaded, bindable, this@Komoinen)
        bindingsManager.addBinding(b)
    }

    /** Creates a new [Value]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified RETURN> value(value: RETURN) = Value(value)

    /** Creates a new [Lambda]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified PARAM> lambda(noinline block: (PARAM) -> Unit) = Lambda(block)

    /** Creates a new [Provider]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified RETURN> provider(noinline block: () -> RETURN) = Provider(block)

    /** Creates a new [Factory]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified RETURN, reified PARAM> factory(noinline block: (PARAM) -> RETURN) = Factory(block)

    /** Creates a new [Singleton]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified RETURN> singleton(noinline block: () -> RETURN) = Singleton(block)

    /** Creates a new [Multiton]-bindable, for use as the right-hand parameter of the [with] operator function. **/
    @Suppress("unused")
    inline fun <reified RETURN, reified PARAM> multiton(noinline block: (PARAM) -> RETURN) = Multiton(block)

    /**
     * Invocation redirection.
     * @see EventManager.registerEvent
     **/
    @Suppress("unused")
    inline fun <reified EVENT:Event> registerEvent(name: String, veto: Predicate<Event>? = null): Boolean {
        if(initialized)
            throw KomoinenException("The injection context is already initialized; no further changes can be made. If necessary, this restriction can be ignored by directly accessing the EventManager.")
        return eventManager.registerEvent(EventType(name, EVENT::class.java, veto))
    }

    /**
     * Registers a named object with the [ObjectRegistry] of the given type.
     *
     * @throws KomoinenException If the given name/object/type triple is already registered.
     **/
    @Suppress("unused")
    inline fun <reified TYPE> registerObject(name: String, instance: TYPE) {
        objectRegistry.registerObject(TYPE::class.java, name, instance)
    }

    /**
     * Lets you walk over the named objects in the [ObjectRegistry] using a lambda function.
     **/
    @Suppress("unused")
    inline fun <reified TYPE> iterateObjects(elevate: Boolean = true, noinline block: (String, TYPE) -> Unit) {
        objectRegistry.iterateObjects(TYPE::class.java, elevate, block)
    }

    /**
     * @param RETURN The return type of the binding in question.
     * @param tag The tag/identifier of the binding in question.
     * @param elevate If true, the query can be elevated to a parent injection context.
     *
     * @return Whether the requested binding exists.
     **/
    @Suppress("unused")
    inline fun <reified RETURN> exists(tag: Any = Unit, elevate: Boolean = true): Boolean = bindingsManager.internal_exists(RETURN::class.java, Unit.javaClass, tag, elevate)

    /**
     * Given a binding type and a parameter object, this function will attempt to find and return an instance of that type.
     *
     * @param RETURN The return type of the binding in question.
     * @param PARAM The parameter type of the binding in question.
     * @param tag The tag/identifier of the binding in question. Default is Unit.
     * @param param The factory/multiton parameter object.
     * @param elevate If true, the query can be elevated to a parent injection context. Default is true.
     *
     * @throws ClassCastException If the requested binding does not match the returned instance.
     **/
    @Suppress("unused")
    inline fun <reified RETURN, reified PARAM> get(tag: Any = Unit, param: PARAM, elevate: Boolean = true): RETURN = bindingsManager.internal_getValue(RETURN::class.java, PARAM::class.java, tag, param as Any, elevate)

    /**
     * Given a binding type, this function will attempt to find and return an instance of that type.
     *
     * @param RETURN The return type of the binding in question.
     * @param tag The tag/identifier of the binding in question. Default is Unit.
     * @param elevate If true, the query can be elevated to a parent injection context. Default is true.
     *
     * @throws ClassCastException If the requested binding does not match the returned instance.
     **/
    @Suppress("unused")
    inline fun <reified RETURN> get(tag: Any = Unit, elevate: Boolean = true): RETURN = bindingsManager.internal_getValue(RETURN::class.java, Unit.javaClass, tag, Unit, elevate)


    /**
     * Given a binding type, this function will attempt to asynchronously find and return an instance of that type.
     *
     * @param RETURN The return type of the binding in question.
     * @param tag The tag/identifier of the binding in question. Default is Unit.
     * @param elevate If true, the query can be elevated to a parent injection context. Default is true.
     * @param executor The [ExecutorService] to use for the asynchronous operation(s).
     *
     * @throws ClassCastException If the requested binding does not match the returned instance.
     **/
    @Suppress("unused")
    inline fun <reified RETURN> getAsync(tag: Any = Unit, elevate: Boolean = true, executor: ExecutorService): Future<RETURN> = bindingsManager.internal_getValueAsync(RETURN::class.java, Unit.javaClass, tag, Unit, elevate, executor)

    /**
     * Given a binding type and a parameter object, this function will attempt to asynchronously find and return an instance of that type.
     *
     * @param RETURN The return type of the binding in question.
     * @param PARAM The parameter type of the binding in question.
     * @param tag The tag/identifier of the binding in question. Default is Unit.
     * @param param The factory/multiton parameter object.
     * @param elevate If true, the query can be elevated to a parent injection context. Default is true.
     * @param executor The [ExecutorService] to use for the asynchronous operation(s).
     *
     * @throws ClassCastException If the requested binding does not match the returned instance.
     **/
    @Suppress("unused")
    inline fun <reified RETURN, reified PARAM> getAsync(tag: Any = Unit, param: PARAM, elevate: Boolean = true, executor: ExecutorService = this.executor): Future<RETURN> = bindingsManager.internal_getValueAsync(RETURN::class.java, PARAM::class.java, tag, param as Any, elevate, executor)

    /**
     * All exceptions thrown by Komoinen extend this Exception-class.
     **/
    inner open class KomoinenException(message: String, cause: Throwable? = null): RuntimeException(message, cause)

    /**
     * This exception is thrown when a data-parsing error occurs.
     **/
    inner class KomoinenParsingException(message:String, cause: Throwable? = null): KomoinenException(message, cause)

    /**
     * This exception is thrown when asset-handling errors occur.
     **/
    inner class KomoinenAssetException(message: String, cause: Throwable? = null): KomoinenException(message, cause)
}
@DslMarker
annotation class KomoinenDsl

