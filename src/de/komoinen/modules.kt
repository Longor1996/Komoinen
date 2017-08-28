/**
 * This is the most I/O-heavy class in the whole project.
 * Module and asset loading is complicated.
 **/
package de.komoinen

import com.beust.klaxon.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader

import java.lang.ref.WeakReference
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList

// The following eventManager are fired in the following function.
class ModulesInitPre: Event()
class ModulesInitMid: Event()
class ModulesInitPost: Event()

interface ModuleAccess {
    fun moduleLoad(path: Path)
    fun moduleDirLoad(path: Path)
}

class ModuleManager: ModuleAccess {
    internal lateinit var komoinen: Komoinen
    val modules = HashMap<String, Module>()
    val modulesInfo = HashMap<String, ModuleInfo>()
    val assetProviders = ArrayList<AssetSource>()
    var filter: (ModuleInfo) -> Boolean = {true}

    fun modulesCheckAndLoad() {
        if(modulesInfo.isEmpty()) {
            println("[$IDEN] No modules to check&load.")
            return
        }

        println("  [$IDEN] Checking ${modulesInfo.size} module(s).")
        var bindingsCount = 0

        // Module dependency sorting & checking
        val mods = kotlin.run {
            val ex = komoinen.KomoinenException("Error(s) occurred during module validation.")
            val mods = modulesInfo.values.associateTo(ConcurrentHashMap()) { it.lowcopy(false).nameVerPair() }

            // Take into account modules from our parent.
            if(komoinen.parent != null) {
                val v = komoinen.parent?.moduleManager!!.modulesInfo
                mods.putAll(v.values.associate { it.lowcopy(true).nameVerPair() })
            }

            // Assign all dependents to their respective dependencies, and also check for missing dependencies.
            outer@for (m in mods) {
                inner@for (d in m.value.dependencies) {
                    val o = mods[d]
                    if (o == null) {
                        // Found a dependency leading nowhere
                        ex.addSuppressed(Exception("Missing dependency: ${m.value.name} -> $d"))
                        mods.put(d, ModuleInfoCopy(null, d))
                        continue@inner
                    }
                    o.dependents += m.value.name
                }
                m.value.original.also {
                    bindingsCount += it?.bindings?.size ?: 0
                }
            }

            if(ex.suppressed.isEmpty()) {
                println("    [$IDEN] No missing modules. Proceeding with dependency check.")
            } else {
                println("    [$IDEN] Missing modules found! Added fake modules for dependency check.")
            }

            // Sort all modules by their dependencies...
            val out = ArrayList<ModuleInfoCopy>()
            val ind = mods.values.filterTo(ArrayList()) { it.isIndependent() }
            while(ind.isNotEmpty()) {
                val n = ind.removeAt(0)
                out.add(n)
                // find all modules m, with a dependency on n
                for(m in mods.values.filterTo(ArrayList()) { it.dependencies.contains(n.name) }) {
                    //println("removing edge m ${m.original.name} to ${n.original.name}")
                    m.dependencies.remove(n.name)
                    n.dependents.remove(m.name)
                    if(m.isIndependent()) {
                        ind.add(m)
                    }
                }
            }

            // Check for dependency cycles...
            for((_, name, dependencies) in mods.map { it.value })
                dependencies
                        .filter { modulesInfo.contains(it) }
                        .forEach { ex.addSuppressed(Exception("Cycle detected: $name -> $it")) }

            // Throw all errors, if there are any...
            if(ex.suppressed.isNotEmpty()) {
                throw ex
            }

            // return L!
            out
        } // module

        // Remove all 'fake' modules.
        mods.removeAll { it.original == null }

        println("    [$IDEN] All module dependencies appear valid.")

        if(bindingsCount > 0)
            println("    [$IDEN] Bindings provided by modules: $bindingsCount")

        println("    [$IDEN] Loading ${modulesInfo.size} module(s).")

        komoinen.fireEvent(ModulesInitPre(), "modulesCheckAndLoad()")

        // ModuleData creation
        for(moduleInfo in mods.map { it.original }) {
            // This can never happen due to 'remove all fake modules', but we gotta do it anyway.
            if(moduleInfo == null) {
                // println("    [$IDEN] A fake module (name= ${module.name}) somehow got trough validation. Skipping...")
                continue
            }

            // Define flags for ModuleData
            val name = moduleInfo.name
            val version = moduleInfo.version
            val assets = moduleInfo.hasAssets()
            val entry = moduleInfo.hasEntry()
            val bindings = moduleInfo.hasBindings()
            val directory = moduleInfo.isDirectory

            // Determine the correct ModuleData implementation using the above flags.
            println("    [$IDEN] Loading module $name v$version... ")
            val data: ModuleData = try { when(true) {
                bindings || entry -> { // bindings or entry defined; must use ClassLoader (URL/Dir)
                    ModuleDataClassLoader(moduleInfo, komoinen)
                }
                directory && assets -> { // assetManager defined, but no bindings; use FileSystem DIR
                    ModuleDataFileSystemDir(moduleInfo, komoinen)
                }
                !directory && assets -> { // assetManager defined, but no bindings; use FileSystem ZIP
                    ModuleDataFileSystemZip(moduleInfo, komoinen)
                }
                else -> { // nothing defined?
                    ModuleDataNone(moduleInfo, "no data")
                }
            } } catch(ex:Exception) {
                println()
                throw komoinen.KomoinenException("Failed to load data for module $name-v$version.", ex)
            } // when -> data

            val module = Module(moduleInfo, data)
            modules.put(name, module)

            if(data is AssetSource) {
                assetProviders.add(data)
            }

            if(data is ModuleDataClassLoader) {
                data.entry?.postinit(module)
            }
        } // foreach ModuleInfo

        komoinen.fireEvent(ModulesInitMid(), "modulesCheckAndLoad()")

        // Finish module initialization.
        for(module in modules.values) {
            if(module.data is ModuleDataClassLoader) {
                module.data.entry?.postinit(module)
            }
        }

        komoinen.fireEvent(ModulesInitPost(), "modulesCheckAndLoad()")
    }//modulesCheckAndLoad()

