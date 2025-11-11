package com.vitra.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.VRenderSystem;
import com.vitra.util.MappedBuffer;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;

import java.util.function.Supplier;

/**
 * DirectX Uniform Supplier Registry
 *
 * Based on VulkanMod's Uniforms class - maps uniform names to supplier functions.
 * Suppliers are lazily evaluated when constant buffers are updated, ensuring uniforms are always current.
 *
 * Architecture:
 * - Each uniform type (int, float, vec2, vec3, vec4, mat4) has its own supplier map
 * - Suppliers return MappedBuffer references (zero-copy) or scalar values
 * - Default uniforms registered in setupDefaultUniforms()
 * - Custom uniforms can be registered dynamically
 *
 * Uniform naming:
 * - Matches Minecraft's shader uniform names exactly
 * - Example: "ModelViewMat", "ProjMat", "ColorModulator", "FogStart"
 *
 * Usage:
 * 1. Register suppliers: Uniforms.vec4f_uniformMap.put("MyUniform", () -> myBuffer)
 * 2. Retrieve supplier: Supplier<MappedBuffer> supplier = Uniforms.getUniformSupplier("vec4", "MyUniform")
 * 3. Get current value: MappedBuffer buffer = supplier.get()
 */
public class Uniforms {

    // ==================== SUPPLIER MAPS ====================

    /**
     * Integer uniform suppliers (sampler indices, flags, enums)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    /**
     * Float uniform suppliers (scalars: time, fog params, line width)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    /**
     * Vec2 uniform suppliers (2D vectors: screen size, UV offsets)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    /**
     * Vec3 uniform suppliers (3D vectors: light directions, positions, model offsets)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    /**
     * Vec4 uniform suppliers (4D vectors: colors, RGBA, quaternions)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    /**
     * Mat4 uniform suppliers (4x4 matrices: transforms, projections)
     */
    public static final Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap =
        new Object2ReferenceOpenHashMap<>();

    // ==================== INITIALIZATION ====================

    static {
        setupDefaultUniforms();
    }

    /**
     * Register all default Minecraft shader uniforms
     * Called automatically on class load
     */
    public static void setupDefaultUniforms() {
        // ==================== MATRIX UNIFORMS ====================

        // Model-View matrix (transforms model space → view space)
        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("ModelViewMatrix", VRenderSystem::getModelViewMatrix); // Alias

        // Projection matrix (transforms view space → clip space)
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("ProjectionMatrix", VRenderSystem::getProjectionMatrix); // Alias

        // Combined Model-View-Projection matrix
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);

