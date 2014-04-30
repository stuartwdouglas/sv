package org.jboss.sv;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Version;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Serialized view is way of expressing an objects serialized JSON representation.
 * <p/>
 * Serialisation happens eagerly to avoid hibernate issues with lazy loading.
 *
 * @author Stuart Douglas
 */
public class SerializedView<T> {

    private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {

        @Override
        protected DateFormat initialValue() {
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            return df;
        }
    };
    private static final String ID = "id";

    private final Class<T> entityClass;
    private final boolean defaultExclude;
    private final boolean forceEntityPropagation;
    private final boolean detach;
    private final Method createMethod;
    private final Set<String> exclude = new LinkedHashSet<String>();
    private final Set<String> include = new LinkedHashSet<String>();
    private final Set<String> allSimple = new LinkedHashSet<String>();
    private final Map<String, SerializedView> includeCustom = new LinkedHashMap<String, SerializedView>();
    private final Map<String, SerializedView> includeAdditional = new LinkedHashMap<String, SerializedView>();
    private final Map<String, Method> getters = new LinkedHashMap<String, Method>();
    private final Map<String, Method> setters = new LinkedHashMap<String, Method>();
    private final boolean entity;
    private final EntityManagerProvider entityManagerProvider;

    private SerializedView(Builder<T> builder) {

        this.entityClass = builder.entityClass;
        this.forceEntityPropagation = builder.forceEntityPropagation;
        this.exclude.addAll(builder.exclude);
        this.include.addAll(builder.include);
        this.allSimple.addAll(builder.allSimple);
        this.defaultExclude = builder.defaultExclude;
        this.getters.putAll(builder.getters);
        this.setters.putAll(builder.setters);
        this.detach = builder.detach;
        this.createMethod = builder.createMethod;
        for (Map.Entry<String, Builder> entry : builder.includeCustom.entrySet()) {
            includeCustom.put(entry.getKey(), entry.getValue().build());
        }

        for (Map.Entry<String, Builder> entry : builder.includeAdditional.entrySet()) {
            includeAdditional.put(entry.getKey(), entry.getValue().build());
        }
        entity = entityClass.isAnnotationPresent(Entity.class);
        EntityManagerProvider provider = null;
        Iterator<EntityManagerProvider> providers = ServiceLoader.load(EntityManagerProvider.class, entityClass.getClassLoader()).iterator();
        if(providers.hasNext()) {
           provider = providers.next();
        }
        this.entityManagerProvider = provider;
    }

    public List<T> deserialize(JsonArray json) {
        return deserialize(json, true);
    }

    private List<T> deserialize(JsonArray json, boolean root) {
        List<T> ret = new ArrayList<T>(json.size());
        for (JsonValue item : json) {
            ret.add(deserialize((JsonObject) item, root));
        }
        return ret;
    }

    public T deserialize(JsonObject json) {
        return deserialize(json, true);
    }


    public T deserialize(String json) {
        JsonObject parsed = Json.createReader(new StringReader(json)).readObject();
        return deserialize(parsed);
    }

    public List<T> deserializeList(String json) {
        JsonArray parsed = Json.createReader(new StringReader(json)).readArray();
        return deserialize(parsed);
    }

