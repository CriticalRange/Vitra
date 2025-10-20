package com.vitra.render.dx11;

import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.shader.HLSLConverter;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * DirectX 11 Uniform Buffer Object (Constant Buffer) manager.
 * Manages uniform data and uploads to GPU constant buffers.
 */
public class DirectX11UniformBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectX11UniformBuffer.class);

    private final int slot;
    private final int size;
    private final ByteBuffer buffer;
    private final Map<String, UniformEntry> uniforms = new HashMap<>();
    private boolean dirty = false;

    /**
     * Represents a single uniform entry in the buffer.
     */
    public static class UniformEntry {
        public final String name;
        public final int offset;
        public final int size;
        public final UniformType type;
        private Supplier<ByteBuffer> supplier;

        public UniformEntry(String name, int offset, int size, UniformType type) {
            this.name = name;
            this.offset = offset;
            this.size = size;
            this.type = type;
        }

        public void setSupplier(Supplier<ByteBuffer> supplier) {
            this.supplier = supplier;
        }

        public Supplier<ByteBuffer> getSupplier() {
            return supplier;
        }
    }

    /**
     * Uniform types supported by DirectX 11.
     */
    public enum UniformType {
        FLOAT(4),
        VEC2(8),
        VEC3(16),  // Padded to 16 bytes
        VEC4(16),
        MAT3(48),  // 3x3 matrix with padding
        MAT4(64),  // 4x4 matrix
        INT(4),
        IVEC2(8),
        IVEC3(16), // Padded to 16 bytes
        IVEC4(16);

        public final int size;

        UniformType(int size) {
            this.size = size;
        }

        public static UniformType fromGLSLType(String glslType) {
            return switch (glslType) {
                case "float" -> FLOAT;
                case "vec2" -> VEC2;
                case "vec3" -> VEC3;
                case "vec4" -> VEC4;
                case "mat3" -> MAT3;
                case "mat4" -> MAT4;
                case "int" -> INT;
                case "ivec2" -> IVEC2;
                case "ivec3" -> IVEC3;
                case "ivec4" -> IVEC4;
                default -> throw new IllegalArgumentException("Unsupported GLSL type: " + glslType);
            };
        }
    }

    /**
     * Create a new uniform buffer.
     *
     * @param slot Constant buffer slot (b0-b3)
     * @param size Buffer size in bytes (must be 16-byte aligned)
     */
    public DirectX11UniformBuffer(int slot, int size) {
        this.slot = slot;
        // Ensure 16-byte alignment
        this.size = (size + 15) & ~15;
        this.buffer = MemoryUtil.memAlloc(this.size);
        MemoryUtil.memSet(buffer, 0);

        LOGGER.debug("Created uniform buffer at slot {} with size {} bytes", slot, this.size);
    }

    /**
     * Add a uniform to this buffer.
     *
     * @param uniformInfo Uniform information from HLSL converter
     * @return The created UniformEntry
     */
    public UniformEntry addUniform(HLSLConverter.UniformInfo uniformInfo) {
        UniformType type = UniformType.fromGLSLType(uniformInfo.type);
        int offset = calculateOffset();

        UniformEntry entry = new UniformEntry(uniformInfo.name, offset, type.size, type);
        uniforms.put(uniformInfo.name, entry);

        LOGGER.debug("Added uniform '{}' of type {} at offset {} (size: {})",
                     uniformInfo.name, type, offset, type.size);

        return entry;
    }

    /**
     * Calculate the next aligned offset for a new uniform.
     */
    private int calculateOffset() {
        int maxOffset = 0;
        int maxSize = 0;

        for (UniformEntry entry : uniforms.values()) {
            int endOffset = entry.offset + entry.size;
            if (endOffset > maxOffset + maxSize) {
                maxOffset = entry.offset;
                maxSize = entry.size;
            }
        }

        int nextOffset = maxOffset + maxSize;

        // Align to 16 bytes for vec3, vec4, mat3, mat4
        if (nextOffset % 16 != 0) {
            nextOffset = (nextOffset + 15) & ~15;
        }

        return nextOffset;
    }

    /**
     * Set float uniform value.
     */
    public void setFloat(String name, float value) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.FLOAT) {
            LOGGER.warn("Uniform '{}' is not a float", name);
            return;
        }

        buffer.putFloat(entry.offset, value);
        dirty = true;
    }

    /**
     * Set vec2 uniform value.
     */
    public void setVec2(String name, float x, float y) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.VEC2) {
            LOGGER.warn("Uniform '{}' is not a vec2", name);
            return;
        }

        buffer.putFloat(entry.offset, x);
        buffer.putFloat(entry.offset + 4, y);
        dirty = true;
    }

    /**
     * Set vec3 uniform value.
     */
    public void setVec3(String name, float x, float y, float z) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.VEC3) {
            LOGGER.warn("Uniform '{}' is not a vec3", name);
            return;
        }

        buffer.putFloat(entry.offset, x);
        buffer.putFloat(entry.offset + 4, y);
        buffer.putFloat(entry.offset + 8, z);
        // Note: vec3 has 4 bytes of padding
        dirty = true;
    }

    /**
     * Set vec4 uniform value.
     */
    public void setVec4(String name, float x, float y, float z, float w) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.VEC4) {
            LOGGER.warn("Uniform '{}' is not a vec4", name);
            return;
        }

        buffer.putFloat(entry.offset, x);
        buffer.putFloat(entry.offset + 4, y);
        buffer.putFloat(entry.offset + 8, z);
        buffer.putFloat(entry.offset + 12, w);
        dirty = true;
    }

    /**
     * Set mat4 uniform value.
     */
    public void setMat4(String name, FloatBuffer matrix) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.MAT4) {
            LOGGER.warn("Uniform '{}' is not a mat4", name);
            return;
        }

        // Copy 16 floats (64 bytes)
        for (int i = 0; i < 16; i++) {
            buffer.putFloat(entry.offset + (i * 4), matrix.get(i));
        }

        dirty = true;
    }

    /**
     * Set int uniform value.
     */
    public void setInt(String name, int value) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            LOGGER.warn("Uniform '{}' not found in buffer", name);
            return;
        }

        if (entry.type != UniformType.INT) {
            LOGGER.warn("Uniform '{}' is not an int", name);
            return;
        }

        buffer.putInt(entry.offset, value);
        dirty = true;
    }

    /**
     * Update uniform data from supplier.
     */
    public void updateFromSupplier(String name) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null || entry.supplier == null) {
            return;
        }

        ByteBuffer sourceData = entry.supplier.get();
        if (sourceData == null) {
            return;
        }

        // Copy data from supplier to our buffer
        sourceData.position(0);
        int bytesToCopy = Math.min(entry.size, sourceData.remaining());

        for (int i = 0; i < bytesToCopy; i++) {
            buffer.put(entry.offset + i, sourceData.get(i));
        }

        dirty = true;
    }

    /**
     * Update all uniforms from their suppliers.
     */
    public void updateAllFromSuppliers() {
        for (UniformEntry entry : uniforms.values()) {
            if (entry.supplier != null) {
                updateFromSupplier(entry.name);
            }
        }
    }

    /**
     * Upload buffer data to GPU if dirty.
     *
     * @param pipeline The pipeline to upload to
     */
    public void uploadIfDirty(DirectX11Pipeline pipeline) {
        if (!dirty) {
            return;
        }

        // Convert ByteBuffer to byte array
        byte[] data = new byte[size];
        buffer.position(0);
        buffer.get(data);

        // Upload to GPU
        pipeline.uploadConstantBuffer(slot, data);

        dirty = false;
    }

    /**
     * Force upload buffer data to GPU.
     *
     * @param pipeline The pipeline to upload to
     */
    public void upload(DirectX11Pipeline pipeline) {
        dirty = true;
        uploadIfDirty(pipeline);
    }

    /**
     * Get uniform entry by name.
     */
    public UniformEntry getUniform(String name) {
        return uniforms.get(name);
    }

    /**
     * Check if buffer is dirty.
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Mark buffer as dirty.
     */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Get buffer slot.
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Get buffer size.
     */
    public int getSize() {
        return size;
    }

    /**
     * Get all uniform entries.
     */
    public java.util.Collection<UniformEntry> getUniforms() {
        return uniforms.values();
    }

    /**
     * Get the underlying ByteBuffer.
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Clean up native resources.
     */
    public void cleanup() {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }
}
