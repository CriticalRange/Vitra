package com.vitra.compat;

/**
 * Compatibility class for BufferBuilder.DrawState which may not exist in all Minecraft versions
 * This provides the correct interface for DrawState functionality
 */
public class DrawState {
    private final int mode;
    private final int vertexCount;
    private final int indexCount;

    public DrawState(int mode, int vertexCount, int indexCount) {
        this.mode = mode;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
    }

    public int mode() {
        return mode;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    @Override
    public String toString() {
        return String.format("DrawState[mode=%d, vertexCount=%d, indexCount=%d]", mode, vertexCount, indexCount);
    }
}