    override fun moduleLoad(path: Path) {
        try {
            val modInfo = readModule(path)

            // This exists for basic module loading security.
            if(!filter(modInfo)) {
                println("    [$IDEN] ModuleInfo loaded but filtered by ModuleManager: $modInfo")
                return
            }

            modulesInfo.put(modInfo.name, modInfo)
            println("    [$IDEN] Found module: $path")
        } catch (ex: Exception) {
            throw komoinen.KomoinenException("Unable to find or the load module: $path", ex)
        }
    }

    override fun moduleDirLoad(path: Path) {
        println("  [$IDEN] Resolving module directory path: $path")
        val resolved_path = try {
            val p = path.toRealPath()
            if( ! Files.isDirectory(path)) {
                throw komoinen.KomoinenException("Given path is not a directory that can hold modulesInfo: $path")
            }
            (p)
        } catch(ex:IOException) {
            throw komoinen.KomoinenException("Failed to resolve path to module directory: $path", ex)
        }

        println("   [$IDEN] Scanning for modules: $resolved_path")
        // Walk trough the files, but skip the module directory itself.
        Files.walk(resolved_path, 1).skip(1).forEach {
            if(Files.isDirectory(it) || it.fileName.toString().endsWith(".zip") || it.fileName.toString().endsWith(".jar")) {
                moduleLoad(it)
            }
        }
    }

}// ModuleManager


fun ModuleManager.readModule(path: Path, allowLinks:Boolean=false): ModuleInfo {
    if(allowLinks && Files.isSymbolicLink(path)) {
        throw komoinen.KomoinenException("Given path to module is a link: $path")
    }
    if(Files.isDirectory(path)) {
        return readModuleFromDir(path)
    }
    if(Files.isRegularFile(path)) {
        return readModuleFromFile(path)
    }
    throw komoinen.KomoinenException("Unable to process path as module: $path")
}

