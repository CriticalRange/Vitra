package com.vitra.mixin;

import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
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
 * - DirectX 11 texture resource tracking and management
 * - Mipmap generation and filtering control
 * - Texture upload/update operations with proper format conversion
 * - Integration with GLInterceptor for OpenGL compatibility
 * - Debug logging and error handling
 * - Memory management and cleanup
 */
@Mixin(AbstractTexture.class)
public abstract class MAbstractTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger(MAbstractTexture.class);

    // Shadow fields from AbstractTexture
    @Shadow
    protected int id = -1;

    @Shadow
    protected boolean blur;

    @Shadow
    protected boolean mipmap;

    // Vitra-specific fields for DirectX 11 texture management
    private long vitraDirectXHandle = 0;
    private boolean vitraInitialized = false;
    private boolean vitraUploadedToDirectX = false;
    private int vitraWidth = 0;
    private int vitraHeight = 0;
    private int vitraFormat = 0; // OpenGL format constant
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
     * Inject into texture loading to initialize DirectX 11 texture tracking
     */
    @Inject(method = "load", at = @At("HEAD"))
    private void vitraOnLoadStart(ResourceManager resourceManager, CallbackInfo ci) {
        LOGGER.debug("MAbstractTexture: Starting texture load for ID {}", getId());

        // Initialize DirectX 11 texture tracking
        if (!vitraInitialized && GLInterceptor.isActive()) {
            vitraInitialized = true;
            totalTexturesCreated++;

            // Register with GLInterceptor for proper tracking
            if (id != -1) {
                vitraDirectXHandle = VitraNativeRenderer.createTextureFromData(null, 1, 1, 0);
                GLInterceptor.registerMAbstractTexture(id, vitraDirectXHandle);
                LOGGER.debug("MAbstractTexture: Created DirectX 11 texture handle 0x{} for OpenGL texture ID {}",
                    Long.toHexString(vitraDirectXHandle), id);
            }
        }
    }

    /**
     * Inject into TextureManager.register() call to track texture location
     */
    @Inject(method = "reset", at = @At("HEAD"))
    private void vitraOnReset(TextureManager textureManager, ResourceManager resourceManager, ResourceLocation path, Executor executor, CallbackInfo ci) {
        vitraLocation = path;
        LOGGER.debug("MAbstractTexture: Registered texture {} at location {}", getId(), path);

        // Ensure DirectX 11 texture is created
        if (!vitraInitialized && GLInterceptor.isActive()) {
            vitraInitialized = true;
            totalTexturesCreated++;

            if (id != -1) {
                vitraDirectXHandle = VitraNativeRenderer.createTextureFromData(null, 1, 1, 0);
                GLInterceptor.registerMAbstractTexture(id, vitraDirectXHandle);
                LOGGER.debug("MAbstractTexture: Created DirectX 11 texture handle 0x{} for registered texture {}",
                    Long.toHexString(vitraDirectXHandle), path);
            }
        }
    }

    /**
     * Inject into setFilter to synchronize DirectX 11 texture filtering
     */
    @Inject(method = "setFilter", at = @At("RETURN"))
    private void vitraOnSetFilter(boolean blur, boolean mipmap, CallbackInfo ci) {
        if (!vitraInitialized || !GLInterceptor.isActive()) {
            return;
        }

        LOGGER.debug("MAbstractTexture: Setting filter for texture ID {} - blur={}, mipmap={}", getId(), blur, mipmap);

        // Forward filtering settings to DirectX 11
        if (vitraDirectXHandle != 0) {
            VitraNativeRenderer.setTextureFilter(vitraDirectXHandle, blur, mipmap);
        }
    }

    /**
     * Inject into bind() to ensure DirectX 11 texture is properly bound
     */
    @Inject(method = "bind", at = @At("RETURN"))
    private void vitraOnBind(CallbackInfo ci) {
        if (!vitraInitialized || !GLInterceptor.isActive()) {
            return;
        }

        LOGGER.debug("MAbstractTexture: Binding texture ID {} with DirectX handle 0x{}", getId(), Long.toHexString(vitraDirectXHandle));

        // Ensure DirectX 11 texture is bound
        if (vitraDirectXHandle != 0) {
            VitraNativeRenderer.bindTexture(vitraDirectXHandle, 0);
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

        LOGGER.debug("MAbstractTexture: Closing texture ID {} with DirectX handle 0x{}", getId(), Long.toHexString(vitraDirectXHandle));

        // Unregister from GLInterceptor
        if (id != -1) {
            GLInterceptor.unregisterMAbstractTexture(id);
        }

        // Cleanup DirectX 11 texture resource
        if (vitraDirectXHandle != 0) {
            VitraNativeRenderer.destroyResource(vitraDirectXHandle);
            vitraDirectXHandle = 0;
        }

        vitraInitialized = false;
        vitraUploadedToDirectX = false;
    }

    /**
     * Helper method to upload NativeImage to DirectX 11 texture
     */
    public void vitraUploadToDirectX(NativeImage image, boolean blur, boolean clamp, boolean mipmap) {
        if (!vitraInitialized || !GLInterceptor.isActive()) {
            return;
        }

        if (image == null) {
            LOGGER.warn("MAbstractTexture: Attempted to upload null image for texture ID {}", getId());
            return;
        }

        try {
            vitraWidth = image.getWidth();
            vitraHeight = image.getHeight();
            vitraFormat = image.format().glFormat();

            LOGGER.debug("MAbstractTexture: Uploading {}x{} image to DirectX 11 texture 0x{} (ID {})",
                vitraWidth, vitraHeight, Long.toHexString(vitraDirectXHandle), getId());

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
            if (vitraDirectXHandle != 0 && pixelData != null) {
                VitraNativeRenderer.updateTextureMipLevel(vitraDirectXHandle, pixelData, vitraWidth, vitraHeight, 0);

                // Apply filtering settings
                VitraNativeRenderer.setTextureFilter(vitraDirectXHandle, blur, mipmap);

                // Apply clamp settings if needed
                if (clamp) {
                    VitraNativeRenderer.setTextureWrap(vitraDirectXHandle, VitraNativeRenderer.TEXTURE_WRAP_CLAMP_TO_EDGE);
                } else {
                    VitraNativeRenderer.setTextureWrap(vitraDirectXHandle, VitraNativeRenderer.TEXTURE_WRAP_REPEAT);
                }

                vitraUploadedToDirectX = true;
                totalDirectXUploads++;

                LOGGER.debug("MAbstractTexture: Successfully uploaded texture {} to DirectX 11", vitraLocation);
            }

        } catch (Exception e) {
            LOGGER.error("MAbstractTexture: Failed to upload texture {} to DirectX 11", vitraLocation, e);
        }
    }

    /**
     * Helper method to update sub-region of DirectX 11 texture
     */
    public void vitraUpdateSubImage(NativeImage image, int xOffset, int yOffset, int width, int height) {
        if (!vitraInitialized || !vitraUploadedToDirectX || !GLInterceptor.isActive()) {
            return;
        }

        if (image == null) {
            LOGGER.warn("MAbstractTexture: Attempted to update with null image for texture ID {}", getId());
            return;
        }

        try {
            LOGGER.debug("MAbstractTexture: Updating {}x{} sub-region at ({},{}) for DirectX 11 texture 0x{}",
                width, height, xOffset, yOffset, Long.toHexString(vitraDirectXHandle));

            // Extract pixel data for the sub-region
            byte[] pixelData = new byte[width * height * 4]; // RGBA = 4 bytes per pixel

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int srcX = xOffset + x;
                    int srcY = yOffset + y;

                    if (srcX < image.getWidth() && srcY < image.getHeight()) {
                        int pixel = image.getPixelRGBA(srcX, srcY);
                        int offset = (y * width + x) * 4;

                        // Extract RGBA components
                        pixelData[offset] = (byte) ((pixel >> 0) & 0xFF);   // R
                        pixelData[offset + 1] = (byte) ((pixel >> 8) & 0xFF); // G
                        pixelData[offset + 2] = (byte) ((pixel >> 16) & 0xFF); // B
                        pixelData[offset + 3] = (byte) ((pixel >> 24) & 0xFF); // A
                    }
                }
            }

            // Update sub-region in DirectX 11
            if (vitraDirectXHandle != 0) {
                VitraNativeRenderer.updateTextureSubRegion(vitraDirectXHandle, pixelData, xOffset, yOffset, width, height, vitraFormat);
                LOGGER.debug("MAbstractTexture: Successfully updated sub-region for texture {}", vitraLocation);
            }

        } catch (Exception e) {
            LOGGER.error("MAbstractTexture: Failed to update sub-region for texture {}", vitraLocation, e);
        }
    }

    /**
     * Helper method to generate mipmaps for DirectX 11 texture
     */
    public void vitraGenerateMipmaps(int levels) {
        if (!vitraInitialized || !vitraUploadedToDirectX || !GLInterceptor.isActive()) {
            return;
        }

        if (vitraDirectXHandle != 0) {
            LOGGER.debug("MAbstractTexture: Generating {} mipmaps for texture {}", levels, vitraLocation);
            VitraNativeRenderer.generateTextureMipmaps(vitraDirectXHandle, levels);
        }
    }

    /**
     * Get DirectX 11 texture handle for external use
     */
    public long getVitraDirectXHandle() {
        return vitraDirectXHandle;
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
        return String.format("MAbstractTexture Stats: %d textures created, %d uploaded to DirectX 11",
            totalTexturesCreated, totalDirectXUploads);
    }

    /**
     * Static method to reset statistics
     */
    public static void resetVitraStats() {
        totalTexturesCreated = 0;
        totalDirectXUploads = 0;
        LOGGER.info("MAbstractTexture: Statistics reset");
    }
}