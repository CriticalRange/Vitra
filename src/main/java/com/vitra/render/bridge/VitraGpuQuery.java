package com.vitra.render.bridge;

import com.mojang.blaze3d.systems.GpuQuery;

import java.util.OptionalLong;

/**
 * DirectX 11 implementation of GpuQuery for timer queries.
 */
public class VitraGpuQuery implements GpuQuery {
    
    @Override
    public OptionalLong getValue() {
        // TODO: Implement D3D11 timer query
        return OptionalLong.of(0);
    }
    
    @Override
    public void close() {
        // Cleanup
    }
}
