package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuFence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX fence implementation using frame-based synchronization.
 *
 * BGFX does NOT have OpenGL-style fences or Vulkan semaphores.
 * Instead, BGFX uses bgfx_frame() as the synchronization point.
 *
 * From BGFX docs:
 * "bgfx::frame() returns the current frame number. This might be used in conjunction
 * with double/multi buffering data outside the library."
 *
 * A fence is considered "signaled" (complete) once bgfx_frame() has been called
 * AFTER the fence was created, indicating that all previous GPU work has been submitted.
 */
public class BgfxFence implements GpuFence {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxFence");

    // Global frame counter incremented by bgfx_frame() in WindowUpdateDisplayMixin
    private static volatile long currentFrame = 0;

    private final long fenceFrame;
    private boolean disposed = false;

    /**
     * Create a BGFX fence at the current frame.
     * The fence is considered complete after the next bgfx_frame() call.
     */
    public BgfxFence() {
        this.fenceFrame = currentFrame;
        LOGGER.trace("Created BGFX fence at frame {}", fenceFrame);
    }

    /**
     * Called by WindowUpdateDisplayMixin after each bgfx_frame() to advance the frame counter.
     * This marks all previous fences as complete.
     */
    public static void advanceFrame() {
        currentFrame++;
    }

    /**
     * Get the current frame number for external synchronization.
     */
    public static long getCurrentFrame() {
        return currentFrame;
    }

    public long getFenceFrame() {
        return fenceFrame;
    }

    /**
     * Wait for the fence to complete.
     * A fence is complete if currentFrame > fenceFrame, meaning bgfx_frame() has been called.
     *
     * @param timeoutNanos Maximum time to wait in nanoseconds (0 = no wait, -1 = infinite)
     * @return true if fence completed, false if timed out
     */
    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        if (disposed) {
            LOGGER.trace("Fence already disposed, considering complete");
            return true;
        }

        // Check if already complete
        if (currentFrame > fenceFrame) {
            LOGGER.trace("Fence at frame {} already complete (current: {})", fenceFrame, currentFrame);
            return true;
        }

        // If no wait requested, return current state
        if (timeoutNanos == 0) {
            return currentFrame > fenceFrame;
        }

        // Wait with timeout
        try {
            if (timeoutNanos > 0) {
                // Wait with timeout
                long startTime = System.nanoTime();
                while (currentFrame <= fenceFrame) {
                    long elapsed = System.nanoTime() - startTime;
                    if (elapsed >= timeoutNanos) {
                        LOGGER.trace("Fence wait timed out at frame {} (current: {})", fenceFrame, currentFrame);
                        return false;
                    }
                    Thread.sleep(1); // Sleep briefly to avoid busy waiting
                }
            } else {
                // Wait indefinitely
                while (currentFrame <= fenceFrame) {
                    Thread.sleep(1);
                }
            }
            LOGGER.trace("Fence at frame {} completed (current: {})", fenceFrame, currentFrame);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Fence wait interrupted", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (!disposed) {
            LOGGER.trace("Closing BGFX fence at frame {}", fenceFrame);
            disposed = true;
        }
    }

    @Override
    public String toString() {
        return String.format("BgfxFence{fenceFrame=%d, currentFrame=%d, complete=%s, disposed=%s}",
            fenceFrame, currentFrame, currentFrame > fenceFrame, disposed);
    }
}