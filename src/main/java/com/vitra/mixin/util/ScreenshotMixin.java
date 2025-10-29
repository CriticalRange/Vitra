package com.vitra.mixin.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.vitra.render.D3D11Texture;
import com.vitra.render.VitraRenderer;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Adapted from VulkanMod's ScreenshotRecorderM
 * Ensures GPU commands are flushed before taking screenshot
 * Without this, screenshots may be black or corrupted
 */
@Mixin(Screenshot.class)
public class ScreenshotMixin {

    /**
     * @author Vitra
     * @reason Add GPU flush before screenshot (VulkanMod pattern) - prevents black screenshots
     */
    @Overwrite
    public static NativeImage takeScreenshot(RenderTarget target) {
        int width = target.width;
        int height = target.height;

        NativeImage nativeimage = new NativeImage(width, height, false);
        D3D11Texture.bindTexture(target.getColorTextureId());

        // CRITICAL: Flush GPU commands before reading texture data
        // Need to submit and wait for cmds if screenshot was requested
        // before the end of the frame
        // In DirectX 11, context->Flush() is called in vitra_d3d11.cpp
        com.vitra.render.jni.VitraD3D11Renderer.finish();

        nativeimage.downloadTexture(0, true);
        return nativeimage;
    }
}
