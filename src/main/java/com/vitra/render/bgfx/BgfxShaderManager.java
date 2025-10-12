package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages shader programs for BGFX rendering.
 *
 * This class uses ONLY BGFX's native methods:
 * - bgfx_create_shader() - Create shader from compiled binary
 * - bgfx_create_program() - Link vertex and fragment shaders
 * - bgfx_submit() - Submit draw call with program
 * - bgfx_destroy_program() - Release program resource
 * - bgfx_destroy_shader() - Release shader resource
 *
 * NO custom shader compilation or custom implementations.
 * All shader operations are delegated to BGFX's native API.
 */
public class BgfxShaderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxShaderManager");

    // Cache: Shader name -> BGFX program handle
    private final Map<String, Short> programHandles = new ConcurrentHashMap<>();

    // Track active program (for bgfx_submit)
    private short activeProgram = -1;

    /**
     * Register a BGFX program handle for a shader name.
     * Does NOT create the program - program creation is done using bgfx_create_program().
     *
     * @param shaderName Minecraft shader name (e.g. "rendertype_solid")
     * @param programHandle BGFX program handle (from bgfx_create_program)
     */
    public void registerProgram(String shaderName, short programHandle) {
        programHandles.put(shaderName, programHandle);
        LOGGER.debug("Registered shader program: {} -> BGFX handle {}", shaderName, programHandle);
    }

    /**
     * Get cached BGFX program handle for a shader name.
     *
     * @param shaderName Minecraft shader name
     * @return BGFX program handle, or -1 if not found
     */
    public short getProgramHandle(String shaderName) {
        return programHandles.getOrDefault(shaderName, (short) -1);
    }

    /**
     * Set the active shader program for rendering.
     * This program will be used in the next bgfx_submit() call.
     *
     * @param programHandle BGFX program handle
     */
    public void setActiveProgram(short programHandle) {
        this.activeProgram = programHandle;
        LOGGER.trace("Active shader program set to: {}", programHandle);
    }

    /**
     * Get the currently active shader program.
     * This handle should be passed to bgfx_submit().
     *
     * @return BGFX program handle
     */
    public short getActiveProgram() {
        return activeProgram;
    }

    /**
     * Destroy shader program using BGFX native method.
     * Uses: bgfx_destroy_program()
     *
     * @param programHandle BGFX program handle to destroy
     */
    public void destroyProgram(short programHandle) {
        if (Util.isValidHandle(programHandle)) {
            BGFX.bgfx_destroy_program(programHandle);
            LOGGER.debug("Destroyed BGFX program: handle={}", programHandle);
        }
    }

    /**
     * Destroy shader using BGFX native method.
     * Uses: bgfx_destroy_shader()
     *
     * @param shaderHandle BGFX shader handle to destroy
     */
    public void destroyShader(short shaderHandle) {
        if (Util.isValidHandle(shaderHandle)) {
            BGFX.bgfx_destroy_shader(shaderHandle);
            LOGGER.debug("Destroyed BGFX shader: handle={}", shaderHandle);
        }
    }

    /**
     * Cleanup all resources using BGFX native methods.
     * Uses: bgfx_destroy_program()
     */
    public void shutdown() {
        // Destroy all program handles
        programHandles.values().forEach(handle -> {
            if (Util.isValidHandle(handle)) {
                BGFX.bgfx_destroy_program(handle);
            }
        });
        programHandles.clear();
        activeProgram = -1;

        LOGGER.info("BgfxShaderManager shutdown complete");
    }
}
