package com.vitra.compat;

import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Compatibility class for VertexBuffer which exists in Minecraft 1.21.1
 * This provides the correct interface for the VertexBuffer class
 */
public class VertexBuffer {
    private long directXHandle = 0;
    private VertexFormat format;
    private boolean valid = false;
    private int vertexCount = 0;

    public VertexBuffer() {
        // Empty constructor for compatibility
    }

    public VertexBuffer(long directXHandle, VertexFormat format, int vertexCount) {
        this.directXHandle = directXHandle;
        this.format = format;
        this.vertexCount = vertexCount;
        this.valid = true;
    }

    public long getDirectXHandle() {
        return directXHandle;
    }

    public VertexFormat getFormat() {
        return format;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public boolean isValid() {
        return valid;
    }

    public void invalidate() {
        this.valid = false;
        this.directXHandle = 0;
    }

    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    @Override
    public String toString() {
        return String.format("VertexBuffer[handle=0x%s, format=%s, vertexCount=%d, valid=%s]",
                           Long.toHexString(directXHandle), format, vertexCount, valid);
    }
}