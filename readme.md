# Komoinen
A basic framework for application development, with a bunch of essentials
(injection, events, modules, assets), written in Kotlin for the JVM platform,
and a small (quick to learn) API.

**Note:**
The great [klaxon library](https://github.com/cbeust/klaxon) is used internally for parsing JSON,
which in turn is used mainly with the module system (as module root file)
and asset metadata functions.

> Due to gradle and co. constantly having problems with various firewalls and proxies,
the klaxon library has been *directly embedded* into this libraries *source-code*.
Sorry about that, there was no other way!

## Features

- **Dependency Injection**
    - Synchronous and asynchronous instance fetching.
    - Overriding of bindings in higher contexts.
    - Tagging of bindings with 'names'.
    - Different 'kinds' of instance bindings:
        - Singletons, Multitons: Cached at first fetch.
        - Providers, Factories: No caching.
        - Lambdas: Callable function.
        - Values: Cached instance.
- **Event-Bus**
    - Event-type & listener registration
    - Automatic listener de-registration
    - Filtering of to-be-fired eventManager.
    - Partially thread-safe listeners.
- **Modules**
    - Created as zip/jar/directories with a `mod.json` at its root.
    - Can depend on each other, based on name and version.
    - Can register dependency injection bindings.
    - Can contain assetManager, including metadata (`*.meta`).
    - Can contain Main-Class for more complex things.
    - Warning: *Modules will create ClassLoader's for bindings and entry-points.*
- **Assets**
    - Load assets whole as String or ByteBuffer.
    - Stream assets bitwise as Characters or Bytes.
    - Loading/streaming can be done both synchronously and asynchronously.
    - Can query assets for their existence, size and hash.
    - Can assign metadata by placing a `FILE.EXT.meta` next to your `FILE.EXT`.
    - Metadata can be queried for existence or be loaded directly as Json-Object.
- **Registry**
    - Stores objects with type & name as key.
    - Fetch objects by name or iterate trough a typed map of name/object pairs.
- **Properties**
    - *Immutability*  
        Once a context is initialized, no more changes can be applied.
        This is a limitation for the sake of keeping things simple.
    - *Hierarchical Architecture*  
        It is possible to create a new context with another context as its parent.
        All systems are able to elevate their actions & queries to a parent context,
        thus creating hierarchies of context.

## Usage Example

```kotlin
// Example event type
class ExampleEvent

fun main(args: Array<String>) {
    // Create a new context...
    val komoinen = Komoinen("root")
    
    val listener = object {
        @EventListener
        fun onExmplEvent(emng: EventManager, event:ExampleEvent) {
            println("received event $event from manager $emng")
        }
    }
    
    // Context Initialization... using the Komoinen-DSL!
    komoinen {
        // Create a new tagged binding and bind a provider to it.
        bind<Long>("time") with provider { System.currentTimeMillis() }
        
        // Create a simple global value:
        bind<String>("workdir") with value("C:/temp")
        
        // How about a lazily created singleton?
        bind<java.sql.Connection>("database") with singleton
        { DriverManager.getConnection("jdbc:mysql//localhost:3306/") }
        
        // Or a dice-throw generator? (note the 'p' after 'bind')
        bindp<Int, Int>("dice") with factory
        { sides: Int -> (System.currentTimeMillis() % sides).toInt() }
        
        // Load modules from a directory...
        moduleDirLoad(Paths.get("./mods"))
        
        // Register a new event type...
        registerEvent<ExampleEvent>("example-event")
        
        // ... and a listener!
        registerListener(listener)
        
        // Register objects with the registry
    }
    
    // Fire an event (with context label):
    komoinen.fireEvent(ExampleEvent(), "main")
    
    // Get the current time!
    println(komoinen.get<Long>("time"))
    
    // Get some dice throws!
    println(komoinen.get<Int>("dice", 6))
    println(komoinen.get<Int>("dice", 20))
}
```

## Contribution
If you want to help with the development of this library, create issues and/or send pull requests.

## Credits

**Creator of Komoinen:** https://github.com/Longor1996  
**Creator of klaxon:** https://github.com/cbeust/klaxon

## TODO

- Assets
	- searching: implementation differs per 'data'-type of module
	    - ClassLoader: requires an index of assets per module
	    - FileSystem: can use existing FileSystem utilities

- Refactoring
    - Try to somehow get rid of the *one* java file, `BindingsCaster`.
        - Possibly wait for better reified generics or the addition of class literals?