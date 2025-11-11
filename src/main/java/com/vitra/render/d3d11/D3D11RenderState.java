package com.vitra.render.d3d11;

import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D3D11 Render State Tracker - Prevents Redundant State Changes
 *
 * Based on VulkanMod's RenderState pattern.
 * Tracks current D3D11 pipeline state and only applies changes when state differs.
 *
 * Why needed:
 * - D3D11 state changes (OMSetBlendState, OMSetDepthStencilState, etc.) are expensive
 * - Minecraft changes state 1000s of times per frame unnecessarily
 * - VulkanMod caches state and filters redundant calls
 *
 * State categories:
 * - Blend State: Source/dest factors, blend op, blend enable
 * - Depth State: Depth test, depth write, depth func
 * - Rasterizer State: Cull mode, fill mode, scissor enable
 * - Dynamic State: Viewport, scissor rect, depth bias
 *
 * Architecture:
 * - All state setters check if state changed before calling native
 * - State object handles cached by hash
 * - Reset on frame start to handle external state changes
 */
public class D3D11RenderState {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11RenderState.class);

    // ==================== BLEND STATE ====================

    private static boolean blendEnabled = false;
    private static int srcRgbFactor = 1;    // GL_ONE
    private static int dstRgbFactor = 0;    // GL_ZERO
    private static int srcAlphaFactor = 1;  // GL_ONE
    private static int dstAlphaFactor = 0;  // GL_ZERO
    private static int blendEquationRgb = 0x8006;   // GL_FUNC_ADD
    private static int blendEquationAlpha = 0x8006; // GL_FUNC_ADD

    private static boolean blendStateDirty = true;

    /**
     * Enable blending (VulkanMod pattern: check if already enabled)
     */
    public static void enableBlend() {
        if (blendEnabled) return; // Already enabled
        blendEnabled = true;
        blendStateDirty = true;
        LOGGER.trace("Blend enabled (state changed)");
    }

    /**
     * Disable blending
     */
    public static void disableBlend() {
        if (!blendEnabled) return; // Already disabled
        blendEnabled = false;
        blendStateDirty = true;
        LOGGER.trace("Blend disabled (state changed)");
    }

    /**
     * Set blend function (src and dst factors for RGB and Alpha)
     */
    public static void blendFunc(int srcFactor, int dstFactor) {
        blendFuncSeparate(srcFactor, dstFactor, srcFactor, dstFactor);
    }

    /**
     * Set blend function with separate RGB and Alpha factors
     */
    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (srcRgbFactor == srcRgb && dstRgbFactor == dstRgb &&
            srcAlphaFactor == srcAlpha && dstAlphaFactor == dstAlpha) {
            return; // No change
        }

        srcRgbFactor = srcRgb;
        dstRgbFactor = dstRgb;
        srcAlphaFactor = srcAlpha;
        dstAlphaFactor = dstAlpha;
        blendStateDirty = true;

        LOGGER.trace("Blend func changed: srcRgb={}, dstRgb={}, srcAlpha={}, dstAlpha={}",
            srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    /**
     * Set blend equation (how src and dst are combined)
     */
    public static void blendEquation(int mode) {
        blendEquationSeparate(mode, mode);
    }

    /**
     * Set blend equation with separate RGB and Alpha modes
     */
    public static void blendEquationSeparate(int modeRgb, int modeAlpha) {
        if (blendEquationRgb == modeRgb && blendEquationAlpha == modeAlpha) {
            return; // No change
        }

        blendEquationRgb = modeRgb;
        blendEquationAlpha = modeAlpha;
        blendStateDirty = true;

        LOGGER.trace("Blend equation changed: rgb={}, alpha={}", modeRgb, modeAlpha);
    }

    /**
     * Apply blend state to D3D11 if dirty
     */
    public static void applyBlendState() {
        if (!blendStateDirty) return;

        if (blendEnabled) {
            VitraD3D11Renderer.enableBlend();
            VitraD3D11Renderer.blendFuncSeparate(srcRgbFactor, dstRgbFactor, srcAlphaFactor, dstAlphaFactor);
            // Note: blendEquationSeparate not available, use blendEquation for RGB only
            // This is acceptable since most Minecraft rendering uses same equation for RGB and Alpha
            VitraD3D11Renderer.blendEquation(blendEquationRgb);
        } else {
            VitraD3D11Renderer.disableBlend();
        }

        blendStateDirty = false;
        LOGGER.trace("Applied blend state to D3D11");
    }

    // ==================== DEPTH STATE ====================

    private static boolean depthTestEnabled = true;
    private static boolean depthWriteEnabled = true;
    private static int depthFunc = 0x0203; // GL_LEQUAL

    private static boolean depthStateDirty = true;

    /**
     * Enable depth test
     */
    public static void enableDepthTest() {
        if (depthTestEnabled) return;
        depthTestEnabled = true;
        depthStateDirty = true;
        LOGGER.trace("Depth test enabled");
    }

    /**
     * Disable depth test
     */
    public static void disableDepthTest() {
        if (!depthTestEnabled) return;
        depthTestEnabled = false;
        depthStateDirty = true;
        LOGGER.trace("Depth test disabled");
    }

    /**
     * Set depth write mask (true = write enabled, false = read-only)
     */
    public static void depthMask(boolean enabled) {
        if (depthWriteEnabled == enabled) return;
        depthWriteEnabled = enabled;
        depthStateDirty = true;
        LOGGER.trace("Depth mask changed: {}", enabled);
    }

    /**
     * Set depth comparison function
     */
    public static void depthFunc(int func) {
        if (depthFunc == func) return;
        depthFunc = func;
        depthStateDirty = true;
        LOGGER.trace("Depth func changed: {}", func);
    }

    /**
     * Apply depth state to D3D11 if dirty
     */
    public static void applyDepthState() {
        if (!depthStateDirty) return;

        if (depthTestEnabled) {
            VitraD3D11Renderer.enableDepthTest();
            VitraD3D11Renderer.depthFunc(depthFunc);
        } else {
            VitraD3D11Renderer.disableDepthTest();
        }

        VitraD3D11Renderer.depthMask(depthWriteEnabled);

        depthStateDirty = false;
        LOGGER.trace("Applied depth state to D3D11");
    }

    // ==================== RASTERIZER STATE ====================

    private static boolean cullEnabled = true;
    private static int cullFace = 0x0405; // GL_BACK
    private static boolean scissorEnabled = false;

    private static boolean rasterStateDirty = true;

    /**
     * Enable face culling
     */
    public static void enableCull() {
        if (cullEnabled) return;
        cullEnabled = true;
        rasterStateDirty = true;
        LOGGER.trace("Culling enabled");
    }

    /**
     * Disable face culling
     */
    public static void disableCull() {
        if (!cullEnabled) return;
        cullEnabled = false;
        rasterStateDirty = true;
        LOGGER.trace("Culling disabled");
    }

    /**
     * Set which face to cull (GL_FRONT, GL_BACK, GL_FRONT_AND_BACK)
     */
    public static void cullFace(int mode) {
        if (cullFace == mode) return;
        cullFace = mode;
        rasterStateDirty = true;
        LOGGER.trace("Cull face changed: {}", mode);
    }

    /**
     * Enable scissor test
     */
    public static void enableScissor() {
        if (scissorEnabled) return;
        scissorEnabled = true;
        rasterStateDirty = true;
        LOGGER.trace("Scissor test enabled");
    }

    /**
     * Disable scissor test
     */
    public static void disableScissor() {
        if (!scissorEnabled) return;
        scissorEnabled = false;
        rasterStateDirty = true;
        LOGGER.trace("Scissor test disabled");
    }

    /**
     * Apply rasterizer state to D3D11 if dirty
     */
    public static void applyRasterizerState() {
        if (!rasterStateDirty) return;

        // Use setRasterizerState which combines cull + fill + scissor
        // cullMode: 0=none, 1=front, 2=back
        // fillMode: 0=solid, 1=wireframe
        int cullModeValue = cullEnabled ? (cullFace == 0x0405 ? 2 : 1) : 0; // GL_BACK=0x0405 → D3D cullMode=2
        int fillModeValue = 0; // Always solid for now

        VitraD3D11Renderer.setRasterizerState(cullModeValue, fillModeValue, scissorEnabled);

        // Separate calls for enable/disable cull as fallback
        if (cullEnabled) {
            VitraD3D11Renderer.enableCull();
        } else {
            VitraD3D11Renderer.disableCull();
        }

        rasterStateDirty = false;
        LOGGER.trace("Applied rasterizer state to D3D11 (cull={}, scissor={})", cullEnabled, scissorEnabled);
    }

    // ==================== DYNAMIC STATE ====================

    private static int viewportX = 0;
    private static int viewportY = 0;
    private static int viewportWidth = 1920;
    private static int viewportHeight = 1080;

    private static int scissorX = 0;
    private static int scissorY = 0;
    private static int scissorWidth = 1920;
    private static int scissorHeight = 1080;

    /**
     * Set viewport (always applied immediately - not cached)
     */
    public static void setViewport(int x, int y, int width, int height) {
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;

        VitraD3D11Renderer.setViewport(x, y, width, height);
        LOGGER.trace("Viewport set: {}x{} at ({}, {})", width, height, x, y);
    }

    /**
     * Set scissor rectangle (applied immediately if scissor enabled)
     */
    public static void setScissor(int x, int y, int width, int height) {
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;

        if (scissorEnabled) {
            VitraD3D11Renderer.setScissor(x, y, width, height);
            LOGGER.trace("Scissor rect set: {}x{} at ({}, {})", width, height, x, y);
        }
    }

    // ==================== STATE MANAGEMENT ====================

    /**
     * Apply all dirty state to D3D11
     * Called before draw commands to ensure state is synchronized
     */
    public static void applyAllState() {
        applyBlendState();
        applyDepthState();
        applyRasterizerState();
    }

    /**
     * Reset all state tracking (mark everything dirty)
     * Called at frame start to handle external state changes
     *
     * VulkanMod pattern: Renderer.resetDescriptors() clears pipeline state
     *
     * CRITICAL FIX: We DON'T reset state values, only mark dirty.
     * This allows state setters to work correctly (they check current values).
     * When dirty flag is set, applyState() will apply regardless of cached value.
     */
    public static void resetState() {
        // Mark all state dirty so next apply() will push to D3D11
        // even if Java-side cached value hasn't changed
        blendStateDirty = true;
        depthStateDirty = true;
        rasterStateDirty = true;

        LOGGER.trace("Reset state tracking (all state marked dirty for next apply)");
    }

    /**
     * Get current state statistics (for debugging)
     */
    public static String getStateStats() {
        return String.format("Blend=%s (dirty=%s), Depth=%s/%s (dirty=%s), Cull=%s (dirty=%s), Scissor=%s",
            blendEnabled, blendStateDirty,
            depthTestEnabled, depthWriteEnabled, depthStateDirty,
            cullEnabled, rasterStateDirty,
            scissorEnabled);
    }

    /**
     * Force reset to default OpenGL state
     * Called during initialization
     */
    public static void setDefaultState() {
        // OpenGL defaults
        blendEnabled = false;
        srcRgbFactor = 1;    // GL_ONE
        dstRgbFactor = 0;    // GL_ZERO
        srcAlphaFactor = 1;
        dstAlphaFactor = 0;
        blendEquationRgb = 0x8006;   // GL_FUNC_ADD
        blendEquationAlpha = 0x8006;

        depthTestEnabled = true;
        depthWriteEnabled = true;
        depthFunc = 0x0203; // GL_LEQUAL

        cullEnabled = true;
        cullFace = 0x0405; // GL_BACK
        scissorEnabled = false;

        // Mark everything dirty to apply defaults
        blendStateDirty = true;
        depthStateDirty = true;
        rasterStateDirty = true;

        LOGGER.info("Set default D3D11 render state (OpenGL-compatible defaults)");
    }
}
