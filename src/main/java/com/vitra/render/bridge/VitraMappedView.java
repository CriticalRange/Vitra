package com.vitra.render.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * DirectX 11 implementation of GpuBuffer.MappedView.
 * Provides access to a mapped buffer region for CPU read/write.
 * 
 * CRITICAL: When close() is called, data is uploaded to the GPU buffer.
 * This is how Minecraft writes vertex/index data to GPU buffers.
 */
public class VitraMappedView implements GpuBuffer.MappedView {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/MappedView");
    private static int uploadCount = 0;
    
    private final VitraGpuBuffer targetBuffer; // The buffer to upload to when closed
    private final ByteBuffer data;
    private final long nativePtr;
    private final long size;
    private boolean closed = false;
    
    public VitraMappedView(VitraGpuBuffer targetBuffer, long size) {
        this.targetBuffer = targetBuffer;
        this.size = size;
        // Allocate direct memory for the mapped buffer
        // CRITICAL FIX: Use nmemCalloc instead of nmemAlloc to zero-initialize memory
        // This prevents garbage data (FLT_MAX values) in constant buffers
        this.nativePtr = MemoryUtil.nmemCalloc(1, size);
        this.data = MemoryUtil.memByteBuffer(nativePtr, (int) size);
    }
    
    @Override
    public ByteBuffer data() {
        return data;
    }
    
    @Override
    public void close() {
        if (!closed) {
            // CRITICAL FIX: Upload the data to the GPU buffer before freeing memory
            // Always upload if we have a target buffer (the buffer was created for this size)
            if (targetBuffer != null) {
                // Set the limit to the full size and position to 0 for reading the whole buffer
                data.clear();  // Reset position to 0, limit to capacity
                targetBuffer.upload(data);
                
                // Log first few uploads at INFO level
                if (uploadCount < 10) {
                    LOGGER.info("[UPLOAD {}] MappedView close: uploaded {} bytes to buffer '{}' handle=0x{}",
                        uploadCount, size, targetBuffer.getName(),
                        Long.toHexString(targetBuffer.getNativeHandle()));
                    uploadCount++;
                }
            }
            
            MemoryUtil.nmemFree(nativePtr);
            closed = true;
        }
    }
}