    private T deserialize(JsonObject json, boolean root) {

        try {
            T object;
            if (!entity) {
                if(createMethod != null) {
                    object = (T) createMethod.invoke(null, json);
                } else {
                    object = entityClass.newInstance();
                }
            } else {
                EntityManager em = entityManagerProvider.getEntityManager();
                if (em == null || !json.keySet().contains(ID) || json.isNull(ID)) {
                    object = entityClass.newInstance();
                } else {
                    object = em.find(entityClass, json.getInt(ID));
                    if (object == null) {
                        throw new RuntimeException(entityClass + "entity with id " + json.get(ID) + " not found");
                    }
                    if(detach) {
                        em.detach(object);
                    }
                    if (!root && !forceEntityPropagation) {
                        //we just load the object, we don't allow the user to change it
                        return object;
                    }
                }
            }
            if (defaultExclude) {
                for (String field : include) {
                    if (json.keySet().contains(field) && !json.isNull(field)) {
                        Method setMethod = setters.get(field);
                        if (setMethod != null) {
                            setMethod.invoke(object, coerceToSimpleType(json.get(field), setMethod.getParameterTypes()[0]));
                        }
                    }
                }
            } else {
                for (String field : allSimple) {
                    if (exclude.contains(field)) {
                        continue;
                    }
                    if (json.keySet().contains(field) && !json.isNull(field)) {
                        Method setMethod = setters.get(field);
                        if (setMethod != null) {
                            setMethod.invoke(object, coerceToSimpleType(json.get(field), setMethod.getParameterTypes()[0]));
                        }
                    }
                }
            }

            for (Map.Entry<String, SerializedView> entry : includeCustom.entrySet()) {
                if (json.keySet().contains(entry.getKey()) && !json.isNull(entry.getKey())) {
                    Method setter = setters.get(entry.getKey());
                    if (setter != null) {
                        if (List.class.isAssignableFrom(setter.getParameterTypes()[0])) {
                            setter.invoke(object, entry.getValue().deserialize(json.getJsonArray(entry.getKey()), false));
                        } else {
                            setter.invoke(object, entry.getValue().deserialize(json.getJsonObject(entry.getKey()), false));
                        }
                    }
                }
            }
            return object;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object coerceToSimpleType(JsonValue jsonValue, Class<?> target) {
        if (jsonValue instanceof JsonNumber) {
            if (target == Byte.class || target == byte.class) {
                return (byte) ((JsonNumber) jsonValue).intValue();
            } else if (target == Short.class || target == short.class) {
                return (short) ((JsonNumber) jsonValue).intValue();
            } else if (target == Integer.class || target == int.class) {
                return ((JsonNumber) jsonValue).intValue();
            } else if (target == Long.class || target == long.class) {
                return ((JsonNumber) jsonValue).longValue();
            } else if (target == Float.class || target == float.class) {
                return (float) ((JsonNumber) jsonValue).doubleValue();
            } else if (target == Double.class || target == double.class) {
                return (float) ((JsonNumber) jsonValue).doubleValue();
            } else if (target == BigDecimal.class || target == BigDecimal.class) {
                return ((JsonNumber) jsonValue).bigDecimalValue();
            }
        } else if (jsonValue instanceof JsonString) {
            String jsonString = ((JsonString) jsonValue).getChars().toString();
            if (target == Byte.class || target == byte.class) {
                return (byte) Integer.parseInt(jsonString);
            } else if (target == Short.class || target == short.class) {
                return (short) Integer.parseInt(jsonString);
            } else if (target == Integer.class || target == int.class) {
                return Integer.parseInt(jsonString);
            } else if (target == Long.class || target == long.class) {
                return Long.parseLong(jsonString);
            } else if (target == Float.class || target == float.class) {
                return Float.parseFloat(jsonString);
            } else if (target == Double.class || target == double.class) {
                return Double.parseDouble(jsonString);
            } else if (target == BigDecimal.class || target == BigDecimal.class) {
                return new BigDecimal(jsonString);
            } else if (target == Date.class) {
                try {
                    return DATE_FORMAT.get().parse(jsonString);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            } else if (target.isEnum()) {
                return Enum.valueOf((Class) target, jsonString);
            } else if (target == String.class) {
                return jsonString;
            } else if (target == Boolean.class || target == boolean.class) {
                return Boolean.parseBoolean(jsonString);
            }
        } else if (jsonValue.getValueType() == JsonValue.ValueType.TRUE) {
            return true;
        } else if (jsonValue.getValueType() == JsonValue.ValueType.FALSE) {
            return false;
        }
        throw new RuntimeException("Could not convert " + jsonValue + " of type " + jsonValue.getValueType() + " to " + target);
    }

    public String serialize(final T object) {
        return serialize(object, Collections.<String, Object>emptyMap());
    }

    public String serialize(final T object, final Map<String, Object> additional) {
        JsonObjectBuilder builder = serializeToJson(object, additional);
        JsonObject jsonObject = builder.build();
        return jsonObject.toString();
    }

    JsonObjectBuilder serializeToJson(Object object, final Map<String, Object> additional) {
        try {
            JsonObjectBuilder builder = Json.createObjectBuilder();
            if (defaultExclude) {
                for (String field : include) {
                    Method getter = getters.get(field);
                    if (getter != null) {
                        Object value = getter.invoke(object);
                        serializeSimple(builder, field, value);
                    }
                }
            } else {
                for (String field : allSimple) {
                    if (exclude.contains(field)) {
                        continue;
                    }
                    Method getter = getters.get(field);
                    if (getter != null) {
                        Object value = getter.invoke(object);
                        if (value != null) {
                            serializeSimple(builder, field, value);
                        }
                    }
                }
            }
            for (Map.Entry<String, SerializedView> entry : includeCustom.entrySet()) {

                Method getter = getters.get(entry.getKey());
                if (getter != null) {
                    Object value = getter.invoke(object);
                    serializeFieldValue(builder, entry.getKey(), entry.getValue(), value);
                }
            }
            for (Map.Entry<String, Object> entry : additional.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof JsonValue) {
                    builder.add(entry.getKey(), (JsonValue) value);
                } else {
                    SerializedView type = includeAdditional.get(entry.getKey());
                    if (type == null) {
                        throw new RuntimeException("Unknown additional JSON value " + entry.getKey());
                    }
                    serializeFieldValue(builder, entry.getKey(), type, value);
                }
            }

            return builder;
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void serializeFieldValue(JsonObjectBuilder builder, String fieldName, SerializedView<?> view, Object value) {
        if (value != null) {
            if (isPrimitiveOrDateOrWrapper(value.getClass())) {
                serializeSimple(builder, fieldName, value);
            } else if (value instanceof List) {
                builder.add(fieldName, view.serializeListToJson((List) value));
            } else if (value instanceof Map) {
                builder.add(fieldName, view.serializeMapToJson((Map) value));
            } else {
                builder.add(fieldName, view.serializeToJson(value, Collections.<String, Object>emptyMap()));
            }
        }
    }

    private static void serializeSimple(JsonObjectBuilder builder, String field, Object value) {
        if (value instanceof Integer) {
            builder.add(field, (Integer) value);
        } else if (value instanceof Long) {
            builder.add(field, (Long) value);
        } else if (value instanceof BigDecimal) {
            builder.add(field, (BigDecimal) value);
        } else if (value instanceof Date) {
            builder.add(field, DATE_FORMAT.get().format(value));
        } else if (value instanceof Boolean) {
            builder.add(field, (Boolean) value);
        } else if (value != null) {
            builder.add(field, value.toString());
        }
    }

    private JsonArrayBuilder serializeListToJson(List<?> values) {
        JsonArrayBuilder builder = Json.createArrayBuilder();
        for (Object value : values) {
            //bit of a hack
            if (value instanceof String) {
                builder.add(value.toString());
            } else {
                builder.add(serializeToJson(value, Collections.<String, Object>emptyMap()));
            }
        }
        return builder;
    }

    public String serializeList(final List<T> values) {
        JsonArrayBuilder array = serializeListToJson(values);
        JsonArray jsonObject = array.build();
        return jsonObject.toString();
    }


    private JsonObjectBuilder serializeMapToJson(Map<?, ?> values) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (Map.Entry<?, ?> value : values.entrySet()) {
            serializeFieldValue(builder, value.getKey().toString(), this, value.getValue());
        }
        return builder;
    }

    public String serializeMap(final Map<String, T> values) {
        JsonObjectBuilder map = serializeMapToJson(values);
        return map.build().toString();
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public static <T> Builder<T> builder(Class<T> entityClass) {
        return new Builder<T>(entityClass);
    }


    public static final class Builder<T> {

        private final Class<T> entityClass;
        private boolean defaultExclude;
        private boolean forceEntityPropagation;
        private boolean detach = true;
        private  Method createMethod;
        private final Set<String> exclude = new LinkedHashSet<String>();
        private final Set<String> include = new LinkedHashSet<String>();
        private final Set<String> allSimple = new LinkedHashSet<String>();
        private final Map<String, Builder> includeCustom = new LinkedHashMap<String, Builder>();
        private final Map<String, Builder> includeAdditional = new LinkedHashMap<String, Builder>();
        private final Map<String, Method> getters = new LinkedHashMap<String, Method>();
        private final Map<String, Method> setters = new LinkedHashMap<String, Method>();

        Builder(Class<T> entityClass) {
            this.entityClass = entityClass;
            try {
                Method m  = entityClass.getDeclaredMethod("deserialize", JsonObject.class);
                if(Modifier.isStatic(m.getModifiers())) {
                    createMethod = m;
                }
            } catch (Exception e) {

            }
            for (Method m : entityClass.getMethods()) {
                if (m.getParameterTypes().length == 0 && m.getName().equals("getClass")) {
                    continue; //ignore
                }
                String methodName = m.getName();
                if (m.getParameterTypes().length == 0 && methodName.startsWith("get") && methodName.length() > 3) {
                    String name = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                    getters.put(name, m);
                    if (isPrimitiveOrDateOrWrapper(m.getReturnType())) {
                        allSimple.add(name);
                    }
                } else if (m.getReturnType() == boolean.class && m.getParameterTypes().length == 0 && methodName.startsWith("is") && methodName.length() > 2) {
                    String name = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
                    getters.put(name, m);
                    allSimple.add(name);
                } else if (m.getParameterTypes().length == 1 && methodName.startsWith("set") && methodName.length() > 3) {
                    String name = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                    setters.put(name, m);
                    if (isPrimitiveOrDateOrWrapper(m.getParameterTypes()[0])) {
                        allSimple.add(name);
                    }
                }
            }
            for (String simple : allSimple) {
                Method setter = setters.get(simple);
                Method getter = getters.get(simple);
                if (getter != null) {
                    if (shouldSkipGetter(getter, setter)) {
                        getters.remove(simple);
                    }
                }
                if (setter != null) {
                    if (shouldSkipSetter(getter, setter)) {
                        setters.remove(simple);
                    }
                }
            }
        }

        /**
         * All primitive fields are serialized by default. If this is set to true
         * then they are not.
         *
         * @return this
         */
        public Builder<T> defaultExclude() {
            this.defaultExclude = true;
            return this;
        }

        /**
         * Only relevant for views that are being used to deserialize a field of an entity type.
         * <p/>
         * By default the entity will be loaded, but no further deserialization will occur, to prevent
         * a malicious user changing the value of an entity field for an entity that was only present as
         * part of a selection.
         *
         * @return this
         */
        public Builder<T> forceEntityPropagation() {
            this.forceEntityPropagation = true;
            return this;
        }

        /**
         * Excludes some fields from serialization.
         *
         * @param fields The fields
         * @return this
         */
        public Builder<T> exclude(final String... fields) {
            for (String field : fields) {
                if (!getters.containsKey(field) && !setters.containsKey(field)) {
                    throw new IllegalArgumentException("No property " + field);
                }
                exclude.add(field);
            }
            return this;
        }

        /**
         * Includes a simple field. This is only relevant if {@link #defaultExclude()} has been called,
         * as simple fields are serialized by default.
         *
         * @param fields The fields to serialize
         * @return this
         */
        public Builder<T> include(final String... fields) {
            for (String field : fields) {
                if (!getters.containsKey(field) && !setters.containsKey(field)) {
                    throw new IllegalArgumentException("No property " + field);
                }
                if (getters.containsKey(field)) {
                    if (!isPrimitiveOrDateOrWrapper(getters.get(field).getReturnType())) {
                        throw new IllegalArgumentException("Property " + field + " is not a simple type");
                    }
                }
                include.add(field);
            }
            return this;
        }

        /**
         * Includes an additional value. This must be passed in the additional values map.
         * <p/>
         * This allows related data that is needed for a view to be passed to the front end without
         * having to modify the underlying entity to fit the view.
         *
         * @param field          The map key of the additional data
         * @param representation The JSON representation of the additional data
         * @return this
         */
        public Builder<T> includeAdditional(final String field, Builder representation) {
            includeAdditional.put(field, representation);
            return this;
        }

        /**
         * Includes a complex field.
         *
         * @param field          The field to include
         * @param representation The Serialized representation
         * @return this
         */
        public Builder<T> include(final String field, Builder representation) {
            if (!getters.containsKey(field) && !setters.containsKey(field)) {
                throw new IllegalArgumentException("No property " + field);
            }
            includeCustom.put(field, representation);
            return this;
        }

        public Builder<T> setDetach(boolean detach) {
            this.detach = detach;
            return this;
        }

        public SerializedView<T> build() {
            return new SerializedView<T>(this);
        }

    }

    private static boolean isPrimitiveOrDateOrWrapper(final Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return true;
        }
        if (clazz == String.class ||
                clazz == Boolean.class ||
                clazz == Integer.class ||
                clazz == Character.class ||
                clazz == Byte.class ||
                clazz == Short.class ||
                clazz == Long.class ||
                clazz == Float.class ||
                clazz == Double.class ||
                clazz == BigDecimal.class) {
            return true;
        }
        if (clazz.isEnum()) {
            return true;
        }
        if (Date.class.isAssignableFrom(clazz)) {
            return true;
        }
        return false;
    }

    private static boolean shouldSkipGetter(Method getter, Method setter) {
        return false;
    }

    private static boolean shouldSkipSetter(Method getter, Method settter) {
        if (getter != null) {
            if (getter.isAnnotationPresent(Version.class)) {
                return true;
            }
        }
        return false;
    }
}
