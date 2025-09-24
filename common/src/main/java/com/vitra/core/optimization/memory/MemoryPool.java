package com.vitra.core.optimization.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Object pooling system for reducing garbage collection pressure
 * Manages pools of frequently allocated objects like buffers, vectors, etc.
 */
public class MemoryPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryPool.class);

    private final ConcurrentLinkedQueue<PooledBuffer> bufferPool;
    private final ConcurrentLinkedQueue<PooledVector> vectorPool;
    private final AtomicInteger pooledObjectCount;
    private final AtomicInteger maxPoolSize;

    private boolean initialized = false;

    public MemoryPool(int maxPoolSize) {
        this.maxPoolSize = new AtomicInteger(maxPoolSize);
        this.bufferPool = new ConcurrentLinkedQueue<>();
        this.vectorPool = new ConcurrentLinkedQueue<>();
        this.pooledObjectCount = new AtomicInteger(0);
    }

    /**
     * Initialize the memory pool
     */
    public void initialize() {
        if (initialized) {
            LOGGER.warn("MemoryPool already initialized");
            return;
        }

        // Pre-populate pools with some objects
        for (int i = 0; i < Math.min(16, maxPoolSize.get() / 4); i++) {
            bufferPool.offer(new PooledBuffer());
            vectorPool.offer(new PooledVector());
        }

        pooledObjectCount.set(bufferPool.size() + vectorPool.size());
        initialized = true;
        LOGGER.info("MemoryPool initialized with max size: {}, pre-populated: {}",
                   maxPoolSize.get(), pooledObjectCount.get());
    }

    /**
     * Shutdown the memory pool
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down MemoryPool...");

        bufferPool.clear();
        vectorPool.clear();
        pooledObjectCount.set(0);
        initialized = false;

        LOGGER.info("MemoryPool shutdown complete");
    }

    /**
     * Get a pooled buffer, creating a new one if the pool is empty
     */
    public PooledBuffer getBuffer() {
        if (!initialized) return new PooledBuffer();

        PooledBuffer buffer = bufferPool.poll();
        if (buffer != null) {
            pooledObjectCount.decrementAndGet();
            buffer.reset();
            return buffer;
        }

        return new PooledBuffer();
    }

    /**
     * Return a buffer to the pool for reuse
     */
    public void returnBuffer(PooledBuffer buffer) {
        if (!initialized || buffer == null) return;

        if (pooledObjectCount.get() < maxPoolSize.get()) {
            buffer.reset();
            bufferPool.offer(buffer);
            pooledObjectCount.incrementAndGet();
        }
    }

    /**
     * Get a pooled vector, creating a new one if the pool is empty
     */
    public PooledVector getVector() {
        if (!initialized) return new PooledVector();

        PooledVector vector = vectorPool.poll();
        if (vector != null) {
            pooledObjectCount.decrementAndGet();
            vector.reset();
            return vector;
        }

        return new PooledVector();
    }

    /**
     * Return a vector to the pool for reuse
     */
    public void returnVector(PooledVector vector) {
        if (!initialized || vector == null) return;

        if (pooledObjectCount.get() < maxPoolSize.get()) {
            vector.reset();
            vectorPool.offer(vector);
            pooledObjectCount.incrementAndGet();
        }
    }

    /**
     * Set the maximum pool size
     */
    public void setMaxPoolSize(int newMaxSize) {
        maxPoolSize.set(newMaxSize);

        // Trim pools if necessary
        while (pooledObjectCount.get() > newMaxSize) {
            if (bufferPool.poll() != null || vectorPool.poll() != null) {
                pooledObjectCount.decrementAndGet();
            } else {
                break;
            }
        }
    }

    public int getPooledObjectCount() {
        return pooledObjectCount.get();
    }

    public float getUtilizationPercentage() {
        return (float) pooledObjectCount.get() / maxPoolSize.get();
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Pooled buffer implementation
     */
    public static class PooledBuffer {
        private float[] data;
        private int capacity;
        private int size;

        public PooledBuffer() {
            this(64); // Default capacity
        }

        public PooledBuffer(int initialCapacity) {
            this.capacity = initialCapacity;
            this.data = new float[capacity];
            this.size = 0;
        }

        public void reset() {
            size = 0;
        }

        public void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity > capacity) {
                capacity = Math.max(capacity * 2, requiredCapacity);
                float[] newData = new float[capacity];
                System.arraycopy(data, 0, newData, 0, size);
                data = newData;
            }
        }

        public void add(float value) {
            ensureCapacity(size + 1);
            data[size++] = value;
        }

        public float get(int index) {
            if (index >= size) throw new IndexOutOfBoundsException();
            return data[index];
        }

        public void set(int index, float value) {
            if (index >= size) throw new IndexOutOfBoundsException();
            data[index] = value;
        }

        public int size() {
            return size;
        }

        public float[] getData() {
            return data;
        }
    }

    /**
     * Pooled vector implementation
     */
    public static class PooledVector {
        public float x, y, z;

        public PooledVector() {
            reset();
        }

        public void reset() {
            x = y = z = 0.0f;
        }

        public void set(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public void add(PooledVector other) {
            x += other.x;
            y += other.y;
            z += other.z;
        }

        public void multiply(float scalar) {
            x *= scalar;
            y *= scalar;
            z *= scalar;
        }

        public float length() {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        public void normalize() {
            float len = length();
            if (len > 0) {
                multiply(1.0f / len);
            }
        }
    }
}