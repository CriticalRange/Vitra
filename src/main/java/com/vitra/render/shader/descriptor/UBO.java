package com.vitra.render.shader.descriptor;

import com.vitra.render.shader.layout.AlignedStruct;
import com.vitra.render.shader.layout.Uniform;

import java.util.List;

/**
 * DirectX Uniform Buffer Object (Constant Buffer)
 *
 * Based on VulkanMod's UBO class - represents a single constant buffer binding.
 * Manages std140-aligned uniform layout and GPU buffer updates.
 *
 * Key features:
 * - std140 layout management via AlignedStruct
 * - Shader stage binding (vertex, fragment, or both)
 * - Supplier-based lazy uniform evaluation
 * - Zero-copy GPU updates
 *
 * DirectX mapping:
 * - Vulkan "descriptor set binding" → DirectX "constant buffer slot" (b0, b1, b2, b3)
 * - Vulkan "dynamic offset" → DirectX Map/Unmap with WRITE_DISCARD
 * - Vulkan "UBO" → DirectX "cbuffer"
 *
 * HLSL usage:
 * ```hlsl
 * cbuffer MatrixBuffer : register(b0) {  // binding = 0
 *     float4x4 ModelViewMat;
 *     float4x4 ProjMat;
 * };
 * ```
 */
public class UBO extends AlignedStruct {

    private final int binding;         // Constant buffer slot (b0, b1, b2, b3)
    private final int stages;          // Shader stages (VERTEX | FRAGMENT)

    /**
     * Construct UBO with explicit uniform list
     *
     * @param binding Constant buffer slot (0-3 for b0-b3)
     * @param stages Shader stages (STAGE_VERTEX | STAGE_FRAGMENT)
     * @param size Total buffer size in bytes (std140 aligned)
     * @param uniformInfos List of uniform descriptors
     */
    public UBO(int binding, int stages, int size, List<Uniform.Info> uniformInfos) {
        this.binding = binding;
        this.stages = stages;
        this.size = size;

        // Build Uniform instances from descriptors
        for (Uniform.Info info : uniformInfos) {
            info.setupSupplier();  // Link to Uniforms registry
            this.uniforms.add(info.build());
        }
    }

    /**
     * Get constant buffer slot (b0, b1, b2, b3)
     */
    public int getBinding() {
        return binding;
    }

    /**
     * Get shader stages this UBO is bound to
     */
    public int getStages() {
        return stages;
    }

    /**
     * Check if UBO is bound to vertex shader
     */
    public boolean isVertexStage() {
        return (stages & ShaderStage.VERTEX) != 0;
    }

    /**
     * Check if UBO is bound to fragment (pixel) shader
     */
    public boolean isFragmentStage() {
        return (stages & ShaderStage.FRAGMENT) != 0;
    }

    @Override
    public String toString() {
        String stageStr = "";
        if (isVertexStage()) stageStr += "VERTEX";
        if (isFragmentStage()) stageStr += (stageStr.isEmpty() ? "" : "|") + "FRAGMENT";

        return String.format("UBO[binding=b%d, stages=%s, size=%d bytes, uniforms=%d]",
            binding, stageStr, size, uniforms.size());
    }

    // ==================== SHADER STAGE CONSTANTS ====================

    /**
     * Shader stage flags (can be OR'd together)
     */
    public static class ShaderStage {
        public static final int VERTEX = 0x1;      // Vertex shader
        public static final int FRAGMENT = 0x2;    // Fragment/Pixel shader
        public static final int ALL = VERTEX | FRAGMENT;
    }

    // ==================== BUILDER PATTERN ====================

    /**
     * Builder for creating UBOs with std140 layout
     */
    public static class Builder extends AlignedStruct.Builder {

