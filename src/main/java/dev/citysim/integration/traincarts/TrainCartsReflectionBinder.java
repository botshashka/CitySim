package dev.citysim.integration.traincarts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates the reflective binding required to work with TrainCarts internals. The binder is
 * responsible for discovering the methods and fields used by {@link TrainCartsStationService}
 * across the range of supported plugin versions.
 */
public class TrainCartsReflectionBinder {

    private static final LegacyComponentSerializer SIGN_TEXT_SERIALIZER = LegacyComponentSerializer.legacySection();

    public interface TrainCartsBinding {
        Object getSignController() throws ReflectiveOperationException;

        Object getWorldController(Object signController, World world) throws ReflectiveOperationException;

        boolean isWorldEnabled(Object worldController) throws ReflectiveOperationException;

        Collection<?> loadSignChunks(Object worldController) throws ReflectiveOperationException;

        Object[] resolveEntries(Object chunk) throws ReflectiveOperationException;

        boolean hasSignActionEvents(Object entry) throws ReflectiveOperationException;

        Block getBlock(Object entry) throws ReflectiveOperationException;

        List<StationText> resolveStationTexts(Object entry, Block block) throws ReflectiveOperationException;
    }

    public record StationText(String header, String action) {
    }

    private final Logger logger;

    public TrainCartsReflectionBinder(Logger logger) {
        this.logger = logger;
    }

    public TrainCartsBinding bind(Plugin trainCarts) throws ReflectiveOperationException {
        Objects.requireNonNull(trainCarts, "trainCarts");

        ClassLoader loader = trainCarts.getClass().getClassLoader();
        Class<?> trainCartsClass = trainCarts.getClass();
        Method getSignControllerMethod = trainCartsClass.getMethod("getSignController");

        Class<?> signControllerClass = Class.forName(
                "com.bergerkiller.bukkit.tc.controller.global.SignController",
                false,
                loader);
        Method signControllerForWorldMethod = signControllerClass.getMethod("forWorld", World.class);

        Class<?> signControllerWorldClass = Class.forName(
                "com.bergerkiller.bukkit.tc.controller.global.SignControllerWorld",
                false,
                loader);
        Method signControllerWorldIsEnabledMethod = signControllerWorldClass.getMethod("isEnabled");

        Class<?> entryClass = Class.forName(
                "com.bergerkiller.bukkit.tc.controller.global.SignController$Entry",
                false,
                loader);

        Class<?> chunkClass = null;
        Method chunkGetEntriesMethod = null;
        try {
            chunkClass = Class.forName(
                    "com.bergerkiller.bukkit.tc.controller.global.SignControllerChunk",
                    false,
                    loader);
            chunkGetEntriesMethod = chunkClass.getMethod("getEntries");
            ensureAccessible(chunkGetEntriesMethod);
        } catch (ClassNotFoundException ignored) {
            // Some versions hide the chunk class; discovery continues via fallbacks.
        }

        Class<?> longHashMapClass = Class.forName(
                "com.bergerkiller.bukkit.common.wrappers.LongHashMap",
                false,
                loader);
        Method longHashMapValuesMethod = longHashMapClass.getMethod("values");

        ChunkIntrospector chunkIntrospector = new ChunkIntrospector(
                chunkClass,
                chunkGetEntriesMethod,
                entryClass,
                longHashMapClass,
                longHashMapValuesMethod);

        SignChunksAccessor signChunksAccessor = discoverSignChunksAccessor(
                signControllerWorldClass,
                chunkClass,
                entryClass,
                chunkIntrospector);

        Method entryGetBlockMethod = entryClass.getMethod("getBlock");
        ensureAccessible(entryGetBlockMethod);

        Method entryHasSignActionEventsMethod;
        try {
            entryHasSignActionEventsMethod = entryClass.getMethod("hasSignActionEvents");
            ensureAccessible(entryHasSignActionEventsMethod);
        } catch (NoSuchMethodException ignored) {
            entryHasSignActionEventsMethod = null;
            logFine("TrainCarts SignController$Entry has no hasSignActionEvents method; counting entries without the filter");
        }

        Class<?> railPieceClass = Class.forName(
                "com.bergerkiller.bukkit.tc.controller.components.RailPiece",
                false,
                loader);
        Method entryCreateFrontTrackedSignMethod;
        try {
            entryCreateFrontTrackedSignMethod = entryClass.getMethod("createFrontTrackedSign", railPieceClass);
            ensureAccessible(entryCreateFrontTrackedSignMethod);
        } catch (NoSuchMethodException ignored) {
            entryCreateFrontTrackedSignMethod = null;
            logFine("TrainCarts SignController$Entry has no createFrontTrackedSign method; falling back to block state text");
        }

        Method entryCreateBackTrackedSignMethod;
        try {
            entryCreateBackTrackedSignMethod = entryClass.getMethod("createBackTrackedSign", railPieceClass);
            ensureAccessible(entryCreateBackTrackedSignMethod);
        } catch (NoSuchMethodException ignored) {
            entryCreateBackTrackedSignMethod = null;
            logFine("TrainCarts SignController$Entry has no createBackTrackedSign method; falling back to block state text");
        }

        Field noneField = railPieceClass.getField("NONE");
        Object railPieceNone = noneField.get(null);

        Class<?> trackedSignClass = Class.forName(
                "com.bergerkiller.bukkit.tc.rails.RailLookup$TrackedSign",
                false,
                loader);
        Method trackedSignGetLineMethod = trackedSignClass.getMethod("getLine", int.class);

        return new ReflectionBinding(
                trainCarts,
                getSignControllerMethod,
                signControllerForWorldMethod,
                signControllerWorldIsEnabledMethod,
                signChunksAccessor,
                entryClass,
                entryGetBlockMethod,
                entryHasSignActionEventsMethod,
                entryCreateFrontTrackedSignMethod,
                entryCreateBackTrackedSignMethod,
                railPieceNone,
                trackedSignGetLineMethod,
                chunkIntrospector);
    }