fun ModuleManager.readModuleFromDir(modDirectory: Path): ModuleInfo {
    val modInfoFile = modDirectory.resolve("mod.json")
    return tryWith(Files.newBufferedReader(modInfoFile)) {
        val mi = Parser().parse(it) as JsonObject
        parseModInfo(mi, modDirectory, isDirectory=true)
    }
}

fun ModuleManager.readModuleFromFile(modArchive: Path): ModuleInfo {
    val archEnv = mapOf("readonly" to "true")
    return tryWith(FileSystems.newFileSystem(URI.create("jar:"+modArchive.toUri()), archEnv)) {
        val modInfoFile = it.getPath("mod.json")
        val modInfo = tryWith(Files.newBufferedReader(modInfoFile, Charsets.UTF_8)) {
            val mi = com.beust.klaxon.Parser().parse(it) as JsonObject
            parseModInfo(mi, modArchive, isDirectory=false)
        }
        modInfo
    }
}

private fun <T> Exception.komParExcDef(message: String, cause: Exception?, default: T): T {
    this.addSuppressed(Exception(message, cause))
    return default
}

fun ModuleManager.parseModInfo(json: JsonObject, path: Path, isDirectory: Boolean):ModuleInfo {
    val exceptions = komoinen.KomoinenException("One or more error(s) occurred during parsing the module-info file (mod.json) for: $path")

    // required module-info fields
    val mod_name = json.string("name")
            ?: exceptions.komParExcDef("ModInfo file is missing internal name.", null, "")
    val mod_author = json.anyList<String>("author")
            ?: exceptions.komParExcDef("ModInfo file is missing author(s).",null, emptyList<String>())
    val mod_version = json.string("version")
            ?: exceptions.komParExcDef("ModInfo file is missing semantic version (MAJOR.MINOR.PATCH).", null, "")

    // Modules are not required to have assets.
    val mod_assets  = json.string("assets") ?: ""

    // Modules are not required to have an entry-point.
    val mod_entry  = json.string("entry") ?: ""

    val mod_depends = parseModDependencies(json["dependencies"], exceptions)
    val mod_bindings = parseModBindings(json["bindings"], exceptions)

    // throw all parsing exceptions at once
    if(exceptions.suppressed.isNotEmpty())
        throw exceptions
    return ModuleInfo(path, json, mod_name, mod_author, mod_version, mod_assets, mod_entry, mod_depends, mod_bindings, isDirectory)
}

fun ModuleManager.parseModDependencies(json: Any?, exceptions: Exception):List<ModuleDepend> {
    json ?: return emptyList()
    if(json !is JsonArray<*>) {
        throw komoinen.KomoinenParsingException("Module-Field 'dependencies' is not a JsonArray of objects: $json")
    }

    fun parseModDependency(json_item:Any?):ModuleDepend? {
        json_item ?: return null
        if(json_item !is JsonObject) {
            return exceptions.komParExcDef("Non-JsonObject field in 'dependencies': $json_item", null, null)
        }

        // required dependency fields
        val bind_name = json_item.string("name")
                ?: exceptions.komParExcDef("ModuleDepend-Field 'name' not found or of wrong type (required: string/identifier) in dependency $json_item.",null,"")
        val bind_version = json_item.string("version")
                ?: exceptions.komParExcDef("ModuleDepend-Field 'version' not found or of wrong type (required: string/semantic-version) in dependency $json_item.",null,"")

        return ModuleDepend(bind_name, bind_version)
    }

    val dependencies = mutableListOf<ModuleDepend>()
    for(x in json) {
        val dependency = parseModDependency(x)
        dependency ?: continue
        dependencies += dependency
    }
    return dependencies
}

