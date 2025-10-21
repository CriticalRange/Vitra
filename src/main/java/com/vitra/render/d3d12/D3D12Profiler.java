package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DirectX 12 Performance Profiler inspired by VulkanMod's profiling system
 * Provides comprehensive performance monitoring and debugging capabilities
 */
public class D3D12Profiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12Profiler");

    // Profiler categories
    public static final int CATEGORY_FRAME = 0;
    public static final int CATEGORY_DRAW = 1;
    public static final int CATEGORY_COMPUTE = 2;
    public static final int CATEGORY_COPY = 3;
    public static final int CATEGORY_RESOURCE_CREATION = 4;
    public static final int CATEGORY_SHADER_COMPILATION = 5;
    public static final int CATEGORY_PIPELINE_CREATION = 6;
    public static final int CATEGORY_TEXTURE_CREATION = 7;
    public static final int CATEGORY_BUFFER_CREATION = 8;
    public static final int CATEGORY_BARRIER = 9;
    public static final int CATEGORY_PRESENT = 10;

    /**
     * Timing sample
     */
    private static class TimingSample {
        final long startTime;
        final long endTime;
        final int category;
        final String name;
        final Map<String, Object> metadata;

        TimingSample(long startTime, long endTime, int category, String name, Map<String, Object> metadata) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.category = category;
            this.name = name;
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }

        public long getDuration() {
            return endTime - startTime;
        }

        public double getDurationMs() {
            return getDuration() / 1_000_000.0;
        }
    }

    /**
     * Performance counter
     */
    private static class PerformanceCounter {
        final String name;
        final AtomicLong totalCount;
        final AtomicLong totalValue;
        final AtomicLong minValue;
        final AtomicLong maxValue;
        final AtomicLong lastValue;
        volatile long lastUpdateTime;

        PerformanceCounter(String name) {
            this.name = name;
            this.totalCount = new AtomicLong(0);
            this.totalValue = new AtomicLong(0);
            this.minValue = new AtomicLong(Long.MAX_VALUE);
            this.maxValue = new AtomicLong(Long.MIN_VALUE);
            this.lastValue = new AtomicLong(0);
            this.lastUpdateTime = System.nanoTime();
        }

        public void addSample(long value) {
            totalCount.incrementAndGet();
            totalValue.addAndGet(value);

            // Update min/max atomically
            updateMin(value);
            updateMax(value);

            lastValue.set(value);
            lastUpdateTime = System.nanoTime();
        }

        private void updateMin(long value) {
            long currentMin = minValue.get();
            while (value < currentMin) {
                if (minValue.compareAndSet(currentMin, value)) {
                    break;
                }
                currentMin = minValue.get();
            }
        }

        private void updateMax(long value) {
            long currentMax = maxValue.get();
            while (value > currentMax) {
                if (maxValue.compareAndSet(currentMax, value)) {
                    break;
                }
                currentMax = maxValue.get();
            }
        }

        public long getTotalCount() { return totalCount.get(); }
        public long getTotalValue() { return totalValue.get(); }
        public long getMinValue() {
            long min = minValue.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }
        public long getMaxValue() {
            long max = maxValue.get();
            return max == Long.MIN_VALUE ? 0 : max;
        }
        public long getAverage() {
            long count = totalCount.get();
            return count > 0 ? totalValue.get() / count : 0;
        }
        public long getLastValue() { return lastValue.get(); }
        public long getLastUpdateTime() { return lastUpdateTime; }
    }

    // Timing samples and counters
    private final List<TimingSample> timingSamples;
    private final Map<String, PerformanceCounter> counters;
    private final Map<Integer, List<TimingSample>> categorizedSamples;

    // Frame tracking
    private final AtomicLong frameCounter = new AtomicLong(0);
    private volatile long currentFrameStartTime = 0;
    private final AtomicLong totalFrameTime = new AtomicLong(0);
    private final AtomicLong minFrameTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxFrameTime = new AtomicLong(Long.MIN_VALUE);
    private final List<Long> recentFrameTimes;

    // Profiler state
    private volatile boolean enabled = true;
    private volatile boolean captureDetailedTimings = false;
    private final int maxSamples = 10000;

    public D3D12Profiler() {
        this.timingSamples = Collections.synchronizedList(new ArrayList<>());
        this.counters = new ConcurrentHashMap<>();
        this.categorizedSamples = new ConcurrentHashMap<>();
        this.recentFrameTimes = Collections.synchronizedList(new ArrayList<>());

        // Initialize categorized sample lists
        for (int i = 0; i <= CATEGORY_PRESENT; i++) {
            categorizedSamples.put(i, Collections.synchronizedList(new ArrayList<>()));
        }

        LOGGER.info("D3D12 Performance Profiler initialized");
    }

    /**
     * Enable/disable profiling
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("D3D12 Profiler {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Enable/disable detailed timing capture
     */
    public void setDetailedTimingsEnabled(boolean enabled) {
        this.captureDetailedTimings = enabled;
        LOGGER.info("D3D12 Profiler detailed timings {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Begin profiling category
     */
    public long beginSample(int category, String name) {
        return beginSample(category, name, null);
    }

    /**
     * Begin profiling category with metadata
     */
    public long beginSample(int category, String name, Map<String, Object> metadata) {
        if (!enabled) {
            return 0;
        }

        if (!captureDetailedTimings) {
            return System.nanoTime();
        }

        long startTime = System.nanoTime();
        LOGGER.trace("Begin sample: {} ({})", name, getCategoryName(category));
        return startTime;
    }

    /**
     * End profiling sample
     */
    public void endSample(int category, String name, long startTime) {
        endSample(category, name, startTime, null);
    }

    /**
     * End profiling sample with metadata
     */
    public void endSample(int category, String name, long startTime, Map<String, Object> metadata) {
        if (!enabled || !captureDetailedTimings || startTime == 0) {
            return;
        }

        long endTime = System.nanoTime();
        long duration = endTime - startTime;

        TimingSample sample = new TimingSample(startTime, endTime, category, name, metadata);

        // Add to collections
        timingSamples.add(sample);
        categorizedSamples.get(category).add(sample);

        // Trim if too many samples
        trimSamplesIfNecessary();

        LOGGER.trace("End sample: {} ({}), duration={}ns", name, getCategoryName(category), duration);
    }

    /**
     * Record performance counter
     */
    public void recordCounter(String name, long value) {
        if (!enabled) {
            return;
        }

        counters.computeIfAbsent(name, PerformanceCounter::new).addSample(value);
    }

    /**
     * Increment performance counter
     */
    public void incrementCounter(String name) {
        recordCounter(name, 1);
    }

    /**
     * Begin frame profiling
     */
    public void beginFrame() {
        if (!enabled) {
            return;
        }

        currentFrameStartTime = System.nanoTime();
        long frameId = frameCounter.incrementAndGet();
        recordCounter("Frames", 1);

        LOGGER.trace("Begin frame: {}", frameId);
    }

    /**
     * End frame profiling
     */
    public void endFrame() {
        if (!enabled || currentFrameStartTime == 0) {
            return;
        }

        long endTime = System.nanoTime();
        long frameTime = endTime - currentFrameStartTime;

        // Update frame statistics
        totalFrameTime.addAndGet(frameTime);
        updateMinFrameTime(frameTime);
        updateMaxFrameTime(frameTime);

        // Store recent frame times
        synchronized (recentFrameTimes) {
            recentFrameTimes.add(frameTime);
            if (recentFrameTimes.size() > 1000) {
                recentFrameTimes.remove(0);
            }
        }

        // Record frame timing
        recordCounter("FrameTime", frameTime);

        LOGGER.trace("End frame: {}, duration={}ms", frameCounter.get(), frameTime / 1_000_000.0);

        currentFrameStartTime = 0;
    }

    /**
     * Record draw call
     */
    public void recordDrawCall(int vertexCount, int instanceCount) {
        if (!enabled) {
            return;
        }

        incrementCounter("DrawCalls");
        recordCounter("VerticesDrawn", vertexCount * instanceCount);
    }

    /**
     * Record compute dispatch
     */
    public void recordComputeDispatch(int threadGroupX, int threadGroupY, int threadGroupZ) {
        if (!enabled) {
            return;
        }

        incrementCounter("ComputeDispatches");
        recordCounter("ComputeThreads", threadGroupX * threadGroupY * threadGroupZ);
    }

    /**
     * Record memory allocation
     */
    public void recordMemoryAllocation(String type, long size) {
        if (!enabled) {
            return;
        }

        incrementCounter("MemoryAllocations_" + type);
        recordCounter("MemoryAllocated_" + type, size);
    }

    /**
     * Record texture creation
     */
    public void recordTextureCreation(int width, int height, int format, long creationTime) {
        if (!enabled) {
            return;
        }

        incrementCounter("TextureCreations");
        recordCounter("TextureCreationTime", creationTime);

        long estimatedSize = (long) width * height * D3D12TextureBuilder.getFormatSize(format);
        recordMemoryAllocation("Texture", estimatedSize);
    }

    /**
     * Record shader compilation
     */
    public void recordShaderCompilation(String type, long size, boolean successful, long compilationTime) {
        if (!enabled) {
            return;
        }

        incrementCounter("ShaderCompilations");
        if (successful) {
            incrementCounter("SuccessfulShaderCompilations");
        } else {
            incrementCounter("FailedShaderCompilations");
        }

        recordCounter("ShaderCompilationSize", size);
        recordCounter("ShaderCompilationTime", compilationTime);
    }

    /**
     * Record pipeline creation
     */
    public void recordPipelineCreation(long creationTime) {
        if (!enabled) {
            return;
        }

        incrementCounter("PipelineCreations");
        recordCounter("PipelineCreationTime", creationTime);
    }

    /**
     * Record resource barrier
     */
    public void recordResourceBarrier(int type) {
        if (!enabled) {
            return;
        }

        incrementCounter("ResourceBarriers");
        incrementCounter("ResourceBarriers_" + type);
    }

    /**
     * Get performance statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== D3D12 Performance Statistics ===\n");
        stats.append("Profiler Enabled: ").append(enabled).append("\n");
        stats.append("Detailed Timings: ").append(captureDetailedTimings).append("\n");
        stats.append("Total Frames: ").append(frameCounter.get()).append("\n");

        // Frame statistics
        stats.append("\n--- Frame Statistics ---\n");
        long frameCount = frameCounter.get();
        if (frameCount > 0) {
            long avgFrameTime = totalFrameTime.get() / frameCount;
            stats.append("Average Frame Time: ").append(String.format("%.2f", avgFrameTime / 1_000_000.0)).append(" ms\n");
            stats.append("FPS: ").append(String.format("%.1f", 1_000_000_000.0 / avgFrameTime)).append("\n");

            long minFrame = minFrameTime.get();
            long maxFrame = maxFrameTime.get();
            stats.append("Min Frame Time: ").append(String.format("%.2f", minFrame / 1_000_000.0)).append(" ms\n");
            stats.append("Max Frame Time: ").append(String.format("%.2f", maxFrame / 1_000_000.0)).append(" ms\n");

            // Recent frame time percentiles
            synchronized (recentFrameTimes) {
                if (!recentFrameTimes.isEmpty()) {
                    long[] sortedTimes = recentFrameTimes.stream().mapToLong(Long::longValue).sorted().toArray();
                    int count = sortedTimes.length;

                    stats.append("Recent Frame Percentiles (last ").append(count).append(" frames):\n");
                    stats.append("  50%: ").append(String.format("%.2f", sortedTimes[count/2] / 1_000_000.0)).append(" ms\n");
                    stats.append("  90%: ").append(String.format("%.2f", sortedTimes[count*9/10] / 1_000_000.0)).append(" ms\n");
                    stats.append("  95%: ").append(String.format("%.2f", sortedTimes[count*95/100] / 1_000_000.0)).append(" ms\n");
                    stats.append("  99%: ").append(String.format("%.2f", sortedTimes[count*99/100] / 1_000_000.0)).append(" ms\n");
                }
            }
        }

        // Performance counters
        stats.append("\n--- Performance Counters ---\n");
        for (Map.Entry<String, PerformanceCounter> entry : counters.entrySet()) {
            PerformanceCounter counter = entry.getValue();
            stats.append("  ").append(entry.getKey()).append(": ")
                 .append("count=").append(counter.getTotalCount())
                 .append(", avg=").append(String.format("%.1f", counter.getAverage()))
                 .append(", min=").append(counter.getMinValue())
                 .append(", max=").append(counter.getMaxValue())
                 .append("\n");
        }

        // Category statistics
        stats.append("\n--- Category Timing Summary ---\n");
        for (Map.Entry<Integer, List<TimingSample>> entry : categorizedSamples.entrySet()) {
            int category = entry.getKey();
            List<TimingSample> samples = entry.getValue();

            if (!samples.isEmpty()) {
                long totalTime = samples.stream().mapToLong(TimingSample::getDuration).sum();
                long avgTime = totalTime / samples.size();
                long minTime = samples.stream().mapToLong(TimingSample::getDuration).min().orElse(0);
                long maxTime = samples.stream().mapToLong(TimingSample::getDuration).max().orElse(0);

                stats.append("  ").append(getCategoryName(category)).append(": ")
                     .append("count=").append(samples.size())
                     .append(", avg=").append(String.format("%.2f", avgTime / 1_000_000.0)).append("ms")
                     .append(", min=").append(String.format("%.2f", minTime / 1_000_000.0)).append("ms")
                     .append(", max=").append(String.format("%.2f", maxTime / 1_000_000.0)).append("ms")
                     .append("\n");
            }
        }

        return stats.toString();
    }

    /**
     * Get category name for logging
     */
    private static String getCategoryName(int category) {
        switch (category) {
            case CATEGORY_FRAME: return "Frame";
            case CATEGORY_DRAW: return "Draw";
            case CATEGORY_COMPUTE: return "Compute";
            case CATEGORY_COPY: return "Copy";
            case CATEGORY_RESOURCE_CREATION: return "ResourceCreation";
            case CATEGORY_SHADER_COMPILATION: return "ShaderCompilation";
            case CATEGORY_PIPELINE_CREATION: return "PipelineCreation";
            case CATEGORY_TEXTURE_CREATION: return "TextureCreation";
            case CATEGORY_BUFFER_CREATION: return "BufferCreation";
            case CATEGORY_BARRIER: return "Barrier";
            case CATEGORY_PRESENT: return "Present";
            default: return "Unknown(" + category + ")";
        }
    }

    /**
     * Trim samples if too many are stored
     */
    private void trimSamplesIfNecessary() {
        if (timingSamples.size() > maxSamples) {
            int removeCount = timingSamples.size() - maxSamples;
            timingSamples.subList(0, removeCount).clear();
        }
    }

    /**
     * Update minimum frame time
     */
    private void updateMinFrameTime(long frameTime) {
        long currentMin = minFrameTime.get();
        while (frameTime < currentMin) {
            if (minFrameTime.compareAndSet(currentMin, frameTime)) {
                break;
            }
            currentMin = minFrameTime.get();
        }
    }

    /**
     * Update maximum frame time
     */
    private void updateMaxFrameTime(long frameTime) {
        long currentMax = maxFrameTime.get();
        while (frameTime > currentMax) {
            if (maxFrameTime.compareAndSet(currentMax, frameTime)) {
                break;
            }
            currentMax = maxFrameTime.get();
        }
    }

    /**
     * Reset all statistics
     */
    public void reset() {
        timingSamples.clear();
        counters.clear();
        for (List<TimingSample> samples : categorizedSamples.values()) {
            samples.clear();
        }

        frameCounter.set(0);
        totalFrameTime.set(0);
        minFrameTime.set(Long.MAX_VALUE);
        maxFrameTime.set(Long.MIN_VALUE);
        recentFrameTimes.clear();

        LOGGER.info("D3D12 Profiler statistics reset");
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        reset();
        LOGGER.info("D3D12 Performance Profiler cleanup completed");
    }

    /**
     * Export statistics to JSON for external analysis
     */
    public String exportToJSON() {
        // This would create a comprehensive JSON export
        // For now, return basic stats in JSON format
        return "{\n" +
               "  \"enabled\": " + enabled + ",\n" +
               "  \"frameCount\": " + frameCounter.get() + ",\n" +
               "  \"counters\": " + counters.size() + "\n" +
               "}";
    }
}