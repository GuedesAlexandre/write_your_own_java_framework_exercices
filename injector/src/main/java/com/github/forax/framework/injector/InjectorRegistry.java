package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> instanceRegistry;

    public InjectorRegistry() {
        instanceRegistry = new HashMap<>();
        super();
    }


    public <T> void registerInstance(Class<T> clazz, T instance) {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(instance);
        registerProvider(clazz, () -> instance);
    }


    public <T> T lookupInstance(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        var supp = instanceRegistry.get(clazz);
        if (supp == null) {
            throw new IllegalStateException("ez");
        }
        return clazz.cast(supp.get());
    }

    public <T> void registerProvider(Class<T> clazz, Supplier<? extends T> supp) {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(supp);
        var result = instanceRegistry.putIfAbsent(clazz, supp);
        if (result != null) {
            throw new IllegalStateException("instance already registered");
        }

    }

    static <T> List<PropertyDescriptor> findInjectableProperties(Class<T> clazz) {
        var beanInfo = Utils.beanInfo(clazz);
        return Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(el -> el.getWriteMethod() != null && el.getWriteMethod().isAnnotationPresent(Inject.class))
                .toList();
    }

    private static <T> Constructor<?> findConstructor(Class<? extends T> type) {
        var constructors = type.getConstructors();
        var foundedConstructors = Arrays.stream(constructors)
                .filter(constructor -> {
                    return constructor.isAnnotationPresent(Inject.class);}).toList();
        return switch (foundedConstructors.size()) {
            case 0 -> Utils.defaultConstructor(type);
            case 1->  foundedConstructors.getFirst();
            default -> throw new IllegalStateException("Either no or multiple constructor candidates found");
        };
    }


    public <T> void registerProviderClass(Class<T> type, Class<? extends T> reg) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(reg);
        var constructor = findConstructor(reg);
        var properties = findInjectableProperties(type);
        registerProvider(type, () -> {
            var args = Arrays.stream(constructor.getParameterTypes()).map(this::lookupInstance).toArray();
            var instance = Utils.newInstance(constructor, args);
            for (var property: properties) {
                var param = lookupInstance(property.getWriteMethod().getParameterTypes()[0]);
                Utils.invokeMethod(instance, property.getWriteMethod(), param);
            }
            return type.cast(instance);
        });
    }

   public <T> void registerProviderClass(Class<T> type){
        Objects.requireNonNull(type);
        registerProviderClass(type, type);
   }


}