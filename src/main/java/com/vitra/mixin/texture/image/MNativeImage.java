package com.vitra.mixin.texture.image;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.texture.VitraTextureFactory;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * CRITICAL DirectX 11 NativeImage Upload Mixin
 *
 * This is THE most important mixin for font and texture loading.
 * Based on VulkanMod's MNativeImage pattern.
 *
 * Key responsibilities:
 * - Cache ByteBuffer wrapper in constructor (zero-copy pattern)
 * - Intercept _upload() method for DirectX 11 texture uploads
 * - Handle unpackSkipRows/unpackSkipPixels parameters for font textures
 * - Prevent heap corruption from invalid memory access
 */
@Mixin(NativeImage.class)
public abstract class MNativeImage {

    @Shadow private long pixels;
    @Shadow private long size;
    @Shadow @Final private int width;
    @Shadow @Final private int height;
    @Shadow @Final private NativeImage.Format format;

    @Shadow public abstract void close();
    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();

    /**
     * CRITICAL: Cached ByteBuffer wrapper for zero-copy uploads
     * This prevents creating new buffers on every upload
     */
    private ByteBuffer buffer;

    /**
     * Constructor injection: Create ByteBuffer wrapper around native pixels
     * Pattern from VulkanMod - CRITICAL for proper memory management
     */
    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZ)V", at = @At("RETURN"))
    private void onConstructor1(NativeImage.Format format, int width, int height, boolean useStb, CallbackInfo ci) {
        if (this.pixels != 0) {
            // Create ByteBuffer wrapper - ZERO COPY!
            buffer = MemoryUtil.memByteBuffer(this.pixels, (int) this.size);
        }
    }

    /**
     * Constructor injection: Create ByteBuffer wrapper for externally-allocated pixels
     */
    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZJ)V", at = @At("RETURN"))
    private void onConstructor2(NativeImage.Format format, int width, int height, boolean useStb, long pixels, CallbackInfo ci) {
        if (this.pixels != 0) {
            // Create ByteBuffer wrapper - ZERO COPY!
            buffer = MemoryUtil.memByteBuffer(this.pixels, (int) this.size);
        }
    }

    /**
     * CRITICAL: Overwrite _upload() method to use renderer-agnostic texture system
     *
     * This is THE final interception point for ALL texture uploads including:
     * - Font textures (the ones causing crashes)
     * - Block/item textures
     * - GUI textures
     * - Mipmap textures
     *
     * @author Vitra (based on VulkanMod pattern)
     * @reason Replace OpenGL texture upload with renderer-agnostic texture system
     */
    @Overwrite
    private void _upload(int level, int xOffset, int yOffset, int unpackSkipPixels, int unpackSkipRows,
                        int widthIn, int heightIn, boolean blur, boolean clamp, boolean mipmap, boolean autoClose) {
        RenderSystem.assertOnRenderThreadOrInit();

        // CRITICAL: Set OpenGL unpack parameters BEFORE upload
        // These are used by texSubImage2D to calculate proper offsets
        VitraTextureFactory.pixelStorei(0x0CF3, unpackSkipRows);   // GL_UNPACK_SKIP_ROWS
        VitraTextureFactory.pixelStorei(0x0CF4, unpackSkipPixels); // GL_UNPACK_SKIP_PIXELS
        VitraTextureFactory.pixelStorei(0x0CF2, this.getWidth());  // GL_UNPACK_ROW_LENGTH = image width

        // Upload texture using renderer-agnostic factory
        // texImage2D will call texSubImage2D internally, which uses the unpack parameters
        VitraTextureFactory.texImage2D(
            0x0DE1,          // GL_TEXTURE_2D
            level,
            this.format.glFormat(),  // Internal format (RGBA, RGB, etc.)
            widthIn,
            heightIn,
            0,               // border (always 0)
            this.format.glFormat(),  // format
            0x1401,          // GL_UNSIGNED_BYTE
            this.buffer      // ByteBuffer with pixel data
        );

        // Reset unpack parameters to defaults after upload
        VitraTextureFactory.pixelStorei(0x0CF3, 0);  // GL_UNPACK_SKIP_ROWS = 0
        VitraTextureFactory.pixelStorei(0x0CF4, 0);  // GL_UNPACK_SKIP_PIXELS = 0
        VitraTextureFactory.pixelStorei(0x0CF2, 0);  // GL_UNPACK_ROW_LENGTH = 0

        // Handle texture parameters (blur/clamp/mipmap) if needed
        // TODO: Implement updateTextureSampler in texture implementations

        if (autoClose) {
            this.close();
        }
    }
}
