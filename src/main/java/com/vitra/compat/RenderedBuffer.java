package com.vitra.compat;

import org.jetbrains.annotations.Nullable;

/**
 * Compatibility class for BufferBuilder.RenderedBuffer which may not exist in all Minecraft versions
 * This provides the correct interface for RenderedBuffer functionality
 */
public class RenderedBuffer {
    private final BufferBuilder.DrawState drawState;
    private final int vertexCount;
    @Nullable private final Object result;

    public RenderedBuffer(BufferBuilder.DrawState drawState, int vertexCount, @Nullable Object result) {
        this.drawState = drawState;
        this.vertexCount = vertexCount;
        this.result = result;
    }

    public BufferBuilder.DrawState drawState() {
        return drawState;
    }

    public int vertexCount() {
        return vertexCount;
    }

    @Nullable
    public Object result() {
        return result;
    }

    @Override
    public String toString() {
        return String.format("RenderedBuffer[vertexCount=%d, drawState=%s]", vertexCount, drawState);
    }
}