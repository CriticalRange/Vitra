package com.vitra.compat;

/**
 * Compatibility class for GpuDevice which may not exist in all Minecraft versions
 * This provides the correct interface for GpuDevice functionality
 */
public class GpuDevice {
    private boolean initialized = false;
    private String name = "DirectX11";

    public GpuDevice() {
        this.initialized = true;
    }

    public GpuDevice(String name) {
        this.name = name;
        this.initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getName() {
        return name;
    }

    public void initialize() {
        this.initialized = true;
    }

    public void shutdown() {
        this.initialized = false;
    }

    /**
     * CompiledRenderPipeline implementation for compatibility
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
    }

    @Override
    public String toString() {
        return String.format("GpuDevice[name=%s, initialized=%s]", name, initialized);
    }
}