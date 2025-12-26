package com.vitra.render.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.vitra.mixin.texture.image.NativeImageAccessor;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of CommandEncoder.
 * Encodes GPU commands for deferred execution.
 * 
 * In D3D11, this maps to ID3D11DeviceContext (immediate or deferred).
 */
public class VitraCommandEncoder implements CommandEncoder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/CommandEncoder");
    
    // GL constants for texture formats
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_RGB = 0x1907;
    private static final int GL_LUMINANCE_ALPHA = 0x190A;
    private static final int GL_LUMINANCE = 0x1909;
    private static final int GL_RED = 0x1903;
    private static final int GL_RG = 0x8227;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    
    private final VitraGpuDevice device;
    
    public VitraCommandEncoder(VitraGpuDevice device) {
        this.device = device;
    }
    
    @Override
    public RenderPass createRenderPass(Supplier<String> nameSupplier, GpuTextureView colorTarget, OptionalInt clearColor) {
        return new VitraRenderPass(this, nameSupplier.get(), colorTarget, null, clearColor, OptionalDouble.empty());
    }
    
    @Override
    public RenderPass createRenderPass(Supplier<String> nameSupplier, GpuTextureView colorTarget, OptionalInt clearColor,
                                        GpuTextureView depthTarget, OptionalDouble clearDepth) {
        return new VitraRenderPass(this, nameSupplier.get(), colorTarget, depthTarget, clearColor, clearDepth);
    }
    
    @Override
    public void clearColorTexture(GpuTexture texture, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        VitraD3D11Renderer.setClearColor(r, g, b, a);
        VitraD3D11Renderer.clear(0x4000); // GL_COLOR_BUFFER_BIT
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int color,
                                           GpuTexture depthTexture, double depth) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        VitraD3D11Renderer.setClearColor(r, g, b, a);
        VitraD3D11Renderer.clear(0x4100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int color,
                                           GpuTexture depthTexture, double depth,
                                           int x, int y, int width, int height) {
        // TODO: Implement scissored clear
        clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
    }
    
    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        VitraD3D11Renderer.clear(0x100); // GL_DEPTH_BUFFER_BIT
    }
    
    @Override
    public void writeToBuffer(GpuBufferSlice bufferSlice, ByteBuffer data) {
        GpuBuffer buffer = bufferSlice.buffer();
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            // CRITICAL FIX: Use upload() which properly creates the native buffer if needed
            // Duplicate data to not modify the original buffer's position
            ByteBuffer dataCopy = data.duplicate();
            vitraBuffer.upload(dataCopy);
            LOGGER.debug("writeToBuffer: buffer='{}' size={} offset={} handle=0x{}",
                vitraBuffer.getName(), data.remaining(), bufferSlice.offset(),
                Long.toHexString(vitraBuffer.getNativeHandle()));
        }
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            // Create a mappable view that will upload data when closed
            return new VitraMappedView(vitraBuffer, buffer.size());
        }
        return new VitraMappedView(null, buffer.size());
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice bufferSlice, boolean read, boolean write) {
        GpuBuffer buffer = bufferSlice.buffer();
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            return new VitraMappedView(vitraBuffer, bufferSlice.length());
        }
        return new VitraMappedView(null, bufferSlice.length());
    }
    
    @Override
    public void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst) {
        LOGGER.debug("copyToBuffer not yet implemented");
    }
    
    // ==================== TEXTURE UPLOAD METHODS ====================
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image) {
        if (texture.isClosed()) {
            LOGGER.warn("Attempted to write to closed texture");
            return;
        }
        
        int texWidth = texture.getWidth(0);
        int texHeight = texture.getHeight(0);
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();
        
        if (imgWidth != texWidth || imgHeight != texHeight) {
            LOGGER.warn("Image size {}x{} doesn't match texture size {}x{}", 
                imgWidth, imgHeight, texWidth, texHeight);
            return;
        }
        
        // Full texture upload at mip level 0, layer 0
        writeToTexture(texture, image, 0, 0, 0, 0, imgWidth, imgHeight, 0, 0);
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image,
                               int mipLevel, int depthOrLayer, int destX, int destY,
                               int width, int height, int sourceX, int sourceY) {
        if (texture.isClosed()) {
            LOGGER.warn("Attempted to write to closed texture");
            return;
        }
        
        // Log entry to confirm this method is called
        LOGGER.info("[WRITE_TEX] writeToTexture called: texture='{}' {}x{} mip={}", 
            texture.getLabel(), image.getWidth(), image.getHeight(), mipLevel);
        
        try {
            // Get pixel pointer from NativeImage using accessor mixin
            long pixelPtr = ((NativeImageAccessor)(Object)image).getPixels();
            if (pixelPtr == 0) {
                LOGGER.warn("[WRITE_TEX] NativeImage pixel pointer is null!");
                return;
            }
            
            // Get texture handle if it's our texture type
            if (texture instanceof VitraGpuTexture vitraTexture) {
                long textureHandle = vitraTexture.getNativeHandle();
                
                // If texture doesn't have native handle yet, create it
                if (textureHandle == 0) {
                    int format = mapFormatToGL(image.format());
                    int dxgiFormat = mapFormatToDXGI(image.format());
                    
                    // Create the texture with initial data
                    int[] pixels = image.getPixels();
                    if (pixels != null && pixels.length > 0) {
                        LOGGER.info("[WRITE_TEX] Creating native D3D11 texture: {}x{}, {} pixels", 
                            image.getWidth(), image.getHeight(), pixels.length);
                        byte[] pixelBytes = new byte[pixels.length * 4];
                        for (int i = 0; i < pixels.length; i++) {
                            int pixel = pixels[i];
                            // ABGR to RGBA conversion
                            pixelBytes[i * 4] = (byte)((pixel >> 16) & 0xFF);     // R
                            pixelBytes[i * 4 + 1] = (byte)((pixel >> 8) & 0xFF);  // G
                            pixelBytes[i * 4 + 2] = (byte)(pixel & 0xFF);         // B
                            pixelBytes[i * 4 + 3] = (byte)((pixel >> 24) & 0xFF); // A
                        }
                        textureHandle = VitraD3D11Renderer.createTextureFromData(
                            pixelBytes, image.getWidth(), image.getHeight(), dxgiFormat);
                        vitraTexture.setNativeHandle(textureHandle);
                        LOGGER.info("[WRITE_TEX] ✓ Created texture handle=0x{} for '{}'", 
                            Long.toHexString(textureHandle), texture.getLabel());
                    } else {
                        LOGGER.warn("[WRITE_TEX] No pixel data available for texture!");
                    }
                    return;
                }
                
                // Update existing texture using texSubImage2D 
                int glFormat = mapFormatToGL(image.format());
                int imgWidth = image.getWidth();
                
                // Calculate the pixel pointer offset for sourceX, sourceY
                int bytesPerPixel = image.format().components();
                long offsetPixelPtr = pixelPtr + ((long)sourceY * imgWidth + sourceX) * bytesPerPixel;
                
                // Use texSubImage2DWithPitch for partial uploads with row pitch
                int rowPitch = imgWidth * bytesPerPixel;
                VitraD3D11Renderer.texSubImage2DWithPitch(
                    GL_TEXTURE_2D, mipLevel, destX, destY, 
                    width, height, glFormat, GL_UNSIGNED_BYTE, 
                    offsetPixelPtr, rowPitch);
                
                LOGGER.debug("Updated texture {} at ({},{}) size {}x{} mip {}", 
                    texture.getLabel(), destX, destY, width, height, mipLevel);
            } else {
                // Fallback for non-Vitra textures - use general texSubImage2D
                int glFormat = mapFormatToGL(image.format());
                VitraD3D11Renderer.texSubImage2D(
                    GL_TEXTURE_2D, mipLevel, destX, destY, 
                    width, height, glFormat, GL_UNSIGNED_BYTE, pixelPtr);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write to texture: {}", e.getMessage());
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, NativeImage.Format format,
                               int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
        if (texture.isClosed()) {
            LOGGER.warn("Attempted to write to closed texture");
            return;
        }
        
        try {
            if (!data.isDirect()) {
                LOGGER.warn("Non-direct ByteBuffer, copying to direct buffer");
                ByteBuffer direct = ByteBuffer.allocateDirect(data.remaining());
                direct.put(data.duplicate());
                direct.flip();
                data = direct;
            }
            
            // Get address of direct buffer
            long address = org.lwjgl.system.MemoryUtil.memAddress(data);
            
            int glFormat = mapFormatToGL(format);
            VitraD3D11Renderer.texSubImage2D(
                GL_TEXTURE_2D, mipLevel, destX, destY, 
                width, height, glFormat, GL_UNSIGNED_BYTE, address);
            
            LOGGER.debug("Uploaded ByteBuffer to texture {} at ({},{}) size {}x{}", 
                texture.getLabel(), destX, destY, width, height);
        } catch (Exception e) {
            LOGGER.error("Failed to write ByteBuffer to texture: {}", e.getMessage());
        }
    }
    
    // ==================== TEXTURE COPY METHODS ====================
    
    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset,
                                    Runnable callback, int mipLevel) {
        LOGGER.debug("copyTextureToBuffer not yet implemented");
        if (callback != null) callback.run();
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset,
                                    Runnable callback, int mipLevel,
                                    int x, int y, int width, int height) {
        copyTextureToBuffer(texture, buffer, offset, callback, mipLevel);
    }
    
    @Override
    public void copyTextureToTexture(GpuTexture src, GpuTexture dst,
                                      int srcX, int srcY, int dstX, int dstY,
                                      int width, int height, int mipLevel) {
        LOGGER.debug("copyTextureToTexture not yet implemented");
    }
    
    private static int presentCount = 0;
    
    @Override
    public void presentTexture(GpuTextureView textureView) {
        // presentTexture should blit the given texture to the swap chain back buffer
        // This is called when Minecraft renders to an off-screen RenderTarget
        // and needs to copy that to the actual back buffer for presentation.
        // The actual buffer swap happens in GpuDevice.presentFrame() (called by RenderSystem.flipFrame)
        
        if (presentCount < 10) {
            LOGGER.info("[PRESENT_TEXTURE {}] Blitting texture to back buffer: view={}", 
                presentCount, textureView != null ? textureView.getClass().getSimpleName() : "null");
            presentCount++;
        }
        
        // Get the texture handle if available
        if (textureView instanceof VitraGpuTextureView vitraView) {
            long textureHandle = vitraView.getNativeHandle();
            if (textureHandle != 0) {
                // Copy/blit the texture to the swap chain back buffer
                VitraD3D11Renderer.blitTextureToBackBuffer(textureHandle, 
                    vitraView.getWidth(0), vitraView.getHeight(0));
                
                if (presentCount <= 10) {
                    LOGGER.debug("[PRESENT_TEXTURE] Blitted texture 0x{} ({}x{}) to back buffer",
                        Long.toHexString(textureHandle), vitraView.getWidth(0), vitraView.getHeight(0));
                }
            } else {
                // Handle is 0 - might mean we're rendering directly to back buffer
                // In that case, no blit is needed
                if (presentCount < 5) {
                    LOGGER.debug("[PRESENT_TEXTURE] Texture handle is 0, assuming direct render to back buffer");
                }
            }
        }
        
        // NOTE: Do NOT call endFrame() here! That causes double-present.
        // GpuDevice.presentFrame() handles the actual SwapChain->Present() call.
    }
    
    // ==================== SYNC METHODS ====================
    
    @Override
    public GpuFence createFence() {
        return new VitraGpuFence();
    }
    
    @Override
    public GpuQuery timerQueryBegin() {
        return new VitraGpuQuery();
    }
    
    @Override
    public void timerQueryEnd(GpuQuery query) {
        // Timer queries not yet implemented
    }
    
    // ==================== FORMAT CONVERSION HELPERS ====================
    
    /**
     * Map NativeImage.Format to OpenGL format constant
     */
    private int mapFormatToGL(NativeImage.Format format) {
        return switch (format) {
            case RGBA -> GL_RGBA;
            case RGB -> GL_RGB;
            case LUMINANCE_ALPHA -> GL_LUMINANCE_ALPHA;
            case LUMINANCE -> GL_LUMINANCE;
        };
    }
    
    /**
     * Map NativeImage.Format to DXGI format constant
     */
    private int mapFormatToDXGI(NativeImage.Format format) {
        return switch (format) {
            case RGBA -> 28;  // DXGI_FORMAT_R8G8B8A8_UNORM
            case RGB -> 28;   // Use RGBA (D3D11 doesn't have RGB8)
            case LUMINANCE_ALPHA -> 49; // DXGI_FORMAT_R8G8_UNORM
            case LUMINANCE -> 61; // DXGI_FORMAT_R8_UNORM
        };
    }
}
