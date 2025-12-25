package com.vitra.render.shader;

import com.vitra.render.jni.VitraD3D11Renderer;
import com.vitra.render.shader.descriptor.UBO;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * DirectX Constant Buffer Wrapper
 *
 * Manages GPU constant buffer allocation, mapping, and updates.
 * Wraps DirectX ID3D11Buffer with D3D11_BIND_CONSTANT_BUFFER flag.
 *
 * Key features:
 * - Dynamic buffer updates via Map/Unmap (D3D11_MAP_WRITE_DISCARD)
 * - CPU-side staging buffer for zero-copy updates
 * - Automatic binding to shader stages (VS/PS)
 * - Alignment to 256-byte boundaries (D3D11 constant buffer requirement)
 *
 * Usage:
 * 1. Create: D3D11ConstantBuffer cb = new D3D11ConstantBuffer(ubo)
 * 2. Update: cb.update(ubo)
 * 3. Bind: cb.bind()
 */
public class D3D11ConstantBuffer {

    private final int binding;      // Constant buffer slot (b0, b1, b2, b3)
    private final int stages;       // Shader stages (VERTEX | FRAGMENT)
    private final int size;         // Buffer size in bytes (256-byte aligned)

    private long nativeHandle;      // DirectX ID3D11Buffer handle
    private ByteBuffer stagingBuffer;  // CPU-side buffer for updates

    /**
     * Create constant buffer from UBO descriptor
     *
     * @param ubo UBO descriptor with layout and binding info
     */
    public D3D11ConstantBuffer(UBO ubo) {
        if (ubo == null) {
            throw new IllegalArgumentException("UBO descriptor cannot be null");
        }

        this.binding = ubo.getBinding();
        this.stages = ubo.getStages();
        this.size = alignTo256(ubo.getSize());

        if (this.size <= 0 || this.size > 65536) { // 64KB max for safety
            throw new IllegalArgumentException("Invalid constant buffer size: " + this.size + " bytes (must be > 0 and <= 64KB)");
        }

        try {
            // Allocate CPU staging buffer
            this.stagingBuffer = MemoryUtil.memAlloc(this.size);

            // Create GPU constant buffer (dynamic usage for frequent updates)
            this.nativeHandle = VitraD3D11Renderer.createConstantBuffer(this.size);

            if (this.nativeHandle == 0) {
                throw new RuntimeException("Failed to create DirectX constant buffer (binding=" + binding +
                    ", size=" + size + " bytes)");
            }
        } catch (Exception e) {
            // Cleanup on failure
            if (this.stagingBuffer != null) {
                MemoryUtil.memFree(this.stagingBuffer);
                this.stagingBuffer = null;
            }
            throw new RuntimeException("Failed to create D3D11 constant buffer", e);
        }
    }

    /**
     * Update constant buffer with current uniform values
     *
     * @param ubo UBO descriptor with suppliers
     */
    private static int debugUpdateCount = 0;

    public void update(UBO ubo) {
        if (ubo == null) {
            throw new IllegalArgumentException("UBO descriptor cannot be null");
        }

        if (stagingBuffer == null || nativeHandle == 0) {
            throw new IllegalStateException("Constant buffer not properly initialized");
        }

        try {
            // DEBUG: Log first 5 updates with first 64 bytes (4x4 matrix)
            if (debugUpdateCount < 5) {
                System.out.println("[D3D11_CB_UPDATE " + debugUpdateCount + "] Updating constant buffer binding=" + binding + ", size=" + size);
                debugUpdateCount++;
            }

            // Clear staging buffer
            MemoryUtil.memSet(MemoryUtil.memAddress(stagingBuffer), 0, size);

            // Get pointer to staging buffer
            long ptr = MemoryUtil.memAddress(stagingBuffer);

            // Update all uniforms (calls suppliers and copies data)
            ubo.update(ptr);

            // Upload to GPU via JNI (convert ByteBuffer to byte array)
            byte[] data = new byte[size];
            stagingBuffer.position(0);
            stagingBuffer.get(data);

            // CRITICAL DEBUG: Log ENTIRE buffer (all 256 bytes) for first 3 uploads to catch garbage
            if (debugUpdateCount <= 3 && size == 256 && binding == 0) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                System.out.println("[CB_JAVA_FULL " + debugUpdateCount + "] binding=" + binding + ", size=" + size);
                System.out.println("  [0-15]   MVP:         " + formatFloats(bb, 0, 16));
                System.out.println("  [16-31]  ModelView:   " + formatFloats(bb, 16, 16));
                System.out.println("  [32-35]  ColorMod:    " + formatFloats(bb, 32, 4));
                System.out.println("  [36-38]  ModelOffset: " + formatFloats(bb, 36, 3) + " (pad: " + bb.getFloat(39*4) + ")");
                System.out.println("  [40-55]  TextureMat:  " + formatFloats(bb, 40, 16));
                System.out.println("  [56]     LineWidth:   " + bb.getFloat(56*4));
                System.out.println("  [57-59]  Padding:     " + formatFloats(bb, 57, 3));
            }

            // Upload to GPU - the method returns void, throws exception on failure
            VitraD3D11Renderer.updateConstantBuffer(nativeHandle, data);

            stagingBuffer.position(0); // Reset position
        } catch (Exception e) {
            throw new RuntimeException("Failed to update constant buffer", e);
        }
    }

    /**
     * Bind constant buffer to shader stages
     */
    public void bind() {
        // Skip binding if buffer was not created successfully
        if (nativeHandle == 0) {
            return;
        }
        
        if (isVertexStage()) {
            VitraD3D11Renderer.bindConstantBufferVS(binding, nativeHandle);
        }
        if (isFragmentStage()) {
            VitraD3D11Renderer.bindConstantBufferPS(binding, nativeHandle);
        }
    }

    /**
     * Update and bind in one call
     */
    public void updateAndBind(UBO ubo) {
        update(ubo);
        bind();
    }

    /**
     * Check if buffer is bound to vertex shader
     */
    private boolean isVertexStage() {
        return (stages & UBO.ShaderStage.VERTEX) != 0;
    }

    /**
     * Check if buffer is bound to fragment shader
     */
    private boolean isFragmentStage() {
        return (stages & UBO.ShaderStage.FRAGMENT) != 0;
    }

    /**
     * Get constant buffer slot
     */
    public int getBinding() {
        return binding;
    }

    /**
     * Get buffer size in bytes
     */
    public int getSize() {
        return size;
    }

    /**
     * Get native DirectX buffer handle
     */
    public long getNativeHandle() {
        return nativeHandle;
    }

    /**
     * Format floats for debug logging
     */
    private static String formatFloats(java.nio.ByteBuffer bb, int startIndex, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0 && i % 4 == 0) sb.append("| ");
            sb.append(String.format("%.3f ", bb.getFloat((startIndex + i) * 4)));
        }
        return sb.toString();
    }

    /**
     * Align size to 256-byte boundary (D3D11 constant buffer requirement)
     */
    private static int alignTo256(int size) {
        return ((size + 255) / 256) * 256;
    }

    /**
     * Cleanup GPU and CPU resources
     */
    public void cleanup() {
        if (nativeHandle != 0) {
            VitraD3D11Renderer.destroyResource(nativeHandle);
            nativeHandle = 0;
        }

        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanup();
        } finally {
            super.finalize();
        }
    }

    @Override
    public String toString() {
        return String.format("D3D11ConstantBuffer[binding=b%d, size=%d bytes, handle=0x%X]",
            binding, size, nativeHandle);
    }
}
