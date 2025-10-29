package com.vitra.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Critical mixin to prevent MainTarget from creating OpenGL framebuffers
 * Pattern based on VulkanMod's MainTargetMixin - redirects to DirectX swap chain
 */
@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/MainTarget");

    public MainTargetMixin(boolean useDepth) {
        super(useDepth);
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Prevent OpenGL framebuffer creation, use DirectX swap chain instead
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        // Don't create OpenGL framebuffer - DirectX swap chain handles this
        this.frameBufferId = 0;

        this.viewWidth = width;
        this.viewHeight = height;  // FIX: Was incorrectly set to width
        this.width = width;
        this.height = height;

        LOGGER.info("MainTarget framebuffer creation intercepted - using DirectX swap chain ({}x{})", width, height);
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Bind DirectX main render target for writing
     */
    @Override
    public void bindWrite(boolean updateViewport) {
        try {
            // Bind DirectX back buffer as render target
            VitraD3D11Renderer.bindMainRenderTarget();

            if (updateViewport) {
                VitraD3D11Renderer.setViewport(0, 0, this.width, this.height);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to bind main render target", e);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Bind DirectX main render target for reading
     */
    @Override
    public void bindRead() {
        try {
            // Bind DirectX back buffer texture for reading
            VitraD3D11Renderer.bindMainRenderTargetTexture();
        } catch (Exception e) {
            LOGGER.error("Failed to bind main render target texture", e);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Return DirectX back buffer texture ID
     */
    @Override
    public int getColorTextureId() {
        try {
            return VitraD3D11Renderer.getMainColorTextureId();
        } catch (Exception e) {
            LOGGER.error("Failed to get main color texture ID", e);
            return 0;
        }
    }
}
