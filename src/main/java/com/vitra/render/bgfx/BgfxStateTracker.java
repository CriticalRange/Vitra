package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks OpenGL state changes and translates them to BGFX state flags.
 *
 * This class maintains the current rendering state and generates BGFX state
 * flags that match OpenGL behavior. BGFX uses a different state model than OpenGL:
 * - OpenGL: State machine with global state (glEnable/glDisable)
 * - BGFX: State is set per draw call via bgfx_set_state()
 *
 * Key BGFX state concepts:
 * - State is encoded as a 64-bit value combining multiple flags
 * - State flags are applied immediately before draw calls
 * - No global state machine - each draw call is independent
 *
 * Reference: https://bkaradzic.github.io/bgfx/bgfx.html#rendering-state
 */
public class BgfxStateTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxStateTracker");

    // Current rendering state flags
    private long currentState = 0;

    // Individual state components
    private boolean depthTestEnabled = false;
    private long depthFunc = BGFX.BGFX_STATE_DEPTH_TEST_LESS;  // Default: GL_LESS
    private boolean depthWriteEnabled = true;

    private boolean blendEnabled = false;
    private long blendSrcRGB = BGFX.BGFX_STATE_BLEND_ONE;
    private long blendDstRGB = BGFX.BGFX_STATE_BLEND_ZERO;
    private long blendSrcAlpha = BGFX.BGFX_STATE_BLEND_ONE;
    private long blendDstAlpha = BGFX.BGFX_STATE_BLEND_ZERO;

    private boolean cullFaceEnabled = false;
    private long cullMode = BGFX.BGFX_STATE_CULL_CW;  // Default: cull clockwise (back faces)

    private boolean colorWriteR = true;
    private boolean colorWriteG = true;
    private boolean colorWriteB = true;
    private boolean colorWriteA = true;

    // Note: Primitive type is NOT part of BGFX state flags
    // It's set separately via the topology parameter in draw calls

    /**
     * Enable/disable depth testing.
     * OpenGL equivalent: glEnable(GL_DEPTH_TEST) / glDisable(GL_DEPTH_TEST)
     */
    public void setDepthTestEnabled(boolean enabled) {
        if (this.depthTestEnabled != enabled) {
            this.depthTestEnabled = enabled;
            LOGGER.trace("Depth test: {}", enabled ? "enabled" : "disabled");
            rebuildState();
        }
    }

    /**
     * Set depth comparison function.
     * OpenGL equivalent: glDepthFunc(func)
     *
     * @param glFunc OpenGL depth function (GL_LESS, GL_LEQUAL, etc.)
     */
    public void setDepthFunc(int glFunc) {
        long bgfxFunc = convertDepthFunc(glFunc);
        if (this.depthFunc != bgfxFunc) {
            this.depthFunc = bgfxFunc;
            LOGGER.trace("Depth func: 0x{}", Integer.toHexString(glFunc));
            rebuildState();
        }
    }

    /**
     * Enable/disable depth buffer writing.
     * OpenGL equivalent: glDepthMask(flag)
     */
    public void setDepthWriteEnabled(boolean enabled) {
        if (this.depthWriteEnabled != enabled) {
            this.depthWriteEnabled = enabled;
            LOGGER.trace("Depth write: {}", enabled ? "enabled" : "disabled");
            rebuildState();
        }
    }

    /**
     * Enable/disable blending.
     * OpenGL equivalent: glEnable(GL_BLEND) / glDisable(GL_BLEND)
     */
    public void setBlendEnabled(boolean enabled) {
        if (this.blendEnabled != enabled) {
            this.blendEnabled = enabled;
            LOGGER.trace("Blend: {}", enabled ? "enabled" : "disabled");
            rebuildState();
        }
    }

    /**
     * Set blend function (same for RGB and Alpha).
     * OpenGL equivalent: glBlendFunc(src, dst)
     */
    public void setBlendFunc(int glSrcFactor, int glDstFactor) {
        long bgfxSrc = convertBlendFactor(glSrcFactor);
        long bgfxDst = convertBlendFactor(glDstFactor);

        if (this.blendSrcRGB != bgfxSrc || this.blendDstRGB != bgfxDst ||
            this.blendSrcAlpha != bgfxSrc || this.blendDstAlpha != bgfxDst) {

            this.blendSrcRGB = bgfxSrc;
            this.blendDstRGB = bgfxDst;
            this.blendSrcAlpha = bgfxSrc;
            this.blendDstAlpha = bgfxDst;

            LOGGER.trace("Blend func: src=0x{}, dst=0x{}",
                Integer.toHexString(glSrcFactor), Integer.toHexString(glDstFactor));
            rebuildState();
        }
    }

    /**
     * Set blend function separately for RGB and Alpha.
     * OpenGL equivalent: glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha)
     */
    public void setBlendFuncSeparate(int glSrcRGB, int glDstRGB, int glSrcAlpha, int glDstAlpha) {
        long bgfxSrcRGB = convertBlendFactor(glSrcRGB);
        long bgfxDstRGB = convertBlendFactor(glDstRGB);
        long bgfxSrcAlpha = convertBlendFactor(glSrcAlpha);
        long bgfxDstAlpha = convertBlendFactor(glDstAlpha);

        if (this.blendSrcRGB != bgfxSrcRGB || this.blendDstRGB != bgfxDstRGB ||
            this.blendSrcAlpha != bgfxSrcAlpha || this.blendDstAlpha != bgfxDstAlpha) {

            this.blendSrcRGB = bgfxSrcRGB;
            this.blendDstRGB = bgfxDstRGB;
            this.blendSrcAlpha = bgfxSrcAlpha;
            this.blendDstAlpha = bgfxDstAlpha;

            LOGGER.trace("Blend func separate: srcRGB=0x{}, dstRGB=0x{}, srcA=0x{}, dstA=0x{}",
                Integer.toHexString(glSrcRGB), Integer.toHexString(glDstRGB),
                Integer.toHexString(glSrcAlpha), Integer.toHexString(glDstAlpha));
            rebuildState();
        }
    }

    /**
     * Enable/disable face culling.
     * OpenGL equivalent: glEnable(GL_CULL_FACE) / glDisable(GL_CULL_FACE)
     */
    public void setCullFaceEnabled(boolean enabled) {
        if (this.cullFaceEnabled != enabled) {
            this.cullFaceEnabled = enabled;
            LOGGER.trace("Cull face: {}", enabled ? "enabled" : "disabled");
            rebuildState();
        }
    }

    /**
     * Set which faces to cull.
     * OpenGL equivalent: glCullFace(mode)
     *
     * @param glMode GL_FRONT, GL_BACK, or GL_FRONT_AND_BACK
     */
    public void setCullMode(int glMode) {
        // OpenGL constants: GL_FRONT=0x0404, GL_BACK=0x0405, GL_FRONT_AND_BACK=0x0408
        long bgfxMode;
        switch (glMode) {
            case 0x0404: // GL_FRONT
                bgfxMode = BGFX.BGFX_STATE_CULL_CCW;  // Cull counter-clockwise (front faces)
                break;
            case 0x0405: // GL_BACK
                bgfxMode = BGFX.BGFX_STATE_CULL_CW;   // Cull clockwise (back faces)
                break;
            case 0x0408: // GL_FRONT_AND_BACK
                // BGFX doesn't support culling both - disable culling
                cullFaceEnabled = false;
                bgfxMode = 0;
                break;
            default:
                LOGGER.warn("Unknown cull mode: 0x{}", Integer.toHexString(glMode));
                bgfxMode = BGFX.BGFX_STATE_CULL_CW;
        }

        if (this.cullMode != bgfxMode) {
            this.cullMode = bgfxMode;
            LOGGER.trace("Cull mode: 0x{}", Integer.toHexString(glMode));
            rebuildState();
        }
    }

    /**
     * Set color write mask.
     * OpenGL equivalent: glColorMask(r, g, b, a)
     */
    public void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        if (this.colorWriteR != r || this.colorWriteG != g ||
            this.colorWriteB != b || this.colorWriteA != a) {

            this.colorWriteR = r;
            this.colorWriteG = g;
            this.colorWriteB = b;
            this.colorWriteA = a;

            LOGGER.trace("Color mask: R={}, G={}, B={}, A={}", r, g, b, a);
            rebuildState();
        }
    }

    /**
     * Get the current BGFX state flags to use with bgfx_set_state().
     * This method combines all individual state components into a single 64-bit value.
     */
    public long getCurrentState() {
        return currentState;
    }

    /**
     * Rebuild the BGFX state flags from individual components.
     * BGFX state is a 64-bit value encoding multiple state flags:
     * - Write masks (color, depth, alpha)
     * - Depth test function
     * - Blend mode and equation
     * - Cull mode
     * - Primitive type
     */
    private void rebuildState() {
        long state = 0;

        // Write masks
        if (colorWriteR) state |= BGFX.BGFX_STATE_WRITE_R;
        if (colorWriteG) state |= BGFX.BGFX_STATE_WRITE_G;
        if (colorWriteB) state |= BGFX.BGFX_STATE_WRITE_B;
        if (colorWriteA) state |= BGFX.BGFX_STATE_WRITE_A;
        if (depthWriteEnabled) state |= BGFX.BGFX_STATE_WRITE_Z;

        // Depth test
        if (depthTestEnabled) {
            state |= depthFunc;
        }

        // Blending
        if (blendEnabled) {
            state |= BGFX.BGFX_STATE_BLEND_FUNC_SEPARATE(
                blendSrcRGB, blendDstRGB, blendSrcAlpha, blendDstAlpha);
        }

        // Culling
        if (cullFaceEnabled) {
            state |= cullMode;
        }

        // Note: Primitive type is NOT included in state flags
        // BGFX doesn't use state flags for primitive topology

        this.currentState = state;

        LOGGER.trace("Rebuilt BGFX state: 0x{}", Long.toHexString(state));
    }

    /**
     * Convert OpenGL depth function to BGFX depth test flag.
     */
    private long convertDepthFunc(int glFunc) {
        // OpenGL depth functions
        switch (glFunc) {
            case 0x0200: // GL_NEVER
                return BGFX.BGFX_STATE_DEPTH_TEST_NEVER;
            case 0x0201: // GL_LESS
                return BGFX.BGFX_STATE_DEPTH_TEST_LESS;
            case 0x0202: // GL_EQUAL
                return BGFX.BGFX_STATE_DEPTH_TEST_EQUAL;
            case 0x0203: // GL_LEQUAL
                return BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
            case 0x0204: // GL_GREATER
                return BGFX.BGFX_STATE_DEPTH_TEST_GREATER;
            case 0x0205: // GL_NOTEQUAL
                return BGFX.BGFX_STATE_DEPTH_TEST_NOTEQUAL;
            case 0x0206: // GL_GEQUAL
                return BGFX.BGFX_STATE_DEPTH_TEST_GEQUAL;
            case 0x0207: // GL_ALWAYS
                return BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
            default:
                LOGGER.warn("Unknown depth func: 0x{}, using LESS", Integer.toHexString(glFunc));
                return BGFX.BGFX_STATE_DEPTH_TEST_LESS;
        }
    }

    /**
     * Convert OpenGL blend factor to BGFX blend factor.
     */
    private long convertBlendFactor(int glFactor) {
        // OpenGL blend factors
        switch (glFactor) {
            case 0: // GL_ZERO
                return BGFX.BGFX_STATE_BLEND_ZERO;
            case 1: // GL_ONE
                return BGFX.BGFX_STATE_BLEND_ONE;
            case 0x0300: // GL_SRC_COLOR
                return BGFX.BGFX_STATE_BLEND_SRC_COLOR;
            case 0x0301: // GL_ONE_MINUS_SRC_COLOR
                return BGFX.BGFX_STATE_BLEND_INV_SRC_COLOR;
            case 0x0302: // GL_SRC_ALPHA
                return BGFX.BGFX_STATE_BLEND_SRC_ALPHA;
            case 0x0303: // GL_ONE_MINUS_SRC_ALPHA
                return BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA;
            case 0x0304: // GL_DST_ALPHA
                return BGFX.BGFX_STATE_BLEND_DST_ALPHA;
            case 0x0305: // GL_ONE_MINUS_DST_ALPHA
                return BGFX.BGFX_STATE_BLEND_INV_DST_ALPHA;
            case 0x0306: // GL_DST_COLOR
                return BGFX.BGFX_STATE_BLEND_DST_COLOR;
            case 0x0307: // GL_ONE_MINUS_DST_COLOR
                return BGFX.BGFX_STATE_BLEND_INV_DST_COLOR;
            case 0x0308: // GL_SRC_ALPHA_SATURATE
                return BGFX.BGFX_STATE_BLEND_SRC_ALPHA_SAT;
            default:
                LOGGER.warn("Unknown blend factor: 0x{}, using ONE", Integer.toHexString(glFactor));
                return BGFX.BGFX_STATE_BLEND_ONE;
        }
    }

    /**
     * Reset all state to defaults matching OpenGL initial state.
     */
    public void reset() {
        depthTestEnabled = false;
        depthFunc = BGFX.BGFX_STATE_DEPTH_TEST_LESS;
        depthWriteEnabled = true;

        blendEnabled = false;
        blendSrcRGB = BGFX.BGFX_STATE_BLEND_ONE;
        blendDstRGB = BGFX.BGFX_STATE_BLEND_ZERO;
        blendSrcAlpha = BGFX.BGFX_STATE_BLEND_ONE;
        blendDstAlpha = BGFX.BGFX_STATE_BLEND_ZERO;

        cullFaceEnabled = false;
        cullMode = BGFX.BGFX_STATE_CULL_CW;

        colorWriteR = true;
        colorWriteG = true;
        colorWriteB = true;
        colorWriteA = true;

        rebuildState();

        LOGGER.info("State tracker reset to defaults");
    }
}
