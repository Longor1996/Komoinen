package de.komoinen.test

import java.nio.file.Paths
import de.komoinen.*
import java.util.function.Predicate

/*
* WARNING: This test-function is set-up to work with my (Longor's) development environment (IntelliJ IDEA) and file/folder locations (total mess)!
*/

fun main(args: Array<String>) {
    val komoinen: Komoinen by lazy { Komoinen("root") }

    komoinen {
        bind<String>("???", true) with lambda { it:String -> println(it.toLowerCase()) }
        bind<Int>("1000") with value(1000)

        bind<Long>("time") with provider { System.currentTimeMillis() }
        bindp<Long, Int>("time/div") with factory { i: Int -> System.currentTimeMillis() / i }

        bind<String>("user.home") with singleton { System.getProperty("user.home") }
        bindp<String, String>("property", true) with multiton { s: String -> System.getProperty(s) }
        moduleDirLoad(Paths.get("D:/projects/Komoinen/mods"))
    }

    println("b(1000)" + komoinen.get<Int>("1000"))
    println("b(time)" + komoinen.get<Long>("time"))
    println("b(time/div)" + komoinen.get<Long, Int>("time/div", 1000))
    println("b(user.home)" + komoinen.get<String>("user.home"))
    println("b(property)" + komoinen.get<String, String>("property", "os.name"))

    //*
    class initevt: Event()
    val obj = object {
        @EventListener
        @Suppress("unused")
        fun foobar(emng: EventManager, event:initevt) {
            println("received event! $event from $emng")
        }
    }

    komoinen.sub {
        bind<String>("???") with lambda { it:String -> println(it.toUpperCase()) }
        //moduleLoad(Paths.get("./lonemod"))

        // registration
        registerEvent<initevt>("test", Predicate {println("pre-fire: $it");true})
        registerListener(obj)

        // test event system
        fireEvent(initevt(), "sub.also{$this.fireEvent}")
    }.also {
        println(it)
    }
    //*/
}
