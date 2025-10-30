package dev.citysim.integration.traincarts;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.StationCountResult;
import dev.citysim.stats.StationCounter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

public class TrainCartsStationService implements StationCounter {
    private interface SignChunksAccessor {
        Collection<?> load(Object worldController) throws ReflectiveOperationException;
    }

    private final JavaPlugin plugin;
    private final Plugin trainCarts;
    private final Method getSignControllerMethod;
    private final Method signControllerForWorldMethod;
    private final Method signControllerWorldIsEnabledMethod;
    private final SignChunksAccessor signChunksAccessor;
    private final Class<?> longHashMapClass;
    private final Method longHashMapValuesMethod;
    private final Map<Class<?>, Optional<Method>> valuesMethodCache = new HashMap<>();
    private final Map<Class<?>, Optional<Method>> chunkEntriesMethodCache = new HashMap<>();
    private final Method chunkGetEntriesMethod;
    private final Class<?> signControllerChunkClass;
    private final Class<?> entryClass;
    private final Method entryGetBlockMethod;
    private final Method entryHasSignActionEventsMethod;
    private final Method entryCreateFrontTrackedSignMethod;
    private final Method entryCreateBackTrackedSignMethod;
    private final Object railPieceNone;
    private final Method trackedSignGetLineMethod;

    private boolean failureLogged;

    public TrainCartsStationService(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Plugin found = findTrainCartsPlugin(plugin);
        if (found == null) {
            throw new IllegalStateException("TrainCarts plugin not found");
        }
        this.trainCarts = found;

        ClassLoader loader = found.getClass().getClassLoader();
        Class<?> trainCartsClass = found.getClass();
        this.getSignControllerMethod = trainCartsClass.getMethod("getSignController");

        Class<?> signControllerClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignController", false, loader);
        this.signControllerForWorldMethod = signControllerClass.getMethod("forWorld", World.class);

        Class<?> signControllerWorldClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignControllerWorld", false, loader);
        this.signControllerWorldIsEnabledMethod = signControllerWorldClass.getMethod("isEnabled");

        this.entryClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignController$Entry", false, loader);

        Class<?> chunkClass = null;
        Method getEntriesMethod = null;
        try {
            chunkClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignControllerChunk", false, loader);
            getEntriesMethod = chunkClass.getMethod("getEntries");
            getEntriesMethod.setAccessible(true);
        } catch (ClassNotFoundException ignored) {
            // newer versions might hide the chunk class
        }
        this.signControllerChunkClass = chunkClass;
        this.chunkGetEntriesMethod = getEntriesMethod;

        this.signChunksAccessor = discoverSignChunksAccessor(signControllerWorldClass, chunkClass);

        this.longHashMapClass = Class.forName("com.bergerkiller.bukkit.common.wrappers.LongHashMap", false, loader);
        this.longHashMapValuesMethod = longHashMapClass.getMethod("values");

        this.entryGetBlockMethod = entryClass.getMethod("getBlock");
        this.entryGetBlockMethod.setAccessible(true);

        Method hasSignActionEvents;
        try {
            hasSignActionEvents = entryClass.getMethod("hasSignActionEvents");
            hasSignActionEvents.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            hasSignActionEvents = null;
            plugin.getLogger().log(Level.FINE,
                    "TrainCarts SignController$Entry has no hasSignActionEvents method; counting entries without the filter");
        }
        this.entryHasSignActionEventsMethod = hasSignActionEvents;
        Class<?> railPieceClass = Class.forName("com.bergerkiller.bukkit.tc.controller.components.RailPiece", false, loader);
        Method createFrontTrackedSign;
        try {
            createFrontTrackedSign = entryClass.getMethod("createFrontTrackedSign", railPieceClass);
            createFrontTrackedSign.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            createFrontTrackedSign = null;
            plugin.getLogger().log(Level.FINE,
                    "TrainCarts SignController$Entry has no createFrontTrackedSign method; falling back to block state text");
        }
        this.entryCreateFrontTrackedSignMethod = createFrontTrackedSign;

        Method createBackTrackedSign;
        try {
            createBackTrackedSign = entryClass.getMethod("createBackTrackedSign", railPieceClass);
            createBackTrackedSign.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            createBackTrackedSign = null;
            plugin.getLogger().log(Level.FINE,
                    "TrainCarts SignController$Entry has no createBackTrackedSign method; falling back to block state text");
        }
        this.entryCreateBackTrackedSignMethod = createBackTrackedSign;

        Field noneField = railPieceClass.getField("NONE");
        this.railPieceNone = noneField.get(null);

        Class<?> trackedSignClass = Class.forName("com.bergerkiller.bukkit.tc.rails.RailLookup$TrackedSign", false, loader);
        this.trackedSignGetLineMethod = trackedSignClass.getMethod("getLine", int.class);
    }

