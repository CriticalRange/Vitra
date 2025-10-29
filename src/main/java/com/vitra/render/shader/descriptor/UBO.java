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
         * Build UBO with specified binding and shader stages
         *
         * @param binding Constant buffer slot (0-3)
         * @param stages Shader stages (VERTEX, FRAGMENT, or VERTEX|FRAGMENT)
         * @return Constructed UBO
         */
        public UBO buildUBO(int binding, int stages) {
            // Ensure minimum 16-byte alignment for DirectX
            int alignedSize = ((this.currentOffset + 15) / 16) * 16;

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
     * Create standard Minecraft vertex shader UBO (matrices + chunk offset)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer VertexUniforms : register(b0) {
     *     float4x4 ModelViewMat;
     *     float4x4 ProjMat;
     *     float4x4 TextureMat;
     *     float3 ChunkOffset;
     * };
     * ```
     */
    public static UBO createStandardVertexUBO() {
        Builder builder = new Builder();
        builder.addUniform("mat4", "ModelViewMat");
        builder.addUniform("mat4", "ProjMat");
        builder.addUniform("mat4", "TextureMat");
        builder.addUniform("vec3", "ChunkOffset");
        return builder.buildVertexUBO(0);
    }

    /**
     * Create standard Minecraft fragment shader UBO (colors + fog)
     *
     * HLSL equivalent:
     * ```hlsl
     * cbuffer FragmentUniforms : register(b1) {
     *     float4 ColorModulator;
     *     float4 FogColor;
     *     float FogStart;
     *     float FogEnd;
     *     int FogShape;
     * };
     * ```
     */
    public static UBO createStandardFragmentUBO() {
        Builder builder = new Builder();
        builder.addUniform("vec4", "ColorModulator");
        builder.addUniform("vec4", "FogColor");
        builder.addUniform("float", "FogStart");
        builder.addUniform("float", "FogEnd");
        builder.addUniform("int", "FogShape");
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
}
