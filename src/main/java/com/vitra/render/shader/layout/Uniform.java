package com.vitra.render.shader.layout;

import com.vitra.render.shader.Uniforms;
import com.vitra.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

/**
 * DirectX Constant Buffer Uniform Field
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
     * @param info Uniform descriptor with float-based offset/size (VulkanMod pattern)
     */
    protected Uniform(Info info) {
        // CRITICAL: VulkanMod pattern - convert float units to bytes
        this.offset = info.offset * 4L;  // Float offset → byte offset
        this.size = info.size * 4;       // Float size → byte size
    }

    private static int debugUpdateCount = 0;

    /**
     * Update constant buffer with current uniform value
     *
     * @param ptr Pointer to constant buffer CPU memory
     */
    public void update(long ptr) {
        if (bufferSupplier != null) {
            // Vector/matrix data - use zero-copy memcpy
            MappedBuffer src = bufferSupplier.get();

            // DEBUG: Log first 50 matrix updates to see when non-identity arrives
            if (debugUpdateCount < 50 && size == 64) { // mat4 = 64 bytes
                if (src == null) {
                    System.out.println("[UNIFORM_UPDATE " + debugUpdateCount + "] MappedBuffer supplier returned NULL (offset=" + offset + ", size=" + size + ")");
                } else {
                    // Read first 16 floats from MappedBuffer
                    StringBuilder matrixDump = new StringBuilder("[UNIFORM_UPDATE " + debugUpdateCount + "] Matrix at offset " + offset + ": ");
                    for (int i = 0; i < 16; i++) {
                        float value = MemoryUtil.memGetFloat(src.address() + (i * 4));
                        matrixDump.append(String.format("%.3f ", value));
                        if ((i + 1) % 4 == 0) matrixDump.append("| ");
                    }
                    System.out.println(matrixDump.toString());
                }
                debugUpdateCount++;
            }

            if (src != null) {
                MemoryUtil.memCopy(src.address(), ptr + this.offset, this.size);
            } else {
                // CRITICAL: If supplier returns null, zero-fill to prevent garbage
                MemoryUtil.memSet(ptr + this.offset, 0, this.size);
            }
        } else if (intSupplier != null) {
            // Integer scalar - write directly
            Integer value = intSupplier.get();
            if (value != null) {
                MemoryUtil.memPutInt(ptr + this.offset, value);
            } else {
                MemoryUtil.memPutInt(ptr + this.offset, 0);
            }
        } else if (floatSupplier != null) {
            // Float scalar - write directly
            Float value = floatSupplier.get();
            if (value != null) {
                MemoryUtil.memPutFloat(ptr + this.offset, value);
            } else {
                MemoryUtil.memPutFloat(ptr + this.offset, 0.0f);
            }
        } else {
            // CRITICAL FIX: No supplier found - zero-fill this uniform to prevent garbage (0xCCCCCCCC)
            // This happens when shader declares uniform but no supplier is registered in Uniforms.java
            MemoryUtil.memSet(ptr + this.offset, 0, this.size);
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
        public final int align;      // Alignment in FLOATS (VulkanMod pattern)
        public final int size;       // Size in FLOATS (VulkanMod pattern)
        public int offset;           // Computed std140 offset (in FLOATS)

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

            // CRITICAL: VulkanMod pattern - alignment and size in FLOAT units (not bytes!)
            // std140 layout rules (in float units):
            // - Scalars: align=1, size=1 (1 float = 4 bytes)
            // - vec2: align=2, size=2 (2 floats = 8 bytes)
            // - vec3: align=4, size=3 (3 floats = 12 bytes, but 16-byte aligned!)
            // - vec4: align=4, size=4 (4 floats = 16 bytes)
            // - mat4: align=4, size=16 (16 floats = 64 bytes, treated as 4x vec4)
            switch (this.type) {
                case "float", "int", "sampler2d" -> {
                    this.align = 1;   // 1 float alignment (4 bytes)
                    this.size = 1;    // 1 float (4 bytes)
                }
                case "vec2" -> {
                    this.align = 2;   // 2 float alignment (8 bytes)
                    this.size = 2;    // 2 floats (8 bytes)
                }
                case "vec3" -> {
                    this.align = 4;   // 4 float alignment (16 bytes) - std140 padding!
                    this.size = 3;    // 3 floats (12 bytes, but padded to 16)
                }
                case "vec4" -> {
                    this.align = 4;   // 4 float alignment (16 bytes)
                    this.size = 4;    // 4 floats (16 bytes)
                }
                case "mat4", "matrix4x4" -> {
                    this.align = 4;   // 4 float alignment (16 bytes)
                    this.size = 16;   // 16 floats (64 bytes = 4x vec4)
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
         * Compute std140 alignment offset (VulkanMod pattern - float units)
         *
         * Aligns the current offset to the uniform's alignment requirement.
         *
         * @param builderOffset Current offset from builder (in FLOATS)
         * @return Aligned offset (in FLOATS)
         */
        public int computeAlignmentOffset(int builderOffset) {
            // CRITICAL: VulkanMod pattern - alignment in float units
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

            // Warn if supplier not found - CRITICAL for debugging constant buffer corruption
            if (this.floatSupplier == null && this.intSupplier == null && this.bufferSupplier == null) {
                String msg = "[UNIFORM_SUPPLIER_MISSING] ❌ No supplier found for uniform '" + this.name +
                    "' (type: " + this.type + ") at offset " + this.offset + " - THIS WILL CAUSE GARBAGE DATA!";
                System.err.println(msg);
                // Also log to standard out so it's visible in game logs
                System.out.println(msg);
            } else {
                System.out.println("[UNIFORM_SUPPLIER_OK] ✓ Supplier found for '" + this.name + "' (type: " + this.type + ")");
            }
        }

        /**
         * Build Uniform instance from this descriptor (VulkanMod pattern)
         */
        public Uniform build() {
            // CRITICAL: VulkanMod pattern - pass Info object to constructor for float→byte conversion
            Uniform uniform = new Uniform(this);
            uniform.bufferSupplier = this.bufferSupplier;
            uniform.intSupplier = this.intSupplier;
            uniform.floatSupplier = this.floatSupplier;
            return uniform;
        }

        @Override
        public String toString() {
            return String.format("Uniform.Info[type=%s, name=%s, offset=%d floats (%d bytes), size=%d floats (%d bytes), align=%d floats]",
                type, name, offset, offset * 4, size, size * 4, align);
        }
    }
}
