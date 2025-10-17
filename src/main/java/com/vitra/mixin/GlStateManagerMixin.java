package com.vitra.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.vitra.VitraMod;
import com.vitra.render.jni.JniUtils;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Critical Mixin: OpenGL State Management → DirectX 11 JNI State Tracking
 *
 * This mixin intercepts ALL OpenGL state changes from GlStateManager and forwards them
 * to the DirectX 11 JNI system. DirectX 11 uses a different state model than OpenGL:
 *
 * OpenGL Model:
 * - Global state machine
 * - glEnable/glDisable changes affect all subsequent draw calls
 * - State persists until explicitly changed
 *
 * DirectX 11 JNI Model:
 * - State is managed through native DirectX 11 calls
 * - State changes must be explicitly forwarded to native layer
 * - Uses immediate mode state management
 *
 * Strategy:
 * 1. Intercept all OpenGL state changes via @Overwrite
 * 2. Forward state changes to DirectX 11 JNI system
 * 3. Track state locally for debugging and synchronization
 * 4. Use @Overwrite to COMPLETELY REPLACE OpenGL calls (VulkanMod strategy)
 *
 * CRITICAL: @Overwrite ensures NO OpenGL code executes at all
 * - More reliable than @Inject + cancel
 * - No dependency on injection point timing
 * - Guarantees OpenGL DLL is never called
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlStateManagerMixin");

    // State tracking for DirectX 11 synchronization and debugging
    private static boolean blendEnabled = false;
    private static boolean depthTestEnabled = true; // Default OpenGL state
    private static boolean cullEnabled = false;
    private static int depthFunc = 513; // GL_LESS by default
    private static boolean depthMask = true;
    private static boolean colorMaskRed = true;
    private static boolean colorMaskGreen = true;
    private static boolean colorMaskBlue = true;
    private static boolean colorMaskAlpha = true;

    /**
     * Get current state for debugging and synchronization
     */
    public static boolean isBlendEnabled() { return blendEnabled; }
    public static boolean isDepthTestEnabled() { return depthTestEnabled; }
    public static boolean isCullEnabled() { return cullEnabled; }
    public static int getDepthFunc() { return depthFunc; }
    public static boolean isDepthMask() { return depthMask; }
    public static boolean[] getColorMask() { return new boolean[]{colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha}; }

    /**
     * Initialize DirectX 11 state to match current OpenGL state
     * Called when DirectX 11 is initialized to synchronize states
     */
    public static void synchronizeDirectXState() {
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            LOGGER.info("Synchronizing DirectX 11 state with current OpenGL state");
            LOGGER.info("Blend: {}, Depth: {}, Cull: {}, DepthFunc: {}, DepthMask: {}, ColorMask: [{}, {}, {}, {}]",
                blendEnabled, depthTestEnabled, cullEnabled, depthFunc, depthMask,
                colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);

            try {
                JniUtils.enableBlend(blendEnabled);
                JniUtils.enableDepthTest(depthTestEnabled);
                JniUtils.enableCull(cullEnabled);
                JniUtils.setDepthFunc(depthFunc);
                JniUtils.setDepthMask(depthMask);
                JniUtils.setColorMask(colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);
                LOGGER.info("DirectX 11 state synchronized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to synchronize DirectX 11 state", e);
            }
        }
    }

    // ============================================================================
    // DEPTH TEST STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        if (!depthTestEnabled) {
            depthTestEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_DEPTH_TEST) -> DirectX 11: Enable depth test");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableDepthTest(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable depth test in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        if (depthTestEnabled) {
            depthTestEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_DEPTH_TEST) -> DirectX 11: Disable depth test");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableDepthTest(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable depth test in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth func with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int func) {
        if (depthFunc != func) {
            depthFunc = func;
            LOGGER.trace("OpenGL: glDepthFunc(0x{}) -> DirectX 11: Set depth function", Integer.toHexString(func));

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setDepthFunc(func);
                } catch (Exception e) {
                    LOGGER.error("Failed to set depth function in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth mask with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean flag) {
        if (depthMask != flag) {
            depthMask = flag;
            LOGGER.trace("OpenGL: glDepthMask({}) -> DirectX 11: Set depth write mask", flag);

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setDepthMask(flag);
                } catch (Exception e) {
                    LOGGER.error("Failed to set depth mask in DirectX 11", e);
                }
            }
        }
    }

    // ============================================================================
    // BLENDING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        if (!blendEnabled) {
            blendEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_BLEND) -> DirectX 11: Enable blending");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableBlend(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable blending in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        if (blendEnabled) {
            blendEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_BLEND) -> DirectX 11: Disable blending");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableBlend(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable blending in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend func with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        LOGGER.trace("OpenGL: glBlendFuncSeparate(0x{}, 0x{}, 0x{}, 0x{}) -> DirectX 11: Set blend factors",
            Integer.toHexString(srcRGB), Integer.toHexString(dstRGB),
            Integer.toHexString(srcAlpha), Integer.toHexString(dstAlpha));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setBlendFunc(srcRGB, dstRGB, srcAlpha, dstAlpha);
            } catch (Exception e) {
                LOGGER.error("Failed to set blend function in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend func with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _blendFunc(int src, int dst) {
        LOGGER.trace("OpenGL: glBlendFunc(0x{}, 0x{}) -> DirectX 11: Set blend factors",
            Integer.toHexString(src), Integer.toHexString(dst));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setBlendFunc(src, dst, src, dst); // Use same factors for RGB and Alpha
            } catch (Exception e) {
                LOGGER.error("Failed to set blend function in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend equation with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _blendEquation(int mode) {
        LOGGER.trace("OpenGL: glBlendEquation(0x{}) -> DirectX 11: Set blend equation", Integer.toHexString(mode));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                // Convert blend equation to DirectX 11 operation
                int dxOperation = convertBlendEquationToDirectX(mode);
                // TODO: Implement blend equation setting in VitraNativeRenderer
                LOGGER.debug("Blend equation conversion needed: 0x{} -> DirectX 11 op: 0x{}",
                    Integer.toHexString(mode), Integer.toHexString(dxOperation));
            } catch (Exception e) {
                LOGGER.error("Failed to set blend equation in DirectX 11", e);
            }
        }
    }

    /**
     * Convert OpenGL blend equation to DirectX 11 operation
     */
    private static int convertBlendEquationToDirectX(int glEquation) {
        switch (glEquation) {
            case 0x8006: return 1; // GL_FUNC_ADD -> D3D11_BLEND_OP_ADD
            case 0x8007: return 2; // GL_FUNC_SUBTRACT -> D3D11_BLEND_OP_SUBTRACT
            case 0x8008: return 3; // GL_FUNC_REVERSE_SUBTRACT -> D3D11_BLEND_OP_REV_SUBTRACT
            case 0x800A: return 4; // GL_MIN -> D3D11_BLEND_OP_MIN
            case 0x800B: return 5; // GL_MAX -> D3D11_BLEND_OP_MAX
            default: return 1; // Default to ADD
        }
    }

    // ============================================================================
    // CULLING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        if (!cullEnabled) {
            cullEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_CULL_FACE) -> DirectX 11: Enable face culling");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableCull(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable face culling in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        if (cullEnabled) {
            cullEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_CULL_FACE) -> DirectX 11: Disable face culling");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableCull(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable face culling in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face mode with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _cullFace(int mode) {
        LOGGER.trace("OpenGL: glCullFace(0x{}) -> DirectX 11: Set cull face mode", Integer.toHexString(mode));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                // Convert OpenGL cull face to DirectX 11 cull mode
                int cullMode = convertCullFaceToDirectX(mode);
                // TODO: Implement cull face setting in VitraNativeRenderer
                LOGGER.debug("Cull face conversion needed: 0x{} -> DirectX 11 cull: 0x{}",
                    Integer.toHexString(mode), Integer.toHexString(cullMode));
            } catch (Exception e) {
                LOGGER.error("Failed to set cull face in DirectX 11", e);
            }
        }
    }

    /**
     * Convert OpenGL cull face to DirectX 11 cull mode
     */
    private static int convertCullFaceToDirectX(int glCullFace) {
        switch (glCullFace) {
            case 0x0404: return 1; // GL_FRONT -> D3D11_CULL_FRONT
            case 0x0405: return 2; // GL_BACK -> D3D11_CULL_BACK
            case 0x0408: return 3; // GL_FRONT_AND_BACK -> D3D11_CULL_NONE (disable culling)
            default: return 2; // Default to BACK
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL front face with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _frontFace(int mode) {
        LOGGER.trace("OpenGL: glFrontFace(0x{}) -> DirectX 11: Set front face", Integer.toHexString(mode));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                // Convert OpenGL front face to DirectX 11 front face
                boolean frontFaceCounterClockwise = (mode == 0x0901); // GL_CCW
                // TODO: Implement front face setting in VitraNativeRenderer
                LOGGER.debug("Front face conversion needed: 0x{} -> DirectX 11 frontCCW: {}",
                    Integer.toHexString(mode), frontFaceCounterClockwise);
            } catch (Exception e) {
                LOGGER.error("Failed to set front face in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // COLOR MASK STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL color mask with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        // Only forward if state actually changed
        if (colorMaskRed != red || colorMaskGreen != green || colorMaskBlue != blue || colorMaskAlpha != alpha) {
            colorMaskRed = red;
            colorMaskGreen = green;
            colorMaskBlue = blue;
            colorMaskAlpha = alpha;
            LOGGER.trace("OpenGL: glColorMask({}, {}, {}, {}) -> DirectX 11: Set color mask", red, green, blue, alpha);

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setColorMask(red, green, blue, alpha);
                } catch (Exception e) {
                    LOGGER.error("Failed to set color mask in DirectX 11", e);
                }
            }
        }
    }

    // ============================================================================
    // VIEWPORT AND SCISSOR (These are view-level operations in DirectX 11)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL viewport with DirectX 11 viewport
     */
    @Overwrite(remap = false)
    public static void _viewport(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glViewport({}, {}, {}, {}) -> DirectX 11: Set viewport", x, y, width, height);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setViewport(x, y, width, height);
            } catch (Exception e) {
                LOGGER.error("Failed to set viewport in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with DirectX 11 scissor
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        LOGGER.trace("OpenGL: glEnable(GL_SCISSOR_TEST) -> DirectX 11: Enable scissor test");

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.enableScissorTest(true);
            } catch (Exception e) {
                LOGGER.error("Failed to enable scissor test in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with DirectX 11 scissor
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        LOGGER.trace("OpenGL: glDisable(GL_SCISSOR_TEST) -> DirectX 11: Disable scissor test");

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.enableScissorTest(false);
            } catch (Exception e) {
                LOGGER.error("Failed to disable scissor test in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor box with DirectX 11 scissor rect
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glScissor({}, {}, {}, {}) -> DirectX 11: Set scissor rect", x, y, width, height);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setScissorRect(x, y, width, height);
            } catch (Exception e) {
                LOGGER.error("Failed to set scissor rect in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // FRAMEBUFFER OPERATIONS - CRITICAL FOR RENDER TARGETS
    // ============================================================================

    // Framebuffer state tracking
    private static int currentDrawFramebuffer = 0;
    private static int currentReadFramebuffer = 0;

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL clear with DirectX 11 clear
     *
     * Minecraft 1.21.1 signature: _clear(int mask) - one parameter
     */
    @Overwrite(remap = false)
    public static void _clear(int mask) {
        LOGGER.trace("OpenGL: glClear(0x{}) -> DirectX 11: Clear render target", Integer.toHexString(mask));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.clear(mask);
            } catch (Exception e) {
                LOGGER.error("Failed to clear render target in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL clear color with DirectX 11 clear color
     */
    @Overwrite(remap = false)
    public static void _clearColor(float red, float green, float blue, float alpha) {
        LOGGER.trace("OpenGL: glClearColor({}, {}, {}, {}) -> DirectX 11: Set clear color", red, green, blue, alpha);

        try {
            // Forward to DirectX 11 native system
            VitraNativeRenderer.setClearColor(red, green, blue, alpha);
        } catch (Exception e) {
            LOGGER.error("Failed to set clear color in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL clear depth with DirectX 11 clear depth
     */
    @Overwrite(remap = false)
    public static void _clearDepth(double depth) {
        LOGGER.trace("OpenGL: glClearDepth({}) -> DirectX 11: Set clear depth", depth);

        try {
            // Forward to DirectX 11 native system
            VitraNativeRenderer.setClearDepth((float) depth);
        } catch (Exception e) {
            LOGGER.error("Failed to set clear depth in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer binding with DirectX 11 render target binding
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int target, int framebuffer) {
        if (target == 0x8CA8) { // GL_DRAW_FRAMEBUFFER
            if (currentDrawFramebuffer != framebuffer) {
                currentDrawFramebuffer = framebuffer;
                LOGGER.trace("OpenGL: glBindFramebuffer(GL_DRAW_FRAMEBUFFER, {}) -> DirectX 11: Set draw render target", framebuffer);

                try {
                    // Forward to DirectX 11 native system
                    VitraNativeRenderer.setRenderTargets(framebuffer);
                } catch (Exception e) {
                    LOGGER.error("Failed to bind draw framebuffer in DirectX 11", e);
                }
            }
        } else if (target == 0x8CA9) { // GL_READ_FRAMEBUFFER
            if (currentReadFramebuffer != framebuffer) {
                currentReadFramebuffer = framebuffer;
                LOGGER.trace("OpenGL: glBindFramebuffer(GL_READ_FRAMEBUFFER, {}) -> DirectX 11: Set read render target", framebuffer);

                try {
                    // Forward to DirectX 11 native system
                    VitraNativeRenderer.setReadRenderTarget(framebuffer);
                } catch (Exception e) {
                    LOGGER.error("Failed to bind read framebuffer in DirectX 11", e);
                }
            }
        } else if (target == 0x8D40) { // GL_FRAMEBUFFER (both read and draw)
            if (currentDrawFramebuffer != framebuffer || currentReadFramebuffer != framebuffer) {
                currentDrawFramebuffer = framebuffer;
                currentReadFramebuffer = framebuffer;
                LOGGER.trace("OpenGL: glBindFramebuffer(GL_FRAMEBUFFER, {}) -> DirectX 11: Set render targets", framebuffer);

                try {
                    // Forward to DirectX 11 native system
                    VitraNativeRenderer.setRenderTargets(framebuffer);
                    VitraNativeRenderer.setReadRenderTarget(framebuffer);
                } catch (Exception e) {
                    LOGGER.error("Failed to bind framebuffer in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer generation with DirectX 11 render target creation
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        LOGGER.trace("OpenGL: glGenFramebuffers() -> DirectX 11: Create render target");

        try {
            // Forward to DirectX 11 native system
            long renderTargetHandle = VitraNativeRenderer.createRenderTarget(1920, 1080, true, true);
            if (renderTargetHandle != 0) {
                // Generate fake framebuffer ID for compatibility
                int fakeFramebufferId = 2000 + (int)(System.currentTimeMillis() % 10000);
                LOGGER.debug("Created DirectX 11 render target 0x{} with fake framebuffer ID {}",
                    Long.toHexString(renderTargetHandle), fakeFramebufferId);
                return fakeFramebufferId;
            } else {
                LOGGER.error("Failed to create DirectX 11 render target");
                return 1; // Fallback ID
            }
        } catch (Exception e) {
            LOGGER.error("Exception creating DirectX 11 render target", e);
            return 1; // Fallback ID
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer deletion with DirectX 11 render target cleanup
     */
    @Overwrite(remap = false)
    public static void glDeleteFramebuffers(int framebuffer) {
        LOGGER.trace("OpenGL: glDeleteFramebuffers({}) -> DirectX 11: Release render target", framebuffer);

        try {
            // Forward to DirectX 11 native system for cleanup
            VitraNativeRenderer.destroyResource(framebuffer);

            // Clear current bound framebuffer if it was the one being deleted
            if (currentDrawFramebuffer == framebuffer) {
                currentDrawFramebuffer = 0;
            }
            if (currentReadFramebuffer == framebuffer) {
                currentReadFramebuffer = 0;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to delete framebuffer in DirectX 11", e);
        }
    }

    // ============================================================================
    // BUFFER OPERATIONS - SIMPLIFIED FOR DIRECTX 11 JNI
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer generation - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        LOGGER.trace("OpenGL: glGenBuffers() - BLOCKED, DirectX 11 handles buffers");
        // Return fake buffer ID - DirectX 11 JNI handles actual buffer creation
        return 999; // Fake ID, won't be used
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer binding - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int target, int buffer) {
        LOGGER.trace("OpenGL: glBindBuffer(target=0x{}, buffer={}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), buffer);
        // No-op: DirectX 11 JNI handles buffer binding internally
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), data != null ? data.remaining() : 0, Integer.toHexString(usage));
        // No-op: DirectX 11 JNI handles buffer data
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data (long size variant) - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, long size, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), size, Integer.toHexString(usage));
        // No-op: DirectX 11 JNI handles buffer data
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer deletion - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int buffer) {
        LOGGER.trace("OpenGL: glDeleteBuffers({}) - BLOCKED, DirectX 11 handles buffers", buffer);
        // No-op: DirectX 11 JNI handles buffer cleanup
    }

    // ============================================================================
    // VERTEX ARRAY OPERATIONS - SIMPLIFIED FOR DIRECTX 11 JNI
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO generation - DirectX 11 handles vertex arrays
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        LOGGER.trace("OpenGL: glGenVertexArrays() - BLOCKED, DirectX 11 handles vertex arrays");
        // Return fake VAO ID - DirectX 11 JNI doesn't use VAOs
        return 888; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO binding - DirectX 11 handles vertex arrays
     */
    @Overwrite(remap = false)
    public static void _glBindVertexArray(int array) {
        LOGGER.trace("OpenGL: glBindVertexArray({}) - BLOCKED, DirectX 11 handles vertex arrays", array);
        // No-op: DirectX 11 JNI doesn't use VAOs
    }

    // NOTE: _glDeleteVertexArrays doesn't exist in 1.21.1 GlStateManager
    // NOTE: _disableVertexAttribArray doesn't exist in 1.21.1 GlStateManager

    // ============================================================================
    // DRAWING OPERATIONS - CRITICAL FOR RENDERING
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL draw arrays - DirectX 11 JNI draw calls
     */
    @Overwrite(remap = false)
    public static void _drawArrays(int mode, int first, int count) {
        LOGGER.trace("OpenGL: glDrawArrays(mode=0x{}, first={}, count={}) -> DirectX 11: Draw arrays",
            Integer.toHexString(mode), first, count);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.drawArrays(mode, first, count);
            } catch (Exception e) {
                LOGGER.error("Failed to draw arrays in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL draw elements - DirectX 11 JNI draw calls
     */
    @Overwrite(remap = false)
    public static void _drawElements(int mode, int count, int type, long indices) {
        LOGGER.trace("OpenGL: glDrawElements(mode=0x{}, count={}, type=0x{}, indices={}) -> DirectX 11: Draw elements",
            Integer.toHexString(mode), count, Integer.toHexString(type), indices);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.drawElements(mode, count, type, indices);
            } catch (Exception e) {
                LOGGER.error("Failed to draw elements in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // TEXTURE OPERATIONS - CRITICAL FOR DIRECTX 11 INTEGRATION
    // ============================================================================

    // Texture state tracking
    private static int currentBoundTexture = 0;
    private static int activeTextureUnit = 0;
    private static int currentTextureTarget = 0; // GL_TEXTURE_2D, etc.

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture generation with DirectX 11 texture creation
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        LOGGER.trace("OpenGL: glGenTextures() -> DirectX 11: Create texture");

        try {
            // Create DirectX 11 texture via native system
            long directXHandle = VitraNativeRenderer.createTextureFromData(null, 1, 1, 0);
            if (directXHandle != 0) {
                // Generate fake OpenGL texture ID for compatibility
                int fakeTextureId = 1000 + (int)(System.currentTimeMillis() % 10000);
                LOGGER.debug("Created DirectX 11 texture 0x{} with fake OpenGL ID {}",
                    Long.toHexString(directXHandle), fakeTextureId);
                return fakeTextureId;
            } else {
                LOGGER.error("Failed to create DirectX 11 texture");
                return 1; // Fallback ID
            }
        } catch (Exception e) {
            LOGGER.error("Exception creating DirectX 11 texture", e);
            return 1; // Fallback ID
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture binding with DirectX 11 texture binding
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int target, int texture) {
        if (currentBoundTexture != texture || currentTextureTarget != target) {
            currentBoundTexture = texture;
            currentTextureTarget = target;

            LOGGER.trace("OpenGL: glBindTexture(target=0x{}, texture={}) -> DirectX 11: Bind texture",
                Integer.toHexString(target), texture);

            try {
                // Forward to DirectX 11 native system
                // For now, we use a simplified binding approach
                VitraNativeRenderer.bindTexture(texture, activeTextureUnit);
            } catch (Exception e) {
                LOGGER.error("Failed to bind texture in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture deletion with DirectX 11 texture cleanup
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int texture) {
        LOGGER.trace("OpenGL: glDeleteTexture({}) -> DirectX 11: Release texture", texture);

        try {
            // Forward to DirectX 11 native system for cleanup
            VitraNativeRenderer.destroyResource(texture);

            // Clear current bound texture if it was the one being deleted
            if (currentBoundTexture == texture) {
                currentBoundTexture = 0;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to delete texture in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL active texture with DirectX 11 texture slot selection
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int texture) {
        // Convert OpenGL texture unit (GL_TEXTURE0 + n) to slot index (n)
        int textureUnit = texture - 0x84C0; // GL_TEXTURE0 = 0x84C0

        if (activeTextureUnit != textureUnit) {
            activeTextureUnit = textureUnit;
            LOGGER.trace("OpenGL: glActiveTexture(0x{}) -> DirectX 11: Set texture slot {}",
                Integer.toHexString(texture), textureUnit);

            try {
                // Forward to DirectX 11 native system
                VitraNativeRenderer.setActiveTextureUnit(textureUnit);
            } catch (Exception e) {
                LOGGER.error("Failed to set active texture in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture parameter with DirectX 11 sampler state
     */
    @Overwrite(remap = false)
    public static void _texParameter(int target, int pname, int param) {
        LOGGER.trace("OpenGL: glTexParameteri(target=0x{}, pname=0x{}, param={}) -> DirectX 11: Set sampler state",
            Integer.toHexString(target), Integer.toHexString(pname), param);

        try {
            // Forward to DirectX 11 native system
            VitraNativeRenderer.setTextureParameter(currentBoundTexture, pname, param);
        } catch (Exception e) {
            LOGGER.error("Failed to set texture parameter in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture parameter (float variant) with DirectX 11 sampler state
     */
    @Overwrite(remap = false)
    public static void _texParameterf(int target, int pname, float param) {
        LOGGER.trace("OpenGL: glTexParameterf(target=0x{}, pname=0x{}, param={}) -> DirectX 11: Set sampler state",
            Integer.toHexString(target), Integer.toHexString(pname), param);

        try {
            // Convert float parameter to int for DirectX 11
            int intParam = Float.floatToIntBits(param);
            VitraNativeRenderer.setTextureParameter(currentBoundTexture, pname, intParam);
        } catch (Exception e) {
            LOGGER.error("Failed to set texture parameter (float) in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture image 2D with DirectX 11 texture data upload
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) {
        LOGGER.trace("OpenGL: glTexImage2D(target=0x{}, level={}, internalformat=0x{}, {}x{}, format=0x{}, type=0x{}) -> DirectX 11: Upload texture data",
            Integer.toHexString(target), level, Integer.toHexString(internalformat), width, height,
            Integer.toHexString(format), Integer.toHexString(type));

        try {
            // Forward to DirectX 11 native system
            VitraNativeRenderer.updateTexture(currentBoundTexture,
                pixels != null ? new byte[pixels.remaining()] : null,
                width, height, format);
        } catch (Exception e) {
            LOGGER.error("Failed to upload texture data in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture sub image 2D with DirectX 11 texture sub-region update
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        LOGGER.trace("OpenGL: glTexSubImage2D(target=0x{}, level={}, offset=({},{}), {}x{}, format=0x{}) -> DirectX 11: Update texture sub-region",
            Integer.toHexString(target), level, xoffset, yoffset, width, height, Integer.toHexString(format));

        try {
            // Forward to DirectX 11 native system
            VitraNativeRenderer.updateTextureSubRegion(currentBoundTexture,
                pixels != null ? new byte[pixels.remaining()] : null,
                xoffset, yoffset, width, height, format);
        } catch (Exception e) {
            LOGGER.error("Failed to update texture sub-region in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL generate mipmap with DirectX 11 mipmap generation
     */
    @Overwrite(remap = false)
    public static void _generateMipmap(int target) {
        LOGGER.trace("OpenGL: glGenerateMipmap(target=0x{}) -> DirectX 11: Generate mipmaps", Integer.toHexString(target));

        try {
            // Forward to DirectX 11 native system
            // Calculate mipmap levels based on texture size (assume max size for now)
            int levels = 1; // TODO: Calculate based on actual texture dimensions
            VitraNativeRenderer.generateTextureMipmaps(currentBoundTexture, levels);
        } catch (Exception e) {
            LOGGER.error("Failed to generate mipmaps in DirectX 11", e);
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program usage - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int program) {
        LOGGER.trace("OpenGL: glUseProgram({}) - BLOCKED, DirectX 11 handles shaders", program);
        // No-op: DirectX 11 JNI handles shader programs
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL error handling - DirectX 11 has no GL errors
     */
    @Overwrite(remap = false)
    public static int _getError() {
        // Always return GL_NO_ERROR (0) - DirectX 11 has different error handling
        return 0;
    }

    // Additional critical methods that should be blocked/simplified:
    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program creation - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        LOGGER.trace("OpenGL: glCreateProgram() - BLOCKED, DirectX 11 handles shaders");
        return 1; // Fake program ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program deletion - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int program) {
        LOGGER.trace("OpenGL: glDeleteProgram({}) - BLOCKED, DirectX 11 handles shaders", program);
        // No-op: DirectX 11 JNI handles shader cleanup
    }
}
