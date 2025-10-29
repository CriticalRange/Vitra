package com.vitra.mixin.texture.image;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * DirectX NativeImage Accessor Mixin
 *
 * Based on VulkanMod's NativeImageAccessor.
 * Provides access to NativeImage's native pixel buffer pointer.
 *
 * Key responsibilities:
 * - Expose native memory pointer (pixels field) for DirectX texture upload
 * - Allow direct access to pixel data without copying
 * - Enable zero-copy texture transfers to DirectX
 *
 * The pixels field is a native pointer (long) pointing to the image data
 * in native memory, which can be directly used by DirectX for texture creation.
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {

    /**
     * Get native pixel buffer pointer
     *
     * Returns the native memory address of the pixel data buffer.
     * This pointer can be passed directly to DirectX texture creation
     * functions for zero-copy texture uploads.
     *
     * @return Native memory pointer to pixel data
     */
    @Accessor
    long getPixels();
}
