package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuFence;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX implementation of GpuFence for synchronization between GPU and CPU
 */
public class BgfxGpuFence implements GpuFence {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGpuFence");

    private final short queryHandle;
    private boolean disposed = false;

    public BgfxGpuFence() {
        this.queryHandle = BGFX.bgfx_create_occlusion_query();
        LOGGER.debug("Created BGFX fence with query handle: {}", queryHandle);
    }

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        if (disposed) {
            LOGGER.warn("Attempting to wait on disposed fence");
            return true;
        }

        LOGGER.debug("Awaiting BGFX fence completion with timeout {} ns (query handle: {})", timeoutNanos, queryHandle);

        long startTime = System.nanoTime();
        int[] result = new int[1];
        int queryResult;

        do {
            queryResult = BGFX.bgfx_get_result(queryHandle, result);
            if (queryResult != 0) {
                LOGGER.debug("BGFX fence completed with result: {}", result[0]);
                return true;
            }

            // Check timeout
            if (timeoutNanos > 0 && (System.nanoTime() - startTime) >= timeoutNanos) {
                LOGGER.debug("BGFX fence await timed out");
                return false;
            }

            // Query not ready yet, yield thread briefly
            Thread.yield();
        } while (queryResult == 0);

        return true;
    }

    @Override
    public void close() {
        if (!disposed && queryHandle != BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.debug("Closing BGFX fence (query handle: {})", queryHandle);
            BGFX.bgfx_destroy_occlusion_query(queryHandle);
            disposed = true;
        }
    }

    public short getQueryHandle() {
        return queryHandle;
    }

    public boolean isDisposed() {
        return disposed;
    }
}