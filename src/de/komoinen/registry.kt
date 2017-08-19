package de.komoinen

interface ObjectRegistryAccess {
    fun <T> registerObject(clazz: Class<T>, name:String, instance: T)
    fun <T> iterateObjects(clazz: Class<T>, elevate: Boolean = true, block: (String, T) -> Unit)
    fun <T> fetchObject(clazz: Class<T>, name:String, elevate: Boolean = true): T?
}

class ObjectRegistry: ObjectRegistryAccess {
    internal lateinit var komoinen: Komoinen
    private val map = HashMap<Class<*>, HashMap<String, *>>()

    override fun <T> registerObject(clazz: Class<T>, name: String, instance: T) {
        if(komoinen.initialized)
            throw komoinen.KomoinenException("Injection-Context is initialized. No more changes may be done.")
        val list: HashMap<String, T> = map[clazz] as HashMap<String, T>? ?: HashMap<String, T>().also { map.put(clazz, it) }
        if(list.containsKey(name)) {
            throw komoinen.KomoinenException("Unable to register object: $name = $instance, with class: $clazz.")
        } else {
            list.put(name, instance)
        }
    }

    override fun <T> iterateObjects(clazz: Class<T>, elevate: Boolean, block: (String, T) -> Unit) {
        val objMap = map[clazz] as HashMap<String, T>? ?: return
        if(elevate && komoinen.parent != null) {
            komoinen.parent!!.objectRegistry.iterateObjects(clazz, true, block)
        }
        for((key, value) in objMap) {
            block(key, value)
        }
    }

    override fun <T> fetchObject(clazz: Class<T>, name:String, elevate: Boolean): T? {
        val objMap = map[clazz] as HashMap<String, T>? ?: return null
        return objMap[name] ?: komoinen.parent?.objectRegistry?.fetchObject(clazz, name, true)
    }
}
