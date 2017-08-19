package de.komoinen

import java.lang.reflect.Method
import java.util.*
import java.util.function.Predicate
import kotlin.collections.HashMap

@Target(AnnotationTarget.FUNCTION)
annotation class EventListener

interface EventAccess {
    fun registerEvent(type:EventType<*>): Boolean
    fun registerListener(listener: Any, thread: Thread? = null)
    fun <E: Event> fireEvent(
            event: E, source: Any,
            exceptions: Exception? = null,
            escalation: Boolean = true
    ): E
}

/**
 * Internal class, that is the 'Event Bus' of this library.
 * DO NOT INSTANCE MANUALLY; CREATE A NEW Komoinen INSTANCE INSTEAD!
 **/
class EventManager(
        internal val eventTypes: HashMap<Class<out Event>, EventType<*>> = HashMap()
): EventAccess {
    internal lateinit var komoinen: Komoinen
    fun init() {
        if(komoinen.parent != null) {
            val parent_events = komoinen.parent?.eventManager!!
            val p_event_types = parent_events.eventTypes
            eventTypes.putAll(p_event_types)
        } else {
            registerEvent<ListenerRegistrationEvent>("ListenerRegistration")
            registerEvent<ModulesInitPre>("ModulesInitializationPre")
            registerEvent<ModulesInitMid>("ModulesInitializationMid")
            registerEvent<ModulesInitPost>("ModulesInitializationPost")
        }
    }

    /**
     * Registers a new type of Event, so instances of it can be fired and listened to.
     *
     * @param name A human-readable name of the event type for debugging/logging.
     * @param veto A predicate that is called before any instance of the event is fired.
     *
     * @return True, if registration is successful. False, if the event-type is already registered.
     **/
    inline fun <reified T:Event> registerEvent(name: String, veto: Predicate<Event>? = null): Boolean {
        return registerEvent(EventType(name, T::class.java, veto))
    }

    /**
     * Registers a new EventType. Do not use directly.
     * This is a public but internal, function.
     * Will print a warning if the type is already registered.
     *
     * @return True, if registration is successful. False, if the event-type is already registered.
     **/
    override fun registerEvent(type:EventType<*>): Boolean {
        if(eventTypes.containsKey(type.clazz)) {
            println("[$IDEN] Event already registered: $type")
            return false
        } else {
            println("[$IDEN] Registered event: $type")
            eventTypes.put(type.clazz, type)
            return true
        }
    }

    /**
     * Registers a listener so it can receive eventManager.
     *
     * @throws KomoinenException If a method marked as EventListener is not accessible.
     * @throws KomoinenException If the first parameter of a EventListener method is not the EventManager class.
     * @throws KomoinenException If the second parameter of a EventListener method does not extend the Event class.
     * @throws KomoinenException If the event-type of the EventListener method was not registered with registerEvent.
     **/
    override fun registerListener(listener: Any, thread: Thread?) {
        fireEvent(ListenerRegistrationEvent(listener), "registerListener(event.listener)")
        for(method in listener.javaClass.declaredMethods) {
            // fast exclusion checks
            if(method.isVarArgs) continue
            if(method.isBridge) continue
            if(method.parameterCount != 2) continue

            // Any methods MUST be marked with the EventListener annotation.
            if(!method.isAnnotationPresent(EventListener::class.java)) continue

            // Make the method accessible if necessary...
            if(!method.isAccessible) {
                try {
                    method.isAccessible = true
                } catch (ex: Exception) {
                    throw komoinen.KomoinenException("[$IDEN] Event-Method is not accessible: $method", ex)
                }
            }

            // Check method parameters.
            val paramMNG = method.parameters[0].type
            val paramEVT = method.parameters[1].type
            if(!EventManager::class.java.isAssignableFrom(paramMNG)) {
                throw komoinen.KomoinenException("[$IDEN] First parameter of event-listener method must be EventManager: $method[0]::$paramMNG")
            }

            if(!Event::class.java.isAssignableFrom(paramEVT)) {
                throw komoinen.KomoinenException("[$IDEN] Second parameter of event-listener method must extend Event: $method[1]::$paramEVT")
            }

            // Actually register the listener with the event-type.
            val eventType = eventTypes[paramEVT]
            if(eventType != null) {
                println("[$IDEN] Registered method ($method) with listener ($listener) for event-type $eventType")
                eventType.listeners.put(listener, EventBinding(thread, method))
            } else {
                throw komoinen.KomoinenException("[$IDEN] Unknown event-type ($paramEVT) on listener method: $method; forgot to registerEvent(${paramEVT.typeName})?")
            }
        }
    }

    /**
     * Fires an event so that any registered listeners may receive it.
     *
     * @param event The event to fire. (Make sure its type is registered!)
     * @param source The source of the event. This is for debugging.
     * @param exceptions Any exceptions that might occur are suppressed and added to this one. Can be null.
     * @param escalation If the event can escalate into a higher injection context. true by default.
     *
     * @throws KomoinenException If the given event-type is not registered.
     * @throws KomoinenException If the current thread is not the thread the listener is running on.
     * @throws KomoinenException If the method reflection invocation fails.
     *
     * @return The event passed in.
     **/
    override fun <E: Event> fireEvent(
            event: E, source: Any,
            exceptions: Exception?,
            escalation: Boolean
    ): E {
        val doNotThrow = exceptions != null
        val eventType = eventTypes[event.javaClass] ?: if(doNotThrow) {
            return event
        } else {
            throw komoinen.KomoinenException("[$IDEN] Attempted to fire event with unknown event-type: $event, from source: $source")
        }

        val exceptions_inner = exceptions ?: Exception("One or more Error(s) occurred while firing the event: $event, from source: $source")

        // If the injector context has a parent, fire the event there first.
        if(escalation && komoinen.parent != null) {
            komoinen.parent?.fireEvent(event, source, exceptions_inner)
        }

        // Can we actually fire the event?
        if(eventType.veto != null && !eventType.veto.test(event)) {
            // Predicate veto'ed against firing.
            return event
        }

        // Tell all listeners about the event.
        for((obj, bnd) in eventType.listeners) {
            val trd = bnd.thread
            val mth = bnd.method
            try {
                val ct = Thread.currentThread()
                if(trd != null && trd != ct) {
                    exceptions_inner.addSuppressed(komoinen.KomoinenException("Listener '$obj' can only receive eventManager from $trd, not from $ct"))
                    continue // skip listener
                }

                mth.invoke(obj, this, event)
            } catch (ex: Exception) {
                exceptions_inner.addSuppressed(komoinen.KomoinenException("[$IDEN] Failed to fire event with event-type: $event, from source: $source", ex))
            }
        }// foreach(listener)

        // Throw any errors that might have occurred.
        if(!doNotThrow && exceptions_inner.suppressed.isNotEmpty()) {
            throw exceptions_inner
        }

        return event
    }
}

/**
 * Internal class, holding the necessary metadata of a registered listener for event processing.
 **/
class EventBinding(
        val thread: Thread?,
        val method: Method
)

/**
 * Internal class, holding all the necessary metadata about a event-type for event processing.
 **/
class EventType<T: Event>(
        val name:String,
        val clazz: Class<T>,
        val veto: Predicate<Event>?,
        val listeners: WeakHashMap<Any, EventBinding> = WeakHashMap()
) {
    override fun toString() = "{name: $name, clazz: ${clazz.canonicalName}, listeners-#: ${listeners.size}}"
}

/** Empty payload. **/
private val emptyBytes = byteArrayOf()

/** Abstract superclass of all Event's that can be fired and listened to. **/
abstract class Event(
        @Suppress("unused") val time_created: Long = System.currentTimeMillis(),
        @Suppress("unused") val payload: ByteArray = emptyBytes
)

/** A event that is fired when a new listener is registered. **/
class ListenerRegistrationEvent(
        @Suppress("unused") val listener: Any
): Event()