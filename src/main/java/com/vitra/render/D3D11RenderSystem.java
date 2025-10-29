package com.vitra.render;

import com.mojang.blaze3d.platform.Window;
import com.vitra.render.jni.VitraD3D11Renderer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * D3D11RenderSystem - Complete DirectX rendering state management
 *
 * This class is equivalent to VulkanMod's VRenderSystem but adapted for DirectX.
 * It maintains all OpenGL-equivalent state and forwards operations to DirectX via JNI.
 *
 * Architecture:
 * - Maintains DirectX rendering state (depth, blend, cull, etc.)
 * - Manages uniform buffers (MVP matrices, shader colors, fog, etc.)
 * - Translates OpenGL state calls to DirectX equivalents
 * - Uses JNI for native DirectX operations
 *
 * Unlike VulkanMod which uses Vulkan bindings directly in Java, we use JNI to call
 * native C++ DirectX code for maximum performance and direct API access.
 */
public abstract class D3D11RenderSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("D3D11RenderSystem");
    private static final float DEFAULT_DEPTH_VALUE = 1.0f;

    private static long window;

    // ===== DirectX Rendering State =====
    // These mirror OpenGL state but control DirectX behavior

    // Depth state
    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFunc = 515; // GL_LEQUAL equivalent

    // Blend state
    public static boolean blendEnabled = false;
    public static int blendSrcFactorRGB = 1;    // GL_ONE
    public static int blendDstFactorRGB = 0;    // GL_ZERO
    public static int blendSrcFactorAlpha = 1;
    public static int blendDstFactorAlpha = 0;

    // Color mask state
    public static boolean colorMaskRed = true;
    public static boolean colorMaskGreen = true;
    public static boolean colorMaskBlue = true;
    public static boolean colorMaskAlpha = true;

    // Cull state
    public static boolean cullEnabled = true;

    // Polygon mode
    public static int polygonMode = 0; // D3D11_FILL_SOLID equivalent

    // Logic op state
    public static boolean logicOpEnabled = false;
    public static int logicOpFunc = 0;

    // Depth bias (polygon offset)
    private static boolean depthBiasEnabled = false;
    private static float depthBiasConstant = 0.0f;
    private static float depthBiasSlope = 0.0f;

    // Clear values
    public static float clearDepthValue = DEFAULT_DEPTH_VALUE;
    public static FloatBuffer clearColor = MemoryUtil.memCallocFloat(4);

    // ===== Uniform Buffers (CPU-side) =====
    // These buffers store data that will be uploaded to DirectX constant buffers

    // Matrix uniforms (16 floats each = 64 bytes)
    public static FloatBuffer modelViewMatrix = MemoryUtil.memCallocFloat(16);
    public static FloatBuffer projectionMatrix = MemoryUtil.memCallocFloat(16);
    public static FloatBuffer textureMatrix = MemoryUtil.memCallocFloat(16);
    public static FloatBuffer MVP = MemoryUtil.memCallocFloat(16); // Combined MVP matrix

    // Vector uniforms
    public static FloatBuffer modelOffset = MemoryUtil.memCallocFloat(3);       // 3 floats = 12 bytes (padded to 16)
    public static FloatBuffer lightDirection0 = MemoryUtil.memCallocFloat(3);   // 3 floats
    public static FloatBuffer lightDirection1 = MemoryUtil.memCallocFloat(3);   // 3 floats

    // Color uniforms
    public static FloatBuffer shaderColor = MemoryUtil.memCallocFloat(4);       // 4 floats = 16 bytes
    public static FloatBuffer shaderFogColor = MemoryUtil.memCallocFloat(4);    // 4 floats = 16 bytes

    // Screen size
    public static FloatBuffer screenSize = MemoryUtil.memCallocFloat(2);        // 2 floats

    // Alpha cutout (for alpha testing)
    public static float alphaCutout = 0.0f;

    // ===== Initialization =====

    /**
     * Initialize DirectX renderer.
     * Called from RenderSystemMixin.initRenderer()
     */
    public static void initRenderer() {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  D3D11RenderSystem INITIALIZATION                          ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Initializing DirectX rendering system...");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        try {
            // Initialize DirectX via JNI
            // Note: Window handle is set via WindowMixin after GLFW window creation
            if (window != 0L) {
                boolean success = VitraD3D11Renderer.initializeDirectX(window, 1920, 1080, false, false);
                if (success) {
                    LOGGER.info("✓ DirectX initialized successfully");
                } else {
                    LOGGER.error("✗ DirectX initialization failed!");
                }
            } else {
                LOGGER.warn("Window handle not available yet - deferring initialization");
            }

            // Initialize default state
            setupDefaultState();

        } catch (Exception e) {
            LOGGER.error("Exception during D3D11RenderSystem initialization", e);
        }
    }

    /**
     * Setup default rendering state.
     * Called after DirectX initialization.
     */
    public static void setupDefaultState() {
        LOGGER.debug("Setting up default DirectX rendering state");

        // Set default clear color (black)
        setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Set default depth clear value
        clearDepthValue = DEFAULT_DEPTH_VALUE;

        // Enable depth test by default
        enableDepthTest();
        depthMask(true);
        depthFunc(515); // GL_LEQUAL

        // Disable blend by default
        disableBlend();

        // Enable culling by default
        enableCull();

        // Set default color mask (write all channels)
        colorMask(true, true, true, true);

        // Initialize matrices to identity
        new Matrix4f().get(modelViewMatrix);
        new Matrix4f().get(projectionMatrix);
        new Matrix4f().get(textureMatrix);
        new Matrix4f().get(MVP);

        // Set default shader color (white)
        setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Set default fog color (black)
        setShaderFogColor(0.0f, 0.0f, 0.0f, 1.0f);

        LOGGER.debug("Default DirectX state configured");
    }

    /**
     * Set the GLFW window handle for DirectX initialization.
     * Called from WindowMixin after window creation.
     */
    public static void setWindow(long windowHandle) {
        D3D11RenderSystem.window = windowHandle;
        LOGGER.debug("Window handle set: 0x{}", Long.toHexString(windowHandle));
    }

    /**
     * Update screen size uniform.
     */
    public static void updateScreenSize() {
        Window window = Minecraft.getInstance().getWindow();
        screenSize.put(0, (float) window.getWidth());
        screenSize.put(1, (float) window.getHeight());
    }

    /**
     * Get screen size uniform buffer.
     */
    public static FloatBuffer getScreenSize() {
        updateScreenSize();
        return screenSize;
    }

    // ===== Matrix Operations =====

    /**
     * Apply both model-view and projection matrices, then calculate MVP.
     */
    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    /**
     * Apply model-view matrix.
     */
    public static void applyModelViewMatrix(Matrix4f mat) {
        mat.get(modelViewMatrix);
        // Upload to DirectX constant buffer via JNI
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_MATRICES,
            modelViewMatrix
        );
    }

    /**
     * Apply projection matrix.
     */
    public static void applyProjectionMatrix(Matrix4f mat) {
        mat.get(projectionMatrix);
        // Upload to DirectX constant buffer via JNI
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_MATRICES,
            projectionMatrix
        );
    }

    /**
     * Calculate combined Model-View-Projection matrix.
     */
    public static void calculateMVP() {
        Matrix4f MV = new Matrix4f();
        MV.set(modelViewMatrix);

        Matrix4f P = new Matrix4f();
        P.set(projectionMatrix);

        // MVP = Projection * ModelView
        P.mul(MV).get(MVP);

        // Upload MVP to DirectX
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_MATRICES,
            MVP
        );
    }

    /**
     * Set texture matrix.
     */
    public static void setTextureMatrix(Matrix4f mat) {
        mat.get(textureMatrix);
        // Upload to DirectX constant buffer
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_MATRICES,
            textureMatrix
        );
    }

    /**
     * Get texture matrix buffer.
     */
    public static FloatBuffer getTextureMatrix() {
        return textureMatrix;
    }

    /**
     * Get model-view matrix buffer.
     */
    public static FloatBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    /**
     * Get projection matrix buffer.
     */
    public static FloatBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    /**
     * Get MVP matrix buffer.
     */
    public static FloatBuffer getMVP() {
        return MVP;
    }

    // ===== Shader Uniforms =====

    /**
     * Set model offset uniform (for chunk rendering optimization).
     */
    public static void setModelOffset(float x, float y, float z) {
        modelOffset.put(0, x);
        modelOffset.put(1, y);
        modelOffset.put(2, z);

        // Upload to DirectX
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_VECTORS,
            modelOffset
        );
    }

    /**
     * Set shader color uniform.
     */
    public static void setShaderColor(float r, float g, float b, float a) {
        shaderColor.put(0, r);
        shaderColor.put(1, g);
        shaderColor.put(2, b);
        shaderColor.put(3, a);

        // Upload to DirectX
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_COLORS,
            shaderColor
        );
    }

    /**
     * Set shader fog color uniform.
     */
    public static void setShaderFogColor(float r, float g, float b, float a) {
        shaderFogColor.put(0, r);
        shaderFogColor.put(1, g);
        shaderFogColor.put(2, b);
        shaderFogColor.put(3, a);

        // Upload to DirectX
        VitraD3D11Renderer.updateConstantBuffer(
            VitraD3D11Renderer.CONSTANT_BUFFER_SLOT_COLORS,
            shaderFogColor
        );
    }

    /**
     * Get shader color buffer.
     */
    public static FloatBuffer getShaderColor() {
        return shaderColor;
    }

    /**
     * Get shader fog color buffer.
     */
    public static FloatBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    // ===== Clear Operations =====

    /**
     * Set clear color.
     */
    public static void setClearColor(float r, float g, float b, float a) {
        clearColor.put(0, r);
        clearColor.put(1, g);
        clearColor.put(2, b);
        clearColor.put(3, a);
    }

    /**
     * Clear buffers (color, depth, stencil).
     * @param mask Bitmask indicating which buffers to clear
     */
    public static void clear(int mask) {
        // Translate OpenGL clear mask to DirectX clear operations
        boolean clearColorBuffer = (mask & 0x4000) != 0; // GL_COLOR_BUFFER_BIT
        boolean clearDepthBuffer = (mask & 0x100) != 0;  // GL_DEPTH_BUFFER_BIT
        boolean clearStencilBuffer = (mask & 0x400) != 0; // GL_STENCIL_BUFFER_BIT

        if (clearColorBuffer || clearDepthBuffer || clearStencilBuffer) {
            VitraD3D11Renderer.clearRenderTarget(
                clearColorBuffer,
                clearDepthBuffer,
                clearStencilBuffer,
                clearColor.get(0),
                clearColor.get(1),
                clearColor.get(2),
                clearColor.get(3)
            );
        }
    }

    /**
     * Set clear depth value.
     */
    public static void clearDepth(double depth) {
        clearDepthValue = (float) depth;
    }

    // ===== Depth State =====

    /**
     * Disable depth test.
     */
    public static void disableDepthTest() {
        if (depthTest) {
            depthTest = false;
            VitraD3D11Renderer.setDepthTestEnabled(false);
        }
    }

    /**
     * Enable depth test.
     */
    public static void enableDepthTest() {
        if (!depthTest) {
            depthTest = true;
            VitraD3D11Renderer.setDepthTestEnabled(true);
        }
    }

    /**
     * Set depth write mask.
     */
    public static void depthMask(boolean mask) {
        if (depthMask != mask) {
            depthMask = mask;
            VitraD3D11Renderer.setDepthWriteMask(mask);
        }
    }

    /**
     * Set depth comparison function.
     */
    public static void depthFunc(int func) {
        if (depthFunc != func) {
            depthFunc = func;
            VitraD3D11Renderer.setDepthFunc(translateDepthFunc(func));
        }
    }

    /**
     * Translate OpenGL depth function to DirectX comparison function.
     */
    private static int translateDepthFunc(int glFunc) {
        return switch (glFunc) {
            case 512 -> 1;  // GL_NEVER -> D3D11_COMPARISON_NEVER
            case 513 -> 2;  // GL_LESS -> D3D11_COMPARISON_LESS
            case 514 -> 3;  // GL_EQUAL -> D3D11_COMPARISON_EQUAL
            case 515 -> 4;  // GL_LEQUAL -> D3D11_COMPARISON_LESS_EQUAL
            case 516 -> 5;  // GL_GREATER -> D3D11_COMPARISON_GREATER
            case 517 -> 6;  // GL_NOTEQUAL -> D3D11_COMPARISON_NOT_EQUAL
            case 518 -> 7;  // GL_GEQUAL -> D3D11_COMPARISON_GREATER_EQUAL
            case 519 -> 8;  // GL_ALWAYS -> D3D11_COMPARISON_ALWAYS
            default -> 4;   // Default to LEQUAL
        };
    }

    // ===== Blend State =====

    /**
     * Enable blending.
     */
    public static void enableBlend() {
        if (!blendEnabled) {
            blendEnabled = true;
            VitraD3D11Renderer.setBlendEnabled(true);
        }
    }

    /**
     * Disable blending.
     */
    public static void disableBlend() {
        if (blendEnabled) {
            blendEnabled = false;
            VitraD3D11Renderer.setBlendEnabled(false);
        }
    }

    /**
     * Set blend function (same for RGB and Alpha).
     */
    public static void blendFunc(int srcFactor, int dstFactor) {
        blendFuncSeparate(srcFactor, dstFactor, srcFactor, dstFactor);
    }

    /**
     * Set blend function separately for RGB and Alpha.
     */
    public static void blendFuncSeparate(int srcFactorRGB, int dstFactorRGB, int srcFactorAlpha, int dstFactorAlpha) {
        if (blendSrcFactorRGB != srcFactorRGB || blendDstFactorRGB != dstFactorRGB ||
            blendSrcFactorAlpha != srcFactorAlpha || blendDstFactorAlpha != dstFactorAlpha) {

            blendSrcFactorRGB = srcFactorRGB;
            blendDstFactorRGB = dstFactorRGB;
            blendSrcFactorAlpha = srcFactorAlpha;
            blendDstFactorAlpha = dstFactorAlpha;

            // Note: DirectX blend state supports separate RGB/Alpha factors, but our JNI method
            // only accepts source/destination factor pairs. For now, use RGB factors for both.
            VitraD3D11Renderer.setBlendFunc(
                translateBlendFactor(srcFactorRGB),
                translateBlendFactor(dstFactorRGB)
            );
        }
    }

    /**
     * Translate OpenGL blend factor to DirectX blend factor.
     */
    private static int translateBlendFactor(int glFactor) {
        return switch (glFactor) {
            case 0 -> 1;        // GL_ZERO -> D3D11_BLEND_ZERO
            case 1 -> 2;        // GL_ONE -> D3D11_BLEND_ONE
            case 768 -> 3;      // GL_SRC_COLOR -> D3D11_BLEND_SRC_COLOR
            case 769 -> 4;      // GL_ONE_MINUS_SRC_COLOR -> D3D11_BLEND_INV_SRC_COLOR
            case 770 -> 5;      // GL_SRC_ALPHA -> D3D11_BLEND_SRC_ALPHA
            case 771 -> 6;      // GL_ONE_MINUS_SRC_ALPHA -> D3D11_BLEND_INV_SRC_ALPHA
            case 772 -> 7;      // GL_DST_ALPHA -> D3D11_BLEND_DEST_ALPHA
            case 773 -> 8;      // GL_ONE_MINUS_DST_ALPHA -> D3D11_BLEND_INV_DEST_ALPHA
            case 774 -> 9;      // GL_DST_COLOR -> D3D11_BLEND_DEST_COLOR
            case 775 -> 10;     // GL_ONE_MINUS_DST_COLOR -> D3D11_BLEND_INV_DEST_COLOR
            default -> 2;       // Default to ONE
        };
    }

    // ===== Color Mask =====

    /**
     * Set color write mask.
     */
    public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (colorMaskRed != red || colorMaskGreen != green ||
            colorMaskBlue != blue || colorMaskAlpha != alpha) {

            colorMaskRed = red;
            colorMaskGreen = green;
            colorMaskBlue = blue;
            colorMaskAlpha = alpha;

            VitraD3D11Renderer.setColorMask(red, green, blue, alpha);
        }
    }

    // ===== Cull State =====

    /**
     * Enable face culling.
     */
    public static void enableCull() {
        if (!cullEnabled) {
            cullEnabled = true;
            VitraD3D11Renderer.setCullEnabled(true);
        }
    }

    /**
     * Disable face culling.
     */
    public static void disableCull() {
        if (cullEnabled) {
            cullEnabled = false;
            VitraD3D11Renderer.setCullEnabled(false);
        }
    }

    // ===== Polygon Mode =====

    /**
     * Set polygon mode (fill, line, point).
     */
    public static void setPolygonMode(int mode) {
        if (polygonMode != mode) {
            polygonMode = mode;
            VitraD3D11Renderer.setPolygonMode(translatePolygonMode(mode));
        }
    }

    /**
     * Translate OpenGL polygon mode to DirectX fill mode.
     */
    private static int translatePolygonMode(int glMode) {
        return switch (glMode) {
            case 6912 -> 1;  // GL_POINT -> D3D11_FILL_POINT (not standard, use wireframe)
            case 6913 -> 2;  // GL_LINE -> D3D11_FILL_WIREFRAME
            case 6914 -> 3;  // GL_FILL -> D3D11_FILL_SOLID
            default -> 3;    // Default to SOLID
        };
    }

    // ===== Color Logic Op =====

    /**
     * Enable color logic operation.
     */
    public static void enableColorLogicOp() {
        logicOpEnabled = true;
        // DirectX doesn't have direct equivalent - implement in shader if needed
        LOGGER.warn("Color logic operations not fully supported in DirectX");
    }

    /**
     * Disable color logic operation.
     */
    public static void disableColorLogicOp() {
        logicOpEnabled = false;
    }

    /**
     * Set logic operation function.
     */
    public static void logicOp(int glLogicOp) {
        logicOpFunc = glLogicOp;
        // DirectX doesn't support all logic ops - would need shader implementation
    }

    // ===== Polygon Offset (Depth Bias) =====

    /**
     * Set polygon offset parameters.
     */
    public static void polygonOffset(float slope, float constant) {
        if (depthBiasConstant != constant || depthBiasSlope != slope) {
            depthBiasConstant = constant;
            depthBiasSlope = slope;

            if (depthBiasEnabled) {
                VitraD3D11Renderer.setDepthBias(constant, slope);
            }
        }
    }

    /**
     * Enable polygon offset.
     */
    public static void enablePolygonOffset() {
        if (!depthBiasEnabled) {
            depthBiasEnabled = true;
            VitraD3D11Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
        }
    }

    /**
     * Disable polygon offset.
     */
    public static void disablePolygonOffset() {
        if (depthBiasEnabled) {
            depthBiasEnabled = false;
            VitraD3D11Renderer.setDepthBias(0.0f, 0.0f);
        }
    }

    // ===== Cleanup =====

    /**
     * Shutdown DirectX rendering system.
     */
    public static void shutdown() {
        LOGGER.info("Shutting down D3D11RenderSystem");

        // Free memory-allocated buffers
        MemoryUtil.memFree(clearColor);
        MemoryUtil.memFree(modelViewMatrix);
        MemoryUtil.memFree(projectionMatrix);
        MemoryUtil.memFree(textureMatrix);
        MemoryUtil.memFree(MVP);
        MemoryUtil.memFree(modelOffset);
        MemoryUtil.memFree(lightDirection0);
        MemoryUtil.memFree(lightDirection1);
        MemoryUtil.memFree(shaderColor);
        MemoryUtil.memFree(shaderFogColor);
        MemoryUtil.memFree(screenSize);

        // Shutdown DirectX via JNI
        VitraD3D11Renderer.shutdown();

        LOGGER.info("D3D11RenderSystem shutdown complete");
    }
}
