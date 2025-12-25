package com.vitra.mixin.texture.mip;

import com.mojang.blaze3d.platform.NativeImage;
import com.vitra.mixin.texture.image.NativeImageAccessor;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX Mipmap Generator Mixin for Minecraft 26.1
 *
 * Based on VulkanMod's MipmapGeneratorM.
 * Improves mipmap generation by fixing artifacts on textures with transparent backgrounds.
 *
 * 26.1 API Changes:
 * - generateMipLevels now takes (Identifier, NativeImage[], int, MipmapStrategy, float)
 * - getPow22() removed - we implement our own gamma blending
 * - New MipmapStrategy enum (AUTO, MEAN, CUTOUT, STRICT_CUTOUT, DARK_CUTOUT)
 */
@Mixin(MipmapGenerator.class)
public abstract class MipmapGeneratorMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/MipmapGenerator");
    private static final int ALPHA_CUTOFF = 50;
    
    // Gamma 2.2 lookup table for accurate color blending
    private static final float[] POW22 = new float[256];
    
    static {
        for (int i = 0; i < 256; i++) {
            POW22[i] = (float) Math.pow(i / 255.0, 2.2);
        }
    }
    
    private static float getPow22(int value) {
        return POW22[value & 0xFF];
    }

    @Shadow
    private static boolean hasTransparentPixel(NativeImage image) {
        return false;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Add an average background color to textures with transparent backgrounds
     * to fix mipmap artifacts. Uses gamma-correct blending for accurate color representation.
     *
     * 26.1 Signature: (Identifier, NativeImage[], int, MipmapStrategy, float)
     */
    @Overwrite
    public static NativeImage[] generateMipLevels(Identifier identifier, NativeImage[] nativeImages, 
                                                   int mipLevels, MipmapStrategy strategy, float alphaCutoff) {
        if (mipLevels + 1 <= nativeImages.length) {
            return nativeImages;
        }
        
        NativeImage[] result = new NativeImage[mipLevels + 1];
        result[0] = nativeImages[0];

        long srcPtr = ((NativeImageAccessor)(Object)result[0]).getPixels();
        boolean hasTransparent = hasTransparentPixelFast(srcPtr, result[0].getWidth(), result[0].getHeight());

        // Pre-process: fill transparent pixels with average opaque color
        if (hasTransparent) {
            int avg = calculateAverage(result[0]);
            avg = avg & 0x00FFFFFF; // Mask out alpha

            NativeImage nativeImage = result[0];
            int width = nativeImage.getWidth();
            int height = nativeImage.getHeight();

            for (int m = 0; m < width; ++m) {
                for (int n = 0; n < height; ++n) {
                    int p0 = MemoryUtil.memGetInt(srcPtr + (m + ((long) n * width)) * 4L);
                    boolean opaque = ((p0 >> 24) & 0xFF) >= ALPHA_CUTOFF;
                    
                    // Keep original alpha, but set RGB to average for transparent pixels
                    p0 = opaque ? p0 : (avg | (p0 & 0xFF000000));
                    MemoryUtil.memPutInt(srcPtr + (m + (long) n * width) * 4L, p0);
                }
            }
        }

        // Generate mipmap chain
        for (int j = 1; j <= mipLevels; ++j) {
            if (j < nativeImages.length) {
                result[j] = nativeImages[j];
            } else {
                NativeImage srcImage = result[j - 1];
                int newWidth = Math.max(1, srcImage.getWidth() >> 1);
                int newHeight = Math.max(1, srcImage.getHeight() >> 1);
                NativeImage dstImage = new NativeImage(newWidth, newHeight, false);
                
                srcPtr = ((NativeImageAccessor)(Object)srcImage).getPixels();
                long dstPtr = ((NativeImageAccessor)(Object)dstImage).getPixels();
                final int srcWidth = srcImage.getWidth();

                for (int m = 0; m < newWidth; ++m) {
                    for (int n = 0; n < newHeight; ++n) {
                        // Sample 4 source pixels
                        int p0 = MemoryUtil.memGetInt(srcPtr + ((m * 2) + ((n * 2) * srcWidth)) * 4L);
                        int p1 = MemoryUtil.memGetInt(srcPtr + ((m * 2 + 1) + ((n * 2) * srcWidth)) * 4L);
                        int p2 = MemoryUtil.memGetInt(srcPtr + ((m * 2) + ((n * 2 + 1) * srcWidth)) * 4L);
                        int p3 = MemoryUtil.memGetInt(srcPtr + ((m * 2 + 1) + ((n * 2 + 1) * srcWidth)) * 4L);

                        // Gamma-correct blend
                        int outColor = blend(p0, p1, p2, p3);
                        MemoryUtil.memPutInt(dstPtr + (m + (long) n * newWidth) * 4L, outColor);
                    }
                }

                result[j] = dstImage;
            }
        }

        return result;
    }

    private static boolean hasTransparentPixelFast(long ptr, int width, int height) {
        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                if (((MemoryUtil.memGetInt(ptr + (i + j * width) * 4L) >> 24) & 0xFF) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int blend(int p0, int p1, int p2, int p3) {
        int a = gammaBlend(p0, p1, p2, p3, 24);
        int b = gammaBlend(p0, p1, p2, p3, 16);
        int g = gammaBlend(p0, p1, p2, p3, 8);
        int r = gammaBlend(p0, p1, p2, p3, 0);
        return a << 24 | b << 16 | g << 8 | r;
    }

    private static int gammaBlend(int i, int j, int k, int l, int shift) {
        float f = getPow22(i >> shift);
        float g = getPow22(j >> shift);
        float h = getPow22(k >> shift);
        float n = getPow22(l >> shift);
        float avg = (f + g + h + n) * 0.25f;
        // Convert back to linear
        float o = (float) Math.pow(avg, 0.45454545454545453);
        return Math.min(255, (int) (o * 255.0f));
    }

    private static int calculateAverage(NativeImage nativeImage) {
        final int width = nativeImage.getWidth();
        final int height = nativeImage.getHeight();
        long srcPtr = ((NativeImageAccessor)(Object)nativeImage).getPixels();

        int sumR = 0, sumG = 0, sumB = 0, count = 0;

        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                int value = MemoryUtil.memGetInt(srcPtr + (i + (long) j * width) * 4L);
                if (((value >> 24) & 0xFF) > 0) {
                    sumR += value & 0xFF;
                    sumG += (value >> 8) & 0xFF;
                    sumB += (value >> 16) & 0xFF;
                    count++;
                }
            }
        }

        if (count == 0) return 0;

        sumR /= count;
        sumG /= count;
        sumB /= count;

        return (sumR & 0xFF) | ((sumG & 0xFF) << 8) | ((sumB & 0xFF) << 16) | (0xFF << 24);
    }
}
