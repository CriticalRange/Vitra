package com.vitra.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX RenderTarget mixin
 *
 * Based on VulkanMod's RenderTargetMixin.
 * Uses deferred clear pattern and GL interception for framebuffer management.
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderTargetMixin");

    @Shadow private int viewWidth;
    @Shadow private int viewHeight;
    @Shadow private boolean useDepth;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow public int frameBufferId;
    @Shadow private float[] clearChannels; // RenderTarget's clear color array

    // VulkanMod pattern: deferred clear support
    @Unique private boolean needClear = false;
    @Unique private boolean bound = false;
    @Unique private static int clearCallCount = 0;

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX framebuffer management through GL30 interception
     */
    @Overwrite
    public void createBuffers(int width, int height, boolean useDepth) {
        this.width = width;
        this.height = height;
        this.viewWidth = width;
        this.viewHeight = height;
        this.useDepth = useDepth;

        // FBO creation is handled by GL30Mixin when glRenderbufferStorage is called
        // This matches VulkanMod's approach
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX cleanup - destroy FBO resources
     */
    @Overwrite
    public void destroyBuffers() {
        if (this.frameBufferId != 0) {
            VitraD3D11Renderer.destroyFramebuffer(this.frameBufferId);
            LOGGER.debug("[RenderTarget.destroyBuffers] Destroyed FBO {}", this.frameBufferId);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX framebuffer binding - create FBO on demand
     */
    @Overwrite
    public void bindWrite(boolean setViewport) {
        // CRITICAL: Create FBO on first use if it doesn't exist
        // Minecraft 1.21.1 doesn't call glGenFramebuffers/glRenderbufferStorage
        // for regular RenderTargets, so we create them on-demand here
        if (this.frameBufferId != 0 && this.width > 0 && this.height > 0) {
            boolean created = VitraD3D11Renderer.createFramebufferTextures(
                this.frameBufferId, this.width, this.height, true, this.useDepth);

            if (created) {
                LOGGER.debug("[RenderTarget.bindWrite] Created FBO {} on-demand ({}x{}, depth={})",
                    this.frameBufferId, this.width, this.height, this.useDepth);
            }
        }

        VitraD3D11Renderer.bindFramebuffer(36160, this.frameBufferId); // GL_FRAMEBUFFER

        if (setViewport) {
            VitraD3D11Renderer.setViewport(0, 0, this.viewWidth, this.viewHeight);
        }

        this.bound = true;

        if (needClear) {
            clear(false);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX texture binding - bind FBO color texture
     */
    @Overwrite
    public void bindRead() {
        if (this.frameBufferId != 0) {
            // Bind the FBO's color texture to texture unit 0
            VitraD3D11Renderer.bindFramebufferTexture(this.frameBufferId, 0);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX unbind
     */
    @Overwrite
    public void unbindRead() {
        // GL interception handles unbinding
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Texture filtering
     */
    @Overwrite
    public void setFilterMode(int filter) {
        // GL interception handles texture filtering
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Unbind framebuffer
     */
    @Overwrite
    public void unbindWrite() {
        VitraD3D11Renderer.bindFramebuffer(36160, 0);
        this.bound = false;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX clear with deferred clear support
     */
    @Overwrite
    public void clear(boolean getError) {
        // DEBUG: Log all clear() calls
        if (clearCallCount < 5) {
            LOGGER.info("[RenderTarget.clear {}] clearChannels=[{}, {}, {}, {}], bound={}",
                clearCallCount,
                this.clearChannels[0], this.clearChannels[1],
                this.clearChannels[2], this.clearChannels[3], bound);
            clearCallCount++;
        }

        // Defer clear if not bound
        if (!bound) {
            needClear = true;
            LOGGER.info("[RenderTarget.clear] Deferred clear (not bound), framebuffer={}", this.frameBufferId);
            return;
        }

        // CRITICAL FIX: Set clear color from RenderTarget's clearChannels BEFORE clearing
        // This is how VulkanMod works - each RenderTarget has its own clear color
        // Without this, we use stale clear color (Mojang logo red persists)

        VitraD3D11Renderer.setClearColor(
            this.clearChannels[0],
            this.clearChannels[1],
            this.clearChannels[2],
            this.clearChannels[3]
        );

        // Clear through DirectX
        int clearFlags = 16384; // GL_COLOR_BUFFER_BIT
        if (this.useDepth) {
            clearFlags |= 256; // GL_DEPTH_BUFFER_BIT
        }

        VitraD3D11Renderer.clear(clearFlags);
        needClear = false;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Resize framebuffer
     */
    @Overwrite
    public void resize(int width, int height, boolean clearError) {
        if (width == this.width && height == this.height) {
            return;
        }

        this.width = width;
        this.height = height;
        this.viewWidth = width;
        this.viewHeight = height;

        // GL interception handles resize
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Get color texture ID
     */
    @Overwrite
    public int getColorTextureId() {
        // Apply deferred clear before reading
        if (needClear) {
            bindWrite(false);
        }

        // Return framebuffer ID as texture ID (GL interception handles this)
        return this.frameBufferId;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Get depth texture ID
     */
    @Overwrite
    public int getDepthTextureId() {
        return this.frameBufferId;
    }
}
