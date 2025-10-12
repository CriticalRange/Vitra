package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.vitra.render.bgfx.BgfxTextureManager;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * CRITICAL MIXIN: Intercepts texture upload operations for fonts and UI
 *
 * In Minecraft 1.21.8, GlCommandEncoder.writeToTexture() is used to upload:
 * - Font glyph textures
 * - UI textures
 * - Skin textures
 * - Other dynamic textures
 *
 * We intercept this to redirect to BGFX texture update instead of OpenGL.
 *
 * Note: GlCommandEncoder is the OpenGL implementation of CommandEncoder interface
 */
@Mixin(GlCommandEncoder.class)
public class CommandEncoderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("CommandEncoderMixin");
    private static int textureUploadCount = 0;

    /**
     * Intercept writeToTexture(GpuTexture, NativeImage) for simple texture uploads.
     *
     * This is used by fonts to upload glyph bitmaps.
     */
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTexture(GpuTexture texture, NativeImage image, CallbackInfo ci) {
        textureUploadCount++;

        if (textureUploadCount <= 10) {
            LOGGER.info("[@Inject WORKING] writeToTexture(GpuTexture, NativeImage) intercepted - call #{}",
                textureUploadCount);
        }

        try {
            // Cancel the original OpenGL texture upload
            ci.cancel();

            if (texture == null || image == null) {
                LOGGER.warn("Null texture or image in writeToTexture, skipping");
                return;
            }

            // Get or create BGFX texture handle for this GpuTexture
            short bgfxTextureHandle = BgfxTextureManager.getOrCreateTexture(texture);

            if (bgfxTextureHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to get BGFX texture handle for GpuTexture");
                return;
            }

            // Upload the image data to BGFX texture
            BgfxTextureManager.uploadTextureData(bgfxTextureHandle, image, 0, 0, 0,
                image.getWidth(), image.getHeight());

            if (textureUploadCount <= 10) {
                LOGGER.info("BGFX texture uploaded successfully: handle={}, size={}x{}",
                    bgfxTextureHandle, image.getWidth(), image.getHeight());
            }

        } catch (Exception e) {
            LOGGER.error("Exception in writeToTexture interception", e);
        }
    }

    /**
     * Intercept writeToTexture with offset and dimensions for partial texture uploads.
     *
     * This is used for updating specific regions of textures.
     */
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIIII)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTextureRegion(GpuTexture texture, NativeImage image,
                                       int mipLevel, int depth,
                                       int offsetX, int offsetY,
                                       int width, int height,
                                       int skipPixels, int skipRows,
                                       CallbackInfo ci) {
        textureUploadCount++;

        if (textureUploadCount <= 10) {
            LOGGER.info("[@Inject WORKING] writeToTexture(region) intercepted - call #{}", textureUploadCount);
        }

        try {
            // Cancel the original OpenGL texture upload
            ci.cancel();

            if (texture == null || image == null) {
                LOGGER.warn("Null texture or image in writeToTextureRegion, skipping");
                return;
            }

            // Get or create BGFX texture handle for this GpuTexture
            short bgfxTextureHandle = BgfxTextureManager.getOrCreateTexture(texture);

            if (bgfxTextureHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to get BGFX texture handle for GpuTexture");
                return;
            }

            // Upload the image data to BGFX texture with offset
            BgfxTextureManager.uploadTextureData(bgfxTextureHandle, image, mipLevel, offsetX, offsetY,
                width, height);

            if (textureUploadCount <= 10) {
                LOGGER.info("BGFX texture region uploaded: handle={}, offset=({},{}), size={}x{}",
                    bgfxTextureHandle, offsetX, offsetY, width, height);
            }

        } catch (Exception e) {
            LOGGER.error("Exception in writeToTextureRegion interception", e);
        }
    }

    /**
     * Intercept writeToTexture with IntBuffer for raw pixel data uploads.
     *
     * This is used for some texture formats.
     */
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/IntBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTextureIntBuffer(GpuTexture texture, IntBuffer buffer,
                                          NativeImage.Format format,
                                          int mipLevel, int depth,
                                          int offsetX, int offsetY,
                                          int width, int height,
                                          CallbackInfo ci) {
        textureUploadCount++;

        if (textureUploadCount <= 10) {
            LOGGER.info("[@Inject WORKING] writeToTexture(IntBuffer) intercepted - call #{}", textureUploadCount);
        }

        try {
            // Cancel the original OpenGL texture upload
            ci.cancel();

            if (texture == null || buffer == null) {
                LOGGER.warn("Null texture or buffer in writeToTextureIntBuffer, skipping");
                return;
            }

            // Get or create BGFX texture handle for this GpuTexture
            short bgfxTextureHandle = BgfxTextureManager.getOrCreateTexture(texture);

            if (bgfxTextureHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to get BGFX texture handle for GpuTexture");
                return;
            }

            // Upload the buffer data to BGFX texture
            BgfxTextureManager.uploadTextureDataFromBuffer(bgfxTextureHandle, buffer, format,
                mipLevel, offsetX, offsetY, width, height);

            if (textureUploadCount <= 10) {
                LOGGER.info("BGFX texture uploaded from IntBuffer: handle={}, size={}x{}",
                    bgfxTextureHandle, width, height);
            }

        } catch (Exception e) {
            LOGGER.error("Exception in writeToTextureIntBuffer interception", e);
        }
    }
}
