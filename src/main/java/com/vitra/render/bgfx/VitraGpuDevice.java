package com.vitra.render.bgfx;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.TextureFormat;
import com.vitra.VitraMod;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.function.BiFunction;

/**
 * BGFX implementation of GpuDevice to replace OpenGL device functionality
 */
public class VitraGpuDevice implements GpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGpuDevice");

    private static VitraGpuDevice instance;

    public VitraGpuDevice() {
        LOGGER.info("VitraGpuDevice created - replacing OpenGL GpuDevice with BGFX DirectX 11");
    }

    public static VitraGpuDevice getInstance() {
        if (instance == null) {
            instance = new VitraGpuDevice();
        }
        return instance;
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        return new BgfxCommandEncoder();
    }

    @Override
    public GpuTexture createTexture(Supplier<String> name, int i, TextureFormat format, int width, int height, int depth, int mipLevels) {
        String textureName = name != null ? name.get() : "unnamed";
        return new BgfxTexture(textureName, i, format, width, height, depth, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String name, int i, TextureFormat format, int width, int height, int depth, int mipLevels) {
        return new BgfxTexture(name, i, format, width, height, depth, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        if (texture instanceof BgfxTexture bgfxTexture) {
            return new BgfxTextureView(bgfxTexture);
        } else {
            throw new IllegalArgumentException("Expected BgfxTexture, got: " + texture.getClass());
        }
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseLevel, int levelCount) {
        if (texture instanceof BgfxTexture bgfxTexture) {
            return new BgfxTextureView(bgfxTexture, baseLevel, levelCount);
        } else {
            throw new IllegalArgumentException("Expected BgfxTexture, got: " + texture.getClass());
        }
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> name, int usage, int size) {
        String bufferName = name != null ? name.get() : "unnamed";
        LOGGER.info("VitraGpuDevice.createBuffer: name={}, usage=0x{}, size={}",
            bufferName, Integer.toHexString(usage), size);
        return new BgfxBuffer(bufferName, size, usage, null); // null = auto-detect type from usage flags
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> name, int usage, ByteBuffer data) {
        String bufferName = name != null ? name.get() : "unnamed";
        LOGGER.info("VitraGpuDevice.createBuffer (with data): name={}, usage=0x{}, data.remaining={}",
            bufferName, Integer.toHexString(usage), data.remaining());
        BgfxBuffer buffer = new BgfxBuffer(bufferName, data.remaining(), usage, null); // null = auto-detect type
        // Update the buffer with the provided data
        buffer.updateData(0, data);
        return buffer;
    }

    @Override
    public String getImplementationInformation() {
        return "BGFX DirectX 11 Implementation for Vitra";
    }

    @Override
    public String getVendor() {
        return "BGFX DirectX 11";
    }

    @Override
    public String getBackendName() {
        return "BGFX D3D11";
    }

    @Override
    public String getRenderer() {
        return "BGFX D3D11 Backend";
    }

    @Override
    public String getVersion() {
        return "BGFX 1.121.8467 (DirectX 11)";
    }

    @Override
    public int getMaxTextureSize() {
        // Return a reasonable default for DirectX 11
        return 16384;
    }

    @Override
    public int getUniformOffsetAlignment() {
        // DirectX 11 constant buffer alignment requirement
        return 256;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public List<String> getEnabledExtensions() {
        return Collections.emptyList();
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline) {
        return new BgfxCompiledRenderPipeline(pipeline);
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderResolver) {
        return new BgfxCompiledRenderPipeline(pipeline, shaderResolver);
    }

    @Override
    public void clearPipelineCache() {
        // TODO: Implement BGFX pipeline cache clearing
    }

    @Override
    public void close() {
        LOGGER.info("VitraGpuDevice.close() called - shutting down BGFX DirectX 11");
        try {
            if (VitraMod.getRenderer() != null) {
                VitraMod.getRenderer().shutdown();
            }
        } catch (Exception e) {
            LOGGER.error("Error shutting down BGFX device", e);
        }
    }
}