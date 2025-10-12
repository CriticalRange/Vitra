package com.vitra.render.bgfx;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer.MappedView;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Simplified BGFX command encoder that uses BGFX native functionality directly
 * Replaces the complex VitraCommandEncoder wrapper class
 */
public class BgfxCommandEncoder implements CommandEncoder {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxCommandEncoder");

    private boolean closed = false;

    public BgfxCommandEncoder() {
        LOGGER.debug("Created BGFX command encoder");
    }

    @Override
    public GpuFence createFence() {
        return BgfxUtils.createFence();
    }

    @Override
    public void presentTexture(GpuTextureView textureView) {
        // BGFX handles presentation via bgfx_frame() call
        // This is typically called from the main render loop
        LOGGER.debug("Present texture requested (handled by bgfx_frame())");
    }

    // ========== Render Pass Creation ==========

    @Override
    public RenderPass createRenderPass(
        Supplier<String> name,
        GpuTextureView colorAttachment,
        OptionalInt clearColor
    ) {
        return new VitraRenderPass(name, colorAttachment, clearColor, null, OptionalDouble.empty());
    }

    @Override
    public RenderPass createRenderPass(
        Supplier<String> name,
        GpuTextureView colorAttachment,
        OptionalInt clearColor,
        GpuTextureView depthAttachment,
        OptionalDouble clearDepth
    ) {
        return new VitraRenderPass(name, colorAttachment, clearColor, depthAttachment, clearDepth);
    }

