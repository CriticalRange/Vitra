package com.vitra.util;

import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Zero-copy native memory buffer wrapper for DirectX uniform data.
 *
 * Based on VulkanMod's MappedBuffer pattern - uses direct ByteBuffer with native pointer
 * for efficient CPU-side uniform storage and GPU upload.
 *
 * Key features:
 * - Direct ByteBuffer allocation via LWJGL MemoryUtil
 * - Native pointer (long) for zero-copy memcpy to constant buffers
 * - sun.misc.Unsafe for fast native memory access
 * - Automatic cleanup on finalization
 *
 * Memory layout:
 * - Allocated with MemoryUtil.memAlloc() (off-heap)
 * - Accessible via both ByteBuffer API and raw pointer
 * - Can be directly passed to JNI for GPU upload
 */
public class MappedBuffer {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe instance", e);
        }
    }

    public final ByteBuffer buffer;
    public final long ptr;  // Native memory address
    private final int capacity;

    /**
     * Allocate native memory buffer
     * @param sizeInBytes Buffer size in bytes
     */
    public MappedBuffer(int sizeInBytes) {
        this.capacity = sizeInBytes;
        this.buffer = MemoryUtil.memAlloc(sizeInBytes);
        this.ptr = MemoryUtil.memAddress(buffer);

        // Initialize to zero
        MemoryUtil.memSet(ptr, 0, sizeInBytes);
    }

    // ==================== FLOAT ACCESS ====================

    /**
     * Write float at byte offset
     * @param byteOffset Offset in bytes (NOT float index)
     * @param value Float value to write
     */
    public void putFloat(int byteOffset, float value) {
        UNSAFE.putFloat(ptr + byteOffset, value);
    }

    /**
     * Read float at byte offset
     * @param byteOffset Offset in bytes (NOT float index)
     * @return Float value
     */
    public float getFloat(int byteOffset) {
        return UNSAFE.getFloat(ptr + byteOffset);
    }

    /**
     * Write float array at byte offset
     * @param byteOffset Starting offset in bytes
     * @param values Float array to write
     */
    public void putFloatArray(int byteOffset, float[] values) {
        for (int i = 0; i < values.length; i++) {
            UNSAFE.putFloat(ptr + byteOffset + (i * 4), values[i]);
        }
    }

    // ==================== INTEGER ACCESS ====================

    /**
     * Write int at byte offset
     * @param byteOffset Offset in bytes
     * @param value Int value to write
     */
    public void putInt(int byteOffset, int value) {
        UNSAFE.putInt(ptr + byteOffset, value);
    }

    /**
     * Read int at byte offset
     * @param byteOffset Offset in bytes
     * @return Int value
     */
    public int getInt(int byteOffset) {
        return UNSAFE.getInt(ptr + byteOffset);
    }

    // ==================== MATRIX ACCESS ====================

    /**
     * Write 4x4 matrix (16 floats) at byte offset
     * @param byteOffset Starting offset in bytes
     * @param matrix 16-element float array (column-major)
     */
    public void putMatrix4f(int byteOffset, float[] matrix) {
        if (matrix.length < 16) {
            throw new IllegalArgumentException("Matrix must have 16 elements");
        }
        putFloatArray(byteOffset, matrix);
    }

    /**
     * Write 4x4 matrix from org.joml.Matrix4f
     * @param byteOffset Starting offset in bytes
     * @param matrix JOML Matrix4f
     */
    public void putMatrix4f(int byteOffset, org.joml.Matrix4f matrix) {
        // JOML matrices are column-major, matching HLSL
        matrix.get(byteOffset, buffer);
    }

    // ==================== VECTOR ACCESS ====================

    /**
     * Write vec2 (2 floats) at byte offset
     */
    public void putVec2(int byteOffset, float x, float y) {
        UNSAFE.putFloat(ptr + byteOffset, x);
        UNSAFE.putFloat(ptr + byteOffset + 4, y);
    }

    /**
     * Write vec3 (3 floats) at byte offset
     * Note: std140 layout requires vec3 to be 16-byte aligned (padded to vec4)
     */
    public void putVec3(int byteOffset, float x, float y, float z) {
        UNSAFE.putFloat(ptr + byteOffset, x);
        UNSAFE.putFloat(ptr + byteOffset + 4, y);
        UNSAFE.putFloat(ptr + byteOffset + 8, z);
        // Padding handled by std140 alignment in AlignedStruct
    }

    /**
     * Write vec4 (4 floats) at byte offset
     */
    public void putVec4(int byteOffset, float x, float y, float z, float w) {
        UNSAFE.putFloat(ptr + byteOffset, x);
        UNSAFE.putFloat(ptr + byteOffset + 4, y);
        UNSAFE.putFloat(ptr + byteOffset + 8, z);
        UNSAFE.putFloat(ptr + byteOffset + 12, w);
    }

    /**
     * Write vec4 from float array
     */
    public void putVec4(int byteOffset, float[] values) {
        if (values.length < 4) {
            throw new IllegalArgumentException("Vec4 requires 4 elements");
        }
        putVec4(byteOffset, values[0], values[1], values[2], values[3]);
    }

    // ==================== BULK OPERATIONS ====================

    /**
     * Copy data from another MappedBuffer
     * @param src Source buffer
     * @param srcOffset Source byte offset
     * @param dstOffset Destination byte offset
     * @param length Number of bytes to copy
     */
    public void copyFrom(MappedBuffer src, int srcOffset, int dstOffset, int length) {
        MemoryUtil.memCopy(src.ptr + srcOffset, this.ptr + dstOffset, length);
    }

    /**
     * Clear buffer to zero
     */
    public void clear() {
        MemoryUtil.memSet(ptr, 0, capacity);
    }

    // ==================== METADATA ====================

    /**
     * Get buffer capacity in bytes
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Get native pointer address
     */
    public long address() {
        return ptr;
    }

    /**
     * Get underlying ByteBuffer
     */
    public ByteBuffer byteBuffer() {
        return buffer;
    }

    // ==================== CLEANUP ====================

    /**
     * Free native memory (must be called manually or via try-with-resources if implementing AutoCloseable)
     */
    public void free() {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            free();
        } finally {
            super.finalize();
        }
    }

    @Override
    public String toString() {
        return String.format("MappedBuffer[ptr=0x%X, capacity=%d bytes]", ptr, capacity);
    }
}
