package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * DirectX 11 implementation of GpuBuffer.MappedView
 *
 * Represents a CPU-accessible view of a GPU buffer through DirectX 11's Map/Unmap mechanism.
 * When the buffer is mapped, DirectX provides a pointer to the buffer's data which we wrap
 * in a ByteBuffer for Java access.
 */
public class DirectX11MappedView implements GpuBuffer.MappedView {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11MappedView");

    private final DirectX11GpuBuffer buffer;
    private final ByteBuffer data;
    private final boolean canRead;
    private final boolean canWrite;
    private boolean closed = false;

    public DirectX11MappedView(DirectX11GpuBuffer buffer, ByteBuffer data, boolean canRead, boolean canWrite) {
        this.buffer = buffer;
        this.data = data;
        this.canRead = canRead;
        this.canWrite = canWrite;

        LOGGER.debug("Created DirectX11MappedView: buffer={}, size={}, read={}, write={}",
            buffer.getDebugLabel(), data.capacity(), canRead, canWrite);
    }

    @Override
    public ByteBuffer data() {
        if (closed) {
            throw new IllegalStateException("MappedView has been closed");
        }
        return data;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        LOGGER.debug("Unmapping buffer: {}", buffer.getDebugLabel());

        // Unmap the buffer in native code
        long nativeHandle = buffer.getNativeHandle();
        if (nativeHandle != 0) {
            VitraNativeRenderer.unmapBuffer(nativeHandle);
        }

        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
