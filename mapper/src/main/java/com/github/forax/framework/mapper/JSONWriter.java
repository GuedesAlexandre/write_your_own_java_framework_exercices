package com.github.forax.framework.mapper;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JSONWriter {
    public JSONWriter() {
        map = new HashMap<>();
        super();
    }

    static class PropertyDescriptorCache extends ClassValue<List<PropertyDescriptor>> {
        @Override
        protected List<PropertyDescriptor> computeValue(Class<?> type) {
            return type.isRecord() ? recordProperties(type) : beanProperties(type);
        }
    }

    interface Generator {
        String generate(JSONWriter writer, Object bean);
    }

    static class GeneratorCache extends ClassValue<Generator> {
        @Override
        protected Generator computeValue(Class<?> type) {
            var properties = type.isRecord() ? recordProperties(type) : beanProperties(type);
            var generators = properties.stream()
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

    private static final GeneratorCache genCache = new GeneratorCache();

    public String toJSON(Object o) {
        return switch (o) {
            case null -> "null";
            case String s -> "\"" + s + "\"";
            case Boolean b -> "" + b;
            case Integer i -> "" + i;
            case Double b -> "" + b;
            case Object obj -> {
                var supplier = map.get(obj.getClass());
                if (supplier != null) {
                    yield supplier.apply(obj);
                }
                yield genCache.get(obj.getClass()).generate(this, obj);
            }
        };
    }

    ;

    private final HashMap<Class<?>, Function<Object, String>> map;

    public <T> void configure(Class<T> clazz, Function<? super T, String> lambda) {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(lambda);

        var result = map.putIfAbsent(clazz, obj -> lambda.apply(clazz.cast(obj)));
        if (result != null) {
            throw new IllegalStateException("already configured");
        }
    }

    private static List<PropertyDescriptor> beanProperties(Class<?> type) {
        var bean = Utils.beanInfo(type);
        return List.of(bean.getPropertyDescriptors());
    }

    private static List<PropertyDescriptor> recordProperties(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(recordComponent -> {
            try {
                var name = recordComponent.getName();
                var accessor = recordComponent.getAccessor();
                return new PropertyDescriptor(name, accessor, null);
            } catch (IntrospectionException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }).toList();
    }

}


