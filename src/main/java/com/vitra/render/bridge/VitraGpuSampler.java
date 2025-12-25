package com.vitra.render.bridge;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalDouble;

/**
 * DirectX 11 implementation of GpuSampler.
 * Wraps a D3D11 SamplerState.
 */
public class VitraGpuSampler extends GpuSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GpuSampler");
    
    private final AddressMode addressU;
    private final AddressMode addressV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    
    // Native D3D11 sampler state handle
    private long nativeHandle = 0;
    private boolean closed = false;
    
    public VitraGpuSampler(AddressMode addressU, AddressMode addressV,
                           FilterMode minFilter, FilterMode magFilter, 
                           int maxAnisotropy, OptionalDouble maxLod) {
        this.addressU = addressU;
        this.addressV = addressV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        
        // TODO: Create native sampler state through JNI
    }
    
    @Override
    public AddressMode getAddressModeU() {
        return addressU;
    }
    
    @Override
    public AddressMode getAddressModeV() {
        return addressV;
    }
    
    @Override
    public FilterMode getMinFilter() {
        return minFilter;
    }
    
    @Override
    public FilterMode getMagFilter() {
        return magFilter;
    }
    
    @Override
    public int getMaxAnisotropy() {
        return maxAnisotropy;
    }
    
    @Override
    public OptionalDouble getMaxLod() {
        return maxLod;
    }
    
    @Override
    public void close() {
        if (!closed) {
            // TODO: Destroy native sampler state
            nativeHandle = 0;
            closed = true;
        }
    }
    
    public long getNativeHandle() {
        return nativeHandle;
    }
}