fun ModuleManager.parseModBindings(json: Any?, exceptions: Exception):List<ModuleBinding> {
    json ?: return emptyList()
    if(json !is JsonArray<*>) {
        return exceptions.komParExcDef("Module 'bindings' is not a JsonArray of objects: $json", null, emptyList())
    }

    // this is horribly ugly code; TODO: delete and rewrite at some point
    fun err_nfofwt(name:String, required:String, given:Any?) = "ModuleBinding-Field '$name' not found or of wrong type; required is $required, given was $given."
    fun parseModBinding(json_item:Any?):ModuleBinding? {
        json_item ?: return null
        if(json_item !is JsonObject) {
            return exceptions.komParExcDef("Non-JsonObject field in 'bindings': $json_item", null, null)
        }
        val bind_name = json_item.string("name") ?: exceptions.komParExcDef(err_nfofwt("name", "string/name", json_item["name"]), null, "")
        val bind_bound_type = json_item.string("bound_type") ?: exceptions.komParExcDef(err_nfofwt("bound_type", "string/classpath", json_item["bound_type"]), null, "")
        val bind_param_type = json_item.string("param_type") ?: exceptions.komParExcDef(err_nfofwt("param_type", "string/classpath", json_item["param_type"]), null, "")
        val bind_maker_type = json_item.string("maker_type") ?: exceptions.komParExcDef(err_nfofwt("maker_type", "string/classpath", json_item["maker_type"]), null, "")
        val bind_maker_kind = json_item.string("maker_kind") ?: exceptions.komParExcDef(err_nfofwt("maker_kind", "string[singleton | multiton | provider | factory | value]", json_item["maker_kind"]), null, "")
        val bind_is_new = json_item.boolean("new") ?: false
        return ModuleBinding(bind_name, bind_bound_type, bind_param_type, bind_maker_kind, bind_maker_type, bind_is_new)
    }

    val bindings = mutableListOf<ModuleBinding>()
    for(x in json) {
        val binding = parseModBinding(x)
        binding ?: continue
        bindings += binding
    }
    return bindings
}

data class ModuleInfo(
        val source: Path,
        val json: JsonObject,
        val name: String,
        val author: List<String>,
        val version: String,
        val assets: String,
        val entry: String,
        val dependencies: List<ModuleDepend>,
        val bindings: List<ModuleBinding>,
        val isDirectory: Boolean
) {
    fun lowcopy(full:Boolean) = ModuleInfoCopy(if(full) null else this, "$name-$version", dependencies.map { "${it.name}-${it.version}" }.toMutableList())
    fun hasBindings() = bindings.isNotEmpty()
    fun hasAssets() = assets.isNotBlank()
    fun hasEntry() = entry.isNotBlank()
}

data class ModuleInfoCopy(val original:ModuleInfo?, val name:String, val dependencies:MutableList<String> = mutableListOf(), val dependents:MutableList<String> = mutableListOf()) {
    fun isIndependent() = dependencies.isEmpty()
    override fun toString() = "ModuleInfoCopy($name, $dependencies)"
    fun nameVerPair() = Pair(name, this)
}

data class ModuleDepend(val name:String, val version:String, var ref: WeakReference<ModuleInfo>? = null)

// kind : enum = factory / provider / singleton / multiton / value / custom
data class ModuleBinding(
        val name: String,
        val bound_type: String,
        val param_type: String,
        val maker_kind: String,
        val maker_type: String,
        val is_new: Boolean
)

sealed class ModuleData(@Suppress("unused") val module:ModuleInfo) {
    abstract val reloadable: Boolean
}

