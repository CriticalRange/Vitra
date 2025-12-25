package com.vitra.render.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.*;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of Minecraft 26.1's GpuDevice interface.
 * 
 * This bridges Minecraft's new GPU abstraction layer to Vitra's JNI-based
 * DirectX 11 renderer. All GPU operations go through this class.
 * 
 * Architecture:
 * Minecraft → GpuDevice interface → VitraGpuDevice → JNI → vitra_d3d11.cpp → D3D11 API
 */
public class VitraGpuDevice implements GpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GpuDevice");
    
    // Device state
    private boolean initialized = false;
    private boolean vsyncEnabled = true;
    private boolean debugEnabled = false;
    
    // Cached device info
    private String vendorName = "NVIDIA"; // TODO: Query from native
    private String rendererName = "DirectX 11 via Vitra";
    private String backendName = "DirectX 11";
    private String version = "11.0";
    private int maxTextureSize = 16384;
    private int uniformOffsetAlignment = 256;
    private int maxAnisotropy = 16;
    
    // Resource tracking
    private final Map<Long, VitraGpuTexture> textures = new HashMap<>();
    private final Map<Long, VitraGpuBuffer> buffers = new HashMap<>();
    private final Map<Long, VitraGpuSampler> samplers = new HashMap<>();
    private final Map<Long, VitraCompiledPipeline> pipelines = new HashMap<>();
    private long nextResourceId = 1;
    
    // Command encoder for batching GPU commands
    private VitraCommandEncoder commandEncoder;
    
    public VitraGpuDevice() {
        LOGGER.info("Creating VitraGpuDevice - DirectX 11 backend");
    }
    
    /**
     * Initialize the DirectX 11 device with window handle.
     */
    public boolean initialize(long windowHandle, int width, int height, boolean debug) {
        LOGGER.info("Initializing DirectX 11 device: window=0x{}, size={}x{}, debug={}",
            Long.toHexString(windowHandle), width, height, debug);
        
        this.debugEnabled = debug;
        
        boolean success = VitraD3D11Renderer.initializeDirectXSafe(windowHandle, width, height, debug, false);
        if (success) {
            this.initialized = true;
            queryDeviceCapabilities();
            LOGGER.info("✓ DirectX 11 device initialized successfully");
        } else {
            LOGGER.error("✗ Failed to initialize DirectX 11 device");
        }
        return success;
    }
    
    /**
     * Query device capabilities from native code.
     */
    private void queryDeviceCapabilities() {
        // TODO: Add JNI methods to query these from DXGI adapter
        maxTextureSize = 16384;
        uniformOffsetAlignment = 256;
        maxAnisotropy = 16;
        
        LOGGER.info("Device capabilities: maxTextureSize={}, uniformAlignment={}, maxAnisotropy={}",
            maxTextureSize, uniformOffsetAlignment, maxAnisotropy);
    }
    
    // ==================== GpuDevice Interface Implementation ====================
    
    @Override
    public CommandEncoder createCommandEncoder() {
        if (commandEncoder == null) {
            commandEncoder = new VitraCommandEncoder(this);
        }
        return commandEncoder;
    }
    
    @Override
    public GpuSampler createSampler(AddressMode addressU, AddressMode addressV,
                                     FilterMode minFilter, FilterMode magFilter,
                                     int maxAnisotropy, OptionalDouble lodBias) {
        VitraGpuSampler sampler = new VitraGpuSampler(addressU, addressV, minFilter, magFilter, maxAnisotropy, lodBias);
        
        LOGGER.debug("Created sampler: addressMode={}/{}, filter={}/{}", 
            addressU, addressV, minFilter, magFilter);
        
        return sampler;
    }
    
    @Override
    public GpuTexture createTexture(Supplier<String> nameSupplier, int usage,
                                     TextureFormat format, int width, int height,
                                     int depth, int mipLevels) {
        return createTexture(nameSupplier.get(), usage, format, width, height, depth, mipLevels);
    }
    
    @Override
    public GpuTexture createTexture(String name, int usage, TextureFormat format,
                                     int width, int height, int depth, int mipLevels) {
        long id = nextResourceId++;
        
        VitraGpuTexture texture = new VitraGpuTexture(usage, name, format, width, height, depth, mipLevels);
        textures.put(id, texture);
        
        LOGGER.debug("Created texture '{}' (id={}): {}x{}x{}, format={}, mips={}",
            name, id, width, height, depth, format, mipLevels);
        
        return texture;
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return createTextureView(texture, 0, texture.getMipLevels());
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mipCount) {
        VitraGpuTexture vitraTexture = (VitraGpuTexture) texture;
        VitraGpuTextureView view = new VitraGpuTextureView(vitraTexture, baseMip, mipCount);
        
        LOGGER.debug("Created texture view for '{}': baseMip={}, mipCount={}",
            vitraTexture.getLabel(), baseMip, mipCount);
        
        return view;
    }
    
    @Override
    public GpuBuffer createBuffer(Supplier<String> nameSupplier, int usage, long size) {
        long id = nextResourceId++;
        String name = nameSupplier.get();
        
        VitraGpuBuffer buffer = new VitraGpuBuffer(name, usage, size);
        buffers.put(id, buffer);
        
        LOGGER.debug("Created buffer '{}' (id={}): size={}, usage={}", name, id, size, usage);
        
        return buffer;
    }
    
    @Override
    public GpuBuffer createBuffer(Supplier<String> nameSupplier, int usage, ByteBuffer data) {
        long size = data.remaining();
        GpuBuffer buffer = createBuffer(nameSupplier, usage, size);
        
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            vitraBuffer.upload(data);
        }
        
        return buffer;
    }
    
    @Override
    public String getImplementationInformation() {
        return String.format("Vitra DirectX 11 Renderer\nVendor: %s\nRenderer: %s\nVersion: %s",
            vendorName, rendererName, version);
    }
    
    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }
    
    @Override
    public boolean isDebuggingEnabled() {
        return debugEnabled;
    }
    
    @Override
    public String getVendor() {
        return vendorName;
    }
    
    @Override
    public String getBackendName() {
        return backendName;
    }
    
    @Override
    public String getVersion() {
        return version;
    }
    
    @Override
    public String getRenderer() {
        return rendererName;
    }
    
    @Override
    public int getMaxTextureSize() {
        return maxTextureSize;
    }
    
    @Override
    public int getUniformOffsetAlignment() {
        return uniformOffsetAlignment;
    }
    
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
        long id = nextResourceId++;
        
        VitraCompiledPipeline compiled = new VitraCompiledPipeline(id, pipeline);
        pipelines.put(id, compiled);
        
        LOGGER.debug("Precompiled pipeline {}", id);
        
        return compiled;
    }
    
    @Override
    public void clearPipelineCache() {
        for (VitraCompiledPipeline pipeline : pipelines.values()) {
            pipeline.close();
        }
        pipelines.clear();
        LOGGER.debug("Cleared pipeline cache");
    }
    
    @Override
    public List<String> getEnabledExtensions() {
        return List.of(
            "D3D_FEATURE_LEVEL_11_0",
            "D3D11_FORMAT_SUPPORT_TEXTURE2D",
            "D3D11_FORMAT_SUPPORT_RENDER_TARGET"
        );
    }
    
    @Override
    public int getMaxSupportedAnisotropy() {
        return maxAnisotropy;
    }
    
    @Override
    public void close() {
        LOGGER.info("Closing VitraGpuDevice...");
        
        clearPipelineCache();
        
        for (VitraGpuTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        
        for (VitraGpuBuffer buffer : buffers.values()) {
            buffer.close();
        }
        buffers.clear();
        
        for (VitraGpuSampler sampler : samplers.values()) {
            sampler.close();
        }
        samplers.clear();
        
        VitraD3D11Renderer.shutdownSafe();
        initialized = false;
        
        LOGGER.info("✓ VitraGpuDevice closed");
    }
    
    @Override
    public void setVsync(boolean enabled) {
        this.vsyncEnabled = enabled;
        VitraD3D11Renderer.recreateSwapChain();
        LOGGER.debug("VSync set to: {}", enabled);
    }
    
    @Override
    public void presentFrame() {
        VitraD3D11Renderer.endFrame();
    }
    
    @Override
    public boolean isZZeroToOne() {
        return true;
    }
    
    // ==================== Helper Methods ====================
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public long getNativeTextureHandle(GpuTexture texture) {
        if (texture instanceof VitraGpuTexture vitraTexture) {
            return vitraTexture.getNativeHandle();
        }
        return 0;
    }
}