    private void logFine(String message) {
        if (logger != null) {
            logger.log(Level.FINE, message);
        }
    }

    private static void ensureAccessible(AccessibleObject member) {
        if (member == null) {
            return;
        }
        if (member.trySetAccessible()) {
            return;
        }
        throw new IllegalStateException("Unable to access member via reflection: " + member);
    }

    private SignChunksAccessor discoverSignChunksAccessor(Class<?> signControllerWorldClass,
                                                           Class<?> chunkClass,
                                                           Class<?> entryClass,
                                                           ChunkIntrospector chunkIntrospector)
            throws ReflectiveOperationException {
        Field namedField = findField(signControllerWorldClass, "signChunks");
        if (namedField != null) {
            return createFieldAccessor(namedField, chunkIntrospector);
        }

        Method namedMethod = findZeroArgMethod(signControllerWorldClass, "getSignChunks");
        if (namedMethod != null) {
            return createMethodAccessor(namedMethod, chunkIntrospector);
        }

        if (chunkClass != null) {
            Field chunkField = findFieldReferencingType(signControllerWorldClass, chunkClass);
            if (chunkField != null) {
                return createFieldAccessor(chunkField, chunkIntrospector);
            }

            Method chunkMethod = findMethodReferencingType(signControllerWorldClass, chunkClass);
            if (chunkMethod != null) {
                return createMethodAccessor(chunkMethod, chunkIntrospector);
            }
        }

        SignChunksAccessor fallback = discoverFallbackAccessor(
                signControllerWorldClass,
                chunkIntrospector,
                chunkClass,
                entryClass);
        if (fallback != null) {
            return fallback;
        }

        throw new NoSuchFieldException("signChunks not found on " + signControllerWorldClass.getName());
    }

    private SignChunksAccessor discoverFallbackAccessor(Class<?> signControllerWorldClass,
                                                         ChunkIntrospector chunkIntrospector,
                                                         Class<?> chunkClass,
                                                         Class<?> entryClass) {
        List<AccessorCandidate> candidates = new ArrayList<>();

        for (Class<?> current = signControllerWorldClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                boolean referencesChunk = typeReferences(field.getGenericType(), chunkClass);
                boolean referencesEntry = typeReferences(field.getGenericType(), entryClass);
                if (!isCollectionLikeRaw(field.getType(), chunkIntrospector)
                        && !referencesChunk
                        && !referencesEntry) {
                    continue;
                }
                ensureAccessible(field);
                candidates.add(new AccessorCandidate(
                        createFieldAccessor(field, chunkIntrospector),
                        field.getDeclaringClass().getName() + "#" + field.getName()));
            }
        }

