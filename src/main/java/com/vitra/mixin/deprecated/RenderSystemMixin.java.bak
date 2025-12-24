package com.vitra.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VRenderSystem;
import com.vitra.render.VitraRenderer;
import com.vitra.VitraMod;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.blaze3d.systems.RenderSystem.*;

/**
 * Vitra unified RenderSystem mixin following VulkanMod standards.
 * Replaces OpenGL matrix operations with renderer-agnostic backend equivalents.
 * Handles projection, model-view, texture matrices, and shader state.
 *
 * Pattern based on VulkanMod's MRenderSystem with unified backend adaptations.
 * Includes thread-safe operations and silent error handling following VulkanMod patterns.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/RenderSystem");

    // Helper to get renderer instance (with null-safety check)
    // Returns null for D3D12 (which doesn't need GL compatibility layer)
    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            // Not yet initialized - this is expected during early initialization
            return null;
        }

        // If it's already a VitraRenderer (D3D11), return it directly
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }

        // For D3D12, return null (D3D12 doesn't use GL compatibility layer)
        // D3D12 handles rendering directly without going through GL emulation
        return null;
    }

    /**
     * Helper method to get the current renderer type
     */
    private static com.vitra.config.RendererType getCurrentRendererType() {
        com.vitra.core.VitraCore core = com.vitra.VitraMod.getCore();
        if (core != null && core.getRenderer() != null) {
            return core.getRenderer().getRendererType();
        }
        return com.vitra.config.RendererType.DIRECTX11; // Fallback
    }

    @Shadow private static Matrix4f projectionMatrix;
    @Shadow private static Matrix4f savedProjectionMatrix;
    @Shadow @Final private static Matrix4fStack modelViewStack;
    @Shadow private static Matrix4f modelViewMatrix;
    @Shadow private static Matrix4f textureMatrix;

    @Shadow @Final private static float[] shaderColor;
    @Shadow @Final private static Vector3f[] shaderLightDirections;
    @Shadow @Final private static float[] shaderFogColor;

    @Shadow private static @Nullable Thread renderThread;

    @Shadow public static VertexSorting vertexSorting;
    @Shadow private static VertexSorting savedVertexSorting;

    @Shadow
    public static void assertOnRenderThread() {}

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL renderer initialization with configured DirectX backend
     */
    @Overwrite(remap = false)
    public static void initRenderer(int debugVerbosity, boolean debugSync) {
        try {
            // Get configured renderer type
            com.vitra.config.VitraConfig config = com.vitra.VitraMod.getConfig();
            com.vitra.config.RendererType rendererType = (config != null) ? config.getRendererType() : com.vitra.config.RendererType.DIRECTX11;

            LOGGER.info("RenderSystem.initRenderer() called - initializing {}", rendererType);

            // Initialize configured backend (following VulkanMod's pattern: VRenderSystem.initRenderer())
            // Window handle was already stored by WindowMixin
            com.vitra.core.VitraCore core = com.vitra.VitraMod.getCore();
            if (core != null && core.getRenderer() != null) {
                com.vitra.render.IVitraRenderer renderer = core.getRenderer();

                // Get window handle from AbstractRenderer
                long windowHandle = 0;
                if (renderer instanceof com.vitra.render.AbstractRenderer abstractRenderer) {
                    windowHandle = abstractRenderer.getWindowHandle();
                }

                // Initialize with the stored window handle
                boolean success = renderer.initializeWithWindowHandle(windowHandle);
                if (success) {
                    LOGGER.info("{} initialized successfully via initRenderer()", rendererType);
                } else {
                    LOGGER.error("{} initialization returned false", rendererType);
                    throw new RuntimeException(rendererType + " initialization failed. Check logs for details.");
                }
            } else {
                LOGGER.error("VitraCore or renderer is null, cannot initialize {}", rendererType);
            }

            // Follow VulkanMod pattern: set render thread priority
            if (renderThread != null) {
                renderThread.setPriority(Thread.NORM_PRIORITY + 2);
            }
        } catch (Exception e) {
            // Silent error following VulkanMod pattern - don't crash Minecraft
            LOGGER.error("Vitra renderer initialization failed", e);
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL default state setup with configured DirectX backend equivalents
     */
    @Overwrite(remap = false)
    public static void setupDefaultState(int x, int y, int width, int height) {
        LOGGER.info("RenderSystem.setupDefaultState intercepted - Vitra JNI handles state internally (1.21.1)");
        // NO-OP: Vitra JNI handles its own state setup
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL max texture size query with Vitra backend equivalent
     */
    @Overwrite(remap = false)
    public static int maxSupportedTextureSize() {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                return renderer.getMaxTextureSize();
            }
            // For D3D12 or not initialized: return default
            return 2048;
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback
            LOGGER.warn("Failed to get Vitra max texture size, returning default");
            return 2048; // Default texture size
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader lights setup with Vitra backend equivalent
     */
    @Overwrite(remap = false)
    public static void setShaderLights(Vector3f dir0, Vector3f dir1) {
        shaderLightDirections[0] = dir0;
        shaderLightDirections[1] = dir1;

        // VulkanMod pattern: write directly to VRenderSystem buffers
        // Upload happens later in shader apply()
        VRenderSystem.lightDirection0.buffer.putFloat(0, dir0.x());
        VRenderSystem.lightDirection0.buffer.putFloat(4, dir0.y());
        VRenderSystem.lightDirection0.buffer.putFloat(8, dir0.z());

        VRenderSystem.lightDirection1.buffer.putFloat(0, dir1.x());
        VRenderSystem.lightDirection1.buffer.putFloat(4, dir1.y());
        VRenderSystem.lightDirection1.buffer.putFloat(8, dir1.z());
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader color setup with DirectX equivalent
     */
    @Overwrite(remap = false)
    private static void _setShaderColor(float r, float g, float b, float a) {
        shaderColor[0] = r;
        shaderColor[1] = g;
        shaderColor[2] = b;
        shaderColor[3] = a;

        // VulkanMod pattern: write directly to VRenderSystem
        // Upload happens later in shader apply()
        VRenderSystem.setShaderColor(r, g, b, a);
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace GLFW swap buffers with DirectX Present()
     */
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
    private static void endFrame(long window) {
        // Call DirectX Present() via VitraCore renderer
        try {
            com.vitra.core.VitraCore core = com.vitra.VitraMod.getCore();
            if (core != null && core.getRenderer() != null) {
                core.getRenderer().endFrame();
            } else {
                LOGGER.warn("[VITRA_PRESENT] Core or renderer is null!");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to present DirectX frame", e);
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader fog color setup with DirectX equivalent
     */
    @Overwrite(remap = false)
    public static void setShaderFogColor(float f, float g, float h, float i) {
        shaderFogColor[0] = f;
        shaderFogColor[1] = g;
        shaderFogColor[2] = h;
        shaderFogColor[3] = i;

        // VulkanMod pattern: write directly to VRenderSystem
        // Upload happens later in shader apply()
        VRenderSystem.setShaderFogColor(f, g, h, i);
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL projection matrix setup with DirectX equivalent
     */
    @Overwrite(remap = false)
    public static void setProjectionMatrix(Matrix4f projectionMatrix, VertexSorting vertexSorting) {
        Matrix4f matrix4f = new Matrix4f(projectionMatrix);
        if (!isOnRenderThread()) {
            recordRenderCall(() -> {
                RenderSystemMixin.projectionMatrix = matrix4f;
                RenderSystemMixin.vertexSorting = vertexSorting;

                // VulkanMod pattern: store in VRenderSystem and recalculate MVP
                VRenderSystem.applyProjectionMatrix(matrix4f);
                VRenderSystem.calculateMVP();  // CRITICAL: Recalculate MVP after projection change
            });
        } else {
            RenderSystemMixin.projectionMatrix = matrix4f;
            RenderSystemMixin.vertexSorting = vertexSorting;

            // VulkanMod pattern: store in VRenderSystem and recalculate MVP
            VRenderSystem.applyProjectionMatrix(matrix4f);
            VRenderSystem.calculateMVP();  // CRITICAL: Recalculate MVP after projection change
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture matrix setup with DirectX equivalent
     */
    @Overwrite(remap = false)
    public static void setTextureMatrix(Matrix4f matrix4f) {
        Matrix4f matrix4f2 = new Matrix4f(matrix4f);
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> {
                textureMatrix = matrix4f2;
                try {
                    VitraRenderer renderer = getRenderer();
                    if (renderer != null) {
                        renderer.setTextureMatrix(new float[]{
                            matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                            matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                            matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                            matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
                        });
                    }
                    // For D3D12 or not initialized: skip
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to set DirectX texture matrix");
                }
            });
        } else {
            textureMatrix = matrix4f2;
            try {
                VitraRenderer renderer = getRenderer();
                if (renderer != null) {
                    renderer.setTextureMatrix(new float[]{
                        matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                        matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                        matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                        matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
                    });
                }
                // For D3D12 or not initialized: skip
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to set DirectX texture matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture matrix reset with DirectX equivalent
     */
    @Overwrite(remap = false)
    public static void resetTextureMatrix() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> {
                textureMatrix.identity();
                try {
                    VitraRenderer renderer = getRenderer();
                    if (renderer != null) {
                        renderer.setTextureMatrix(new float[]{
                            1.0f, 0.0f, 0.0f, 0.0f,
                            0.0f, 1.0f, 0.0f, 0.0f,
                            0.0f, 0.0f, 1.0f, 0.0f,
                            0.0f, 0.0f, 0.0f, 1.0f
                        });
                    }
                    // For D3D12 or not initialized: skip
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to reset DirectX texture matrix");
                }
            });
        } else {
            textureMatrix.identity();
            try {
                VitraRenderer renderer = getRenderer();
                if (renderer != null) {
                    renderer.setTextureMatrix(new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                    });
                }
                // For D3D12 or not initialized: skip
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to reset DirectX texture matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL model view matrix application with DirectX equivalent
     */
    @Overwrite(remap = false)
    public static void applyModelViewMatrix() {
        Matrix4f matrix4f = new Matrix4f(modelViewStack);
        if (!isOnRenderThread()) {
            recordRenderCall(() -> {
                modelViewMatrix = matrix4f;
                // VulkanMod pattern: store in VRenderSystem and recalculate MVP
                VRenderSystem.applyModelViewMatrix(matrix4f);
                VRenderSystem.calculateMVP();  // CRITICAL: Recalculate MVP after modelview change
            });
        } else {
            modelViewMatrix = matrix4f;
            // VulkanMod pattern: store in VRenderSystem and recalculate MVP
            VRenderSystem.applyModelViewMatrix(matrix4f);
            VRenderSystem.calculateMVP();  // CRITICAL: Recalculate MVP after modelview change
        }
    }

    /**
     * Helper method to upload all transform matrices (MVP, ModelView, Projection) from VRenderSystem to native
     * CRITICAL: This uses the COMPUTED MVP from VRenderSystem, ensuring correct transformation
     */
    private static void uploadTransformMatricesToNative() {
        VitraRenderer renderer = getRenderer();
        if (renderer != null) {
            // Extract matrices from VRenderSystem's MappedBuffers
            float[] mvpArray = new float[16];
            float[] mvArray = new float[16];
            float[] projArray = new float[16];

            // Read from VRenderSystem's computed matrices
            // CRITICAL FIX: Must reset buffer position before each read!
            // asFloatBuffer() creates a view but .get() moves the position, leaving garbage for subsequent reads
            VRenderSystem.getMVP().byteBuffer().asFloatBuffer().get(0, mvpArray);
            VRenderSystem.getModelViewMatrix().byteBuffer().asFloatBuffer().get(0, mvArray);
            VRenderSystem.getProjectionMatrix().byteBuffer().asFloatBuffer().get(0, projArray);

            // Upload all three matrices to native constant buffer
            // CRITICAL: This is the FIX - we're now sending the COMPUTED MVP, not just projection!
            renderer.setTransformMatrices(mvpArray, mvArray, projArray);
        }
        // For D3D12 or not initialized: skip (D3D12 handles its own matrix management)
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL projection matrix restoration with DirectX equivalent
     */
    @Overwrite(remap = false)
    private static void _restoreProjectionMatrix() {
        projectionMatrix = savedProjectionMatrix;
        vertexSorting = savedVertexSorting;

        // VulkanMod pattern: restore projection and recalculate MVP
        VRenderSystem.applyProjectionMatrix(projectionMatrix);
        VRenderSystem.calculateMVP();  // CRITICAL: Recalculate MVP after restore
    }
}
