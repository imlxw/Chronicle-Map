/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

import net.openhft.chronicle.hash.KeyContext;
import net.openhft.chronicle.hash.Data;
import net.openhft.chronicle.hash.impl.VanillaChronicleHash;
import net.openhft.chronicle.hash.serialization.BytesReader;
import net.openhft.chronicle.hash.serialization.SizeMarshaller;
import net.openhft.chronicle.hash.serialization.internal.MetaBytesInterop;
import net.openhft.chronicle.hash.serialization.internal.MetaProvider;
import net.openhft.chronicle.hash.serialization.internal.SerializationBuilder;
import net.openhft.chronicle.map.impl.*;
import net.openhft.chronicle.map.impl.ret.InstanceReturnValue;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.model.DataValueClasses;
import net.openhft.lang.threadlocal.Provider;
import net.openhft.lang.threadlocal.ThreadLocalCopies;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Set;
import java.util.function.BiFunction;

import static net.openhft.chronicle.map.ChronicleMapBuilder.greatestCommonDivisor;

public class VanillaChronicleMap<K, KI, MKI extends MetaBytesInterop<K, ? super KI>,
        V, VI, MVI extends MetaBytesInterop<V, ? super VI>, R>
        extends VanillaChronicleHash<K, KI, MKI, MapKeyContext<K, V>,
        ExternalMapQueryContext<K, V, ?>>
        implements AbstractChronicleMap<K, V>  {

    private static final Logger LOG = LoggerFactory.getLogger(VanillaChronicleMap.class);

    private static final long serialVersionUID = 3L;

    /////////////////////////////////////////////////
    // Value Data model
    final Class<V> vClass;
    final Class nativeValueClass;
    public final SizeMarshaller valueSizeMarshaller;
    public final BytesReader<V> originalValueReader;
    public final VI originalValueInterop;
    public final MVI originalMetaValueInterop;
    public final MetaProvider<V, VI, MVI> metaValueInteropProvider;

    final DefaultValueProvider<K, V> defaultValueProvider;

    public final boolean constantlySizedEntry;

    public transient Provider<BytesReader<V>> valueReaderProvider;
    public transient Provider<VI> valueInteropProvider;

    /////////////////////////////////////////////////
    // Event listener and meta data
    public final int metaDataBytes;

    /////////////////////////////////////////////////
    // Behavior
    final boolean putReturnsNull;
    final boolean removeReturnsNull;

    /////////////////////////////////////////////////
    // Memory management and dependent fields
    public final Alignment alignment;
    public final int worstAlignment;
    public final boolean couldNotDetermineAlignmentBeforeAllocation;

    transient Set<Entry<K, V>> entrySet;
    
    // TODO initialize
    public transient MapEntryOperations<K, V, R> entryOperations;
    public transient MapMethods<K, V, R> methods;
    
    transient ThreadLocal<?> queryCxt;
    transient ThreadLocal<?> iterCxt;
    

    public VanillaChronicleMap(ChronicleMapBuilder<K, V> builder, boolean replicated)
            throws IOException {
        super(builder, replicated);
        SerializationBuilder<V> valueBuilder = builder.valueBuilder;
        vClass = valueBuilder.eClass;
        if (vClass.getName().endsWith("$$Native")) {
            nativeValueClass = vClass;
        } else if (vClass.isInterface()) {
            Class nativeValueClass = null;
            try {
                nativeValueClass = DataValueClasses.directClassFor(vClass);
            } catch (Exception e) {
                // fall through
            }
            this.nativeValueClass = nativeValueClass;
        } else {
            nativeValueClass = null;
        }
        valueSizeMarshaller = valueBuilder.sizeMarshaller();
        originalValueReader = valueBuilder.reader();
        originalValueInterop = (VI) valueBuilder.interop();
        originalMetaValueInterop = (MVI) valueBuilder.metaInterop();
        metaValueInteropProvider = (MetaProvider) valueBuilder.metaInteropProvider();

        defaultValueProvider = builder.defaultValueProvider();

        constantlySizedEntry = builder.constantlySizedEntries();

        // Event listener and meta data
        metaDataBytes = builder.metaDataBytes();

        // Behavior
        putReturnsNull = builder.putReturnsNull();
        removeReturnsNull = builder.removeReturnsNull();

        // Concurrency (number of segments), memory management and dependent fields
        alignment = builder.valueAlignment();
        worstAlignment = builder.worstAlignment(replicated);
        int alignment = this.alignment.alignment();
        couldNotDetermineAlignmentBeforeAllocation =
                greatestCommonDivisor((int) chunkSize, alignment) != alignment;

        initTransientsFromBuilder(builder);
        initTransients();
    }
    
    void initTransientsFromBuilder(ChronicleMapBuilder<K, V> builder) {
        this.entryOperations = (MapEntryOperations<K, V, R>) builder.entryOperations;
        this.methods = (MapMethods<K, V, R>) builder.methods;
    }

    @Override
    public void initTransients() {
        super.initTransients();
        ownInitTransients();
    }

    private void ownInitTransients() {
        valueReaderProvider = Provider.of((Class) originalValueReader.getClass());
        valueInteropProvider = Provider.of((Class) originalValueInterop.getClass());

        if (defaultValueProvider instanceof ConstantValueProvider) {
            ConstantValueProvider<K, V> constantValueProvider =
                    (ConstantValueProvider<K, V>) defaultValueProvider;
            if (constantValueProvider.wasDeserialized()) {
                ThreadLocalCopies copies = valueReaderProvider.getCopies(null);
                BytesReader<V> valueReader = valueReaderProvider.get(copies, originalValueReader);
                constantValueProvider.initTransients(valueReader);
            }
        }
        initQueryContext();
        initIterationContext();
    }

     void initQueryContext() {
        queryCxt = new ThreadLocal<CompiledMapQueryContext<K, KI, MKI, V, VI, MVI, R, ?>>() {
            @Override
            protected CompiledMapQueryContext<K, KI, MKI, V, VI, MVI, R, ?> initialValue() {
                return new CompiledMapQueryContext<>(VanillaChronicleMap.this);
            }
        };
    }
    
    void initIterationContext() {
        iterCxt = new ThreadLocal<CompiledMapIterationContext<K, KI, MKI, V, VI, MVI, R, ?>>() {
            @Override
            protected CompiledMapIterationContext<K, KI, MKI, V, VI, MVI, R, ?> initialValue() {
                return new CompiledMapIterationContext<>(VanillaChronicleMap.this);
            }
        };
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        ownInitTransients();
    }

    public final V checkValue(Object value) {
        if (!vClass.isInstance(value)) {
            throw new ClassCastException("Value must be a " + vClass.getName() +
                    " but was a " + value.getClass());
        }
        return (V) value;
    }

    @Override
    public MapKeyContext<K, V> context(K key) {
        return queryContext(key).deprecatedMapKeyContext();
    }

    @Override
    public int actualSegments() {
        return actualSegments;
    }

    public V defaultValue(KeyContext keyContext) {
        if (defaultValueProvider != null)
            return defaultValueProvider.get(keyContext);
        throw new IllegalStateException("To call acquire*() methods, " +
                "you should specify either default value or default value provider " +
                "during map building");
    }

    @Override
    public V newValueInstance() {
        if (vClass == CharSequence.class || vClass == StringBuilder.class)
            return (V) new StringBuilder();
        return newInstance(vClass, false);
    }

    @Override
    public K newKeyInstance() {
        return newInstance(kClass, true);
    }

    @Override
    public Class<K> keyClass() {
        return kClass;
    }

    @Override
    public Class<V> valueClass() {
        return vClass;
    }

    static <T> T newInstance(Class<T> aClass, boolean isKey) {
        try {
            // TODO optimize -- save heap class / direct class in transient fields and call
            // newInstance() on them directly
            return isKey ? DataValueClasses.newInstance(aClass) :
                    DataValueClasses.newDirectInstance(aClass);

        } catch (Exception e) {
            if (e.getCause() instanceof IllegalStateException)
                throw e;

            if (aClass.isInterface())
                throw new IllegalStateException("It not possible to create a instance from " +
                        "interface=" + aClass.getSimpleName() + " we recommend you create an " +
                        "instance in the usual way.", e);

            try {
                return (T) aClass.newInstance();
            } catch (Exception e1) {
                throw new IllegalStateException("It has not been possible to create a instance " +
                        "of class=" + aClass.getSimpleName() +
                        ", Note : its more efficient if your chronicle map is configured with " +
                        "interface key " +
                        "and value types rather than classes, as this method is able to use " +
                        "interfaces to generate off heap proxies that point straight at your data. " +
                        "In this case you have used a class and chronicle is unable to create an " +
                        "instance of this class has it does not have a default constructor. " +
                        "If your class is mutable, we " +
                        "recommend you create and instance of your class=" + aClass.getSimpleName() +
                        " in the usual way, rather than using this method.", e);
            }
        }
    }


    @NotNull
    @Override
    public final MapKeyContext<K, V> acquireContext(K key, V usingValue) {
        QueryContextInterface<K, V, R> q = queryContext(key);
        // TODO optimize to update lock in certain cases
        try {
            q.writeLock().lock();
            acquireUsingBody(q, usingValue);
            return q.deprecatedMapAcquireContext();
        } catch (Throwable e) {
            try {
                q.close();
            } catch (Throwable suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
    
    private static <V> void checkAcquiredUsing(V acquired, V using) {
        if (acquired != using) {
            throw new IllegalArgumentException("acquire*() MUST reuse the given " +
                    "value. Given value " + using + " cannot be reused to read " + acquired);
        }
        
    }

    @NotNull
    @Override
    public final Set<Entry<K, V>> entrySet() {
        return (entrySet != null) ? entrySet : (entrySet = newEntrySet());
    }

    @Override
    public int hashCode() {
        return mapHashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return mapEquals(obj);
    }

    @Override
    public String toString() {
        return mapToString();
    }
    
    @Override
    public void clear() {
        forEachEntry(KeyContext::remove);
    }

    public final long readValueSize(Bytes entry) {
        long valueSize = valueSizeMarshaller.readSize(entry);
        alignment.alignPositionAddr(entry);
        return valueSize;
    }
    
    private CompiledMapQueryContext<K, KI, MKI, V, VI, MVI, R, ?> q() {
        return (CompiledMapQueryContext<K, KI, MKI, V, VI, MVI, R, ?>) queryCxt.get();
    }

    public QueryContextInterface<K, V, R> mapContext() {
        CompiledMapQueryContext<K, KI, MKI, V, VI, MVI, R, ?> q = q().getContext();
        q.initUsed(true);
        return q;
    }
    
    private CompiledMapIterationContext<K, KI, MKI, V, VI, MVI, R, ?> i() {
        return (CompiledMapIterationContext<K, KI, MKI, V, VI, MVI, R, ?>) iterCxt.get();
    }

    @Override
    public IterationContextInterface<K, V> iterationContext() {
        CompiledMapIterationContext<K, KI, MKI, V, VI, MVI, R, ?> c = i().getContext();
        c.initUsed(true);
        return c;
    }

    @Override
    @NotNull
    public QueryContextInterface<K, V, R> queryContext(Object key) {
        checkKey(key);
        QueryContextInterface<K, V, R> q = mapContext();
        q.inputKeyInstanceValue().initKey((K) key);
        q.initInputKey(q.inputKeyInstanceValue());
        return q;
    }

    @Override
    @NotNull
    public QueryContextInterface<K, V, R> queryContext(Data<K, ?> key) {
        QueryContextInterface<K, V, R> q = mapContext();
        q.initInputKey(key);
        return q;
    }

    @Override
    public V get(Object key) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            methods.get(q, q.defaultReturnValue());
            return q.defaultReturnValue().returnValue();
        }
    }

    @Override
    public V getUsing(K key, V usingValue) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.usingReturnValue().initUsingReturnValue(usingValue);
            methods.get(q, q.usingReturnValue());
            return q.usingReturnValue().returnValue();
        }
    }

    @Override
    public V acquireUsing(@NotNull K key, V usingValue) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            V returnValue = acquireUsingBody(q, usingValue);
            return returnValue;
        }
    }

    private V acquireUsingBody(QueryContextInterface<K, V, R> q, V usingValue) {
        q.usingReturnValue().initUsingReturnValue(usingValue);
        methods.acquireUsing(q, q.usingReturnValue());
        V returnValue = q.usingReturnValue().returnValue();
        checkAcquiredUsing(returnValue, usingValue);
        return returnValue;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        checkValue(value);
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(value);
            methods.putIfAbsent(q, q.inputValueInstanceValue(), q.defaultReturnValue());
            return q.defaultReturnValue().returnValue();
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (value == null)
            return false; // ConcurrentHashMap compatibility
        V v = checkValue(value);
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(v);
            return methods.remove(q, q.inputValueInstanceValue());
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        checkValue(oldValue);
        checkValue(newValue);
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(oldValue);
            return methods.replace(q, q.inputValueInstanceValue(), q.wrapValueAsValue(newValue));
        }
    }

    @Override
    public V replace(K key, V value) {
        checkValue(value);
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(value);
            methods.replace(q, q.inputValueInstanceValue(), q.defaultReturnValue());
            return q.defaultReturnValue().returnValue();
        }
    }

    @Override
    public boolean containsKey(Object key) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            return methods.containsKey(q);
        }
    }

    @Override
    public V put(K key, V value) {
        checkValue(value);
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(value);
            InstanceReturnValue<V> returnValue =
                    putReturnsNull ? NullReturnValue.get() : q.defaultReturnValue();
            methods.put(q, q.inputValueInstanceValue(), returnValue);
            return returnValue.returnValue();
        }
    }

    @Override
    public V remove(Object key) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            InstanceReturnValue<V> returnValue =
                    removeReturnsNull ? NullReturnValue.get() : q.defaultReturnValue();
            methods.remove(q, returnValue);
            return returnValue.returnValue();
        }
    }

    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            q.inputValueInstanceValue().initValue(value);
            methods.merge(q, q.inputValueInstanceValue(), remappingFunction,
                    q.defaultReturnValue());
            return q.defaultReturnValue().returnValue();
        }
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        try (QueryContextInterface<K, V, R> q = queryContext(key)) {
            methods.compute(q, remappingFunction, q.defaultReturnValue());
            return q.defaultReturnValue().returnValue();
        }
    }
}