class ModuleDataClassLoader(
        module:ModuleInfo,
        val komoinen: Komoinen,
        private val src:Path=module.source,
        private val cl:ClassLoader = URLClassLoader(src.toUri().toURL().asArray()),
        val entry: ModuleInstance? = if(module.hasEntry()) {
            val entry_path = module.entry
            try {
                val entry_clazz = cl.loadClass(entry_path)
                if(ModuleInstance::class.java.isAssignableFrom(entry_clazz)) {
                    entry_clazz.newInstance() as ModuleInstance
                } else {
                    throw ClassCastException("Cannot cast entry-point of module to ModuleInstance interface; module= $module")
                }
            } catch (ex: Exception) {
                throw komoinen.KomoinenException("Unable to create/use instance of Module entry-point; module= $module", ex)
            }
        } else null
): ModuleData(module), AssetSource {
    override val reloadable = false
    override fun toString() = "(classloader= $cl, path= $src)"

    init {
        try {
            entry?.preinit(module)
        } catch (ex: Exception) {
            throw komoinen.KomoinenException("An exception occurred while pre-initializing the module entry-point; module= $module")
        }

        for(rawbinding in module.bindings) {
            val tag = rawbinding.name
            val bound_type = Class.forName(rawbinding.bound_type)
            val param_type = Class.forName(rawbinding.param_type)

            if(komoinen.bindingsManager.internal_exists(bound_type, param_type, tag)) {
                // binding exists: no-op
            } else {
                // no binding exists
                if(rawbinding.is_new) {
                    // is a new binding anyway: no-op
                } else {
                    throw komoinen.KomoinenException("There is no binding registered matching the requested definition: $rawbinding")
                }
            }

            val maker_clazz_str = rawbinding.maker_type
            val maker_clazz = cl.loadClass(maker_clazz_str)

            val binding = when(rawbinding.maker_kind) {
                "value" -> {
                    val bindable = BindingsCaster.valueFrom(maker_clazz)
                    Binding(bound_type, param_type, tag, false, false, false, bindable, komoinen)
                }
                "singleton" -> {
                    val bindable = BindingsCaster.singletonFrom(maker_clazz, bound_type)
                    Binding(bound_type, param_type, tag, false, false, false, bindable, komoinen)
                }
                "multiton" -> {
                    val bindable = BindingsCaster.multitonFrom(maker_clazz, bound_type, param_type)
                    Binding(bound_type, param_type, tag, false, false, false, bindable, komoinen)
                }
                "provider" -> {
                    val bindable = BindingsCaster.providerFrom(maker_clazz, bound_type)
                    Binding(bound_type, param_type, tag, false, false, false, bindable, komoinen)
                }
                "factory" -> {
                    val bindable = BindingsCaster.factoryFrom(maker_clazz, bound_type, param_type)
                    Binding(bound_type, param_type, tag, false, false, false, bindable, komoinen)
                }
                else-> throw komoinen.KomoinenException("Unknown kind of binding '${rawbinding.maker_kind}', in $rawbinding")
            }

            // If everything went well, this must NOT crash.
            komoinen.bindingsManager.addBinding(binding)
        }
    }//init

    private fun AssetPath.asURL(): URL? = cl.getResource("/assets/$namespace/$path")

    private fun err404(assetPath: AssetPath)
            = FileNotFoundException("Unable to find asset-file: $assetPath, in ClassLoader: $src/$cl")

    override fun exists(assetPath: AssetPath, suppressedExceptions: Exception): Boolean {
        return assetPath.asURL() != null
    }

    override fun size(assetPath: AssetPath, suppressedExceptions: Exception): Int? = try {
        val url = assetPath.asURL() ?: throw err404(assetPath)
        url.openConnection().contentLength
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asBytes(assetPath: AssetPath, suppressedExceptions: Exception): ByteBuffer? = try {
        val url = assetPath.asURL() ?: throw err404(assetPath)
        url.getAsByteBuffer()
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asString(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): String? = try {
        val url = assetPath.asURL() ?: throw err404((assetPath))
        String(url.getAsByteArray(), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asByteStream(assetPath: AssetPath, suppressedExceptions: Exception): ByteStream? = try {
        val url = assetPath.asURL() ?: throw err404((assetPath))
        url.openStream()
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asCharStream(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): CharStream? = try {
        val url = assetPath.asURL() ?: throw err404((assetPath))
        InputStreamReader(url.openStream(), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }
}

class ModuleDataFileSystemDir(
        module: ModuleInfo,
        val komoinen: Komoinen,
        private val dir: Path= module.source
): ModuleData(module), AssetSource {
    override val reloadable = true
    override fun toString() = "(directory= $dir)"

    private fun AssetPath.asPath() = dir.resolve(dir.fileSystem.getPath("/assets", this.namespace, this.path))

    override fun exists(assetPath: AssetPath, suppressedExceptions: Exception): Boolean {
        return Files.exists(assetPath.asPath(), LinkOption.NOFOLLOW_LINKS)
    }

    override fun size(assetPath: AssetPath, suppressedExceptions: Exception): Int? = try {
        val path = assetPath.asPath()
        Files.size(path).toInt()
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asBytes(assetPath: AssetPath, suppressedExceptions: Exception): ByteBuffer? = try {
        val channel = Files.newByteChannel(assetPath.asPath())
        val length = channel.size()
        val buffer = ByteBuffer.allocateDirect(length.toInt())
        channel.read(buffer)
        channel.close()
        buffer.flip()
        buffer
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asString(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): String? = try  {
        String(Files.readAllBytes(assetPath.asPath()), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asByteStream(assetPath: AssetPath, suppressedExceptions: Exception): ByteStream? = try  {
        Files.newInputStream(assetPath.asPath())
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asCharStream(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): CharStream? = try  {
        Files.newBufferedReader(assetPath.asPath(), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }
}

class ModuleDataFileSystemZip(
        module:ModuleInfo,
        val komoinen: Komoinen,
        private val fs:FileSystem= FileSystems.newFileSystem((URI.create("jar:"+module.source.toUri())), mapOf("readonly" to "true"))
): ModuleData(module), AssetSource {
    override val reloadable = true
    override fun toString() = "(archive= $fs)"

    private fun AssetPath.asPath() = fs.getPath("/assets", this.namespace, this.path)

    override fun exists(assetPath: AssetPath, suppressedExceptions: Exception): Boolean {
        return Files.exists(assetPath.asPath(), LinkOption.NOFOLLOW_LINKS)
    }

    override fun size(assetPath: AssetPath, suppressedExceptions: Exception): Int? = try {
        val path = assetPath.asPath()
        Files.size(path).toInt()
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asBytes(assetPath: AssetPath, suppressedExceptions: Exception): ByteBuffer? = try {
        val channel = Files.newByteChannel(assetPath.asPath())
        val length = channel.size()
        val buffer = ByteBuffer.allocateDirect(length.toInt())
        channel.read(buffer)
        channel.close()
        buffer.flip()
        buffer
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asString(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): String? = try {
        String(Files.readAllBytes(assetPath.asPath()), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asByteStream(assetPath: AssetPath, suppressedExceptions: Exception): ByteStream? = try {
        Files.newInputStream(assetPath.asPath())
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }

    override fun asCharStream(assetPath: AssetPath, suppressedExceptions: Exception, charset: Charset): CharStream? = try {
        Files.newBufferedReader(assetPath.asPath(), charset)
    } catch (ex: Exception) {
        suppressedExceptions.addSuppressed(ex); null
    }
}

class ModuleDataNone(module:ModuleInfo, private val reason:Any): ModuleData(module) {
    override val reloadable = false
    override fun toString() = "(null, reason= $reason)"
}

@Suppress("unused")
interface ModuleInstance {
    fun preinit(module: ModuleInfo)
    fun postinit(module: Module)
}

@Suppress("unused")
class Module(
        modInfo:ModuleInfo,
        val data: ModuleData,
        val name: String = modInfo.name,
        val source: Path = modInfo.source,
        val assets: Boolean = modInfo.assets.isNotBlank(),
        val version: String = modInfo.version,
        val author: List<String> = modInfo.author,
        val bindings: List<ModuleBinding> = modInfo.bindings,
        val dependencies: List<ModuleDepend> = modInfo.dependencies
)
