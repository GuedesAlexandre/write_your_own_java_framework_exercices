package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class InterceptorRegistry {
    private AroundAdvice advice;
    private final HashMap<Class<? extends Annotation>, List<Interceptor>> interceptorMap;

    public InterceptorRegistry() {
        interceptorMap = new HashMap<>();
        super();
    }


    public void addInterceptor(Class<? extends Annotation> annotation, Interceptor interceptor) {
        Objects.requireNonNull(interceptor);
        Objects.requireNonNull(annotation);
        interceptorMap.computeIfAbsent(annotation, _ -> new ArrayList<>()).add(interceptor);
        invocationMap.clear();

    }

    public void addAroundAdvice(Class<? extends Annotation> annotation, AroundAdvice advice) {
        Objects.requireNonNull(annotation);
        Objects.requireNonNull(advice);
        addInterceptor(annotation, (instance, method, args, invocation) -> {
            advice.before(instance, method, args);
            var result = invocation.proceed(instance, method, args);
            advice.after(instance, method, args, result);
            return result;
        });
    }

    public List<Interceptor> findInterceptors(Method method) {
        var classAnnotations = method.getDeclaringClass().getAnnotations();
        var methodAnnotations = method.getAnnotations();
        var methodParameterAnnotations = method.getParameterAnnotations();

        return Stream.of(Arrays.stream(classAnnotations), Arrays.stream(methodAnnotations), Arrays.stream(methodParameterAnnotations).flatMap(Arrays::stream))
                .flatMap(s -> s)
                .distinct()
                .flatMap(annotation -> interceptorMap.getOrDefault(annotation.annotationType(), List.of()).stream())
                .toList();
    }


    public List<Interceptor> findAdvices(Method method) {
        Objects.requireNonNull(method);
        return findInterceptors(method);
//        var list = new ArrayList<AroundAdvice>();
//        for (var annotation : method.getDeclaredAnnotations()) {
//            list.addAll(map.get(annotation.annotationType()));
//        }
//        return Collections.unmodifiableList(list);
    }

    public static Invocation getInvocation(List<Interceptor> interceptors) {
        Objects.requireNonNull(interceptors);
        Invocation invocation = Utils::invokeMethod;
        for (var interceptor : interceptors.reversed()) {
            var previousInvocation = invocation;
            invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, previousInvocation);
        }
        return invocation;
    }

    private final HashMap<Method, Invocation> invocationMap = new HashMap<>();

    public <T> T createProxy(Class<T> type, T delegate) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(delegate);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, ((proxy, method, args) -> {
            var invocation = invocationMap.computeIfAbsent(method, m -> getInvocation(findInterceptors(m)));
            return invocation.proceed(delegate, method, args);
        })));
    }
}
