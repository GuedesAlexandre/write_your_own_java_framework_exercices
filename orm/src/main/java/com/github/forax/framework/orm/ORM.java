package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
    private ORM() {
        throw new AssertionError();
    }

    private static final ThreadLocal<Connection> DATA_THREAD_LOCAL = new ThreadLocal<>();

    static Connection currentConnection() {
        var threadLocal = DATA_THREAD_LOCAL.get();
        if (threadLocal == null) {
            throw new IllegalStateException();
        }
        return threadLocal;
    }

    public static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
        var instance = Utils.newInstance(constructor);
        var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
                .toArray(PropertyDescriptor[]::new);
        for (var propertyDescriptor : properties) {
            Utils.invokeMethod(instance, propertyDescriptor.getWriteMethod(), resultSet.getObject(findColumnName(propertyDescriptor)));
        }
        return instance;
    }

    public static PropertyDescriptor findProperty(BeanInfo beanInfo, String property) {
        return Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(propertyDescriptor -> propertyDescriptor.getName().equals(property))
                .findFirst().orElseThrow(IllegalStateException::new);
    }


    @FunctionalInterface
    public interface TransactionBlock {
        void run() throws SQLException;
    }

    private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
            int.class, "INTEGER",
            Integer.class, "INTEGER",
            long.class, "BIGINT",
            Long.class, "BIGINT",
            String.class, "VARCHAR(255)"
    );

    private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
        var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
                .flatMap(superInterface -> {
                    if (superInterface instanceof ParameterizedType parameterizedType
                            && parameterizedType.getRawType() == Repository.class) {
                        return Stream.of(parameterizedType);
                    }
                    return null;
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
        var typeArgument = repositorySupertype.getActualTypeArguments()[0];
        if (typeArgument instanceof Class<?> beanType) {
            return beanType;
        }
        throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
    }

    private static class UncheckedSQLException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 42L;

        private UncheckedSQLException(SQLException cause) {
            super(cause);
        }

        @Override
        public SQLException getCause() {
            return (SQLException) super.getCause();
        }
    }

    public static String findTableName(Class<?> repositoryType) {
        return repositoryType.getAnnotation(Table.class) != null ? repositoryType.getAnnotation(Table.class).value().toUpperCase(Locale.ROOT) : repositoryType.getSimpleName().toUpperCase(Locale.ROOT);
    }

    public static String findColumnName(PropertyDescriptor propertyDescriptor) {
        var name = propertyDescriptor.getName();
        var getter = propertyDescriptor.getReadMethod();
        if (getter == null) {
            return name;
        }
        var column = getter.getAnnotation(Column.class);
        return column != null ? column.value() : name;
    }

    public static void createTable(Class<?> beanClass) throws SQLException {
        var bean = Utils.beanInfo(beanClass);
        var connection = currentConnection();
        var prefix = "CREATE TABLE " + findTableName(beanClass) + " (";
        var query = Arrays.stream(bean.getPropertyDescriptors())
                .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
                .map(el -> getColumn(el) + generatedValue(el) + id(el))
                .collect(Collectors.joining(",\n", prefix, ");"));

        IO.println(query);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(query);
        }
        connection.commit();
    }

    private static String getColumn(PropertyDescriptor el) {
        return findColumnName(el) + " " + TYPE_MAPPING.getOrDefault(el.getPropertyType(), "VARCHAR(255)") + (el.getPropertyType().isPrimitive() ? " NOT NULL" : "");
    }

    private static String generatedValue(PropertyDescriptor propertyDescriptor) {
        var getter = propertyDescriptor.getReadMethod();
        if (getter == null) {
            return "";
        }
        return getter.isAnnotationPresent(GeneratedValue.class) ? " AUTO_INCREMENT" : "";
    }

    private static String id(PropertyDescriptor propertyDescriptor) {
        var getter = propertyDescriptor.getReadMethod();
        if (getter == null) {
            return "";
        }
        return getter.isAnnotationPresent(Id.class) ? ",\n PRIMARY KEY (" + findColumnName(propertyDescriptor) + ")" : "";
    }

    static List<Object> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor, Object... args) throws SQLException {
        Objects.requireNonNull(constructor);
        Objects.requireNonNull(sqlQuery);
        Objects.requireNonNull(beanInfo);
        var entityList = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
            if (args != null) {
                for (var i = 0; i < args.length; i++) {
                    try {
                        statement.setObject(i + 1, args[i]);
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entityList.add(toEntityClass(resultSet, beanInfo, constructor));
                }
            }
        }
        return Collections.unmodifiableList(entityList);
    }


    public static <R extends Repository<?, ?>> R createRepository(Class<R> repositoryType) {
        Objects.requireNonNull(repositoryType);
        var bean = findBeanTypeFromRepository(repositoryType);
        var tableName = findTableName(bean);
        var beanInfo = Utils.beanInfo(bean);
        var constructor = Utils.defaultConstructor(bean);
        var idProperty = findId(beanInfo);
        return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType}, (_, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        throw new UnsupportedOperationException();
                    }
                    try {
                        if (method.isAnnotationPresent(Query.class)) {
                            return findAll(currentConnection(), method.getAnnotation(Query.class).value(), beanInfo, constructor, args);
                        }
                        var name = method.getName();
                        if (name.startsWith("findBy")) {
                            var property = findProperty(beanInfo, Introspector.decapitalize(name.substring(6)));
                            return findAll(currentConnection(), "SELECT * FROM " + tableName + " WHERE " + findColumnName(property) + " = ?;",
                                    beanInfo, constructor, args).stream().findFirst();
                        }
                        return switch (name) {
                            case "findAll" -> findAll(currentConnection(), "SELECT * FROM " + tableName + ";",
                                    beanInfo, constructor);
                            case "findById" ->
                                    findAll(currentConnection(), "SELECT * FROM " + tableName + " WHERE " + findColumnName(idProperty) + " = ?;",
                                            beanInfo, constructor, args).stream().findFirst();
                            case "save" -> {
                                try {
                                    var entity = args[0];
                                    save(currentConnection(), tableName, beanInfo, entity, idProperty);
                                    yield entity;
                                } catch (SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            }
                            default -> throw new IllegalStateException("unknown method " + method.getName());
                        };
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

        ));
    }

    static void save(Connection connection, String tableName, BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) throws SQLException {
        var query = createSaveQuery(tableName, beanInfo);
        var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(el -> !el.getName().equals("class")).toList();
        if (idProperty != null) {
            try (PreparedStatement statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
                statementPrepare(bean, properties, statement);
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        Utils.invokeMethod(bean, idProperty.getWriteMethod(), generatedKeys.getObject(1, idProperty.getPropertyType()));
                    }
                }
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statementPrepare(bean, properties, statement);
            }
        }

    }

    private static void statementPrepare(Object bean, List<PropertyDescriptor> properties, PreparedStatement statement) throws SQLException {
        for (var i = 0; i < properties.size(); i++) {
            try {
                statement.setObject(i + 1, Utils.invokeMethod(bean, properties.get(i).getReadMethod()));
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
        ;
        statement.executeUpdate();
    }

    static PropertyDescriptor findId(BeanInfo beanInfo) {
        return Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(el -> el.getReadMethod().isAnnotationPresent(Id.class)).findFirst().orElse(null);
    }

    static String createSaveQuery(String tableName, BeanInfo beanInfo) {
        var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(el -> !el.getName().equals("class"))
                .toList();
        var columns = properties.stream()
                .map(ORM::findColumnName)
                .collect(Collectors.joining(", ", " (", ")"));
        var params = String.join(", ", Collections.nCopies(properties.size(), "?"));

        return "MERGE INTO " + tableName + columns + " VALUES (" + params + ");";
    }


    public static void transaction(DataSource dataSource, TransactionBlock transactionBlock) throws SQLException {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(transactionBlock);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            DATA_THREAD_LOCAL.set(connection);
            try {
                transactionBlock.run();
                connection.commit();
            } catch (SQLException sqlException) {
                connection.rollback();
                throw sqlException;
            } catch (UncheckedSQLException e) {
                connection.rollback();
                throw e.getCause();
            }

        } finally {
            DATA_THREAD_LOCAL.remove();
        }
    }
}