        /**
         * Build UBO with specified binding and shader stages (VulkanMod pattern)
         *
         * @param binding Constant buffer slot (0-3)
         * @param stages Shader stages (VERTEX, FRAGMENT, or VERTEX|FRAGMENT)
         * @return Constructed UBO
         */
        public UBO buildUBO(int binding, int stages) {
            // CRITICAL: VulkanMod pattern - convert float units to bytes
            // currentOffset is in FLOATS, multiply by 4 to get bytes
            int sizeInBytes = this.currentOffset * 4;

            // Ensure minimum 16-byte alignment for DirectX
            int alignedSize = ((sizeInBytes + 15) / 16) * 16;

            return new UBO(binding, stages, alignedSize, this.uniforms);
        }

        /**
         * Build UBO with vertex-only binding
         */
        public UBO buildVertexUBO(int binding) {
            return buildUBO(binding, ShaderStage.VERTEX);
        }

        /**
         * Build UBO with fragment-only binding
         */
        public UBO buildFragmentUBO(int binding) {
            return buildUBO(binding, ShaderStage.FRAGMENT);
        }

        /**
         * Build UBO with both vertex and fragment binding
         */
        public UBO buildAllStagesUBO(int binding) {
            return buildUBO(binding, ShaderStage.ALL);
        }
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Create standard Minecraft vertex shader UBO (matches cbuffer_common.hlsli b0)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer DynamicTransforms : register(b0) {
     *     float4x4 MVP;             // Offset 0, 64 bytes
     *     float4x4 ModelViewMat;    // Offset 64, 64 bytes
     *     float4 ColorModulator;    // Offset 128, 16 bytes
     *     float3 ModelOffset;       // Offset 144, 12 bytes (ChunkOffset)
     *     float _pad0;              // Offset 156, 4 bytes
     *     float4x4 TextureMat;      // Offset 160, 64 bytes
     *     float LineWidth;          // Offset 224, 4 bytes
     * };
     * ```
     */
    public static UBO createStandardVertexUBO() {
        Builder builder = new Builder();
        builder.addUniform("mat4", "MVP");              // Offset 0, 64 bytes
        builder.addUniform("mat4", "ModelViewMat");     // Offset 64, 64 bytes
        builder.addUniform("vec4", "ColorModulator");   // Offset 128, 16 bytes
        builder.addUniform("vec3", "ModelOffset");      // Offset 144, 12 bytes (matches HLSL cbuffer_common.hlsli)
        // _pad0: float at offset 156 (4 bytes) - handled automatically by std140 vec3 alignment
        builder.addUniform("mat4", "TextureMat");       // Offset 160, 64 bytes
        builder.addUniform("float", "LineWidth");       // Offset 224, 4 bytes
        builder.addUniform("vec3", "_pad1");            // Offset 228, 12 bytes (CRITICAL: matches HLSL cbuffer_common.hlsli line 22)
        // TOTAL: 240 bytes (matches HLSL shader expectation)
        return builder.buildVertexUBO(0);
    }

    /**
     * Create standard Minecraft projection UBO (matches cbuffer_common.hlsli b1)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer Projection : register(b1) {
     *     float4x4 ProjMat;   // 64 bytes
     * };
     * ```
     */
    public static UBO createStandardFragmentUBO() {
        Builder builder = new Builder();
        builder.addUniform("mat4", "ProjMat");
        return builder.buildFragmentUBO(1);
    }

    /**
     * Create minimal UBO with only MVP matrix (for simple shaders)
     */
    public static UBO createMinimalVertexUBO() {
        Builder builder = new Builder();
        builder.addUniform("mat4", "MVP");
        return builder.buildVertexUBO(0);
    }

    /**
     * Create minimal fragment UBO with only color modulator
     */
    public static UBO createMinimalFragmentUBO() {
        Builder builder = new Builder();
        builder.addUniform("vec4", "ColorModulator");
        return builder.buildFragmentUBO(0);
    }

