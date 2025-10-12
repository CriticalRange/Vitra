package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of Minecraft's GpuDevice interface
 *
 * This class wraps the native DirectX 11 renderer and provides the GPU abstraction
 * layer that Minecraft expects. All actual rendering is delegated to the native
 * vitra-native.dll library through JNI.
 */
public class DirectX11GpuDevice implements GpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11GpuDevice");

    private final long windowHandle;
    private final boolean debugEnabled;
    private final List<String> debugMessages = new ArrayList<>();
    private boolean closed = false;

    public DirectX11GpuDevice(long windowHandle, int debugVerbosity, boolean sync) {
        this.windowHandle = windowHandle;
        this.debugEnabled = debugVerbosity > 0;

        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  DIRECTX 11 GPUDEVICE CREATED                              ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Window Handle: 0x{}", Long.toHexString(windowHandle));
        LOGGER.info("║ Debug Enabled: {}", debugEnabled);
        LOGGER.info("║ VSync:         {}", sync);
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        LOGGER.debug("createCommandEncoder()");
        return new DirectX11CommandEncoder();
    }

    @Override
    public GpuTexture createTexture(Supplier<String> labelSupplier, int usage, TextureFormat format,
                                     int width, int height, int depthOrLayers, int mipLevels) {
        String label = labelSupplier.get();
        LOGGER.debug("createTexture({}, {}x{}x{}, format={}, mips={})",
            label, width, height, depthOrLayers, format, mipLevels);

        return new DirectX11GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, TextureFormat format,
                                     int width, int height, int depthOrLayers, int mipLevels) {
        LOGGER.debug("createTexture({}, {}x{}x{}, format={}, mips={})",
            label, width, height, depthOrLayers, format, mipLevels);

        return new DirectX11GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        LOGGER.debug("createTextureView({})", texture.getLabel());

        if (!(texture instanceof DirectX11GpuTexture dx11Texture)) {
            throw new IllegalArgumentException("Texture must be DirectX11GpuTexture");
        }

        return new DirectX11GpuTextureView(dx11Texture);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        LOGGER.debug("createTextureView({}, baseMip={}, mipCount={})",
            texture.getLabel(), baseMipLevel, mipLevels);

        if (!(texture instanceof DirectX11GpuTexture dx11Texture)) {
            throw new IllegalArgumentException("Texture must be DirectX11GpuTexture");
        }

        return new DirectX11GpuTextureView(dx11Texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> labelSupplier, int usage, int size) {
        String label = labelSupplier.get();
        LOGGER.debug("createBuffer({}, size={}, usage=0x{})",
            label, size, Integer.toHexString(usage));

        return new DirectX11GpuBuffer(usage, size, label);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> labelSupplier, int usage, ByteBuffer data) {
        String label = labelSupplier.get();
        LOGGER.debug("createBuffer({}, dataSize={}, usage=0x{})",
            label, data.remaining(), Integer.toHexString(usage));

        return new DirectX11GpuBuffer(usage, data, label);
    }

    @Override
    public String getImplementationInformation() {
        String deviceInfo = "Vitra DirectX 11 Renderer (JNI)";

        // Try to get more detailed device info from native code
        try {
            if (VitraNativeRenderer.isInitialized()) {
                String nativeInfo = VitraNativeRenderer.nativeGetDeviceInfo();
                if (nativeInfo != null && !nativeInfo.isEmpty()) {
                    deviceInfo = nativeInfo;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get native device info", e);
        }

        return deviceInfo;
    }

    @Override
    public List<String> getLastDebugMessages() {
        // Try to get debug messages from native DirectX debug layer
        if (debugEnabled) {
            try {
                String nativeMessages = VitraNativeRenderer.nativeGetDebugMessages();
                if (nativeMessages != null && !nativeMessages.isEmpty()) {
                    // Split by newline and add to debug messages
                    String[] lines = nativeMessages.split("\n");
                    debugMessages.clear();
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            debugMessages.add(line.trim());
                        }
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                // Native debug method not implemented yet
                // Return empty list silently
            } catch (Exception e) {
                LOGGER.warn("Failed to get native debug messages", e);
            }
        }

        return new ArrayList<>(debugMessages);
    }

    @Override
    public boolean isDebuggingEnabled() {
        return debugEnabled;
    }

    @Override
    public String getVendor() {
        // TODO: Query from DirectX 11 adapter
        return "Microsoft DirectX 11";
    }

    @Override
    public String getBackendName() {
        return "DirectX 11 (Vitra JNI)";
    }

    @Override
    public String getVersion() {
        return "DirectX 11.0";
    }

    @Override
    public String getRenderer() {
        // TODO: Query from DirectX 11 adapter description (DXGI_ADAPTER_DESC)
        return "DirectX 11 Device";
    }

    @Override
    public int getMaxTextureSize() {
        // DirectX 11 feature level 11.0 guarantees at least 16384
        return 16384;
    }

    @Override
    public int getUniformOffsetAlignment() {
        // DirectX 11 constant buffer alignment requirement
        return 256;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline,
                                                      BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter) {
        LOGGER.debug("precompilePipeline({})", pipeline);

        // Create a compiled pipeline wrapper
        DirectX11CompiledRenderPipeline compiledPipeline = new DirectX11CompiledRenderPipeline(pipeline);

        // TODO: Actually compile shaders using the shaderSourceGetter
        // For now, return the wrapper which will be populated later

        return compiledPipeline;
    }

    @Override
    public void clearPipelineCache() {
        LOGGER.debug("clearPipelineCache()");
        // TODO: Implement pipeline cache clearing if we maintain a cache
    }

    @Override
    public List<String> getEnabledExtensions() {
        // DirectX 11 doesn't have "extensions" like OpenGL
        // We can return feature level information instead
        return List.of(
            "DirectX 11.0",
            "Feature Level 11.0",
            "Shader Model 5.0"
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  DIRECTX 11 GPUDEVICE CLOSING                              ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Window Handle: 0x{}", Long.toHexString(windowHandle));
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        // Cleanup is handled by VitraNativeRenderer.shutdown()
        // which is called from VitraMod shutdown hook

        closed = true;
    }

    /**
     * Check if the device has been closed
     */
    public boolean isClosed() {
        return closed;
    }
}
