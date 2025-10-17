package com.vitra.mixin;

import com.vitra.compat.GpuDevice;
import com.vitra.compat.RenderPipeline;
import com.vitra.compat.CompiledRenderPipeline;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for GpuDevice compatibility with DirectX 11 backend.
 * This provides GpuDevice functionality for Minecraft 1.21.1 compatibility.
 *
 * Key features:
 * - DirectX 11 pipeline compilation and management
 * - GpuDevice singleton pattern implementation
 * - Pipeline caching and optimization
 * - Integration with Vitra's DirectX 11 backend
 */
public class GpuDeviceHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(GpuDeviceHelper.class);

    // Performance tracking
    private static long totalPipelinesCreated = 0;
    private static long totalPipelinesCompiled = 0;

    /**
     * Initialize GpuDevice for DirectX 11 backend
     */
    public static void initializeGpuDevice() {
        LOGGER.debug("GpuDevice initialized for DirectX 11 backend");
    }

    /**
     * Precompile DirectX 11 pipeline
     */
    public static CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline) {
        if (pipeline == null) {
            LOGGER.warn("Attempted to precompile null pipeline");
            return new GpuDevice.DirectxCompiledPipeline(0, "null pipeline");
        }

        LOGGER.debug("Precompiling DirectX 11 pipeline: {}", pipeline.getName());

        try {
            // Create DirectX 11 pipeline using compatibility interface
            int pipelineId = VitraNativeRenderer.createShaderPipeline(pipeline.getName());

            if (pipelineId != -1) {
                totalPipelinesCreated++;
                CompiledRenderPipeline compiled = new GpuDevice.DirectxCompiledPipeline(pipelineId, pipeline.getName());
                LOGGER.debug("Successfully precompiled DirectX 11 pipeline: {} -> {}", pipeline.getName(), pipelineId);
                return compiled;
            } else {
                LOGGER.error("Failed to create DirectX 11 pipeline: {}", pipeline.getName());
                return new GpuDevice.DirectxCompiledPipeline(0, "Failed to create pipeline: " + pipeline.getName());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to precompile DirectX 11 pipeline: {}", pipeline != null ? pipeline.getName() : "null", e);
            return new GpuDevice.DirectxCompiledPipeline(0, "Exception creating pipeline: " + (pipeline != null ? pipeline.getName() : "null"));
        }
    }

    /**
     * Compile DirectX 11 pipeline
     */
    public static CompiledRenderPipeline compilePipeline(RenderPipeline pipeline) {
        if (pipeline == null) {
            LOGGER.warn("Attempted to compile null pipeline");
            return new GpuDevice.DirectxCompiledPipeline(0, "null pipeline");
        }

        LOGGER.debug("Compiling DirectX 11 pipeline: {}", pipeline.getName());

        try {
            // Create DirectX 11 pipeline using compatibility interface
            int pipelineId = VitraNativeRenderer.createShaderPipeline(pipeline.getName());

            if (pipelineId != -1) {
                totalPipelinesCompiled++;
                CompiledRenderPipeline compiled = new GpuDevice.DirectxCompiledPipeline(pipelineId, pipeline.getName());
                LOGGER.debug("Successfully compiled DirectX 11 pipeline: {} -> {}", pipeline.getName(), pipelineId);
                return compiled;
            } else {
                LOGGER.error("Failed to compile DirectX 11 pipeline: {}", pipeline.getName());
                return new GpuDevice.DirectxCompiledPipeline(0, "Failed to compile pipeline: " + pipeline.getName());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to compile DirectX 11 pipeline: {}", pipeline != null ? pipeline.getName() : "null", e);
            return new GpuDevice.DirectxCompiledPipeline(0, "Exception compiling pipeline: " + (pipeline != null ? pipeline.getName() : "null"));
        }
    }

    /**
     * Get list of compiled pipelines
     */
    public static List<CompiledRenderPipeline> getPipelines() {
        // Return empty list for now - could be enhanced to track actual pipelines
        return new ArrayList<>();
    }

    /**
     * Check if device is initialized
     */
    public static boolean isInitialized() {
        return GLInterceptor.isAvailable() && VitraNativeRenderer.isInitialized();
    }

    /**
     * Initialize the device
     */
    public static void initialize() {
        LOGGER.debug("Initializing GpuDevice for DirectX 11");
        if (!GLInterceptor.isAvailable()) {
            LOGGER.warn("GLInterceptor not available - GpuDevice initialization incomplete");
        }
    }

    /**
     * Shutdown the device
     */
    public static void shutdown() {
        LOGGER.debug("Shutting down GpuDevice");
        // Cleanup would be handled by GLInterceptor shutdown
    }

    /**
     * DirectxCompiledPipeline implementation for compatibility
     */
    public static class DirectxCompiledPipeline implements CompiledRenderPipeline {
        private final int pipelineId;
        private final String name;
        private boolean valid = true;

        public DirectxCompiledPipeline(int pipelineId, String name) {
            this.pipelineId = pipelineId;
            this.name = name;
            this.valid = pipelineId != 0;
        }

        public int getPipelineId() {
            return pipelineId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isValid() {
            return valid && pipelineId != 0;
        }

        public void invalidate() {
            this.valid = false;
        }

        @Override
        public String toString() {
            return String.format("DirectxCompiledPipeline[id=%d, name=%s, valid=%s]",
                pipelineId, name, isValid());
        }

        /**
         * Cleanup method
         */
        public void cleanup() {
            if (valid && pipelineId != 0) {
                try {
                    VitraNativeRenderer.releaseShaderPipeline(pipelineId);
                } catch (Exception e) {
                    LOGGER.warn("Failed to cleanup pipeline {}: {}", name, e.getMessage());
                }
                invalidate();
            }
        }
    }

    /**
     * Get performance statistics
     */
    public static String getPerformanceStats() {
        return String.format("GpuDevice Stats: %d pipelines created, %d pipelines compiled",
            totalPipelinesCreated, totalPipelinesCompiled);
    }

    /**
     * Reset performance statistics
     */
    public static void resetPerformanceStats() {
        totalPipelinesCreated = 0;
        totalPipelinesCompiled = 0;
        LOGGER.info("GpuDevice: Statistics reset");
    }

    /**
     * Finalize method for cleanup
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            LOGGER.debug("GpuDevice finalizing");
            shutdown();
        } finally {
            super.finalize();
        }
    }
}