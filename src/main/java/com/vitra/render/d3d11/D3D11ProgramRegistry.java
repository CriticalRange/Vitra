package com.vitra.render.d3d11;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for associating OpenGL program IDs with D3D11 pipelines.
 * Similar to VulkanMod's VkGlProgram system.
 *
 * This class maintains the mapping between Minecraft's OpenGL-style programId
 * and our D3D11 shader pipeline handles, ensuring that when a shader
 * calls apply(), the correct pipeline is bound.
 */
public class D3D11ProgramRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11ProgramRegistry.class);

    /**
     * Map of OpenGL program ID -> D3D11 Pipeline
     * This is the key mapping that associates Minecraft's shader system with our D3D11 backend.
     */
    private static final ConcurrentHashMap<Integer, D3D11Pipeline> programToPipeline = new ConcurrentHashMap<>();

    /**
     * Register a pipeline with a program ID.
     * This should be called after creating a shader pipeline in ShaderInstanceMixin.
     *
     * @param programId The OpenGL program ID from ShaderInstance
     * @param pipeline The D3D11 pipeline to associate
     */
    public static void registerPipeline(int programId, D3D11Pipeline pipeline) {
        if (programId <= 0) {
            LOGGER.warn("Attempted to register pipeline with invalid programId: {}", programId);
            return;
        }

        if (pipeline == null) {
            LOGGER.warn("Attempted to register null pipeline for programId: {}", programId);
            return;
        }

        D3D11Pipeline existing = programToPipeline.put(programId, pipeline);

        if (existing != null) {
            LOGGER.debug("Replaced existing pipeline for programId {}: {} -> {}",
                programId, existing.getName(), pipeline.getName());
        } else {
            LOGGER.debug("Registered pipeline '{}' for programId {}", pipeline.getName(), programId);
        }
    }

    /**
     * Get the pipeline associated with a program ID.
     *
     * @param programId The OpenGL program ID
     * @return The associated D3D11 pipeline, or null if not found
     */
    public static D3D11Pipeline getPipeline(int programId) {
        D3D11Pipeline pipeline = programToPipeline.get(programId);

        if (pipeline == null) {
            LOGGER.warn("No pipeline found for programId: {}", programId);
        }

        return pipeline;
    }

    /**
     * Unregister a pipeline for a program ID.
     *
     * @param programId The OpenGL program ID
     */
    public static void unregisterPipeline(int programId) {
        D3D11Pipeline removed = programToPipeline.remove(programId);

        if (removed != null) {
            LOGGER.debug("Unregistered pipeline '{}' for programId {}", removed.getName(), programId);
        }
    }

    /**
     * Clear all registered pipelines.
     * Should be called during renderer cleanup.
     */
    public static void clear() {
        int count = programToPipeline.size();
        programToPipeline.clear();
        LOGGER.info("Cleared {} registered pipelines", count);
    }

    /**
     * Get the number of registered pipelines.
     *
     * @return The number of pipelines in the registry
     */
    public static int getRegisteredCount() {
        return programToPipeline.size();
    }

    /**
     * Check if a program ID has a registered pipeline.
     *
     * @param programId The OpenGL program ID
     * @return true if a pipeline is registered for this ID
     */
    public static boolean hasRegisteredPipeline(int programId) {
        return programToPipeline.containsKey(programId);
    }

    /**
     * Get a pipeline by name (e.g., "position", "position_tex").
     * Useful for auto-selecting correct pipeline based on vertex format.
     *
     * @param name The shader/pipeline name
     * @return The pipeline with matching name, or null if not found
     */
    public static D3D11Pipeline getPipelineByName(String name) {
        for (D3D11Pipeline pipeline : programToPipeline.values()) {
            if (pipeline.getName().equals(name)) {
                return pipeline;
            }
        }
        LOGGER.debug("No pipeline found with name: {}", name);
        return null;
    }
}
