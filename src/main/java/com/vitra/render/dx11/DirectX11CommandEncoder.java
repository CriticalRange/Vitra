package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of Minecraft's CommandEncoder
 *
 * Manages command recording for DirectX 11 rendering operations.
 * Commands include creating render passes, clearing textures, copying data, etc.
 */
public class DirectX11CommandEncoder implements CommandEncoder {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11CommandEncoder");

    public DirectX11CommandEncoder() {
        LOGGER.debug("Created DirectX11CommandEncoder");
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> nameSupplier, GpuTextureView colorTarget,
                                       OptionalInt clearColor) {
        String name = nameSupplier.get();
        LOGGER.debug("Create render pass: {} (color target={}, clear={})",
            name, colorTarget, clearColor.isPresent());

        if (!(colorTarget instanceof DirectX11GpuTextureView dx11ColorTarget)) {
            throw new IllegalArgumentException("Color target must be DirectX11GpuTextureView");
        }

        // If clear color is specified, clear the render target
        if (clearColor.isPresent()) {
            int color = clearColor.getAsInt();
            float r = ((color >> 24) & 0xFF) / 255.0f;
            float g = ((color >> 16) & 0xFF) / 255.0f;
            float b = ((color >> 8) & 0xFF) / 255.0f;
            float a = (color & 0xFF) / 255.0f;
            VitraNativeRenderer.clear(r, g, b, a);
        }

        return new DirectX11RenderPass(name, dx11ColorTarget, null);
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> nameSupplier, GpuTextureView colorTarget,
                                       OptionalInt clearColor, GpuTextureView depthTarget,
                                       OptionalDouble clearDepth) {
        String name = nameSupplier.get();
        LOGGER.debug("Create render pass: {} (color={}, depth={}, clearColor={}, clearDepth={})",
            name, colorTarget, depthTarget, clearColor.isPresent(), clearDepth.isPresent());

        if (!(colorTarget instanceof DirectX11GpuTextureView dx11ColorTarget)) {
            throw new IllegalArgumentException("Color target must be DirectX11GpuTextureView");
        }

        DirectX11GpuTextureView dx11DepthTarget = null;
        if (depthTarget != null) {
            if (!(depthTarget instanceof DirectX11GpuTextureView)) {
                throw new IllegalArgumentException("Depth target must be DirectX11GpuTextureView");
            }
            dx11DepthTarget = (DirectX11GpuTextureView) depthTarget;
        }

        // Clear color if specified
        if (clearColor.isPresent()) {
            int color = clearColor.getAsInt();
            float r = ((color >> 24) & 0xFF) / 255.0f;
            float g = ((color >> 16) & 0xFF) / 255.0f;
            float b = ((color >> 8) & 0xFF) / 255.0f;
            float a = (color & 0xFF) / 255.0f;
            VitraNativeRenderer.clear(r, g, b, a);
        }

        // TODO: Clear depth if specified (need native method for depth-only clear)

        return new DirectX11RenderPass(name, dx11ColorTarget, dx11DepthTarget);
    }

    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        LOGGER.debug("Clear color texture: {} (color=0x{})", texture, Integer.toHexString(color));

