package com.vitra.compat;

import org.jetbrains.annotations.Nullable;

/**
 * Compatibility class for MeshData which may not exist in all Minecraft versions
 * This provides the correct interface for the MeshData class used in BufferUploader
 */
public class MeshData {
    private final BufferBuilder.VertexFormat.Mode mode;
    private final BufferBuilder.VertexFormat format;
    private final int vertexCount;
    private final int indexCount;
    @Nullable private final RenderedBuffer renderedBuffer;

    public MeshData(BufferBuilder.VertexFormat.Mode mode, BufferBuilder.VertexFormat format, int vertexCount, int indexCount,
                    @Nullable RenderedBuffer renderedBuffer) {
        this.mode = mode;
        this.format = format;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.renderedBuffer = renderedBuffer;
    }

    public BufferBuilder.VertexFormat.Mode mode() {
        return mode;
    }

    public BufferBuilder.VertexFormat format() {
        return format;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    @Nullable
    public RenderedBuffer renderedBuffer() {
        return renderedBuffer;
    }

    @Override
    public String toString() {
        return String.format("MeshData[mode=%s, format=%s, vertexCount=%d, indexCount=%d]",
                           mode, format, vertexCount, indexCount);
    }
}