    /**
     * Create Fog constant buffer (b2)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer Fog : register(b2) {
     *     float4 FogColor;                  // Offset 0, 16 bytes
     *     float FogStart;                   // Offset 16, 4 bytes
     *     float FogEnd;                     // Offset 20, 4 bytes
     *     float FogEnvironmentalStart;      // Offset 24, 4 bytes
     *     float FogEnvironmentalEnd;        // Offset 28, 4 bytes
     *     float FogRenderDistanceStart;     // Offset 32, 4 bytes
     *     float FogRenderDistanceEnd;       // Offset 36, 4 bytes
     *     float FogSkyEnd;                  // Offset 40, 4 bytes
     *     float FogCloudsEnd;               // Offset 44, 4 bytes
     *     int FogShape;                     // Offset 48, 4 bytes
     *     float3 _pad2;                     // Offset 52, 12 bytes
     * };
     * ```
     */
    public static UBO createFogUBO() {
        Builder builder = new Builder();
        builder.addUniform("vec4", "FogColor");               // Offset 0, 16 bytes
        builder.addUniform("float", "FogStart");              // Offset 16, 4 bytes
        builder.addUniform("float", "FogEnd");                // Offset 20, 4 bytes
        builder.addUniform("float", "FogEnvironmentalStart"); // Offset 24, 4 bytes
        builder.addUniform("float", "FogEnvironmentalEnd");   // Offset 28, 4 bytes
        builder.addUniform("float", "FogRenderDistanceStart");// Offset 32, 4 bytes
        builder.addUniform("float", "FogRenderDistanceEnd");  // Offset 36, 4 bytes
        builder.addUniform("float", "FogSkyEnd");             // Offset 40, 4 bytes
        builder.addUniform("float", "FogCloudsEnd");          // Offset 44, 4 bytes
        builder.addUniform("int", "FogShape");                // Offset 48, 4 bytes
        builder.addUniform("vec3", "_pad2");                  // Offset 52, 12 bytes (padding)
        return builder.buildFragmentUBO(2);
    }

    /**
     * Create Globals constant buffer (b3)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer Globals : register(b3) {
     *     float2 ScreenSize;       // Offset 0, 8 bytes
     *     float GlintAlpha;        // Offset 8, 4 bytes
     *     float GameTime;          // Offset 12, 4 bytes
     *     int MenuBlurRadius;      // Offset 16, 4 bytes
     *     float3 _pad3;            // Offset 20, 12 bytes
     * };
     * ```
     */
    public static UBO createGlobalsUBO() {
        Builder builder = new Builder();
        builder.addUniform("vec2", "ScreenSize");       // Offset 0, 8 bytes
        builder.addUniform("float", "GlintAlpha");      // Offset 8, 4 bytes
        builder.addUniform("float", "GameTime");        // Offset 12, 4 bytes
        builder.addUniform("int", "MenuBlurRadius");    // Offset 16, 4 bytes
        builder.addUniform("vec3", "_pad3");            // Offset 20, 12 bytes (padding)
        return builder.buildFragmentUBO(3);
    }

    /**
     * Create Lighting constant buffer (b4)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer Lighting : register(b4) {
     *     float3 Light0_Direction;  // Offset 0, 12 bytes
     *     float _pad4;              // Offset 12, 4 bytes
     *     float3 Light1_Direction;  // Offset 16, 12 bytes
     *     float _pad5;              // Offset 28, 4 bytes
     * };
     * ```
     */
    public static UBO createLightingUBO() {
        Builder builder = new Builder();
        builder.addUniform("vec3", "Light0_Direction"); // Offset 0, 12 bytes
        builder.addUniform("float", "_pad4");           // Offset 12, 4 bytes (padding)
        builder.addUniform("vec3", "Light1_Direction"); // Offset 16, 12 bytes
        builder.addUniform("float", "_pad5");           // Offset 28, 4 bytes (padding)
        return builder.buildFragmentUBO(4);
    }
}
