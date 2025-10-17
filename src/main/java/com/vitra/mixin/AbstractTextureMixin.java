package com.vitra.mixin;

import com.vitra.compat.GpuTexture;
import com.vitra.compat.GpuTextureView;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.mixin.VitraErrorHandler;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Mixin for AbstractTexture to provide comprehensive DirectX 11 texture management.
 * This mixin intercepts texture lifecycle operations and integrates with Vitra's DirectX 11 backend.
 *
 * Key features:
 * - DirectX 11 texture resource tracking and management using compatibility classes
 * - GpuTexture and GpuTextureView abstraction for Minecraft 1.21.1 compatibility
 * - Integration with GLInterceptor for OpenGL compatibility
 * - Debug logging and error handling
 * - Memory management and cleanup
 */
@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTextureMixin.class);

    // Shadow fields from AbstractTexture
    @Shadow
    protected int id = -1;

    @Shadow
    protected boolean blur;

    @Shadow
    protected boolean mipmap;

    // Vitra-specific fields for DirectX 11 texture management
    private GpuTexture dx11Texture = null;
    private GpuTextureView dx11TextureView = null;
    private boolean vitraInitialized = false;
    private boolean vitraUploadedToDirectX = false;
    private int vitraWidth = 0;
    private int vitraHeight = 0;
    private int vitraFormat = 0;
    private ResourceLocation vitraLocation = null;

    // Performance tracking
    private static long totalTexturesCreated = 0;
    private static long totalDirectXUploads = 0;

    /**
     * Shadow method for getId() to access texture ID
     */
    @Shadow
    public abstract int getId();

    /**
     * Shadow method for bind() to bind texture
     */
    @Shadow
    public abstract void bind();

    /**
     * Shadow method for setFilter() to control texture filtering
     */
    @Shadow
    public abstract void setFilter(boolean blur, boolean mipmap);

    /**
     * Shadow method for reset() to register with TextureManager
     */
    @Shadow
    public abstract void reset(TextureManager textureManager, ResourceManager resourceManager, ResourceLocation path, Executor executor);

    /**
     * Shadow method for close() to cleanup resources
     */
    @Shadow
    public abstract void close();

    /**
     * Abstract field for texture (compatibility)
     */
    protected GpuTexture texture;

    /**
     * Inject into texture loading to initialize DirectX 11 texture tracking
     */
    @Inject(method = "load", at = @At("HEAD"))
    private void vitraOnLoadStart(ResourceManager resourceManager, CallbackInfo ci) {
        LOGGER.debug("AbstractTextureMixin: Starting texture load for ID {}", getId());

        // Initialize DirectX 11 texture tracking
        if (!vitraInitialized && GLInterceptor.isAvailable()) {
            vitraInitialized = true;
            totalTexturesCreated++;

            // Create DirectX 11 GpuTexture
            long directXHandle = VitraNativeRenderer.createGpuTexture("AbstractTexture");
            if (directXHandle != 0) {
                this.dx11Texture = new GpuTexture(directXHandle, "AbstractTexture");
            }
            if (this.dx11Texture != null) {
                GLInterceptor.registerMAbstractTexture(id, this.dx11Texture.getDirectXHandle());
                LOGGER.debug("AbstractTextureMixin: Created DirectX 11 GpuTexture handle 0x{} for OpenGL texture ID {}",
                    Long.toHexString(this.dx11Texture.getDirectXHandle()), id);
            }
        }
    }

    /**
     * Inject into setFilter to synchronize DirectX 11 texture filtering
     */
    @Inject(method = "setFilter", at = @At("RETURN"))
    private void vitraOnSetFilter(boolean blur, boolean mipmap, CallbackInfo ci) {
        if (!vitraInitialized || !GLInterceptor.isAvailable()) {
            return;
        }

        LOGGER.debug("AbstractTextureMixin: Setting filter for texture ID {} - blur={}, mipmap={}", getId(), blur, mipmap);

        // Forward filtering settings to DirectX 11
        if (dx11Texture != null && dx11Texture.isValid()) {
            VitraNativeRenderer.setTextureFilter(dx11Texture.getDirectXHandle(), blur, mipmap);
        }
    }

    /**
     * Inject into bind() to ensure DirectX 11 texture is properly bound
     */
    @Inject(method = "bind", at = @At("RETURN"))
    private void vitraOnBind(CallbackInfo ci) {
        if (!vitraInitialized || !GLInterceptor.isAvailable()) {
            return;
        }

        LOGGER.debug("AbstractTextureMixin: Binding texture ID {} with DirectX handle 0x{}", getId(),
            dx11Texture != null ? Long.toHexString(dx11Texture.getDirectXHandle()) : "null");

        // Ensure DirectX 11 texture is bound
        if (dx11Texture != null && dx11Texture.isValid()) {
            VitraNativeRenderer.bindTexture(dx11Texture.getDirectXHandle(), 0);
        }
    }

    /**
     * Inject into close() to cleanup DirectX 11 resources
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void vitraOnClose(CallbackInfo ci) {
        if (!vitraInitialized) {
            return;
        }

        LOGGER.debug("AbstractTextureMixin: Closing texture ID {} with DirectX handle 0x{}", getId(),
            dx11Texture != null ? Long.toHexString(dx11Texture.getDirectXHandle()) : "null");

        // Unregister from GLInterceptor
        if (id != -1) {
            GLInterceptor.unregisterMAbstractTexture(id);
        }

        // Cleanup DirectX 11 texture resource
        if (dx11Texture != null && dx11Texture.isValid()) {
            VitraNativeRenderer.destroyResource(dx11Texture.getDirectXHandle());
            dx11Texture.invalidate();
        }

        // Cleanup texture view
        if (dx11TextureView != null && dx11TextureView.isValid()) {
            dx11TextureView = null;
        }

        vitraInitialized = false;
        vitraUploadedToDirectX = false;
    }

    /**
     * Upload method for compatibility
     */
    public void upload(NativeImage image) {
        if (!vitraInitialized || !GLInterceptor.isAvailable()) {
            return;
        }

        if (image == null) {
            LOGGER.warn("AbstractTextureMixin: Attempted to upload null image for texture ID {}", getId());
            VitraErrorHandler.handleTextureError("AbstractTexture", "upload", getId(),
                new IllegalArgumentException("Null image"));
            return;
        }

        try {
            vitraWidth = image.getWidth();
            vitraHeight = image.getHeight();
            vitraFormat = image.format().glFormat();

            LOGGER.debug("AbstractTextureMixin: Uploading {}x{} image to DirectX 11 texture 0x{} (ID {})",
                vitraWidth, vitraHeight,
                dx11Texture != null ? Long.toHexString(dx11Texture.getDirectXHandle()) : "null",
                getId());

            // Extract pixel data from NativeImage
            byte[] pixelData = new byte[vitraWidth * vitraHeight * 4]; // RGBA = 4 bytes per pixel

            // Copy pixel data from NativeImage to byte array
            for (int y = 0; y < vitraHeight; y++) {
                for (int x = 0; x < vitraWidth; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int offset = (y * vitraWidth + x) * 4;

                    // Extract RGBA components (NativeImage stores as ABGR)
                    pixelData[offset] = (byte) ((pixel >> 0) & 0xFF);   // R
                    pixelData[offset + 1] = (byte) ((pixel >> 8) & 0xFF); // G
                    pixelData[offset + 2] = (byte) ((pixel >> 16) & 0xFF); // B
                    pixelData[offset + 3] = (byte) ((pixel >> 24) & 0xFF); // A
                }
            }

            // Upload to DirectX 11
            if (dx11Texture != null && dx11Texture.isValid() && pixelData != null) {
                VitraNativeRenderer.updateTextureMipLevel(dx11Texture.getDirectXHandle(), pixelData, vitraWidth, vitraHeight, 0);

                // Apply filtering settings
                VitraNativeRenderer.setTextureFilter(dx11Texture.getDirectXHandle(), blur, mipmap);

                // Apply wrap settings
                VitraNativeRenderer.setTextureWrap(dx11Texture.getDirectXHandle(), VitraNativeRenderer.TEXTURE_WRAP_REPEAT);

                vitraUploadedToDirectX = true;
                totalDirectXUploads++;

                LOGGER.debug("AbstractTextureMixin: Successfully uploaded texture {} to DirectX 11", vitraLocation);
            }

        } catch (Exception e) {
            LOGGER.error("AbstractTextureMixin: Failed to upload texture {} to DirectX 11", vitraLocation, e);
            VitraErrorHandler.handleTextureError("AbstractTexture", "upload", getId(), e);
        }
    }

    /**
     * Get GpuTexture for external use
     */
    public GpuTexture getTexture() {
        return dx11Texture;
    }

    /**
     * Get GpuTextureView for external use
     */
    public GpuTextureView getTextureView() {
        if (dx11TextureView == null && dx11Texture != null && dx11Texture.isValid()) {
            dx11TextureView = new GpuTextureView(dx11Texture, 0, 0);
        }
        return dx11TextureView;
    }

    /**
     * Get DirectX 11 GpuTexture handle for external use
     */
    public GpuTexture getDx11Texture() {
        return dx11Texture;
    }

    /**
     * Get DirectX 11 GpuTextureView handle for external use
     */
    public GpuTextureView getDx11TextureView() {
        return getTextureView();
    }

    /**
     * Check if texture is uploaded to DirectX 11
     */
    public boolean isVitraUploadedToDirectX() {
        return vitraUploadedToDirectX;
    }

    /**
     * Get texture dimensions
     */
    public int getVitraWidth() {
        return vitraWidth;
    }

    public int getVitraHeight() {
        return vitraHeight;
    }

    /**
     * Get texture location for debugging
     */
    @Nullable
    public ResourceLocation getVitraLocation() {
        return vitraLocation;
    }

    /**
     * Static method to get performance statistics
     */
    public static String getVitraStats() {
        return String.format("AbstractTextureMixin Stats: %d textures created, %d uploaded to DirectX 11",
            totalTexturesCreated, totalDirectXUploads);
    }

    /**
     * Static method to reset statistics
     */
    public static void resetVitraStats() {
        totalTexturesCreated = 0;
        totalDirectXUploads = 0;
        LOGGER.info("AbstractTextureMixin: Statistics reset");
    }

    /**
     * Finalize method for cleanup
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (vitraInitialized) {
                LOGGER.debug("AbstractTextureMixin: Finalizing texture ID {}", getId());
                vitraOnClose(null);
            }
        } finally {
            super.finalize();
        }
    }
}