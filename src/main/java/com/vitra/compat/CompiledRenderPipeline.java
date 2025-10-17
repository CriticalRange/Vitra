package com.vitra.compat;

/**
 * Compatibility class for CompiledRenderPipeline which may not exist in all Minecraft versions
 * This provides the correct interface for CompiledRenderPipeline functionality
 */
public interface CompiledRenderPipeline {
    /**
     * Get the name of this compiled pipeline
     */
    String getName();

    /**
     * Check if this pipeline is valid and ready for use
     */
    boolean isValid();

    /**
     * Get a string representation of this pipeline
     */
    String toString();
}