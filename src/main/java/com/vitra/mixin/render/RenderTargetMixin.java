package com.vitra.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 RenderTarget mixin
 *
 * Based on VulkanMod's RenderTargetMixin but adapted for DirectX 11 backend.
 * Handles framebuffer operations including color/depth buffer management,
 * render target binding, and viewport operations.
 *
 * Key responsibilities:
 * - RenderTarget creation and management for DirectX 11
 * - Framebuffer binding operations (read/write)
 * - Clear operations with proper DirectX 11 state management
 * - Viewport management integration
 * - Texture attachment management
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderTargetMixin");

    @Shadow private int viewWidth;
    @Shadow private int viewHeight;
    @Shadow private boolean useDepth;
    @Shadow private int width;
    @Shadow private int height;

    // DirectX 11 render target handle storage
    @Unique private long directXRenderTargetHandle = 0;
    @Unique private long directXDepthStencilHandle = 0;
    @Unique private boolean isInitialized = false;

    /**
     * @author Vitra
     * @reason Create DirectX 11 render target instead of OpenGL framebuffer
     *
     * Replaces OpenGL framebuffer creation with DirectX 11 render target creation.
     * Creates color texture and depth stencil buffer attachments.
     */
    @Overwrite
    public void createBuffers(int width, int height, boolean useDepth) {
        LOGGER.debug("Creating DirectX 11 render target: {}x{}, depth={}", width, height, useDepth);

        this.width = width;
        this.height = height;
        this.viewWidth = width;
        this.viewHeight = height;
        this.useDepth = useDepth;

        try {
            // Release existing resources if any
            if (isInitialized) {
                releaseDirectXResources();
            }

            // Create main render target with color attachment
            directXRenderTargetHandle = VitraNativeRenderer.createMainRenderTarget(width, height, useDepth);

            if (directXRenderTargetHandle == 0) {
                LOGGER.error("Failed to create DirectX 11 render target for {}x{}", width, height);
                return;
            }

            // Create depth stencil buffer if needed
            if (useDepth) {
                directXDepthStencilHandle = VitraNativeRenderer.createDepthStencilBuffer(
                    width, height, VitraNativeRenderer.DEPTH_FORMAT_D24_UNORM_S8_UINT);

                if (directXDepthStencilHandle == 0) {
                    LOGGER.warn("Failed to create depth stencil buffer for render target");
                }
            }

            isInitialized = true;
            LOGGER.debug("Successfully created DirectX 11 render target: color=0x{}, depth=0x{}",
                Long.toHexString(directXRenderTargetHandle),
                directXDepthStencilHandle != 0 ? Long.toHexString(directXDepthStencilHandle) : "none");

        } catch (Exception e) {
            LOGGER.error("Exception creating DirectX 11 render target", e);
            releaseDirectXResources();
        }
    }

    /**
     * @author Vitra
     * @reason Destroy DirectX 11 render target resources
     *
     * Properly cleanup DirectX 11 resources when render target is no longer needed.
     */
    @Overwrite
    public void destroyBuffers() {
        LOGGER.debug("Destroying DirectX 11 render target resources");

        releaseDirectXResources();
        isInitialized = false;
    }

    /**
     * @author Vitra
     *reason Bind render target for writing operations
     *
     * Binds DirectX 11 render target for rendering output.
     * Updates viewport to match render target dimensions.
     */
    @Overwrite
    public void bindWrite(boolean setViewport) {
        if (!isInitialized || directXRenderTargetHandle == 0) {
            LOGGER.warn("Attempted to bind uninitialized render target for writing");
            return;
        }

        try {
            // Bind render target for writing
            VitraNativeRenderer.bindRenderTargetForWriting(directXRenderTargetHandle, setViewport);

            // Bind depth stencil buffer if available
            if (directXDepthStencilHandle != 0) {
                VitraNativeRenderer.bindDepthStencilBuffer(directXDepthStencilHandle);
            }

            // Set viewport if requested
            if (setViewport) {
                VitraNativeRenderer.setViewport(0, 0, viewWidth, viewHeight);
            }

            LOGGER.trace("Bound render target for writing: {}x{}", viewWidth, viewHeight);

        } catch (Exception e) {
            LOGGER.error("Exception binding render target for writing", e);
        }
    }

    /**
     * @author Vitra
     * @reason Bind render target for reading operations
     *
     * Binds render target's color texture for sampling operations.
     * Essential for post-processing effects and texture readback.
     */
    @Overwrite
    public void bindRead() {
        if (!isInitialized || directXRenderTargetHandle == 0) {
            LOGGER.warn("Attempted to bind uninitialized render target for reading");
            return;
        }

        try {
            // Bind render target as texture for reading
            VitraNativeRenderer.bindRenderTargetAsTexture(directXRenderTargetHandle);

            LOGGER.trace("Bound render target for reading");

        } catch (Exception e) {
            LOGGER.error("Exception binding render target for reading", e);
        }
    }

    /**
     * @author Vitra
     * @reason Unbind render target from write operations
     *
     * Unbinds render target and restores default framebuffer.
     */
    @Overwrite
    public void unbindWrite() {
        try {
            // Bind default render target (0)
            VitraNativeRenderer.bindRenderTargetForWriting(0, true);

            LOGGER.trace("Unbound render target from writing");

        } catch (Exception e) {
            LOGGER.error("Exception unbinding render target from writing", e);
        }
    }

    /**
     * @author Vitra
     * @reason Clear render target with DirectX 11 operations
     *
     * Performs color and depth buffer clearing using DirectX 11.
     * Integrates with Minecraft's clear color management.
     */
    @Overwrite
    public void clear(boolean clearDepth) {
        if (!isInitialized || directXRenderTargetHandle == 0) {
            LOGGER.warn("Attempted to clear uninitialized render target");
            return;
        }

        try {
            // Clear color buffer (uses current clear color from VitraNativeRenderer)
            // Clear depth buffer if requested and available
            VitraNativeRenderer.clearRenderTarget(true, clearDepth, false, 0.0f, 0.0f, 0.0f, 1.0f);

            LOGGER.trace("Cleared render target: color=true, depth={}", clearDepth);

        } catch (Exception e) {
            LOGGER.error("Exception clearing render target", e);
        }
    }

    /**
     * @author Vitra
     * @reason Resize render target to new dimensions
     *
     * Recreates DirectX 11 render target with new dimensions.
     * Essential for window resize operations.
     */
    @Overwrite
    public void resize(int width, int height) {
        if (width == this.width && height == this.height) {
            return; // No resize needed
        }

        LOGGER.debug("Resizing DirectX 11 render target: {}x{} -> {}x{}",
            this.width, this.height, width, height);

        // Store new dimensions
        this.width = width;
        this.height = height;
        this.viewWidth = width;
        this.viewHeight = height;

        if (isInitialized && directXRenderTargetHandle != 0) {
            try {
                // Resize existing render target
                long newHandle = VitraNativeRenderer.resizeRenderTarget(
                    directXRenderTargetHandle, width, height,
                    VitraNativeRenderer.DEPTH_FORMAT_D24_UNORM_S8_UINT);

                if (newHandle != 0) {
                    directXRenderTargetHandle = newHandle;

                    // Resize depth stencil buffer if present
                    if (useDepth && directXDepthStencilHandle != 0) {
                        directXDepthStencilHandle = VitraNativeRenderer.resizeDepthStencilBuffer(
                            directXDepthStencilHandle, width, height,
                            VitraNativeRenderer.DEPTH_FORMAT_D24_UNORM_S8_UINT);
                    }

                    LOGGER.debug("Successfully resized render target to {}x{}", width, height);
                } else {
                    LOGGER.error("Failed to resize render target, recreating");
                    // Fallback: recreate completely
                    createBuffers(width, height, useDepth);
                }

            } catch (Exception e) {
                LOGGER.error("Exception resizing render target", e);
                // Fallback: recreate completely
                createBuffers(width, height, useDepth);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Get color texture for shader sampling
     *
     * Returns the DirectX 11 color texture handle for shader sampling.
     * Essential for post-processing effects.
     */
    @Overwrite
    public int getColorTextureId() {
        if (!isInitialized || directXRenderTargetHandle == 0) {
            return 0;
        }

        try {
            // Get color texture handle from render target
            long colorTextureHandle = VitraNativeRenderer.getRenderTargetColorTexture(directXRenderTargetHandle);

            // Return as OpenGL texture ID for compatibility
            if (colorTextureHandle != 0) {
                // Register with GLInterceptor for compatibility
                int glTextureId = GLInterceptor.getOrCreateGlTextureId(colorTextureHandle);
                return glTextureId;
            }

        } catch (Exception e) {
            LOGGER.error("Exception getting color texture ID", e);
        }

        return 0;
    }

    /**
     * @author Vitra
     * @reason Get depth texture for shader sampling
     *
     * Returns the depth texture handle for depth-aware effects.
     */
    @Overwrite
    public int getDepthTextureId() {
        if (!isInitialized || directXDepthStencilHandle == 0) {
            return 0;
        }

        try {
            // Register depth buffer with GLInterceptor for compatibility
            int glTextureId = GLInterceptor.getOrCreateGlTextureId(directXDepthStencilHandle);
            return glTextureId;

        } catch (Exception e) {
            LOGGER.error("Exception getting depth texture ID", e);
        }

        return 0;
    }

    /**
     * @author Vitra
     * @reason Set viewport dimensions for this render target
     *
     * Updates viewport dimensions for proper coordinate transformation.
     */
    @Overwrite
    public void setViewport(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;

        // Update DirectX 11 viewport if this render target is bound
        try {
            VitraNativeRenderer.setViewport(0, 0, width, height);
        } catch (Exception e) {
            LOGGER.error("Exception setting viewport", e);
        }
    }

    /**
     * @author Vitra
     * @reason Check if render target is initialized
     *
     * Returns true if DirectX 11 resources are properly created.
     */
    @Unique
    public boolean isDirectXInitialized() {
        return isInitialized && directXRenderTargetHandle != 0;
    }

    /**
     * @author Vitra
     * @reason Get DirectX 11 render target handle
     *
     * Returns the underlying DirectX 11 render target handle.
     */
    @Unique
    public long getDirectXRenderTargetHandle() {
        return directXRenderTargetHandle;
    }

    /**
     * @author Vitra
     * @reason Get DirectX 11 depth stencil handle
     *
     * Returns the underlying DirectX 11 depth stencil buffer handle.
     */
    @Unique
    public long getDirectXDepthStencilHandle() {
        return directXDepthStencilHandle;
    }

    /**
     * Release DirectX 11 resources
     */
    @Unique
    private void releaseDirectXResources() {
        try {
            if (directXRenderTargetHandle != 0) {
                VitraNativeRenderer.releaseRenderTarget(directXRenderTargetHandle);
                directXRenderTargetHandle = 0;
            }

            if (directXDepthStencilHandle != 0) {
                VitraNativeRenderer.releaseDepthStencilBuffer(directXDepthStencilHandle);
                directXDepthStencilHandle = 0;
            }

        } catch (Exception e) {
            LOGGER.error("Exception releasing DirectX resources", e);
        }
    }

    /**
     * Validate render target state
     */
    @Unique
    public boolean validateRenderTargetState() {
        if (!isInitialized) {
            LOGGER.warn("Render target not initialized");
            return false;
        }

        if (directXRenderTargetHandle == 0) {
            LOGGER.warn("DirectX render target handle is null");
            return false;
        }

        if (useDepth && directXDepthStencilHandle == 0) {
            LOGGER.warn("Depth requested but depth stencil handle is null");
            return false;
        }

        return true;
    }

    /**
     * Get render target information for debugging
     */
    @Unique
    public String getRenderTargetInfo() {
        return String.format("RenderTarget[size=%dx%d, viewSize=%dx%d, depth=%s, " +
                           "directXHandle=0x%s, depthHandle=0x%s, initialized=%s]",
                           width, height, viewWidth, viewHeight, useDepth,
                           Long.toHexString(directXRenderTargetHandle),
                           directXDepthStencilHandle != 0 ? Long.toHexString(directXDepthStencilHandle) : "none",
                           isInitialized);
    }
}