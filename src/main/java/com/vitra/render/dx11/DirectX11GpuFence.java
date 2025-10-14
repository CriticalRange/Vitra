package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuFence;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 implementation of GPU fence for CPU-GPU synchronization
 *
 * Uses ID3D11Query with D3D11_QUERY_EVENT to implement fence semantics.
 * The fence is automatically signaled when created - the query End() is called
 * immediately, and the GPU will mark it complete when all previous commands finish.
 */
public class DirectX11GpuFence implements GpuFence {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11GpuFence");

    private final long fenceHandle;
    private boolean closed = false;

    public DirectX11GpuFence() {
        this.fenceHandle = VitraNativeRenderer.createFence();

        if (fenceHandle == 0) {
            throw new RuntimeException("Failed to create DirectX 11 GPU fence");
        }

        // Signal the fence immediately (issue End() command)
        // GPU will complete this when all previous commands finish
        VitraNativeRenderer.signalFence(fenceHandle);

        LOGGER.debug("Created and signaled GPU fence: handle=0x{}", Long.toHexString(fenceHandle));
    }

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        if (closed) {
            LOGGER.warn("Attempted to await on closed fence: 0x{}", Long.toHexString(fenceHandle));
            return false;
        }

        LOGGER.debug("Awaiting fence completion (timeout={}ns): 0x{}", timeoutNanos, Long.toHexString(fenceHandle));

        // Check if already signaled (non-blocking check)
        if (VitraNativeRenderer.isFenceSignaled(fenceHandle)) {
            LOGGER.debug("Fence already completed: 0x{}", Long.toHexString(fenceHandle));
            return true;
        }

        // For DirectX 11, we don't have native timeout support on queries
        // So we either poll or block indefinitely
        if (timeoutNanos == 0) {
            // timeout=0 means check immediately without waiting
            return VitraNativeRenderer.isFenceSignaled(fenceHandle);
        } else if (timeoutNanos < 0) {
            // Negative timeout means wait indefinitely
            VitraNativeRenderer.waitForFence(fenceHandle);
            return true;
        } else {
            // Positive timeout: poll with a time limit
            long startTime = System.nanoTime();
            while ((System.nanoTime() - startTime) < timeoutNanos) {
                if (VitraNativeRenderer.isFenceSignaled(fenceHandle)) {
                    LOGGER.debug("Fence completed: 0x{}", Long.toHexString(fenceHandle));
                    return true;
                }
                // Small sleep to avoid busy-waiting
                try {
                    Thread.sleep(0, 100000); // 0.1ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            LOGGER.debug("Fence await timeout: 0x{}", Long.toHexString(fenceHandle));
            return false;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        LOGGER.debug("Closing GPU fence: 0x{}", Long.toHexString(fenceHandle));

        // Cleanup is handled by destroyResource in native code
        VitraNativeRenderer.destroyResource(fenceHandle);

        closed = true;
    }

    /**
     * Get the native fence handle
     */
    public long getNativeHandle() {
        return fenceHandle;
    }

    /**
     * Check if this fence has been closed
     */
    public boolean isClosed() {
        return closed;
    }
}
