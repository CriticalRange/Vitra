package com.vitra.mixin.compatibility;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.VitraD3D11Renderer;
import com.vitra.render.VitraRenderer;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * DirectX 11 Program compatibility mixin
 *
 * Based on VulkanMod's ProgramM but adapted for DirectX 11 shader compilation.
 * Handles shader program compilation by intercepting OpenGL shader creation
 * and redirecting to precompiled DirectX 11 HLSL bytecode.
 *
 * Key responsibilities:
 * - Intercept OpenGL shader compilation (glCreateShader, glCompileShader)
 * - Load precompiled DirectX 11 shaders instead of compiling GLSL at runtime
 * - Process GLSL source for compatibility (preprocessor directives)
 * - Return dummy shader IDs for OpenGL compatibility
 * - Integrate with D3D11ShaderManager for shader caching
 *
 * Architecture:
 * Minecraft's shader system compiles GLSL shaders at runtime using OpenGL.
 * This mixin intercepts compilation and instead:
 * 1. Reads GLSL source from resources (for compatibility checking)
 * 2. Processes GLSL with preprocessor (handles #define, #ifdef, etc.)
 * 3. Loads corresponding precompiled DirectX 11 shader from /shaders/compiled/
 * 4. Returns dummy shader ID (actual DirectX 11 shader handle managed separately)
 *
 * Shader compilation flow (OpenGL):
 * 1. Program.compileShaderInternal() called by Minecraft
 * 2. GLSL source loaded from InputStream
 * 3. GLSL preprocessor processes source (handles directives)
 * 4. glCreateShader() creates OpenGL shader object
 * 5. glShaderSource() uploads GLSL source
 * 6. glCompileShader() compiles GLSL to GPU bytecode
 * 7. glGetShaderiv() checks compilation status
 * 8. Returns shader ID
 *
 * Shader compilation flow (DirectX 11):
 * 1. Program.compileShaderInternal() intercepted by this mixin
 * 2. GLSL source loaded from InputStream (same as OpenGL)
 * 3. GLSL preprocessor processes source (compatibility)
 * 4. Extract shader name from resource path
 * 5. Load precompiled DirectX 11 shader via D3D11ShaderManager
 * 6. Return dummy shader ID (0) for compatibility
 *
 * Precompiled shader naming convention:
 * - GLSL vertex shader: "shaders/program/my_shader.vsh"
 * - Compiled DX11 vertex shader: "shaders/compiled/my_shader_vs.cso"
 * - GLSL fragment shader: "shaders/program/my_shader.fsh"
 * - Compiled DX11 pixel shader: "shaders/compiled/my_shader_ps.cso"
 *
 * Future enhancements (commented architecture):
 * - GLSL→HLSL converter for runtime shader translation
 * - D3DCompile API integration for runtime HLSL compilation
 * - Shader reflection for automatic input layout generation
 */
@Mixin(Program.class)
public class ProgramMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/ProgramM");

    // Helper to get renderer instance (with null-safety check)
    private static VitraRenderer getRenderer() {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VitraRenderer not initialized yet. Ensure renderer is initialized before OpenGL calls.");
        }
        return renderer;
    }

    // Shader type constants (renderer-agnostic)
    private static final int SHADER_TYPE_VERTEX = 0;
    private static final int SHADER_TYPE_PIXEL = 1;

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL shader compilation with DirectX 11 precompiled shader loading
     *
     * Intercepts Minecraft's shader compilation and redirects to precompiled
     * DirectX 11 HLSL bytecode. GLSL source is still processed for compatibility
     * but not compiled at runtime.
     *
     * @param type Shader type (VERTEX or FRAGMENT)
     * @param shaderName Shader name (e.g., "rendertype_solid")
     * @param inputStream GLSL source input stream
     * @param sourceName Source identifier (e.g., "minecraft:shaders/core/rendertype_solid.vsh")
     * @param glslPreprocessor GLSL preprocessor for handling directives
     * @return Dummy shader ID (0) - actual DirectX 11 shader managed separately
     * @throws IOException If GLSL source cannot be read
     */
    @Overwrite
    public static int compileShaderInternal(Program.Type type, String shaderName,
                                           InputStream inputStream, String sourceName,
                                           GlslPreprocessor glslPreprocessor) throws IOException {

        LOGGER.debug("Intercepting shader compilation: type={}, name={}, source={}",
            type.getName(), shaderName, sourceName);

        try {
            // Step 1: Read GLSL source from input stream
            String glslSource = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

            if (glslSource == null || glslSource.isEmpty()) {
                throw new IOException("Could not load program " + type.getName() +
                    " from source: " + sourceName);
            }

            LOGGER.trace("Loaded GLSL source ({} bytes): {}", glslSource.length(), sourceName);

            // Step 2: Process GLSL with preprocessor (handles #define, #ifdef, etc.)
            // This ensures compatibility with Minecraft's shader system even though
            // we're not actually compiling GLSL
            // Note: process() returns List<String> in Minecraft 1.21.1
            glslPreprocessor.process(glslSource);

            LOGGER.trace("Processed GLSL with preprocessor");

            // Step 3: Load precompiled DirectX 11 shader
            // Extract shader base name from source path
            // Example: "minecraft:shaders/core/rendertype_solid.vsh" -> "rendertype_solid"
            String shaderBaseName = extractShaderBaseName(sourceName, shaderName);

            // Determine DirectX 11 shader type
            int d3d11ShaderType = mapProgramTypeToD3D11(type);

            LOGGER.debug("Loading precompiled DirectX 11 shader: baseName={}, type={}",
                shaderBaseName, d3d11ShaderType);

            // Load shader via D3D11ShaderManager
            D3D11ShaderManager shaderManager = getShaderManager();
            if (shaderManager != null) {
                long shaderHandle = shaderManager.loadShader(shaderBaseName, d3d11ShaderType);

                if (shaderHandle == 0) {
                    LOGGER.warn("Failed to load precompiled DirectX 11 shader: {}", shaderBaseName);
                } else {
                    LOGGER.debug("Loaded DirectX 11 shader successfully: {} (handle: 0x{})",
                        shaderBaseName, Long.toHexString(shaderHandle));
                }
            } else {
                LOGGER.warn("D3D11ShaderManager not available, shader not loaded: {}", shaderBaseName);
            }

            // TODO: Strategy 2 - GLSL→HLSL runtime converter (future enhancement)
            /*
            // Convert GLSL to HLSL at runtime
            GlslToHlslConverter converter = new GlslToHlslConverter();
            converter.setShaderType(type);
            converter.process(processedGlsl);
            String hlslSource = converter.getHlslSource();

            // Compile HLSL to DirectX 11 bytecode at runtime
            D3DCompiler compiler = new D3DCompiler();
            String profile = (type == Program.Type.VERTEX) ? "vs_5_0" : "ps_5_0";
            byte[] bytecode = compiler.compile(hlslSource, "main", profile);

            // Create DirectX 11 shader from bytecode
            long shaderHandle = getRenderer().createGLProgramShader(
                bytecode, bytecode.length, d3d11ShaderType);

            LOGGER.info("Compiled runtime HLSL shader: {} (handle: 0x{})",
                shaderBaseName, Long.toHexString(shaderHandle));
            */

            // Step 4: Return dummy shader ID
            // OpenGL compatibility requires a shader ID, but DirectX 11 shaders
            // are managed separately via handles in D3D11ShaderManager
            return 0;

        } catch (IOException e) {
            LOGGER.error("IOException during shader compilation interception: {}", sourceName, e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Unexpected exception during shader compilation: {}", sourceName, e);
            throw new IOException("Failed to compile shader: " + sourceName, e);
        }
    }

    /**
     * Extract shader base name from source path
     *
     * Converts Minecraft resource location to shader base name:
     * - "minecraft:shaders/core/rendertype_solid.vsh" -> "rendertype_solid"
     * - "shaders/program/my_effect.fsh" -> "my_effect"
     *
     * @param sourceName Source identifier (resource location)
     * @param fallbackName Fallback name if extraction fails
     * @return Shader base name
     */
    private static String extractShaderBaseName(String sourceName, String fallbackName) {
        try {
            // Remove namespace prefix (minecraft:)
            String path = sourceName;
            int namespaceIndex = path.indexOf(':');
            if (namespaceIndex >= 0) {
                path = path.substring(namespaceIndex + 1);
            }

            // Extract filename from path
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                path = path.substring(lastSlash + 1);
            }

            // Remove file extension (.vsh, .fsh)
            int lastDot = path.lastIndexOf('.');
            if (lastDot >= 0) {
                path = path.substring(0, lastDot);
            }

            return path;
        } catch (Exception e) {
            LOGGER.warn("Failed to extract shader base name from: {}, using fallback: {}",
                sourceName, fallbackName, e);
            return fallbackName;
        }
    }

    /**
     * Map Minecraft Program.Type to DirectX 11 shader type
     *
     * @param type Minecraft shader type
     * @return DirectX 11 shader type constant
     */
    private static int mapProgramTypeToD3D11(Program.Type type) {
        return switch (type) {
            case VERTEX -> SHADER_TYPE_VERTEX;
            case FRAGMENT -> SHADER_TYPE_PIXEL;
        };
    }

    /**
     * Get D3D11ShaderManager instance from VitraRenderer
     *
     * @return D3D11ShaderManager instance or null if not available
     */
    private static D3D11ShaderManager getShaderManager() {
        try {
            VitraRenderer renderer = (VitraRenderer) VitraRenderer.getRenderer();
            if (renderer != null) {
                Object shaderManagerObj = renderer.getShaderManager();
                if (shaderManagerObj instanceof D3D11ShaderManager) {
                    return (D3D11ShaderManager) shaderManagerObj;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get D3D11ShaderManager from VitraRenderer", e);
        }
        return null;
    }
}
