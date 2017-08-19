package de.komoinen;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class has the job of creating instances of the various Bindable's for use in the creation of injection bindings.
 * Why use Java one might ask? Because the Kotlin compiler does not support generic class literal conversions like this.
 **/
@SuppressWarnings({"unchecked", "unused"})
public class BindingsCaster {
    public static <MOD, T> Singleton<T> singletonFrom(
            Class<MOD> modClass,
            Class<T> primary
    ) throws Exception {
        return new Singleton<T>(((Supplier<T>) modClass.newInstance())::get);
    }

    public static <MOD, T, P> Multiton<T, P> multitonFrom(
            Class<MOD> modClass,
            Class<T> primary,
            Class<P> secondary
    ) throws Exception {
        return new Multiton<T, P>(((Function<P, T>) modClass.newInstance())::apply);
    }

    public static <MOD, T> Provider<T> providerFrom(
            Class<MOD> modClass,
            Class<T> primary
    ) throws Exception {
        return new Provider<T>(((Supplier<T>) modClass.newInstance())::get);
    }

    public static <MOD, T, P> Factory<T, P> factoryFrom(
            Class<MOD> modClass,
            Class<T> primary,
            Class<P> secondary
    ) throws Exception {
        return new Factory<T, P>(((Function<P, T>) modClass.newInstance())::apply);
    }

    public static <MOD> Value<MOD> valueFrom(
            Class<MOD> modClass
    ) throws Exception {
        return new Value(modClass.newInstance());
    }
}
