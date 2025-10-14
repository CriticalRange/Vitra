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

        // Clear depth if specified
        if (clearDepth.isPresent()) {
            VitraNativeRenderer.clearDepth((float)clearDepth.getAsDouble());
        }

        return new DirectX11RenderPass(name, dx11ColorTarget, dx11DepthTarget);
    }

    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        LOGGER.debug("Clear color texture: {} (color=0x{})", texture, Integer.toHexString(color));

        // TODO: Implement proper texture-specific clearing
        // Currently this would incorrectly clear the swap chain back buffer
        // Need to:
        // 1. Get render target view for this specific texture
        // 2. Set it as active render target
        // 3. Clear that specific view
        // For now, skip to avoid crash
        LOGGER.warn("clearColorTexture not yet implemented - skipping clear of texture {}", texture);
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
        VitraNativeRenderer.clearDepth((float)depth);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, java.nio.ByteBuffer data) {
        GpuBuffer buffer = slice.buffer();
        if (!(buffer instanceof DirectX11GpuBuffer dx11Buffer)) {
            LOGGER.error("Buffer is not DirectX11GpuBuffer: {}", buffer.getClass());
            return;
        }

        int sliceOffset = slice.offset();
        int dataSize = data.remaining();
        int requiredSize = sliceOffset + dataSize;
        int currentSize = buffer.size();

        LOGGER.debug("Write to buffer: {} (offset={}, dataSize={}, currentSize={}, requiredSize={})",
            dx11Buffer.getDebugLabel(), sliceOffset, dataSize, currentSize, requiredSize);

        // CRITICAL: Check if buffer needs to grow
        if (requiredSize > currentSize) {
            LOGGER.warn("Buffer {} too small! Current={}, required={} - GROWING BUFFER",
                dx11Buffer.getDebugLabel(), currentSize, requiredSize);

            // Calculate new size with exponential growth (like Minecraft's AutoStorageIndexBuffer)
            // This prevents frequent reallocations
            int newSize = currentSize;
            while (newSize < requiredSize) {
                newSize = newSize * 2; // Double the size
            }

            LOGGER.info("Growing buffer {} from {} to {} bytes ({}x growth)",
                dx11Buffer.getDebugLabel(), currentSize, newSize, newSize / currentSize);

            // Grow the buffer (this will create a new native buffer and update size)
            boolean success = growBuffer(dx11Buffer, newSize);
            if (!success) {
                LOGGER.error("Failed to grow buffer {} - write will fail!", dx11Buffer.getDebugLabel());
                return;
            }
        }

        long nativeHandle = dx11Buffer.getNativeHandle();
        if (nativeHandle == 0) {
            LOGGER.error("Buffer {} has no native handle", dx11Buffer.getDebugLabel());
            return;
        }

        // Map the buffer, write data, unmap
        // Access flags: write-only (2)
        ByteBuffer mappedData = VitraNativeRenderer.mapBuffer(nativeHandle, buffer.size(), 2);

        if (mappedData == null) {
            LOGGER.error("Failed to map buffer: {}", dx11Buffer.getDebugLabel());
            return;
        }

        try {
            // Position the mapped buffer to the slice offset
            mappedData.position(sliceOffset);

            // Copy data from source to mapped buffer
            mappedData.put(data);

            LOGGER.debug("Wrote {} bytes to buffer {} at offset {}", dataSize, dx11Buffer.getDebugLabel(), sliceOffset);
        } finally {
            // Always unmap the buffer
            VitraNativeRenderer.unmapBuffer(nativeHandle);
        }
    }

    /**
     * Grow a DirectX11GpuBuffer to a new size by recreating the native buffer
     * DirectX 11 buffers cannot be resized in-place, so we must:
     * 1. Read existing data (if any)
     * 2. Create new buffer with larger size
     * 3. Copy old data to new buffer
     * 4. Update buffer size tracking
     */
    private boolean growBuffer(DirectX11GpuBuffer buffer, int newSize) {
        long oldHandle = buffer.getNativeHandle();

        // Store old data if buffer exists
        byte[] oldData = null;
        int oldSize = buffer.size();

        if (oldHandle != 0 && oldSize > 0) {
            LOGGER.debug("Reading {} bytes from old buffer before recreation", oldSize);

            // Map old buffer for reading
            ByteBuffer mappedOld = VitraNativeRenderer.mapBuffer(oldHandle, oldSize, 1); // read-only
            if (mappedOld != null) {
                oldData = new byte[oldSize];
                mappedOld.get(oldData);
                VitraNativeRenderer.unmapBuffer(oldHandle);
            } else {
                LOGGER.warn("Could not read old buffer data - growing without preserving data");
            }

            // Destroy old buffer
            VitraNativeRenderer.destroyResource(oldHandle);
        }

        // Create new buffer with larger size
        int vertexStride = buffer.getVertexStride();
        if (vertexStride <= 0) {
            vertexStride = 32; // Default stride
        }

        byte[] initialData = new byte[newSize];

        // Copy old data to new buffer if we saved it
        if (oldData != null) {
            System.arraycopy(oldData, 0, initialData, 0, Math.min(oldData.length, newSize));
            LOGGER.debug("Preserved {} bytes from old buffer", oldData.length);
        }

        // Create new native buffer
        long newHandle;
        if (buffer.isIndexBuffer()) {
            newHandle = VitraNativeRenderer.createIndexBuffer(initialData, newSize, VitraNativeRenderer.INDEX_FORMAT_32_BIT);
        } else {
            newHandle = VitraNativeRenderer.createVertexBuffer(initialData, newSize, vertexStride);
        }

        if (newHandle == 0) {
            LOGGER.error("Failed to create new buffer with size {}", newSize);
            return false;
        }

        // Update buffer's native handle using reflection
        try {
            java.lang.reflect.Field handleField = DirectX11GpuBuffer.class.getDeclaredField("nativeHandle");
            handleField.setAccessible(true);
            handleField.setLong(buffer, newHandle);

            // Update size using the existing updateSize method (via reflection since it's private)
            java.lang.reflect.Method updateSizeMethod = DirectX11GpuBuffer.class.getDeclaredMethod("updateSize", int.class);
            updateSizeMethod.setAccessible(true);
            updateSizeMethod.invoke(buffer, newSize);

            LOGGER.info("Successfully grew buffer {} to {} bytes (new handle=0x{})",
                buffer.getDebugLabel(), newSize, Long.toHexString(newHandle));
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to update buffer handle/size after recreation", e);
            // Clean up the new buffer we just created
            VitraNativeRenderer.destroyResource(newHandle);
            return false;
        }
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

        GpuBuffer srcBuffer = source.buffer();
        GpuBuffer dstBuffer = dest.buffer();

        if (!(srcBuffer instanceof DirectX11GpuBuffer srcDx11Buffer)) {
            LOGGER.error("Source buffer is not DirectX11GpuBuffer: {}", srcBuffer.getClass());
            return;
        }

        if (!(dstBuffer instanceof DirectX11GpuBuffer dstDx11Buffer)) {
            LOGGER.error("Destination buffer is not DirectX11GpuBuffer: {}", dstBuffer.getClass());
            return;
        }

        long srcHandle = srcDx11Buffer.getNativeHandle();
        long dstHandle = dstDx11Buffer.getNativeHandle();

        if (srcHandle == 0 || dstHandle == 0) {
            LOGGER.error("Invalid buffer handles: src=0x{}, dst=0x{}",
                Long.toHexString(srcHandle), Long.toHexString(dstHandle));
            return;
        }

        int srcOffset = source.offset();
        int dstOffset = dest.offset();
        int size = Math.min(source.length(), dest.length());

        VitraNativeRenderer.copyBuffer(srcHandle, dstHandle, srcOffset, dstOffset, size);
        LOGGER.debug("Copied {} bytes from buffer offset {} to buffer offset {}", size, srcOffset, dstOffset);
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

        if (!(target instanceof DirectX11GpuTexture dx11Target)) {
            LOGGER.warn("Target texture is not DirectX11GpuTexture");
            return;
        }

        // Extract pixel data from NativeImage
        int[] pixels = source.getPixels();
        byte[] bytePixels = new byte[pixels.length * 4];

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            bytePixels[i * 4 + 0] = (byte) ((pixel >> 24) & 0xFF); // R
            bytePixels[i * 4 + 1] = (byte) ((pixel >> 16) & 0xFF); // G
            bytePixels[i * 4 + 2] = (byte) ((pixel >> 8) & 0xFF);  // B
            bytePixels[i * 4 + 3] = (byte) (pixel & 0xFF);         // A
        }

        // ✅ CRITICAL: Use actual mipLevel parameter AND check dimensions!
        // Mip level 0 defines the texture size - if it changes, we must recreate
        if (mipLevel == 0) {
            // This is base mip level (mip 0) - it defines texture dimensions
            // Always use createNativeTexture for mip 0, which handles dimension changes
            dx11Target.createNativeTexture(bytePixels, source.getWidth(), source.getHeight());
        } else {
            // This is a higher mip level (mip 1, 2, 3, etc.) - dimensions must match
            // Update the mip level data
            if (dx11Target.getNativeHandle() == 0) {
                LOGGER.error("Cannot upload mip {} before base texture (mip 0) is created!", mipLevel);
                return;
            }
            dx11Target.updateNativeTexture(bytePixels, source.getWidth(), source.getHeight(), mipLevel);
        }
    }

    @Override
    public void writeToTexture(GpuTexture target, IntBuffer source, NativeImage.Format format,
                                int mipLevel, int depth, int offsetX, int offsetY,
                                int width, int height) {
        LOGGER.debug("Write to texture (IntBuffer): target={}, format={}, mip={}, size={}x{}",
            target, format, mipLevel, width, height);

        if (!(target instanceof DirectX11GpuTexture dx11Target)) {
            LOGGER.warn("Target texture is not DirectX11GpuTexture");
            return;
        }

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

        // ✅ CRITICAL: Use actual mipLevel parameter AND check dimensions!
        // Mip level 0 defines the texture size - if it changes, we must recreate
        if (mipLevel == 0) {
            // This is base mip level (mip 0) - it defines texture dimensions
            // Always use createNativeTexture for mip 0, which handles dimension changes
            dx11Target.createNativeTexture(byteData, width, height);
        } else {
            // This is a higher mip level (mip 1, 2, 3, etc.) - dimensions must match
            // Update the mip level data
            if (dx11Target.getNativeHandle() == 0) {
                LOGGER.error("Cannot upload mip {} before base texture (mip 0) is created!", mipLevel);
                return;
            }
            dx11Target.updateNativeTexture(byteData, width, height, mipLevel);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, int offset,
                                     Runnable dataUploadedCallback, int mipLevel) {
        LOGGER.debug("Copy texture to buffer: {} -> {} (offset={}, mip={})",
            source, target, offset, mipLevel);

        if (!(source instanceof DirectX11GpuTexture srcDx11Texture)) {
            LOGGER.error("Source texture is not DirectX11GpuTexture: {}", source.getClass());
            if (dataUploadedCallback != null) {
                dataUploadedCallback.run();
            }
            return;
        }

        if (!(target instanceof DirectX11GpuBuffer dstDx11Buffer)) {
            LOGGER.error("Target buffer is not DirectX11GpuBuffer: {}", target.getClass());
            if (dataUploadedCallback != null) {
                dataUploadedCallback.run();
            }
            return;
        }

        long srcHandle = srcDx11Texture.getNativeHandle();
        long dstHandle = dstDx11Buffer.getNativeHandle();

        if (srcHandle == 0 || dstHandle == 0) {
            LOGGER.error("Invalid handles: texture=0x{}, buffer=0x{}",
                Long.toHexString(srcHandle), Long.toHexString(dstHandle));
            if (dataUploadedCallback != null) {
                dataUploadedCallback.run();
            }
            return;
        }

        VitraNativeRenderer.copyTextureToBuffer(srcHandle, dstHandle, mipLevel);
        LOGGER.debug("Copied texture to buffer at mip level {}", mipLevel);

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

        if (!(source instanceof DirectX11GpuTexture srcDx11Texture)) {
            LOGGER.error("Source texture is not DirectX11GpuTexture: {}", source.getClass());
            return;
        }

        if (!(target instanceof DirectX11GpuTexture dstDx11Texture)) {
            LOGGER.error("Target texture is not DirectX11GpuTexture: {}", target.getClass());
            return;
        }

        long srcHandle = srcDx11Texture.getNativeHandle();
        long dstHandle = dstDx11Texture.getNativeHandle();

        if (srcHandle == 0 || dstHandle == 0) {
            LOGGER.error("Invalid texture handles: src=0x{}, dst=0x{}",
                Long.toHexString(srcHandle), Long.toHexString(dstHandle));
            return;
        }

        // Use copyTextureRegion for partial copies with offset control
        VitraNativeRenderer.copyTextureRegion(
            srcHandle, dstHandle,
            sourceX, sourceY, 0,  // Source position (srcZ=0 for 2D textures)
            intoX, intoY, 0,      // Destination position (dstZ=0 for 2D textures)
            width, height, 1,     // Size (depth=1 for 2D textures)
            mipLevel              // Mip level
        );

        LOGGER.debug("Copied {}x{} texture region at mip level {}", width, height, mipLevel);
    }

    @Override
    public void presentTexture(GpuTextureView textureView) {
        LOGGER.debug("Present texture: {}", textureView);

        // CRITICAL: This is where Minecraft 1.21.8 expects the frame to be presented!
        // The textureView contains the fully rendered frame that needs to be displayed
        // We must present the swap chain here, not in endFrame()

        if (VitraNativeRenderer.isInitialized()) {
            // Present the DirectX 11 swap chain to display the rendered frame
            VitraNativeRenderer.endFrame();
        } else {
            LOGGER.warn("Cannot present texture - DirectX 11 not initialized");
        }
    }

    @Override
    public GpuFence createFence() {
        LOGGER.debug("Create fence");
        return new DirectX11GpuFence();
    }
}
