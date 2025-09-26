package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BGFX binary shader loader for pre-compiled .bin shader files
 * Shaders should be compiled offline using BGFX's shaderc tool
 */
public class BgfxShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxShaderCompiler");

    // Cache for loaded shaders and programs to avoid reloading
    private static final Map<String, Short> shaderCache = new ConcurrentHashMap<>();
    private static final Map<String, Short> programCache = new ConcurrentHashMap<>();

    // Resource limits to prevent memory leaks
    private static final int MAX_CACHED_SHADERS = 50;
    private static final int MAX_CACHED_PROGRAMS = 25;

    /**
     * Load a compiled BGFX shader binary from resources (cached)
     * @param resourcePath Path to .bin shader file in resources
     * @return BGFX shader handle or BGFX_INVALID_HANDLE on failure
     */
    public static short loadShader(String resourcePath) {
        // Check cache first
        Short cachedHandle = shaderCache.get(resourcePath);
        if (cachedHandle != null) {
            LOGGER.debug("Using cached shader: {} (handle: {})", resourcePath, cachedHandle);
            return cachedHandle;
        }

        try (InputStream inputStream = BgfxShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                LOGGER.error("*** SHADER DEBUG: Binary not found: {}", resourcePath);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            // Read all bytes from the binary file
            byte[] shaderData = inputStream.readAllBytes();
            LOGGER.debug("*** SHADER DEBUG: Loading new shader - {} bytes from {}", shaderData.length, resourcePath);

            // Create ByteBuffer for BGFX
            ByteBuffer shaderBuffer = ByteBuffer.allocateDirect(shaderData.length);
            shaderBuffer.put(shaderData);
            shaderBuffer.flip();

            // Copy to BGFX memory and create shader
            BGFXMemory bgfxMemory = BGFX.bgfx_copy(shaderBuffer);
            if (bgfxMemory == null) {
                LOGGER.error("*** SHADER DEBUG: Failed to create BGFX memory for {}", resourcePath);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            short shaderHandle = BGFX.bgfx_create_shader(bgfxMemory);
            LOGGER.debug("*** SHADER DEBUG: bgfx_create_shader returned handle {} for {} (INVALID={}) ", shaderHandle, resourcePath, BGFX.BGFX_INVALID_HANDLE);

            if (shaderHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("*** SHADER DEBUG: Failed to create BGFX shader from binary: {}", resourcePath);
            } else {
                LOGGER.debug("*** SHADER DEBUG: Successfully loaded BGFX shader: {} (handle: {})", resourcePath, shaderHandle);
                // Cache the successful shader with size limit
                if (shaderCache.size() < MAX_CACHED_SHADERS) {
                    shaderCache.put(resourcePath, shaderHandle);
                } else {
                    LOGGER.warn("Shader cache full ({} shaders), not caching: {}", MAX_CACHED_SHADERS, resourcePath);
                }
            }

            return shaderHandle;

        } catch (IOException e) {
            LOGGER.error("*** SHADER DEBUG: Error loading shader binary: {}", resourcePath, e);
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
     * Create a shader program from pre-compiled vertex and fragment shaders (cached)
     * @param vertexShaderName Name of vertex shader binary (without .bin)
     * @param fragmentShaderName Name of fragment shader binary (without .bin)
     * @return BGFX program handle
     */
    public static short createProgram(String vertexShaderName, String fragmentShaderName) {
        String programKey = vertexShaderName + "+" + fragmentShaderName;

        // Check cache first
        Short cachedProgram = programCache.get(programKey);
        if (cachedProgram != null) {
            LOGGER.debug("Using cached program: {} (handle: {})", programKey, cachedProgram);
            return cachedProgram;
        }

        try {
            short vertexShader = loadVertexShader(vertexShaderName);
            short fragmentShader = loadFragmentShader(fragmentShaderName);

            if (vertexShader == BGFX.BGFX_INVALID_HANDLE || fragmentShader == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to load shaders: {} / {}", vertexShaderName, fragmentShaderName);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            LOGGER.debug("*** SHADER DEBUG: About to link new program with vertex {} (handle {}) and fragment {} (handle {})", vertexShaderName, vertexShader, fragmentShaderName, fragmentShader);
            short program = BGFX.bgfx_create_program(vertexShader, fragmentShader, true);
            LOGGER.info("*** FRAMEBUFFER TEST: Created shader program {} / {} = handle {} (vertex={}, fragment={})",
                       vertexShaderName, fragmentShaderName, program, vertexShader, fragmentShader);

            if (program == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("*** SHADER DEBUG: Failed to create shader program: {} / {} - Individual shaders loaded OK but linking failed", vertexShaderName, fragmentShaderName);
            } else {
                LOGGER.debug("*** SHADER DEBUG: Created shader program: {} / {} (handle: {})", vertexShaderName, fragmentShaderName, program);
                // Cache the successful program with size limit
                if (programCache.size() < MAX_CACHED_PROGRAMS) {
                    programCache.put(programKey, program);
                } else {
                    LOGGER.warn("Program cache full ({} programs), not caching: {}", MAX_CACHED_PROGRAMS, programKey);
                }
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
        // Use working position+tex+color shader instead of broken vs_basic/fs_basic
        return createProgram("vs_position_tex_color", "fs_position_tex_color");
    }

    /**
     * Load terrain/chunk rendering shader program
     * @return BGFX program handle for terrain rendering
     */
    public static short createChunkProgram() {
        return createProgram("vs_terrain", "fs_terrain");
    }

    /**
     * Load entity rendering shader program
     * @return BGFX program handle for entity rendering
     */
    public static short createEntityProgram() {
        return createProgram("vs_entity", "fs_entity");
    }

    /**
     * Load UI/GUI rendering shader program
     * @return BGFX program handle for UI rendering
     */
    public static short createGuiProgram() {
        return createProgram("vs_gui", "fs_gui");
    }

    /**
     * Load position+color shader program
     * @return BGFX program handle for position+color rendering
     */
    public static short createPositionColorProgram() {
        return createProgram("vs_position_color", "fs_position_color");
    }

    /**
     * Load position+texture shader program
     * @return BGFX program handle for position+texture rendering
     */
    public static short createPositionTexProgram() {
        return createProgram("vs_position_tex", "fs_position_tex");
    }

    /**
     * Load position+texture+color shader program
     * @return BGFX program handle for position+texture+color rendering
     */
    public static short createPositionTexColorProgram() {
        return createProgram("vs_position_tex_color", "fs_position_tex_color");
    }

    /**
     * Load position shader program (minimal)
     * @return BGFX program handle for position-only rendering
     */
    public static short createPositionProgram() {
        return createProgram("vs_position", "fs_position");
    }

    /**
     * Load particle rendering shader program
     * @return BGFX program handle for particle rendering
     */
    public static short createParticleProgram() {
        return createProgram("vs_particle", "fs_particle");
    }

    /**
     * Load sky rendering shader program
     * @return BGFX program handle for sky rendering
     */
    public static short createSkyProgram() {
        return createProgram("vs_sky", "fs_sky");
    }

    /**
     * Load stars rendering shader program
     * @return BGFX program handle for stars rendering
     */
    public static short createStarsProgram() {
        return createProgram("vs_stars", "fs_stars");
    }

    /**
     * Load lightmap shader program
     * @return BGFX program handle for lightmap rendering
     */
    public static short createLightmapProgram() {
        return createProgram("vs_position_color_lightmap", "fs_lightmap");
    }

    /**
     * Load glint/enchantment shader program
     * @return BGFX program handle for glint rendering
     */
    public static short createGlintProgram() {
        return createProgram("vs_glint", "fs_glint");
    }

    /**
     * Load line rendering shader program
     * @return BGFX program handle for line rendering
     */
    public static short createLinesProgram() {
        return createProgram("vs_rendertype_lines", "fs_rendertype_lines");
    }

    /**
     * Load transparency shader program
     * @return BGFX program handle for transparency rendering
     */
    public static short createTransparencyProgram() {
        return createProgram("vs_position_tex_color", "fs_transparency_dx11");
    }

    /**
     * Load panorama shader program
     * @return BGFX program handle for panorama rendering
     */
    public static short createPanoramaProgram() {
        return createProgram("vs_panorama", "fs_panorama");
    }

    /**
     * Load blit shader program for screen copying
     * @return BGFX program handle for blit operations
     */
    public static short createBlitProgram() {
        return createProgram("vs_blit", "fs_blit");
    }

    /**
     * Load text rendering shader program
     * @return BGFX program handle for text rendering
     */
    public static short createRenderTypeTextProgram() {
        return createProgram("vs_rendertype_text", "fs_rendertype_text");
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
        StringBuilder info = new StringBuilder("Available BGFX DirectX 11 shader binaries:\n");

        String[] shaders = {
            "vs_basic", "fs_basic",
            "vs_terrain", "fs_terrain",
            "vs_entity", "fs_entity",
            "vs_gui", "fs_gui",
            "vs_position", "fs_position",
            "vs_position_color", "fs_position_color",
            "vs_position_tex", "fs_position_tex",
            "vs_position_tex_color", "fs_position_tex_color",
            "vs_particle", "fs_particle",
            "vs_sky", "fs_sky",
            "vs_stars", "fs_stars",
            "vs_glint", "fs_glint",
            "vs_rendertype_lines", "fs_rendertype_lines",
            "vs_rendertype_text", "fs_rendertype_text",
            "vs_panorama", "fs_panorama",
            "vs_blit", "fs_blit",
            "fs_transparency_dx11", "fs_lightmap"
        };

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