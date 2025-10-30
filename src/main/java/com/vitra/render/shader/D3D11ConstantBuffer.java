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
    public void update(UBO ubo) {
        if (ubo == null) {
            throw new IllegalArgumentException("UBO descriptor cannot be null");
        }

        if (stagingBuffer == null || nativeHandle == 0) {
            throw new IllegalStateException("Constant buffer not properly initialized");
        }

        try {
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