        for (Class<?> current = signControllerWorldClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || method.getReturnType() == void.class) {
                    continue;
                }
                boolean referencesChunk = typeReferences(method.getGenericReturnType(), chunkClass);
                boolean referencesEntry = typeReferences(method.getGenericReturnType(), entryClass);
                if (!isCollectionLikeRaw(method.getReturnType(), chunkIntrospector)
                        && !referencesChunk
                        && !referencesEntry) {
                    continue;
                }
                ensureAccessible(method);
                candidates.add(new AccessorCandidate(
                        createMethodAccessor(method, chunkIntrospector),
                        method.getDeclaringClass().getName() + "#" + method.getName() + "()"));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return new AdaptiveSignChunksAccessor(
                signControllerWorldClass,
                candidates,
                entryClass,
                chunkIntrospector);
    }

    private boolean isCollectionLikeRaw(Class<?> rawType, ChunkIntrospector chunkIntrospector) {
        if (rawType == null) {
            return false;
        }
        if (rawType.isArray()) {
            return true;
        }
        if (Collection.class.isAssignableFrom(rawType)
                || Iterable.class.isAssignableFrom(rawType)
                || Map.class.isAssignableFrom(rawType)) {
            return true;
        }
        return chunkIntrospector != null && chunkIntrospector.isKnownContainerType(rawType);
    }

    private SignChunksAccessor createFieldAccessor(Field field, ChunkIntrospector chunkIntrospector) {
        ensureAccessible(field);
        return worldController -> chunkIntrospector.toCollection(field.get(worldController));
    }

    private SignChunksAccessor createMethodAccessor(Method method, ChunkIntrospector chunkIntrospector) {
        ensureAccessible(method);
        return worldController -> {
            try {
                Object container = method.invoke(worldController);
                return chunkIntrospector.toCollection(container);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
        };
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                ensureAccessible(field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Method findZeroArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    ensureAccessible(method);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
            }
            current = current.getSuperclass();
        }
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() == 0) {
                ensureAccessible(method);
                return method;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private Field findFieldReferencingType(Class<?> type, Class<?> referencedClass) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (typeReferences(field.getGenericType(), referencedClass)) {
                    ensureAccessible(field);
                    return field;
                }
            }
        }
        return null;
    }

    private Method findMethodReferencingType(Class<?> type, Class<?> referencedClass) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && typeReferences(method.getGenericReturnType(), referencedClass)) {
                    ensureAccessible(method);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getParameterCount() == 0 && typeReferences(method.getGenericReturnType(), referencedClass)) {
                ensureAccessible(method);
                return method;
            }
        }
        return null;
    }

    private static boolean typeReferences(Type type, Class<?> referencedClass) {
        if (type == null || referencedClass == null) {
            return false;
        }
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                return typeReferences(clazz.getComponentType(), referencedClass);
            }
            return referencedClass.isAssignableFrom(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (typeReferences(parameterized.getRawType(), referencedClass)) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (typeReferences(argument, referencedClass)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type upper : wildcard.getUpperBounds()) {
                if (typeReferences(upper, referencedClass)) {
                    return true;
                }
            }
            for (Type lower : wildcard.getLowerBounds()) {
                if (typeReferences(lower, referencedClass)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType arrayType) {
            return typeReferences(arrayType.getGenericComponentType(), referencedClass);
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                if (typeReferences(bound, referencedClass)) {
                    return true;
                }
            }
            return false;
        }
        String name = type.getTypeName();
        return name != null && name.equals(referencedClass.getName());
    }

    private static final class AccessorCandidate {
        private final SignChunksAccessor accessor;
        private final String description;

        private AccessorCandidate(SignChunksAccessor accessor, String description) {
            this.accessor = accessor;
            this.description = description;
        }
    }

    private interface SignChunksAccessor {
        Collection<?> load(Object worldController) throws ReflectiveOperationException;
    }

    private static final class AdaptiveSignChunksAccessor implements SignChunksAccessor {
        private final Class<?> worldClass;
        private final Class<?> entryType;
        private final ChunkIntrospector chunkIntrospector;
        private List<AccessorCandidate> pending;
        private SignChunksAccessor delegate;

        private AdaptiveSignChunksAccessor(Class<?> worldClass,
                                           List<AccessorCandidate> candidates,
                                           Class<?> entryType,
                                           ChunkIntrospector chunkIntrospector) {
            this.worldClass = worldClass;
            this.entryType = entryType;
            this.chunkIntrospector = chunkIntrospector;
            this.pending = new ArrayList<>(candidates);
        }

        @Override
        public synchronized Collection<?> load(Object worldController) throws ReflectiveOperationException {
            if (delegate != null) {
                return delegate.load(worldController);
            }
            if (worldController == null) {
                return null;
            }

            List<AccessorCandidate> retry = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            for (AccessorCandidate candidate : pending) {
                Collection<?> values;
                try {
                    values = candidate.accessor.load(worldController);
                } catch (ReflectiveOperationException ex) {
                    failures.add(candidate.description + " threw " + ex.getClass().getSimpleName()
                            + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
                    continue;
                }

                if (values == null || values.isEmpty()) {
                    retry.add(candidate);
                    continue;
                }

                if (containsEntry(values)) {
                    delegate = candidate.accessor;
                    pending = null;
                    return delegate.load(worldController);
                }

                failures.add(candidate.description + " did not expose SignController$Entry instances");
            }

            pending = retry;
            if (!retry.isEmpty()) {
                return null;
            }

            StringBuilder message = new StringBuilder("Unable to locate sign chunk collection on ")
                    .append(worldClass.getName());
            if (!failures.isEmpty()) {
                message.append(" (attempted: ");
                for (int i = 0; i < failures.size(); i++) {
                    if (i > 0) {
                        message.append("; ");
                    }
                    message.append(failures.get(i));
                }
                message.append(')');
            }
            throw new NoSuchFieldException(message.toString());
        }

        private boolean containsEntry(Collection<?> chunks) throws ReflectiveOperationException {
            for (Object chunk : chunks) {
                if (chunk == null) {
                    continue;
                }
                Object[] entries = chunkIntrospector.toEntries(chunk);
                if ((entries == null || entries.length == 0)) {
                    Collection<?> fallback = chunkIntrospector.toCollection(chunk);
                    if (fallback != null && !fallback.isEmpty()) {
                        entries = fallback.toArray();
                    }
                }
                if (entries == null || entries.length == 0) {
                    continue;
                }
                for (Object entry : entries) {
                    if (entryType.isInstance(entry)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class ChunkIntrospector {
        private final Class<?> chunkClass;
        private final Method chunkGetEntriesMethod;
        private final Class<?> entryClass;
        private final Class<?> longHashMapClass;
        private final Method longHashMapValuesMethod;
        private final Map<Class<?>, Optional<Method>> chunkEntriesMethodCache = new HashMap<>();
        private final Map<Class<?>, Optional<Method>> valuesMethodCache = new HashMap<>();

        private ChunkIntrospector(Class<?> chunkClass,
                                  Method chunkGetEntriesMethod,
                                  Class<?> entryClass,
                                  Class<?> longHashMapClass,
                                  Method longHashMapValuesMethod) {
            this.chunkClass = chunkClass;
            this.chunkGetEntriesMethod = chunkGetEntriesMethod;
            this.entryClass = entryClass;
            this.longHashMapClass = longHashMapClass;
            this.longHashMapValuesMethod = longHashMapValuesMethod;
        }

        boolean isKnownContainerType(Class<?> rawType) {
            if (rawType == null) {
                return false;
            }
            if (longHashMapClass != null && longHashMapClass.isAssignableFrom(rawType)) {
                return true;
            }
            return chunkClass != null
                    && (rawType.isAssignableFrom(chunkClass) || chunkClass.isAssignableFrom(rawType));
        }

        Collection<?> toCollection(Object container) throws ReflectiveOperationException {
            if (container == null) {
                return null;
            }
            if (container instanceof Collection<?> collection) {
                return collection;
            }
            if (container instanceof Map<?, ?> map) {
                return map.values();
            }
            if (container instanceof Iterable<?> iterable) {
                List<Object> values = new ArrayList<>();
                for (Object value : iterable) {
                    values.add(value);
                }
                return values;
            }
            Class<?> containerClass = container.getClass();
            if (containerClass.isArray()) {
                int length = Array.getLength(container);
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(Array.get(container, i));
                }
                return values;
            }
            if (longHashMapClass.isInstance(container)) {
                Object nested = longHashMapValuesMethod.invoke(container);
                return toCollection(nested);
            }
            Method valuesMethod = findValuesLikeMethod(containerClass);
            if (valuesMethod != null) {
                Object nested = invoke(valuesMethod, container);
                return toCollection(nested);
            }
            return null;
        }

        Object[] toEntries(Object chunk) throws ReflectiveOperationException {
            if (chunk == null) {
                return null;
            }
            if (chunk instanceof Object[] array) {
                return array;
            }
            if (chunk instanceof Collection<?> collection) {
                return collection.toArray();
            }
            if (chunk instanceof Map<?, ?> map) {
                Collection<?> values = map.values();
                return values.toArray();
            }
            if (chunk instanceof Iterable<?> iterable) {
                List<Object> values = new ArrayList<>();
                for (Object value : iterable) {
                    values.add(value);
                }
                return values.toArray();
            }
            Class<?> chunkType = chunk.getClass();
            if (chunkType.isArray()) {
                int length = Array.getLength(chunk);
                Object[] values = new Object[length];
                for (int i = 0; i < length; i++) {
                    values[i] = Array.get(chunk, i);
                }
                return values;
            }
            if (chunkGetEntriesMethod != null && chunkGetEntriesMethod.getDeclaringClass().isInstance(chunk)) {
                Object result = invoke(chunkGetEntriesMethod, chunk);
                return convertEntriesResult(result);
            }
            Method method = findChunkEntriesMethod(chunkType);
            if (method == null) {
                return null;
            }
            Object value = invoke(method, chunk);
            return convertEntriesResult(value);
        }

        private Method findChunkEntriesMethod(Class<?> type) {
            Optional<Method> cached = chunkEntriesMethodCache.get(type);
            if (cached != null) {
                return cached.orElse(null);
            }
            Method located = locateChunkEntriesMethod(type);
            chunkEntriesMethodCache.put(type, Optional.ofNullable(located));
            return located;
        }

        private Method locateChunkEntriesMethod(Class<?> type) {
            Method namedGetEntries = findZeroArgMethod(type, "getEntries");
            if (namedGetEntries != null && isEntriesCandidate(namedGetEntries)) {
                return namedGetEntries;
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (isEntriesCandidate(method)) {
                    ensureAccessible(method);
                    return method;
                }
            }
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (isEntriesCandidate(method)) {
                        ensureAccessible(method);
                        return method;
                    }
                }
            }
            return null;
        }

        private boolean isEntriesCandidate(Method method) {
            if (method == null || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                return false;
            }
            if (typeReferences(method.getGenericReturnType(), entryClass)) {
                return true;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.contains("entries") || name.contains("signs") || name.contains("values")) {
                Class<?> raw = method.getReturnType();
                if (raw.isArray()) {
                    return true;
                }
                return Collection.class.isAssignableFrom(raw)
                        || Iterable.class.isAssignableFrom(raw)
                        || Map.class.isAssignableFrom(raw);
            }
            return false;
        }

        private Object invoke(Method method, Object target) throws ReflectiveOperationException {
            try {
                return method.invoke(target);
            } catch (IllegalAccessException ex) {
                throw new ReflectiveOperationException(ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
        }

        private Object[] convertEntriesResult(Object value) throws ReflectiveOperationException {
            if (value == null) {
                return null;
            }
            if (value instanceof Object[] array) {
                return array;
            }
            if (value instanceof Collection<?> collection) {
                return collection.toArray();
            }
            if (value instanceof Map<?, ?> map) {
                Collection<?> values = map.values();
                return values.toArray();
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> values = new ArrayList<>();
                for (Object element : iterable) {
                    values.add(element);
                }
                return values.toArray();
            }
            Class<?> valueClass = value.getClass();
            if (valueClass.isArray()) {
                int length = Array.getLength(value);
                Object[] array = new Object[length];
                for (int i = 0; i < length; i++) {
                    array[i] = Array.get(value, i);
                }
                return array;
            }
            if (longHashMapClass.isInstance(value)) {
                Object nested = longHashMapValuesMethod.invoke(value);
                return convertEntriesResult(nested);
            }
            Method valuesMethod = findValuesLikeMethod(valueClass);
            if (valuesMethod != null) {
                Object nested = invoke(valuesMethod, value);
                return convertEntriesResult(nested);
            }
            return null;
        }

        private Method findValuesLikeMethod(Class<?> type) {
            Optional<Method> cached = valuesMethodCache.get(type);
            if (cached != null) {
                return cached.orElse(null);
            }
            Method located = locateValuesLikeMethod(type);
            valuesMethodCache.put(type, Optional.ofNullable(located));
            return located;
        }

        private Method locateValuesLikeMethod(Class<?> type) {
            Method namedValues = findZeroArgMethod(type, "values");
            if (namedValues != null && isValuesCandidate(namedValues)) {
                return namedValues;
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (isValuesCandidate(method)) {
                    ensureAccessible(method);
                    return method;
                }
            }
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (isValuesCandidate(method)) {
                        ensureAccessible(method);
                        return method;
                    }
                }
            }
            return null;
        }

        private boolean isValuesCandidate(Method method) {
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                return false;
            }
            if (typeReferences(method.getGenericReturnType(), chunkClass)) {
                return true;
            }
            Type genericType = method.getGenericReturnType();
            if (genericType instanceof Class<?> raw && raw.getTypeParameters().length == 0) {
                return Collection.class.isAssignableFrom(raw)
                        || Iterable.class.isAssignableFrom(raw)
                        || Map.class.isAssignableFrom(raw)
                        || raw.isArray();
            }
            return false;
        }

        private Method findZeroArgMethod(Class<?> type, String name) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Method method = current.getDeclaredMethod(name);
                    if (method.getParameterCount() == 0) {
                        ensureAccessible(method);
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {
                }
                current = current.getSuperclass();
            }
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0) {
                    ensureAccessible(method);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
            }
            return null;
        }
    }

    private static final class ReflectionBinding implements TrainCartsBinding {
        private final Plugin trainCarts;
        private final Method getSignControllerMethod;
        private final Method signControllerForWorldMethod;
        private final Method signControllerWorldIsEnabledMethod;
        private final SignChunksAccessor signChunksAccessor;
        private final Class<?> entryClass;
        private final Method entryGetBlockMethod;
        private final Method entryHasSignActionEventsMethod;
        private final Method entryCreateFrontTrackedSignMethod;
        private final Method entryCreateBackTrackedSignMethod;
        private final Object railPieceNone;
        private final Method trackedSignGetLineMethod;
        private final ChunkIntrospector chunkIntrospector;

        private ReflectionBinding(Plugin trainCarts,
                                  Method getSignControllerMethod,
                                  Method signControllerForWorldMethod,
                                  Method signControllerWorldIsEnabledMethod,
                                  SignChunksAccessor signChunksAccessor,
                                  Class<?> entryClass,
                                  Method entryGetBlockMethod,
                                  Method entryHasSignActionEventsMethod,
                                  Method entryCreateFrontTrackedSignMethod,
                                  Method entryCreateBackTrackedSignMethod,
                                  Object railPieceNone,
                                  Method trackedSignGetLineMethod,
                                  ChunkIntrospector chunkIntrospector) {
            this.trainCarts = trainCarts;
            this.getSignControllerMethod = getSignControllerMethod;
            this.signControllerForWorldMethod = signControllerForWorldMethod;
            this.signControllerWorldIsEnabledMethod = signControllerWorldIsEnabledMethod;
            this.signChunksAccessor = signChunksAccessor;
            this.entryClass = entryClass;
            this.entryGetBlockMethod = entryGetBlockMethod;
            this.entryHasSignActionEventsMethod = entryHasSignActionEventsMethod;
            this.entryCreateFrontTrackedSignMethod = entryCreateFrontTrackedSignMethod;
            this.entryCreateBackTrackedSignMethod = entryCreateBackTrackedSignMethod;
            this.railPieceNone = railPieceNone;
            this.trackedSignGetLineMethod = trackedSignGetLineMethod;
            this.chunkIntrospector = chunkIntrospector;
        }

        @Override
        public Object getSignController() throws ReflectiveOperationException {
            return invoke(trainCarts, getSignControllerMethod);
        }

        @Override
        public Object getWorldController(Object signController, World world) throws ReflectiveOperationException {
            if (signController == null || world == null) {
                return null;
            }
            return invoke(signController, signControllerForWorldMethod, world);
        }

        @Override
        public boolean isWorldEnabled(Object worldController) throws ReflectiveOperationException {
            if (worldController == null) {
                return false;
            }
            Object result = invoke(worldController, signControllerWorldIsEnabledMethod);
            return Boolean.TRUE.equals(result);
        }

        @Override
        public Collection<?> loadSignChunks(Object worldController) throws ReflectiveOperationException {
            if (signChunksAccessor == null) {
                return null;
            }
            return signChunksAccessor.load(worldController);
        }

        @Override
        public Object[] resolveEntries(Object chunk) throws ReflectiveOperationException {
            return chunkIntrospector.toEntries(chunk);
        }

        @Override
        public boolean hasSignActionEvents(Object entry) throws ReflectiveOperationException {
            if (entryHasSignActionEventsMethod == null) {
                return true;
            }
            Object value = invoke(entry, entryHasSignActionEventsMethod);
            return Boolean.TRUE.equals(value);
        }

        @Override
        public Block getBlock(Object entry) throws ReflectiveOperationException {
            Object block = invoke(entry, entryGetBlockMethod);
            if (block instanceof Block b) {
                return b;
            }
            return null;
        }

        @Override
        public List<StationText> resolveStationTexts(Object entry, Block block) throws ReflectiveOperationException {
            List<StationText> texts = new ArrayList<>(3);
            addTrackedSignText(texts, entry, entryCreateFrontTrackedSignMethod);
            addTrackedSignText(texts, entry, entryCreateBackTrackedSignMethod);
            StationText fromBlock = resolveBlockText(block);
            if (fromBlock != null) {
                texts.add(fromBlock);
            }
            return texts;
        }

        private void addTrackedSignText(List<StationText> texts, Object entry, Method factory)
                throws ReflectiveOperationException {
            if (factory == null) {
                return;
            }
            Object trackedSign;
            try {
                trackedSign = factory.invoke(entry, railPieceNone);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
            StationText text = resolveTrackedSignText(trackedSign);
            if (text != null) {
                texts.add(text);
            }
        }

        private StationText resolveTrackedSignText(Object trackedSign) throws ReflectiveOperationException {
            if (trackedSign == null) {
                return null;
            }
            String header = readTrackedSignLine(trackedSign, 0);
            String action = readTrackedSignLine(trackedSign, 1);
            if (header == null && action == null) {
                return null;
            }
            return new StationText(header, action);
        }

        private String readTrackedSignLine(Object trackedSign, int index) throws ReflectiveOperationException {
            try {
                Object value = trackedSignGetLineMethod.invoke(trackedSign, index);
                if (value instanceof String str) {
                    return str;
                }
                return value != null ? value.toString() : null;
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
        }

        private StationText resolveBlockText(Block block) {
            if (block == null) {
                return null;
            }
            Object state = block.getState();
            if (!(state instanceof Sign sign)) {
                return null;
            }
            String header = safeGetLine(sign, 0);
            String action = safeGetLine(sign, 1);
            if (header == null && action == null) {
                return null;
            }
            return new StationText(header, action);
        }

        private String safeGetLine(Sign sign, int index) {
            try {
                SignSide front = sign.getSide(Side.FRONT);
                Component line = front.line(index);
                if (line == null) {
                    return null;
                }
                return SIGN_TEXT_SERIALIZER.serialize(line);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object invoke(Object target, Method method, Object... args) throws ReflectiveOperationException {
            try {
                return method.invoke(target, args);
            } catch (IllegalAccessException ex) {
                throw new ReflectiveOperationException(ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
        }
    }
}
