package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JSONWriter {
    static class PropertyDescriptorCache extends ClassValue<PropertyDescriptor[]> {
        @Override
        protected PropertyDescriptor[] computeValue(Class<?> type) {
            var bean = Utils.beanInfo(type);
            return bean.getPropertyDescriptors();
        }
    }

    interface Generator {
        String generate(JSONWriter writer, Object bean);
    }

    static class GeneratorCache extends ClassValue<Generator> {
        @Override
        protected Generator computeValue(Class<?> type) {
            var beanInfo = Utils.beanInfo(type);
            var generators = Arrays.stream(beanInfo.getPropertyDescriptors())
                    .filter(el -> !el.getName().equals("class"))
                    .filter(el -> el.getReadMethod() != null)
                    .<Generator>map(property -> {
                        var getter = property.getReadMethod();

                        var annotation = getter.getAnnotation(JSONProperty.class);
                        var name = annotation == null ? property.getName() : annotation.value();
                        var prefix = '"' + name + "\": ";
                        return (writer, bean) -> {
                            var value = Utils.invokeMethod(bean, getter);
                            return prefix + writer.toJSON(value);
                        };
                    }).toList();
            return (writer, bean) -> generators.stream()
                    .map(generator -> generator.generate(writer, bean))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    private static final PropertyDescriptorCache cache = new PropertyDescriptorCache();
    private static final GeneratorCache genCache = new GeneratorCache();

    public String toJSON(Object o) {
        return switch (o) {
            case null -> "null";
            case String s -> "\"" + s + "\"";
            case Boolean b -> "" + b;
            case Integer i -> "" + i;
            case Double b -> "" + b;
            case Object obj ->{
                var func = map.get(obj.getClass());
                if(func !=null){
                    yield func.apply(obj);
                }
              yield  genCache.get(obj.getClass()).generate(this, obj);
            }
        };
    };

    private static final HashMap<Class<?>, Function<Object,String>> map = new HashMap<>();

    public <T> void configure(Class<T> clazz, Function<? super T,String> lambda) throws InstantiationException, IllegalAccessException {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(lambda);
        if(map.putIfAbsent(clazz, obj -> lambda.apply(clazz.cast(obj)))!=null){
            throw new IllegalStateException("Class already configured" + clazz.getName());
        }
    }
}


