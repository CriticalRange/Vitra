package com.vitra.render.shader.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * DirectX Constant Buffer Layout Manager (std140)
 *
 * Based on VulkanMod's AlignedStruct - manages std140 layout alignment for constant buffer fields.
 * Ensures all uniform fields are properly aligned according to DirectX/HLSL std140 rules.
 *
 * std140 Alignment Rules:
 * - Scalars (float/int): 4-byte aligned
 * - vec2: 8-byte aligned
 * - vec3: 16-byte aligned (padded to vec4 size!)
 * - vec4: 16-byte aligned
 * - mat4: 16-byte aligned (treated as 4x vec4)
 *
 * Usage:
 * 1. Create builder: AlignedStruct.Builder builder = new AlignedStruct.Builder()
 * 2. Add uniforms: builder.addUniform("mat4", "ModelViewMat")
 * 3. Build UBO: UBO ubo = builder.buildUBO(binding, stages)
 * 4. Update GPU: ubo.update(constantBufferPtr)
 */
public abstract class AlignedStruct {

    protected List<Uniform> uniforms = new ArrayList<>();
    protected int size;  // Total size in bytes (std140 aligned)

    /**
     * Update all uniform fields in constant buffer
     *
     * Iterates through all uniform fields, calls their suppliers,
     * and copies data to the constant buffer via memcpy.
     *
     * @param ptr Pointer to constant buffer CPU memory (from Map())
     */
    public void update(long ptr) {
        for (Uniform uniform : this.uniforms) {
            uniform.update(ptr);
        }
    }

    /**
     * Get total constant buffer size in bytes
     */
    public int getSize() {
        return size;
    }

    /**
     * Get list of uniform fields
     */
    public List<Uniform> getUniforms() {
        return uniforms;
    }

    // ==================== BUILDER PATTERN ====================

    /**
     * Builder for creating std140-aligned constant buffer layouts (VulkanMod pattern)
     */
    public static class Builder {
        protected List<Uniform.Info> uniforms = new ArrayList<>();
        protected int currentOffset = 0;  // Current offset in FLOATS (VulkanMod pattern)

        /**
         * Add uniform field to constant buffer (VulkanMod pattern - float units)
         *
         * Automatically computes std140 alignment offset.
         *
         * @param uniformInfo Uniform descriptor (type, name, size, alignment in FLOATS)
         */
        public void addUniformInfo(Uniform.Info uniformInfo) {
            // CRITICAL: VulkanMod pattern - compute std140-aligned offset in FLOAT units
            this.currentOffset = uniformInfo.computeAlignmentOffset(this.currentOffset);

            // Add to uniform list
            this.uniforms.add(uniformInfo);

            // Advance offset by uniform size (in floats)
            this.currentOffset += uniformInfo.size;
        }

        /**
         * Add uniform field (convenience method)
         *
         * @param type Uniform type ("mat4", "vec4", etc.)
         * @param name Uniform name
         */
        public void addUniform(String type, String name) {
            Uniform.Info info = new Uniform.Info(type, name);
            addUniformInfo(info);
        }

        /**
         * Add uniform field with count (for arrays)
         *
         * @param type Uniform type
         * @param name Uniform name
         * @param count Array element count (currently unused, reserved for future)
         */
        public void addUniform(String type, String name, int count) {
            Uniform.Info info = new Uniform.Info(type, name, count);
            addUniformInfo(info);
        }

        /**
         * Get current total size in FLOATS (VulkanMod pattern)
         *
         * To convert to bytes: getCurrentSize() * 4
         */
        public int getCurrentSize() {
            return currentOffset;
        }

        /**
         * Get list of uniform descriptors
         */
        public List<Uniform.Info> getUniforms() {
            return uniforms;
        }

        /**
         * Build constant buffer layout
         *
         * Finalizes the layout, sets up suppliers, and returns a concrete AlignedStruct implementation.
         *
         * @return Built AlignedStruct (subclass determines concrete type)
         */
        protected AlignedStruct build(int finalSize) {
            // This is called by subclass builders (UBO.Builder)
            // Subclasses override to create their specific struct type
            throw new UnsupportedOperationException("Use subclass builder (e.g., UBO.Builder)");
        }
    }

    // ==================== DEBUGGING ====================

    /**
     * Print constant buffer layout for debugging
     */
    public void printLayout() {
        System.out.println("=== Constant Buffer Layout (std140) ===");
        System.out.println("Total size: " + size + " bytes");
        System.out.println("Uniforms:");

        for (Uniform uniform : uniforms) {
            System.out.printf("  [%4d] %s%n", uniform.getOffset(), uniform);
        }
    }

    @Override
    public String toString() {
        return String.format("AlignedStruct[size=%d bytes, uniforms=%d]", size, uniforms.size());
    }
}