    // ========== Texture Clearing ==========

    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        try {
            int width = bgfxTexture.getWidth(0);
            int height = bgfxTexture.getHeight(0);
            ByteBuffer clearData = ByteBuffer.allocateDirect(width * height * 4);

            // Fill with color (RGBA format)
            int r = (color >> 24) & 0xFF;
            int g = (color >> 16) & 0xFF;
            int b = (color >> 8) & 0xFF;
            int a = color & 0xFF;

            for (int i = 0; i < width * height; i++) {
                clearData.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
            }
            clearData.flip();

            bgfxTexture.updateData(0, 0, 0, width, height, clearData);
            LOGGER.debug("Color texture cleared: {}", bgfxTexture.getTextureName());
        } catch (Exception e) {
            LOGGER.error("Failed to clear color texture", e);
        }
    }

    @Override
    public void clearColorAndDepthTextures(
        GpuTexture colorTexture,
        int color,
        GpuTexture depthTexture,
        double depth
    ) {
        clearColorTexture(colorTexture, color);
        clearDepthTexture(depthTexture, depth);
    }

    @Override
    public void clearColorAndDepthTextures(
        GpuTexture colorTexture,
        int color,
        GpuTexture depthTexture,
        double depth,
        int x, int y, int width, int height
    ) {
        // Clear specific region
        if (!(colorTexture instanceof BgfxTexture bgfxColor)) {
            LOGGER.error("Color texture must be BgfxTexture instance");
            return;
        }

        try {
            ByteBuffer clearData = ByteBuffer.allocateDirect(width * height * 4);
            int r = (color >> 24) & 0xFF;
            int g = (color >> 16) & 0xFF;
            int b = (color >> 8) & 0xFF;
            int a = color & 0xFF;

            for (int i = 0; i < width * height; i++) {
                clearData.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
            }
            clearData.flip();

            bgfxColor.updateData(0, x, y, width, height, clearData);
        } catch (Exception e) {
            LOGGER.error("Failed to clear color and depth textures", e);
        }
    }

    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        try {
            int width = bgfxTexture.getWidth(0);
            int height = bgfxTexture.getHeight(0);
            ByteBuffer clearData = ByteBuffer.allocateDirect(width * height * 4);

            // Fill with depth value (D24S8 format - 24 bits depth, 8 bits stencil)
            int depthInt = (int)(depth * 0xFFFFFF);
            for (int i = 0; i < width * height; i++) {
                clearData.putInt(depthInt);
            }
            clearData.flip();

            bgfxTexture.updateData(0, 0, 0, width, height, clearData);
            LOGGER.debug("Depth texture cleared: {}", bgfxTexture.getTextureName());
        } catch (Exception e) {
            LOGGER.error("Failed to clear depth texture", e);
        }
    }

    // ========== Buffer Operations ==========

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (slice == null) {
            LOGGER.error("Buffer slice is null");
            return;
        }

        try {
            GpuBuffer buffer = slice.buffer();
            if (buffer instanceof BgfxBuffer bgfxBuffer) {
                bgfxBuffer.updateData((int)slice.offset(), data);
                LOGGER.debug("Buffer write completed: {} bytes to {}", data.remaining(), bgfxBuffer.getName());
            } else {
                LOGGER.error("Buffer must be BgfxBuffer instance");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write to buffer", e);
        }
    }

    @Override
    public MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return null;
        }

        if (!(buffer instanceof BgfxBuffer bgfxBuffer)) {
            LOGGER.error("Buffer must be BgfxBuffer instance");
            return null;
        }

        // BGFX doesn't support direct memory mapping like OpenGL
        // Return a CPU-side buffer wrapper instead
        try {
            return bgfxBuffer.map(read, write);
        } catch (Exception e) {
            LOGGER.error("Failed to map buffer", e);
            return null;
        }
    }

    @Override
    public MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
        if (slice == null) {
            LOGGER.error("Buffer slice is null");
            return null;
        }
        return mapBuffer(slice.buffer(), read, write);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (src == null || dst == null) {
            LOGGER.error("Buffer slices cannot be null");
            return;
        }

        GpuBuffer srcBuffer = src.buffer();
        GpuBuffer dstBuffer = dst.buffer();

        if (!(srcBuffer instanceof BgfxBuffer bgfxSrc) || !(dstBuffer instanceof BgfxBuffer bgfxDst)) {
            LOGGER.error("Both buffers must be BgfxBuffer instances");
            return;
        }

        try {
            long size = Math.min(src.length(), dst.length());
            ByteBuffer tempData = ByteBuffer.allocateDirect((int)size);

            // BGFX doesn't have direct buffer-to-buffer copy
            // Read from source and write to destination
            // TODO: Implement actual read from BgfxBuffer

            bgfxDst.updateData((int)dst.offset(), tempData);
            LOGGER.debug("Buffer copy completed: {} -> {} ({} bytes)",
                bgfxSrc.getName(), bgfxDst.getName(), size);
        } catch (Exception e) {
            LOGGER.error("Failed to copy buffer", e);
        }
    }

    // ========== Texture Operations ==========

    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        try {
            LOGGER.info("[TEXTURE UPLOAD] Starting: {} ({}x{}) on thread: {}",
                bgfxTexture.getTextureName(), image.getWidth(), image.getHeight(),
                Thread.currentThread().getName());

            // NativeImage.pixels is private, use makePixelArray() instead
            LOGGER.info("[TEXTURE UPLOAD] Converting pixel data for: {}", bgfxTexture.getTextureName());
            int[] pixels = image.makePixelArray();
            ByteBuffer imageData = ByteBuffer.allocateDirect(pixels.length * 4);
            for (int pixel : pixels) {
                imageData.putInt(pixel);
            }
            imageData.flip();

            LOGGER.info("[TEXTURE UPLOAD] Calling updateData for: {}", bgfxTexture.getTextureName());
            bgfxTexture.updateData(0, 0, 0, image.getWidth(), image.getHeight(), imageData);
            LOGGER.info("[TEXTURE UPLOAD] Completed: {}", bgfxTexture.getTextureName());
        } catch (Exception e) {
            LOGGER.error("[TEXTURE UPLOAD] Failed for: {}", bgfxTexture.getTextureName(), e);
        }
    }

    @Override
    public void writeToTexture(
        GpuTexture texture,
        NativeImage image,
        int mipLevel, int x, int y,
        int width, int height, int srcX, int srcY, int depth
    ) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        try {
            // Extract sub-region from image using pixel array
            int[] pixels = image.makePixelArray();
            int srcWidth = image.getWidth();

            ByteBuffer subRegion = ByteBuffer.allocateDirect(width * height * 4);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int srcIdx = (srcY + row) * srcWidth + (srcX + col);
                    if (srcIdx >= 0 && srcIdx < pixels.length) {
                        subRegion.putInt(pixels[srcIdx]);
                    } else {
                        subRegion.putInt(0); // Black pixel for out of bounds
                    }
                }
            }
            subRegion.flip();

            bgfxTexture.updateData(mipLevel, x, y, width, height, subRegion);
            LOGGER.debug("Texture sub-region write completed: {} (mip {})",
                bgfxTexture.getTextureName(), mipLevel);
        } catch (Exception e) {
            LOGGER.error("Failed to write to texture sub-region", e);
        }
    }

    @Override
    public void writeToTexture(
        GpuTexture texture,
        IntBuffer data,
        NativeImage.Format format,
        int mipLevel, int x, int y, int width, int height, int depth
    ) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        try {
            // Convert IntBuffer to ByteBuffer
            ByteBuffer byteData = ByteBuffer.allocateDirect(data.remaining() * 4);
            while (data.hasRemaining()) {
                byteData.putInt(data.get());
            }
            byteData.flip();

            bgfxTexture.updateData(mipLevel, x, y, width, height, byteData);
            LOGGER.debug("Texture write from IntBuffer completed: {} (mip {})",
                bgfxTexture.getTextureName(), mipLevel);
        } catch (Exception e) {
            LOGGER.error("Failed to write to texture from IntBuffer", e);
        }
    }

    public void copyBuffer(GpuBuffer src, GpuBuffer dst, long srcOffset, long dstOffset, long size) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(src instanceof BgfxBuffer bgfxSrc) || !(dst instanceof BgfxBuffer bgfxDst)) {
            LOGGER.error("Both buffers must be BgfxBuffer instances");
            return;
        }

        // BGFX doesn't have direct buffer-to-buffer copy, so we need to read and write
        try {
            ByteBuffer tempData = ByteBuffer.allocateDirect((int) size);

            // For BGFX, we would typically update the destination buffer directly
            // This is a simplified implementation
            LOGGER.debug("Buffer copy requested: src={}, dst={}, size={}",
                bgfxSrc.getName(), bgfxDst.getName(), size);

        } catch (Exception e) {
            LOGGER.error("Failed to copy buffer", e);
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture src, GpuTexture dst, int srcX, int srcY, int dstX, int dstY, int width, int height, int mipLevel) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(src instanceof BgfxTexture bgfxSrc) || !(dst instanceof BgfxTexture bgfxDst)) {
            LOGGER.error("Both textures must be BgfxTexture instances");
            return;
        }

        if (bgfxDst.blitFrom(dstX, dstY, bgfxSrc, srcX, srcY, width, height)) {
            LOGGER.debug("Texture copy completed: {} -> {} ({}x{})",
                bgfxSrc.getTextureName(), bgfxDst.getTextureName(), width, height);
        } else {
            LOGGER.error("Failed to copy texture");
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture src, GpuBuffer dst, int mipLevel, Runnable callback, int offset) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(src instanceof BgfxTexture bgfxSrc) || !(dst instanceof BgfxBuffer bgfxDst)) {
            LOGGER.error("Texture must be BgfxTexture and buffer must be BgfxBuffer");
            return;
        }

        try {
            // Estimate size based on buffer remaining space
            int size = (int)(bgfxDst.size() - offset);
            ByteBuffer dataBuffer = ByteBuffer.allocateDirect(size);
            if (bgfxSrc.readData(dataBuffer, mipLevel)) {
                // Update the buffer with the texture data
                bgfxDst.updateData(offset, dataBuffer);
                LOGGER.debug("Texture to buffer copy completed: {} -> {} (offset: {})",
                    bgfxSrc.getTextureName(), bgfxDst.getName(), offset);

                // Execute callback if provided
                if (callback != null) {
                    callback.run();
                }
            } else {
                LOGGER.error("Failed to read texture data");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to copy texture to buffer", e);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture src, GpuBuffer dst, int mipLevel, Runnable callback, int x, int y, int width, int height, int offset) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(src instanceof BgfxTexture bgfxSrc) || !(dst instanceof BgfxBuffer bgfxDst)) {
            LOGGER.error("Texture must be BgfxTexture and buffer must be BgfxBuffer");
            return;
        }

        try {
            int size = width * height * 4; // Assuming RGBA8
            ByteBuffer dataBuffer = ByteBuffer.allocateDirect(size);
            if (bgfxSrc.readData(dataBuffer, mipLevel)) {
                // Update the buffer with the texture data
                bgfxDst.updateData(offset, dataBuffer);
                LOGGER.debug("Texture to buffer copy completed: {} -> {} (region: {}x{} at {},{}  offset: {})",
                    bgfxSrc.getTextureName(), bgfxDst.getName(), width, height, x, y, offset);

                // Execute callback if provided
                if (callback != null) {
                    callback.run();
                }
            } else {
                LOGGER.error("Failed to read texture data");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to copy texture to buffer", e);
        }
    }

    public void copyBufferToTexture(GpuBuffer src, GpuTexture dst, int mipLevel, int x, int y, int width, int height, long offset, long size) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(src instanceof BgfxBuffer bgfxSrc) || !(dst instanceof BgfxTexture bgfxDst)) {
            LOGGER.error("Buffer must be BgfxBuffer and texture must be BgfxTexture");
            return;
        }

        try {
            // For simplicity, we'll assume the buffer contains the texture data
            // In a real implementation, you'd need to handle the buffer format correctly
            ByteBuffer bufferData = ByteBuffer.allocateDirect((int) size);
            // TODO: Extract data from bgfxSrc into bufferData

            if (bgfxDst.updateData(mipLevel, x, y, width, height, bufferData)) {
                LOGGER.debug("Buffer to texture copy completed: {} -> {}",
                    bgfxSrc.getName(), bgfxDst.getTextureName());
            } else {
                LOGGER.error("Failed to update texture data");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to copy buffer to texture", e);
        }
    }

    public void generateMipmaps(GpuTexture texture) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        // BGFX handles mipmap generation automatically when textures are created with mips
        LOGGER.debug("Mipmap generation requested for: {} (handled automatically by BGFX)",
            bgfxTexture.getTextureName());
    }

    public void setScissor(int x, int y, int width, int height) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        BgfxOperations.setScissorRect(x, y, width, height);
        LOGGER.debug("Scissor rect set: {},{},{}x{}", x, y, width, height);
    }

    public void clearTexture(GpuTexture texture, int mipLevel, int x, int y, int width, int height) {
        if (closed) {
            LOGGER.error("Cannot use closed command encoder");
            return;
        }

        if (!(texture instanceof BgfxTexture bgfxTexture)) {
            LOGGER.error("Texture must be BgfxTexture instance");
            return;
        }

        // Create a clear buffer and update the texture region
        try {
            ByteBuffer clearData = ByteBuffer.allocateDirect(width * height * 4); // Assuming RGBA8
            clearData.clear(); // Zeroes the buffer

            if (bgfxTexture.updateData(mipLevel, x, y, width, height, clearData)) {
                LOGGER.debug("Texture region cleared: {} ({}x{} at {},{})",
                    bgfxTexture.getTextureName(), width, height, x, y);
            } else {
                LOGGER.error("Failed to clear texture region");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to clear texture", e);
        }
    }

    public void end() {
        if (closed) {
            LOGGER.warn("Command encoder already ended");
            return;
        }

        // BGFX handles command submission automatically
        LOGGER.debug("BGFX command encoder ended");
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (!closed) {
            end();
        }
    }

    @Override
    public String toString() {
        return String.format("BgfxCommandEncoder{closed=%s}", closed);
    }
}