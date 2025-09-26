package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * BGFX binary shader loader for pre-compiled .bin shader files
 * Shaders should be compiled offline using BGFX's shaderc tool
 */
public class BgfxShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxShaderCompiler");

    /**
     * Load a compiled BGFX shader binary from resources
     * @param resourcePath Path to .bin shader file in resources
     * @return BGFX shader handle or BGFX_INVALID_HANDLE on failure
     */
    public static short loadShader(String resourcePath) {
        try (InputStream inputStream = BgfxShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                LOGGER.error("Shader binary not found: {}", resourcePath);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            // Read all bytes from the binary file
            byte[] shaderData = inputStream.readAllBytes();

            // Create ByteBuffer for BGFX
            ByteBuffer shaderBuffer = ByteBuffer.allocateDirect(shaderData.length);
            shaderBuffer.put(shaderData);
            shaderBuffer.flip();

            // Copy to BGFX memory and create shader
            BGFXMemory bgfxMemory = BGFX.bgfx_copy(shaderBuffer);
            short shaderHandle = BGFX.bgfx_create_shader(bgfxMemory);

            if (shaderHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to create BGFX shader from binary: {}", resourcePath);
            } else {
                LOGGER.info("Successfully loaded BGFX shader: {} (handle: {})", resourcePath, shaderHandle);
            }

            return shaderHandle;

        } catch (IOException e) {
            LOGGER.error("Error loading shader binary: {}", resourcePath, e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Load vertex shader binary for DirectX 11
     * @param shaderName Name of the shader (without .bin extension)
     * @return BGFX shader handle
     */
    public static short loadVertexShader(String shaderName) {
        String resourcePath = "/shaders/dx11/" + shaderName + ".bin";
        LOGGER.debug("Loading vertex shader: {}", resourcePath);
        return loadShader(resourcePath);
    }

    /**
     * Load fragment shader binary for DirectX 11
     * @param shaderName Name of the shader (without .bin extension)
     * @return BGFX shader handle
     */
    public static short loadFragmentShader(String shaderName) {
        String resourcePath = "/shaders/dx11/" + shaderName + ".bin";
        LOGGER.debug("Loading fragment shader: {}", resourcePath);
        return loadShader(resourcePath);
    }

    /**
     * Load basic vertex shader for simple rendering
     * @return BGFX vertex shader handle
     */
    public static short createBasicVertexShader() {
        return loadVertexShader("vs_basic");
    }

    /**
     * Load basic fragment shader for simple rendering
     * @return BGFX fragment shader handle
     */
    public static short createBasicFragmentShader() {
        return loadFragmentShader("fs_basic");
    }

    /**
     * Load vertex shader for Minecraft chunk rendering
     * @return BGFX vertex shader handle
     */
    public static short createConvertedVertexShader() {
        return loadVertexShader("vs_chunk");
    }

    /**
     * Load fragment shader for Minecraft chunk rendering
     * @return BGFX fragment shader handle
     */
    public static short createConvertedFragmentShader() {
        return loadFragmentShader("fs_chunk");
    }

    /**
     * Create a shader program from pre-compiled vertex and fragment shaders
     * @param vertexShaderName Name of vertex shader binary (without .bin)
     * @param fragmentShaderName Name of fragment shader binary (without .bin)
     * @return BGFX program handle
     */
    public static short createProgram(String vertexShaderName, String fragmentShaderName) {
        try {
            short vertexShader = loadVertexShader(vertexShaderName);
            short fragmentShader = loadFragmentShader(fragmentShaderName);

            if (vertexShader == BGFX.BGFX_INVALID_HANDLE || fragmentShader == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to load shaders: {} / {}", vertexShaderName, fragmentShaderName);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            short program = BGFX.bgfx_create_program(vertexShader, fragmentShader, true);
            if (program == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to create shader program: {} / {}", vertexShaderName, fragmentShaderName);
            } else {
                LOGGER.info("Created shader program: {} / {} (handle: {})", vertexShaderName, fragmentShaderName, program);
            }

            return program;
        } catch (Exception e) {
            LOGGER.error("Error creating shader program: {} / {}", vertexShaderName, fragmentShaderName, e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create basic shader program for simple rendering
     * @return BGFX program handle
     */
    public static short createBasicProgram() {
        return createProgram("vs_basic", "fs_basic");
    }

    /**
     * Load chunk rendering shader program
     * @return BGFX program handle for chunk rendering
     */
    public static short createChunkProgram() {
        return createProgram("vs_chunk", "fs_chunk");
    }

    /**
     * Load entity rendering shader program
     * @return BGFX program handle for entity rendering
     */
    public static short createEntityProgram() {
        return createProgram("vs_entity", "fs_entity");
    }

    /**
     * Load UI rendering shader program
     * @return BGFX program handle for UI rendering
     */
    public static short createGuiProgram() {
        return createProgram("vs_gui", "fs_gui");
    }

    /**
     * Check if a shader binary exists in resources
     * @param shaderName Name of the shader binary (without .bin)
     * @param isVertex true for vertex shader, false for fragment shader
     * @return true if shader binary exists
     */
    public static boolean shaderExists(String shaderName, boolean isVertex) {
        String resourcePath = "/shaders/dx11/" + shaderName + ".bin";
        return BgfxShaderCompiler.class.getResource(resourcePath) != null;
    }

    /**
     * Get information about available shader binaries
     * @return String describing available shaders
     */
    public static String getShaderInfo() {
        StringBuilder info = new StringBuilder("Available BGFX shader binaries:\n");

        String[] shaders = {"vs_basic", "fs_basic", "vs_chunk", "fs_chunk", "vs_entity", "fs_entity", "vs_gui", "fs_gui"};

        for (String shader : shaders) {
            boolean exists = shaderExists(shader, shader.startsWith("vs_"));
            info.append(String.format("  %s: %s\n", shader, exists ? "FOUND" : "MISSING"));
        }

        return info.toString();
    }

    /**
     * Destroy a BGFX shader handle
     * @param shaderHandle BGFX shader handle to destroy
     */
    public static void destroyShader(short shaderHandle) {
        if (shaderHandle != BGFX.BGFX_INVALID_HANDLE) {
            BGFX.bgfx_destroy_shader(shaderHandle);
            LOGGER.debug("Destroyed shader handle: {}", shaderHandle);
        }
    }

    /**
     * Destroy a BGFX program handle
     * @param programHandle BGFX program handle to destroy
     */
    public static void destroyProgram(short programHandle) {
        if (programHandle != BGFX.BGFX_INVALID_HANDLE) {
            BGFX.bgfx_destroy_program(programHandle);
            LOGGER.debug("Destroyed program handle: {}", programHandle);
        }
    }
}