        // Texture matrix (for texture coordinate transformation)
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);
        mat4f_uniformMap.put("TextureMatrix", VRenderSystem::getTextureMatrix); // Alias

        // Inverse view-rotation matrix (for skybox rendering)
        mat4f_uniformMap.put("IViewRotMat", VRenderSystem::getInverseViewRotationMatrix);

        // ==================== VECTOR UNIFORMS (VEC4) ====================

        // Color modulator (RGBA multiplier for all fragments)
        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);

        // Fog color (RGBA)
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);

        // ==================== VECTOR UNIFORMS (VEC3) ====================

        // Light directions (for lighting calculations)
        vec3f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec3f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);

        // Model offset (chunk offset for terrain rendering)
        // CRITICAL: Register BOTH names - HLSL uses "ModelOffset", Minecraft uses "ChunkOffset"
        vec3f_uniformMap.put("ChunkOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("ModelOffset", () -> VRenderSystem.modelOffset);

        // Padding uniform (for HLSL cbuffer alignment)
        vec3f_uniformMap.put("_pad1", () -> {
            // Return zero-filled vec3 for padding
            MappedBuffer buf = new MappedBuffer(12);
            buf.putVec3(0, 0.0f, 0.0f, 0.0f);
            return buf;
        });

        // ==================== VECTOR UNIFORMS (VEC2) ====================

        // Screen size (width, height) for GUI shaders
        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);

        // ==================== SCALAR UNIFORMS (FLOAT) ====================

        // Fog parameters
        vec1f_uniformMap.put("FogStart", VRenderSystem::getFogStart);
        vec1f_uniformMap.put("FogEnd", VRenderSystem::getFogEnd);

        // Game time (for animated shaders, water waves, etc.)
        vec1f_uniformMap.put("GameTime", VRenderSystem::getGameTime);

        // Line width (for line rendering)
        vec1f_uniformMap.put("LineWidth", VRenderSystem::getLineWidth);

        // ==================== SCALAR UNIFORMS (INTEGER) ====================

        // Fog shape (0 = sphere, 1 = cylinder)
        vec1i_uniformMap.put("FogShape", VRenderSystem::getFogShape);

        // Sampler indices (texture unit bindings)
        vec1i_uniformMap.put("Sampler0", () -> 0);
        vec1i_uniformMap.put("Sampler1", () -> 1);
        vec1i_uniformMap.put("Sampler2", () -> 2); // Lightmap

        // ==================== FOG UNIFORMS (b2) ====================

        // Extended fog parameters (for advanced fog rendering)
        vec1f_uniformMap.put("FogEnvironmentalStart", () -> 0.0f);      // TODO: Get from Minecraft
        vec1f_uniformMap.put("FogEnvironmentalEnd", () -> 0.0f);
        vec1f_uniformMap.put("FogRenderDistanceStart", () -> 0.0f);
        vec1f_uniformMap.put("FogRenderDistanceEnd", () -> 0.0f);
        vec1f_uniformMap.put("FogSkyEnd", () -> 0.0f);
        vec1f_uniformMap.put("FogCloudsEnd", () -> 0.0f);

        // Padding for b2
        vec3f_uniformMap.put("_pad2", () -> {
            MappedBuffer buf = new MappedBuffer(12);
            buf.putVec3(0, 0.0f, 0.0f, 0.0f);
            return buf;
        });

        // ==================== GLOBALS UNIFORMS (b3) ====================

        // Glint rendering parameters
        vec1f_uniformMap.put("GlintAlpha", () -> 1.0f);                 // TODO: Get from Minecraft
        vec1i_uniformMap.put("MenuBlurRadius", () -> 0);                // TODO: Get from Minecraft

        // Padding for b3
        vec3f_uniformMap.put("_pad3", () -> {
            MappedBuffer buf = new MappedBuffer(12);
            buf.putVec3(0, 0.0f, 0.0f, 0.0f);
            return buf;
        });

        // ==================== LIGHTING UNIFORMS (b4) ====================

        // Padding floats for b4 (lighting cbuffer alignment)
        vec1f_uniformMap.put("_pad4", () -> 0.0f);
        vec1f_uniformMap.put("_pad5", () -> 0.0f);

        // ==================== RENDERSYSTEM PASS-THROUGHS ====================

        // These uniforms are read directly from RenderSystem each frame
        // (in case VRenderSystem sync is not called)

        vec1f_uniformMap.put("_FogStart", RenderSystem::getShaderFogStart);
        vec1f_uniformMap.put("_FogEnd", RenderSystem::getShaderFogEnd);
        vec1i_uniformMap.put("_FogShape", () -> RenderSystem.getShaderFogShape().getIndex());
        vec1f_uniformMap.put("_GameTime", RenderSystem::getShaderGameTime);
        vec1f_uniformMap.put("_LineWidth", RenderSystem::getShaderLineWidth);
    }

    // ==================== SUPPLIER RETRIEVAL ====================

    /**
     * Get uniform supplier by type and name
     *
     * @param type Uniform type: "float", "int", "vec2", "vec3", "vec4", "mat4", "matrix4x4"
     * @param name Uniform name (e.g., "ModelViewMat", "ColorModulator")
     * @return Supplier for the uniform, or null if not found
     */
    public static Supplier<?> getUniformSupplier(String type, String name) {
        return switch (type.toLowerCase()) {
            case "float" -> vec1f_uniformMap.get(name);
            case "int", "sampler2d" -> vec1i_uniformMap.get(name);
            case "vec2" -> vec2f_uniformMap.get(name);
            case "vec3" -> vec3f_uniformMap.get(name);
            case "vec4" -> vec4f_uniformMap.get(name);
            case "mat4", "matrix4x4" -> mat4f_uniformMap.get(name);
            default -> null;
        };
    }

    /**
     * Check if uniform exists
     *
     * @param type Uniform type
     * @param name Uniform name
     * @return true if uniform is registered
     */
    public static boolean hasUniform(String type, String name) {
        return getUniformSupplier(type, name) != null;
    }

    // ==================== DYNAMIC REGISTRATION ====================

    /**
     * Register custom integer uniform
     */
    public static void registerInt(String name, Supplier<Integer> supplier) {
        vec1i_uniformMap.put(name, supplier);
    }

    /**
     * Register custom float uniform
     */
    public static void registerFloat(String name, Supplier<Float> supplier) {
        vec1f_uniformMap.put(name, supplier);
    }

    /**
     * Register custom vec2 uniform
     */
    public static void registerVec2(String name, Supplier<MappedBuffer> supplier) {
        vec2f_uniformMap.put(name, supplier);
    }

    /**
     * Register custom vec3 uniform
     */
    public static void registerVec3(String name, Supplier<MappedBuffer> supplier) {
        vec3f_uniformMap.put(name, supplier);
    }

    /**
     * Register custom vec4 uniform
     */
    public static void registerVec4(String name, Supplier<MappedBuffer> supplier) {
        vec4f_uniformMap.put(name, supplier);
    }

    /**
     * Register custom mat4 uniform
     */
    public static void registerMat4(String name, Supplier<MappedBuffer> supplier) {
        mat4f_uniformMap.put(name, supplier);
    }

    // ==================== DEBUGGING ====================

    /**
     * Get statistics about registered uniforms
     */
    public static String getStats() {
        return String.format("Uniforms: int=%d, float=%d, vec2=%d, vec3=%d, vec4=%d, mat4=%d",
            vec1i_uniformMap.size(),
            vec1f_uniformMap.size(),
            vec2f_uniformMap.size(),
            vec3f_uniformMap.size(),
            vec4f_uniformMap.size(),
            mat4f_uniformMap.size()
        );
    }

    /**
     * List all registered uniform names
     */
    public static void printAllUniforms() {
        System.out.println("=== Registered Uniforms ===");
        System.out.println("INT: " + vec1i_uniformMap.keySet());
        System.out.println("FLOAT: " + vec1f_uniformMap.keySet());
        System.out.println("VEC2: " + vec2f_uniformMap.keySet());
        System.out.println("VEC3: " + vec3f_uniformMap.keySet());
        System.out.println("VEC4: " + vec4f_uniformMap.keySet());
        System.out.println("MAT4: " + mat4f_uniformMap.keySet());
    }
}
