package com.vitra.render.shader.layout;

import com.vitra.render.shader.Uniforms;
import com.vitra.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

/**
 * DirectX 11 Constant Buffer Uniform Field
 *
 * Based on VulkanMod's Uniform class - represents a single field within a constant buffer (UBO).
 * Manages std140 layout alignment, data suppliers, and GPU upload.
 *
 * Key features:
 * - Supplier-based lazy evaluation (data retrieved only when needed)
 * - std140 alignment calculation (vec3 → 16 bytes, mat4 → 64 bytes)
 * - Zero-copy memcpy to GPU constant buffer
 * - Type-safe access (int, float, MappedBuffer)
 *
 * Memory layout (std140):
 * - Scalars (int/float): 4 bytes, 4-byte aligned
 * - vec2: 8 bytes, 8-byte aligned
 * - vec3: 12 bytes, 16-byte aligned (padded!)
 * - vec4: 16 bytes, 16-byte aligned
 * - mat4: 64 bytes (4x vec4), 16-byte aligned
 */
public class Uniform {

    protected Supplier<MappedBuffer> bufferSupplier;  // Supplier for vector/matrix data
    protected Supplier<Integer> intSupplier;          // Supplier for integer scalars
    protected Supplier<Float> floatSupplier;          // Supplier for float scalars

    protected final long offset;  // Byte offset in constant buffer
    protected final int size;     // Size in bytes

    /**
     * Construct uniform field
     *
     * @param offset Byte offset in constant buffer (std140 aligned)
     * @param size Size in bytes
     */
    protected Uniform(long offset, int size) {
        this.offset = offset;
        this.size = size;
    }

    /**
     * Update constant buffer with current uniform value
     *
     * @param ptr Pointer to constant buffer CPU memory
     */
    public void update(long ptr) {
        if (bufferSupplier != null) {
            // Vector/matrix data - use zero-copy memcpy
            MappedBuffer src = bufferSupplier.get();
            if (src != null) {
                MemoryUtil.memCopy(src.address(), ptr + this.offset, this.size);
            }
        } else if (intSupplier != null) {
            // Integer scalar - write directly
            Integer value = intSupplier.get();
            if (value != null) {
                MemoryUtil.memPutInt(ptr + this.offset, value);
            }
        } else if (floatSupplier != null) {
            // Float scalar - write directly
            Float value = floatSupplier.get();
            if (value != null) {
                MemoryUtil.memPutFloat(ptr + this.offset, value);
            }
        }
    }

    /**
     * Get byte offset in constant buffer
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Get size in bytes
     */
    public int getSize() {
        return size;
    }

    // ==================== UNIFORM INFO (BUILDER PATTERN) ====================

    /**
     * Uniform field descriptor (used during constant buffer construction)
     *
     * Stores uniform metadata and computes std140 alignment offsets.
     */
    public static class Info {
        public final String type;    // "mat4", "vec4", "vec3", "vec2", "float", "int"
        public final String name;    // Uniform name (e.g., "ModelViewMat")
        public final int align;      // Alignment in bytes (4, 8, 16)
        public final int size;       // Size in bytes
        public int offset;           // Computed std140 offset (in bytes)

        // Suppliers (set later via setupSupplier())
        Supplier<MappedBuffer> bufferSupplier;
        Supplier<Integer> intSupplier;
        Supplier<Float> floatSupplier;

        /**
         * Create uniform info from type and name
         *
         * @param type Uniform type ("mat4", "vec4", etc.)
         * @param name Uniform name
         */
        public Info(String type, String name) {
            this.type = type.toLowerCase();
            this.name = name;

            // Determine alignment and size based on type (std140 layout)
            switch (this.type) {
                case "float", "int", "sampler2d" -> {
                    this.align = 4;   // 4-byte aligned
                    this.size = 4;    // 4 bytes
                }
                case "vec2" -> {
                    this.align = 8;   // 8-byte aligned
                    this.size = 8;    // 8 bytes
                }
                case "vec3" -> {
                    this.align = 16;  // 16-byte aligned (std140 padding!)
                    this.size = 12;   // 12 bytes (but padded to 16)
                }
                case "vec4" -> {
                    this.align = 16;  // 16-byte aligned
                    this.size = 16;   // 16 bytes
                }
                case "mat4", "matrix4x4" -> {
                    this.align = 16;  // 16-byte aligned
                    this.size = 64;   // 64 bytes (4x vec4)
                }
                default -> throw new IllegalArgumentException("Unknown uniform type: " + type);
            }
        }

        /**
         * Create uniform info from JSON-style count parameter
         *
         * @param type Uniform type
         * @param name Uniform name
         * @param count Element count (for arrays, currently unused)
         */
        public Info(String type, String name, int count) {
            this(type, name);
            // Array support could be added here if needed
            // For now, count is ignored (Minecraft doesn't use uniform arrays in most shaders)
        }

        /**
         * Compute std140 alignment offset
         *
         * Aligns the current offset to the uniform's alignment requirement.
         *
         * @param builderOffset Current offset from builder (in bytes)
         * @return Aligned offset (in bytes)
         */
        public int computeAlignmentOffset(int builderOffset) {
            // std140 alignment formula: offset = align_to(current_offset, alignment)
            // align_to(x, n) = x + ((n - (x % n)) % n)
            this.offset = builderOffset + ((align - (builderOffset % align)) % align);
            return this.offset;
        }

        /**
         * Setup supplier from Uniforms registry
         *
         * Links this uniform field to its data source via supplier pattern.
         */
        public void setupSupplier() {
            switch (this.type) {
                case "float" -> this.floatSupplier = Uniforms.vec1f_uniformMap.get(this.name);
                case "int", "sampler2d" -> this.intSupplier = Uniforms.vec1i_uniformMap.get(this.name);
                case "vec2" -> this.bufferSupplier = Uniforms.vec2f_uniformMap.get(this.name);
                case "vec3" -> this.bufferSupplier = Uniforms.vec3f_uniformMap.get(this.name);
                case "vec4" -> this.bufferSupplier = Uniforms.vec4f_uniformMap.get(this.name);
                case "mat4", "matrix4x4" -> this.bufferSupplier = Uniforms.mat4f_uniformMap.get(this.name);
            }

            // Warn if supplier not found
            if (this.floatSupplier == null && this.intSupplier == null && this.bufferSupplier == null) {
                System.err.println("[Uniform] Warning: No supplier found for uniform '" + this.name +
                    "' (type: " + this.type + ")");
            }
        }

        /**
         * Build Uniform instance from this descriptor
         */
        public Uniform build() {
            Uniform uniform = new Uniform(this.offset, this.size);
            uniform.bufferSupplier = this.bufferSupplier;
            uniform.intSupplier = this.intSupplier;
            uniform.floatSupplier = this.floatSupplier;
            return uniform;
        }

        @Override
        public String toString() {
            return String.format("Uniform.Info[type=%s, name=%s, offset=%d, size=%d, align=%d]",
                type, name, offset, size, align);
        }
    }
}
