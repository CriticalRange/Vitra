package com.vitra.benchmark;

import com.vitra.config.RendererType;
import com.vitra.render.IVitraRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Performance benchmarking tool for Vitra renderers
 * Supports comparison between DirectX 11 and DirectX 12 Ultimate
 */
public class PerformanceBenchmark {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceBenchmark.class);

    private IVitraRenderer renderer;
    private boolean isRunning = false;
    private long benchmarkStartTime = 0L;

    // Performance metrics
    private final List<Long> frameTimes = new ArrayList<>();
    private final List<Float> gpuUtilizations = new ArrayList<>();
    private final List<Integer> drawCalls = new ArrayList<>();
    private long totalFrames = 0;
    private long totalRenderTime = 0;

    // Benchmark results
    private final Map<RendererType, BenchmarkResult> results = new HashMap<>();

    public PerformanceBenchmark(IVitraRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Start benchmarking session
     */
    public void startBenchmark() {
        if (isRunning) {
            LOGGER.warn("Benchmark already running");
            return;
        }

        LOGGER.info("Starting performance benchmark for {} renderer", renderer.getRendererType().getDisplayName());

        reset();
        isRunning = true;
        benchmarkStartTime = System.nanoTime();

        // Reset renderer performance counters
        renderer.resetPerformanceCounters();
    }

    /**
     * Stop benchmarking session and calculate results
     */
    public BenchmarkResult stopBenchmark() {
        if (!isRunning) {
            LOGGER.warn("No benchmark running");
            return null;
        }

        long endTime = System.nanoTime();
        totalRenderTime = TimeUnit.NANOSECONDS.toMillis(endTime - benchmarkStartTime);
        isRunning = false;

        BenchmarkResult result = calculateResults();
        results.put(renderer.getRendererType(), result);

        LOGGER.info("Benchmark completed for {} renderer", renderer.getRendererType().getDisplayName());
        LOGGER.info("Average FPS: {:.2f}, Average Frame Time: {:.2f}ms",
            result.getAverageFPS(), result.getAverageFrameTime());

        return result;
    }

    /**
     * Record frame data during benchmark
     */
    public void recordFrame() {
        if (!isRunning) return;

        totalFrames++;

        long frameTime = renderer.getFrameTime();
        float gpuUtilization = renderer.getGpuUtilization();
        int drawCallCount = renderer.getDrawCallsPerFrame();

        frameTimes.add(frameTime);
        gpuUtilizations.add(gpuUtilization);
        drawCalls.add(drawCallCount);
    }

    /**
     * Reset benchmark data
     */
    public void reset() {
        frameTimes.clear();
        gpuUtilizations.clear();
        drawCalls.clear();
        totalFrames = 0;
        totalRenderTime = 0;
        benchmarkStartTime = 0L;
    }

    /**
     * Calculate benchmark results from collected data
     */
    private BenchmarkResult calculateResults() {
        if (frameTimes.isEmpty()) {
            return new BenchmarkResult(renderer.getRendererType(), 0, 0, 0, 0, 0, 0, 0);
        }

        // Calculate frame time statistics
        double totalFrameTime = frameTimes.stream().mapToLong(Long::longValue).sum();
        double averageFrameTime = totalFrameTime / frameTimes.size();
        long minFrameTime = frameTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxFrameTime = frameTimes.stream().mapToLong(Long::longValue).max().orElse(0);

        // Calculate GPU utilization statistics
        double totalGpuUtilization = gpuUtilizations.stream().mapToDouble(Float::doubleValue).sum();
        double averageGpuUtilization = totalGpuUtilization / gpuUtilizations.size();

        // Calculate draw call statistics
        double totalDrawCalls = drawCalls.stream().mapToInt(Integer::intValue).sum();
        double averageDrawCalls = totalDrawCalls / drawCalls.size();

        // Calculate FPS
        double averageFPS = 1000.0 / averageFrameTime;

        return new BenchmarkResult(
            renderer.getRendererType(),
            averageFPS,
            averageFrameTime,
            minFrameTime,
            maxFrameTime,
            averageGpuUtilization,
            averageDrawCalls,
            totalFrames
        );
    }

    /**
     * Compare performance between different renderers
     */
    public String compareRenderers() {
        if (results.size() < 2) {
            return "Need at least 2 renderer benchmarks for comparison";
        }

        StringBuilder comparison = new StringBuilder();
        comparison.append("=== Vitra Renderer Performance Comparison ===\n\n");

        for (Map.Entry<RendererType, BenchmarkResult> entry : results.entrySet()) {
            BenchmarkResult result = entry.getValue();
            comparison.append(String.format("%s Results:\n", entry.getKey().getDisplayName()));
            comparison.append(String.format("  Average FPS: %.2f\n", result.getAverageFPS()));
            comparison.append(String.format("  Average Frame Time: %.2f ms\n", result.getAverageFrameTime()));
            comparison.append(String.format("  Min/Max Frame Time: %.2f/%.2f ms\n",
                result.getMinFrameTime(), result.getMaxFrameTime()));
            comparison.append(String.format("  Average GPU Utilization: %.1f%%\n", result.getAverageGpuUtilization()));
            comparison.append(String.format("  Average Draw Calls: %.0f\n", result.getAverageDrawCalls()));
            comparison.append(String.format("  Total Frames: %d\n\n", result.getTotalFrames()));
        }

        // Performance improvement calculations
        if (results.containsKey(RendererType.DIRECTX11) &&
            results.containsKey(RendererType.DIRECTX12_ULTIMATE)) {

            BenchmarkResult dx11Result = results.get(RendererType.DIRECTX11);
            BenchmarkResult dx12Result = results.get(RendererType.DIRECTX12_ULTIMATE);

            double fpsImprovement = ((dx12Result.getAverageFPS() - dx11Result.getAverageFPS()) / dx11Result.getAverageFPS()) * 100;
            double frameTimeImprovement = ((dx11Result.getAverageFrameTime() - dx12Result.getAverageFrameTime()) / dx11Result.getAverageFrameTime()) * 100;

            comparison.append("Performance Improvements (DirectX 12 Ultimate vs DirectX 11):\n");
            comparison.append(String.format("  FPS Improvement: %.1f%%\n", fpsImprovement));
            comparison.append(String.format("  Frame Time Reduction: %.1f%%\n", frameTimeImprovement));
        }

        return comparison.toString();
    }

    /**
     * Run comprehensive benchmark suite
     */
    public void runBenchmarkSuite() {
        LOGGER.info("Starting comprehensive benchmark suite...");

        // This would run different test scenarios
        // For now, we'll just log what would be tested
        LOGGER.info("Benchmark scenarios would include:");
        LOGGER.info("  - Static scene rendering");
        LOGGER.info("  - Moving entities");
        LOGGER.info("  - Complex terrain");
        LOGGER.info("  - Particle effects");
        LOGGER.info("  - GUI rendering");
        LOGGER.info("  - Ray tracing (if supported)");
        LOGGER.info("  - Variable Rate Shading (if supported)");
    }

    /**
     * Get current benchmark status
     */
    public String getBenchmarkStatus() {
        if (!isRunning) {
            return "Benchmark not running";
        }

        long currentTime = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currentTime - benchmarkStartTime);

        StringBuilder status = new StringBuilder();
        status.append("=== Benchmark Status ===\n");
        status.append("Renderer: ").append(renderer.getRendererType().getDisplayName()).append("\n");
        status.append("Running Time: ").append(elapsedMs / 1000.0).append("s\n");
        status.append("Frames Recorded: ").append(totalFrames).append("\n");

        if (!frameTimes.isEmpty()) {
            double recentAvgFrameTime = frameTimes.subList(Math.max(0, frameTimes.size() - 60), frameTimes.size())
                .stream().mapToLong(Long::longValue).average().orElse(0);
            status.append("Recent Average FPS: ").append(String.format("%.1f", 1000.0 / recentAvgFrameTime)).append("\n");
        }

        return status.toString();
    }

    // Getters
    public boolean isRunning() {
        return isRunning;
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    public Map<RendererType, BenchmarkResult> getResults() {
        return new HashMap<>(results);
    }

    /**
     * Benchmark result data class
     */
    public static class BenchmarkResult {
        private final RendererType rendererType;
        private final double averageFPS;
        private final double averageFrameTime;
        private final long minFrameTime;
        private final long maxFrameTime;
        private final double averageGpuUtilization;
        private final double averageDrawCalls;
        private final long totalFrames;

        public BenchmarkResult(RendererType rendererType, double averageFPS, double averageFrameTime,
                              long minFrameTime, long maxFrameTime, double averageGpuUtilization,
                              double averageDrawCalls, long totalFrames) {
            this.rendererType = rendererType;
            this.averageFPS = averageFPS;
            this.averageFrameTime = averageFrameTime;
            this.minFrameTime = minFrameTime;
            this.maxFrameTime = maxFrameTime;
            this.averageGpuUtilization = averageGpuUtilization;
            this.averageDrawCalls = averageDrawCalls;
            this.totalFrames = totalFrames;
        }

        // Getters
        public RendererType getRendererType() { return rendererType; }
        public double getAverageFPS() { return averageFPS; }
        public double getAverageFrameTime() { return averageFrameTime; }
        public long getMinFrameTime() { return minFrameTime; }
        public long getMaxFrameTime() { return maxFrameTime; }
        public double getAverageGpuUtilization() { return averageGpuUtilization; }
        public double getAverageDrawCalls() { return averageDrawCalls; }
        public long getTotalFrames() { return totalFrames; }

        @Override
        public String toString() {
            return String.format("%s: %.1f FPS, %.2f ms frame time",
                rendererType.getDisplayName(), averageFPS, averageFrameTime);
        }
    }
}