    private Plugin findTrainCartsPlugin(JavaPlugin plugin) {
        var pluginManager = plugin.getServer().getPluginManager();
        var direct = pluginManager.getPlugin("TrainCarts");
        if (isTrainCartsPlugin(direct)) {
            return direct;
        }
        var underscored = pluginManager.getPlugin("Train_Carts");
        if (isTrainCartsPlugin(underscored)) {
            return underscored;
        }
        for (var candidate : pluginManager.getPlugins()) {
            if (isTrainCartsPlugin(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isTrainCartsPlugin(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        return isTrainCartsName(plugin.getName());
    }

    private boolean isTrainCartsName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace("_", "").replace("-", "").toLowerCase();
        return "traincarts".equals(normalized);
    }

    @Override
    public Optional<StationCountResult> countStations(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return Optional.of(new StationCountResult(0, 0));
        }

        Map<World, List<Cuboid>> cuboidsByWorld = new HashMap<>();
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            World world = Bukkit.getWorld(cuboid.world);
            if (world == null) {
                continue;
            }
            cuboidsByWorld.computeIfAbsent(world, w -> new ArrayList<>()).add(cuboid);
        }

        if (cuboidsByWorld.isEmpty()) {
            return Optional.of(new StationCountResult(0, 0));
        }

        Object signController;
        try {
            signController = getSignControllerMethod.invoke(trainCarts);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return logFailure("accessing TrainCarts sign controller", e);
        }
        if (signController == null) {
            return Optional.empty();
        }

        Set<String> counted = new HashSet<>();
        int total = 0;

        try {
            for (Map.Entry<World, List<Cuboid>> entry : cuboidsByWorld.entrySet()) {
                World world = entry.getKey();
                Object worldController = signControllerForWorldMethod.invoke(signController, world);
                if (worldController == null) {
                    continue;
                }
                boolean enabled = Boolean.TRUE.equals(signControllerWorldIsEnabledMethod.invoke(worldController));
                if (!enabled) {
                    continue;
                }

                Collection<?> chunks = getSignChunks(worldController);
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }

                for (Object chunk : chunks) {
                    if (chunk == null) {
                        continue;
                    }
                    Object[] entries = toEntries(chunk);
                    if (entries == null || entries.length == 0) {
                        continue;
                    }
                    for (Object rawEntry : entries) {
                        if (rawEntry == null) {
                            continue;
                        }
                        if (entryHasSignActionEventsMethod != null
                                && !Boolean.TRUE.equals(entryHasSignActionEventsMethod.invoke(rawEntry))) {
                            continue;
                        }
                        Block block = (Block) entryGetBlockMethod.invoke(rawEntry);
                        if (block == null || block.getWorld() != world) {
                            continue;
                        }
                        if (!isInsideAny(entry.getValue(), block.getX(), block.getY(), block.getZ())) {
                            continue;
                        }
                        if (!isStationEntry(rawEntry, block)) {
                            continue;
                        }
                        String key = blockKey(world, block.getX(), block.getY(), block.getZ());
                        if (counted.add(key)) {
                            total++;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            return logFailure("counting TrainCarts station signs", e);
        }

        failureLogged = false;
        int stations = (total >>> 1) + (total & 1);
        return Optional.of(new StationCountResult(stations, total));
    }

    private Optional<StationCountResult> logFailure(String context, Exception ex) {
        if (!failureLogged) {
            plugin.getLogger().log(Level.WARNING, "Failed while " + context + " for TrainCarts integration: " + ex.getMessage(), ex);
            failureLogged = true;
        }
        return Optional.empty();
    }

    private Collection<?> getSignChunks(Object worldController) throws ReflectiveOperationException {
        if (signChunksAccessor == null) {
            return null;
        }
        return signChunksAccessor.load(worldController);
    }

    private SignChunksAccessor discoverSignChunksAccessor(Class<?> signControllerWorldClass, Class<?> chunkClass)
            throws ReflectiveOperationException {
        Field namedField = findField(signControllerWorldClass, "signChunks");
        if (namedField != null) {
            return createFieldAccessor(namedField);
        }

        Method namedMethod = findZeroArgMethod(signControllerWorldClass, "getSignChunks");
        if (namedMethod != null) {
            return createMethodAccessor(namedMethod);
        }

        if (chunkClass != null) {
            Field chunkField = findFieldReferencingType(signControllerWorldClass, chunkClass);
            if (chunkField != null) {
                return createFieldAccessor(chunkField);
            }

            Method chunkMethod = findMethodReferencingType(signControllerWorldClass, chunkClass);
            if (chunkMethod != null) {
                return createMethodAccessor(chunkMethod);
            }
        }

        SignChunksAccessor fallback = discoverFallbackAccessor(signControllerWorldClass);
        if (fallback != null) {
            return fallback;
        }

        throw new NoSuchFieldException("signChunks not found on " + signControllerWorldClass.getName());
    }

    private SignChunksAccessor discoverFallbackAccessor(Class<?> signControllerWorldClass) {
        Class<?> longHashMapType = null;
        try {
            longHashMapType = Class.forName(
                    "com.bergerkiller.bukkit.common.wrappers.LongHashMap",
                    false,
                    signControllerWorldClass.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }

        List<AccessorCandidate> candidates = new ArrayList<>();

        for (Class<?> current = signControllerWorldClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!isCollectionLikeRaw(field.getType(), longHashMapType)) {
                    continue;
                }
                field.setAccessible(true);
                candidates.add(new AccessorCandidate(
                        createFieldAccessor(field),
                        field.getDeclaringClass().getName() + "#" + field.getName()));
            }
        }

        for (Class<?> current = signControllerWorldClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || method.getReturnType() == void.class) {
                    continue;
                }
                if (!isCollectionLikeRaw(method.getReturnType(), longHashMapType)) {
                    continue;
                }
                method.setAccessible(true);
                candidates.add(new AccessorCandidate(
                        createMethodAccessor(method),
                        method.getDeclaringClass().getName() + "#" + method.getName() + "()"));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return new AdaptiveSignChunksAccessor(signControllerWorldClass, candidates);
    }

    private SignChunksAccessor createFieldAccessor(Field field) {
        field.setAccessible(true);
        return worldController -> toCollection(field.get(worldController));
    }

    private SignChunksAccessor createMethodAccessor(Method method) {
        method.setAccessible(true);
        return worldController -> {
            try {
                Object container = method.invoke(worldController);
                return toCollection(container);
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
                field.setAccessible(true);
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
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
            }
            current = current.getSuperclass();
        }
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() == 0) {
                method.setAccessible(true);
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
                    field.setAccessible(true);
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
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getParameterCount() == 0 && typeReferences(method.getGenericReturnType(), referencedClass)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private boolean typeReferences(Type type, Class<?> referencedClass) {
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

    private Collection<?> toCollection(Object container) throws ReflectiveOperationException {
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
            Object values = longHashMapValuesMethod.invoke(container);
            return toCollection(values);
        }
        Method valuesMethod = findValuesLikeMethod(containerClass);
        if (valuesMethod != null) {
            try {
                Object values = valuesMethod.invoke(container);
                return toCollection(values);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new ReflectiveOperationException(cause != null ? cause : ex);
            }
        }
        return null;
    }

    private boolean isCollectionLikeRaw(Class<?> rawType, Class<?> longHashMapType) {
        if (rawType == null) {
            return false;
        }
        if (rawType.isArray()) {
            return true;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return true;
        }
        if (Iterable.class.isAssignableFrom(rawType)) {
            return true;
        }
        return longHashMapType != null && longHashMapType.isAssignableFrom(rawType);
    }

    private static final class AccessorCandidate {
        private final SignChunksAccessor accessor;
        private final String description;

        private AccessorCandidate(SignChunksAccessor accessor, String description) {
            this.accessor = accessor;
            this.description = description;
        }
    }

    private final class AdaptiveSignChunksAccessor implements SignChunksAccessor {
        private final Class<?> worldClass;
        private List<AccessorCandidate> pendingCandidates;
        private SignChunksAccessor delegate;

        private AdaptiveSignChunksAccessor(Class<?> worldClass, List<AccessorCandidate> candidates) {
            this.worldClass = worldClass;
            this.pendingCandidates = new ArrayList<>(candidates);
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

            for (AccessorCandidate candidate : pendingCandidates) {
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

                boolean matches = false;
                for (Object chunk : values) {
                    if (chunk == null) {
                        continue;
                    }
                    Object[] entries;
                    try {
                        entries = toEntries(chunk);
                    } catch (ReflectiveOperationException ex) {
                        failures.add(candidate.description + " chunk inspection failed with "
                                + ex.getClass().getSimpleName()
                                + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
                        entries = null;
                        break;
                    }
                    if (entries == null || entries.length == 0) {
                        continue;
                    }
                    for (Object entry : entries) {
                        if (entryClass.isInstance(entry)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        break;
                    }
                }

                if (matches) {
                    delegate = candidate.accessor;
                    pendingCandidates = null;
                    return delegate.load(worldController);
                }

                failures.add(candidate.description + " did not expose SignController$Entry instances");
            }

            pendingCandidates = retry;
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
                method.setAccessible(true);
                return method;
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (isValuesCandidate(method)) {
                    method.setAccessible(true);
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
        if (typeReferences(method.getGenericReturnType(), signControllerChunkClass)) {
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

    private Object[] toEntries(Object chunk) throws ReflectiveOperationException {
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
        Class<?> chunkClass = chunk.getClass();
        if (chunkClass.isArray()) {
            int length = Array.getLength(chunk);
            Object[] values = new Object[length];
            for (int i = 0; i < length; i++) {
                values[i] = Array.get(chunk, i);
            }
            return values;
        }
        if (chunkGetEntriesMethod != null && chunkGetEntriesMethod.getDeclaringClass().isInstance(chunk)) {
            Object result = invokeChunkEntriesMethod(chunkGetEntriesMethod, chunk);
            return convertEntriesResult(result);
        }
        Method method = findChunkEntriesMethod(chunkClass);
        if (method == null) {
            return null;
        }
        Object value = invokeChunkEntriesMethod(method, chunk);
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
                method.setAccessible(true);
                return method;
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (isEntriesCandidate(method)) {
                    method.setAccessible(true);
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

    private Object invokeChunkEntriesMethod(Method method, Object chunk) throws ReflectiveOperationException {
        try {
            return method.invoke(chunk);
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
            Object nested = invokeChunkEntriesMethod(valuesMethod, value);
            return convertEntriesResult(nested);
        }
        return null;
    }

    private boolean isStationEntry(Object entry, Block block) throws ReflectiveOperationException {
        if (entryCreateFrontTrackedSignMethod != null
                && isStationTrackedSign(entryCreateFrontTrackedSignMethod.invoke(entry, railPieceNone))) {
            return true;
        }
        if (entryCreateBackTrackedSignMethod != null
                && isStationTrackedSign(entryCreateBackTrackedSignMethod.invoke(entry, railPieceNone))) {
            return true;
        }
        return isStationBlock(block);
    }

    private boolean isStationTrackedSign(Object trackedSign) throws ReflectiveOperationException {
        if (trackedSign == null) {
            return false;
        }
        String header = readSignLine(trackedSign, 0);
        String action = readSignLine(trackedSign, 1);
        return isStationLines(header, action);
    }

    private boolean isStationBlock(Block block) {
        if (block == null) {
            return false;
        }
        var state = block.getState();
        if (!(state instanceof Sign sign)) {
            return false;
        }
        String header = safeGetLine(sign, 0);
        String action = safeGetLine(sign, 1);
        return isStationLines(header, action);
    }

    private String safeGetLine(Sign sign, int index) {
        try {
            return sign.getLine(index);
        } catch (Throwable ignored) {
            // Older Bukkit versions might throw for out-of-bounds; treat as missing text
            return null;
        }
    }

    private boolean isStationLines(String header, String action) {
        if (header == null || action == null) {
            return false;
        }
        if (!isTrainOrCartHeader(header)) {
            return false;
        }
        return isStationActionLine(action);
    }

    private String readSignLine(Object trackedSign, int index) throws ReflectiveOperationException {
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

    private boolean isTrainOrCartHeader(String line) {
        String cleaned = normalizeLine(line);
        if (cleaned == null) {
            return false;
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }
        cleaned = stripPrefixCharacters(cleaned, "+-!/\\");
        if (cleaned.isEmpty()) {
            return false;
        }
        return cleaned.equals("train") || cleaned.equals("cart");
    }

    private boolean isStationActionLine(String line) {
        String cleaned = normalizeLine(line);
        if (cleaned == null) {
            return false;
        }
        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        cleaned = stripPrefixCharacters(cleaned, "+-!/\\");
        return cleaned.equals("station");
    }

    private String normalizeLine(String line) {
        String stripped = stripFormatting(line);
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.charAt(0) == '[' && stripped.length() > 1) {
            stripped = stripped.substring(1);
        }
        if (!stripped.isEmpty() && stripped.charAt(stripped.length() - 1) == ']') {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        stripped = stripped.trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    private String stripFormatting(String line) {
        if (line == null) {
            return "";
        }
        String stripped = ChatColor.stripColor(line);
        if (stripped == null) {
            stripped = line;
        }
        return stripped.trim();
    }

    private String stripPrefixCharacters(String input, String characters) {
        int index = 0;
        while (index < input.length() && characters.indexOf(input.charAt(index)) >= 0) {
            index++;
        }
        return index >= input.length() ? "" : input.substring(index);
    }

    private boolean isInsideAny(List<Cuboid> cuboids, int x, int y, int z) {
        for (Cuboid cuboid : cuboids) {
            if (cuboid == null) {
                continue;
            }
            if (x < cuboid.minX || x > cuboid.maxX) {
                continue;
            }
            if (z < cuboid.minZ || z > cuboid.maxZ) {
                continue;
            }
            if (y < cuboid.minY || y > cuboid.maxY) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String blockKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ':' + y + ':' + z;
    }
}
