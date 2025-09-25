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
public class BgfxGpuDevice implements GpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGpuDevice");

    private static BgfxGpuDevice instance;

    public BgfxGpuDevice() {
        LOGGER.info("BgfxGpuDevice created - replacing OpenGL GpuDevice with BGFX DirectX 11");
    }

    public static BgfxGpuDevice getInstance() {
        if (instance == null) {
            instance = new BgfxGpuDevice();
        }
        return instance;
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        LOGGER.debug("createCommandEncoder() called - creating BGFX CommandEncoder");
        return new BgfxCommandEncoder();
    }

    @Override
    public GpuTexture createTexture(Supplier<String> name, int i, TextureFormat format, int width, int height, int depth, int mipLevels) {
        String textureName = name != null ? name.get() : "unnamed";
        LOGGER.debug("createTexture() called with supplier '{}': {}x{}x{}, format: {}", textureName, width, height, depth, format);
        return new BgfxGpuTexture(textureName, i, format, width, height, depth, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String name, int i, TextureFormat format, int width, int height, int depth, int mipLevels) {
        LOGGER.debug("createTexture() called with name '{}': {}x{}x{}, format: {}", name, width, height, depth, format);
        return new BgfxGpuTexture(name, i, format, width, height, depth, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        LOGGER.debug("createTextureView() called");
        if (texture instanceof BgfxGpuTexture bgfxTexture) {
            return new BgfxGpuTextureView(bgfxTexture);
        } else {
            throw new IllegalArgumentException("Expected BgfxGpuTexture, got: " + texture.getClass());
        }
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseLevel, int levelCount) {
        LOGGER.debug("createTextureView() called with levels {}-{}", baseLevel, levelCount);
        if (texture instanceof BgfxGpuTexture bgfxTexture) {
            return new BgfxGpuTextureView(bgfxTexture, baseLevel, levelCount);
        } else {
            throw new IllegalArgumentException("Expected BgfxGpuTexture, got: " + texture.getClass());
        }
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> name, int usage, int size) {
        LOGGER.debug("createBuffer() called with supplier: size {}, usage {}", size, usage);
        return new BgfxGpuBuffer(size, mapUsageToBgfxBufferType(usage));
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> name, int usage, ByteBuffer data) {
        LOGGER.debug("createBuffer() called with supplier and ByteBuffer: size {}, usage {}", data.remaining(), usage);
        return new BgfxGpuBuffer(data.remaining(), mapUsageToBgfxBufferType(usage));
    }

    private BgfxGpuBuffer.BufferType mapUsageToBgfxBufferType(int usage) {
        // Map GpuBuffer usage flags to BGFX buffer types
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            return BgfxGpuBuffer.BufferType.DYNAMIC_INDEX_BUFFER;
        } else if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            return BgfxGpuBuffer.BufferType.DYNAMIC_VERTEX_BUFFER;
        } else if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            return BgfxGpuBuffer.BufferType.UNIFORM_BUFFER;
        } else {
            // Default to dynamic vertex buffer for unknown usage
            return BgfxGpuBuffer.BufferType.DYNAMIC_VERTEX_BUFFER;
        }
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
        LOGGER.debug("precompilePipeline() called (default)");
        return new BgfxCompiledRenderPipeline(pipeline);
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderResolver) {
        LOGGER.debug("precompilePipeline() called with shader resolver");
        return new BgfxCompiledRenderPipeline(pipeline, shaderResolver);
    }

    @Override
    public void clearPipelineCache() {
        LOGGER.debug("clearPipelineCache() called");
        // TODO: Implement BGFX pipeline cache clearing
    }

    @Override
    public void close() {
        LOGGER.info("BgfxGpuDevice.close() called - shutting down BGFX DirectX 11");
        try {
            if (VitraMod.getRenderer() != null) {
                VitraMod.getRenderer().shutdown();
            }
        } catch (Exception e) {
            LOGGER.error("Error shutting down BGFX device", e);
        }
    }
}