        float r = ((color >> 24) & 0xFF) / 255.0f;
        float g = ((color >> 16) & 0xFF) / 255.0f;
        float b = ((color >> 8) & 0xFF) / 255.0f;
        float a = (color & 0xFF) / 255.0f;
        VitraNativeRenderer.clear(r, g, b, a);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int color,
                                            GpuTexture depthTexture, double depth) {
        LOGGER.debug("Clear color and depth textures: color={}, depth={}", colorTexture, depthTexture);

        // Clear color
        clearColorTexture(colorTexture, color);
        // TODO: Clear depth (need native method)
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int color,
                                            GpuTexture depthTexture, double depth,
                                            int x, int y, int width, int height) {
        LOGGER.debug("Clear color and depth textures (rect): color={}, depth={}, rect=({},{},{}x{})",
            colorTexture, depthTexture, x, y, width, height);

        // TODO: Implement rect-based clearing (need native method with scissor)
        clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
    }

    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        LOGGER.debug("Clear depth texture: {} (depth={})", texture, depth);
        // TODO: Implement depth-only clear (need native method)
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, java.nio.ByteBuffer data) {
        LOGGER.debug("Write to buffer: slice={}, dataSize={}", slice, data.remaining());
        // TODO: Implement buffer data upload (need native method for updating buffers)
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        LOGGER.debug("Map buffer: {} (read={}, write={})", buffer, read, write);

        if (!(buffer instanceof DirectX11GpuBuffer dx11Buffer)) {
            LOGGER.error("Buffer is not DirectX11GpuBuffer: {}", buffer.getClass());
            return null;
        }

        // Get the native buffer handle
        long nativeHandle = dx11Buffer.getNativeHandle();
        if (nativeHandle == 0) {
            LOGGER.error("Buffer {} has no native handle", dx11Buffer.getDebugLabel());
            return null;
        }

        // Map the buffer using DirectX 11 Map
        // Access flags: read=1, write=2, read+write=3
        int accessFlags = (read ? 1 : 0) | (write ? 2 : 0);
        ByteBuffer mappedData = VitraNativeRenderer.mapBuffer(nativeHandle, buffer.size(), accessFlags);

        if (mappedData == null) {
            LOGGER.error("Failed to map buffer: {}", dx11Buffer.getDebugLabel());
            return null;
        }

        return new DirectX11MappedView(dx11Buffer, mappedData, read, write);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
        return mapBuffer(slice.buffer(), read, write);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice dest) {
        LOGGER.debug("Copy buffer to buffer: {} -> {}", source, dest);
        // TODO: Implement buffer-to-buffer copy (need native method)
    }

    @Override
    public void writeToTexture(GpuTexture target, NativeImage source) {
        LOGGER.debug("Write to texture: {} <- NativeImage({}x{})",
            target, source.getWidth(), source.getHeight());

        if (!(target instanceof DirectX11GpuTexture dx11Target)) {
            LOGGER.warn("Target texture is not DirectX11GpuTexture");
            return;
        }

        // Extract pixel data from NativeImage
        // getPixels() returns RGBA int array, we need to convert to byte array
        int[] pixels = source.getPixels();
        byte[] bytePixels = new byte[pixels.length * 4];

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            bytePixels[i * 4 + 0] = (byte) ((pixel >> 24) & 0xFF); // R
            bytePixels[i * 4 + 1] = (byte) ((pixel >> 16) & 0xFF); // G
            bytePixels[i * 4 + 2] = (byte) ((pixel >> 8) & 0xFF);  // B
            bytePixels[i * 4 + 3] = (byte) (pixel & 0xFF);         // A
        }

        // Use actual NativeImage dimensions, not GpuTexture dimensions
        dx11Target.createNativeTexture(bytePixels, source.getWidth(), source.getHeight());
    }

    @Override
    public void writeToTexture(GpuTexture target, NativeImage source, int mipLevel, int depth,
                                int offsetX, int offsetY, int width, int height,
                                int skipPixels, int skipRows) {
        LOGGER.debug("Write to texture (detailed): target={}, mip={}, depth={}, offset=({},{}), size={}x{}",
            target, mipLevel, depth, offsetX, offsetY, width, height);

        // Simplified implementation - just upload the whole image for now
        writeToTexture(target, source);
        // TODO: Implement partial texture upload with mip levels
    }

    @Override
    public void writeToTexture(GpuTexture target, IntBuffer source, NativeImage.Format format,
                                int mipLevel, int depth, int offsetX, int offsetY,
                                int width, int height) {
        LOGGER.debug("Write to texture (IntBuffer): target={}, format={}, mip={}, size={}x{}",
            target, format, mipLevel, width, height);

        // Convert IntBuffer to byte array
        int[] intData = new int[source.remaining()];
        source.get(intData);
        source.rewind();

        byte[] byteData = new byte[intData.length * 4];
        for (int i = 0; i < intData.length; i++) {
            int pixel = intData[i];
            byteData[i * 4 + 0] = (byte) ((pixel >> 24) & 0xFF); // R
            byteData[i * 4 + 1] = (byte) ((pixel >> 16) & 0xFF); // G
            byteData[i * 4 + 2] = (byte) ((pixel >> 8) & 0xFF);  // B
            byteData[i * 4 + 3] = (byte) (pixel & 0xFF);         // A
        }

        if (target instanceof DirectX11GpuTexture dx11Target) {
            // Use the provided width and height dimensions
            dx11Target.createNativeTexture(byteData, width, height);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, int offset,
                                     Runnable dataUploadedCallback, int mipLevel) {
        LOGGER.debug("Copy texture to buffer: {} -> {} (offset={}, mip={})",
            source, target, offset, mipLevel);
        // TODO: Implement texture-to-buffer copy
        if (dataUploadedCallback != null) {
            dataUploadedCallback.run();
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, int offset,
                                     Runnable dataUploadedCallback, int mipLevel,
                                     int intoX, int intoY, int width, int height) {
        LOGGER.debug("Copy texture to buffer (rect): {} -> {} (rect=({},{},{}x{}))",
            source, target, intoX, intoY, width, height);
        copyTextureToBuffer(source, target, offset, dataUploadedCallback, mipLevel);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mipLevel,
                                      int intoX, int intoY, int sourceX, int sourceY,
                                      int width, int height) {
        LOGGER.debug("Copy texture to texture: {} -> {} (mip={}, src=({},{}), dst=({},{}), size={}x{})",
            source, target, mipLevel, sourceX, sourceY, intoX, intoY, width, height);
        // TODO: Implement texture-to-texture copy
    }

    @Override
    public void presentTexture(GpuTextureView textureView) {
        LOGGER.debug("Present texture: {}", textureView);
        // Frame presentation is handled by VitraNativeRenderer.endFrame()
    }

    @Override
    public GpuFence createFence() {
        LOGGER.debug("Create fence");
        // TODO: Implement GPU fence/synchronization primitive
        return null;
    }
}
