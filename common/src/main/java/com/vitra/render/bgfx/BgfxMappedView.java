package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;

/**
 * BGFX implementation of GpuBuffer.MappedView for mapped buffer access
 */
public class BgfxMappedView implements GpuBuffer.MappedView {
    private final ByteBuffer buffer;
    private final GpuBufferSlice slice;

    public BgfxMappedView(ByteBuffer buffer, GpuBufferSlice slice) {
        this.buffer = buffer;
        this.slice = slice;
    }

    @Override
    public ByteBuffer data() {
        return buffer;
    }

    @Override
    public void close() {
        // BGFX transient buffers are automatically managed, no explicit cleanup needed
    }
}