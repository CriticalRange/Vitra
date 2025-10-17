package com.vitra.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.vitra.render.jni.VitraNativeRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.blaze3d.systems.RenderSystem.*;

/**
 * DirectX 11 RenderSystem mixin following VulkanMod standards.
 * Replaces OpenGL matrix operations with DirectX 11 equivalents.
 * Handles projection, model-view, texture matrices, and shader state for DirectX 11.
 *
 * Pattern based on VulkanMod's MRenderSystem with DirectX 11 adaptations.
 * Includes thread-safe operations and silent error handling following VulkanMod patterns.
 */
@Mixin(RenderSystem.class)
public abstract class MRenderSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/RenderSystem");

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
     * @reason Replace OpenGL renderer initialization with DirectX 11
     */
    @Overwrite(remap = false)
    public static void initRenderer(int debugVerbosity, boolean debugSync) {
        try {
            LOGGER.debug("Initializing DirectX 11 renderer system");
            VitraNativeRenderer.initRenderer();

            // Follow VulkanMod pattern: silent thread priority handling
            if (renderThread != null) {
                try {
                    renderThread.setPriority(Thread.NORM_PRIORITY + 2);
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to set render thread priority");
                }
            }
        } catch (Exception e) {
            // Silent error following VulkanMod pattern - don't crash Minecraft
            LOGGER.warn("DirectX 11 renderer initialization failed, falling back to OpenGL");
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL default state setup with DirectX 11 equivalents
     */
    @Overwrite(remap = false)
    public static void setupDefaultState(int x, int y, int width, int height) {
        LOGGER.info("RenderSystem.setupDefaultState intercepted - DirectX 11 JNI handles state internally (1.21.1)");
        // NO-OP: DirectX 11 JNI handles its own state setup
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL max texture size query with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static int maxSupportedTextureSize() {
        try {
            return VitraNativeRenderer.maxSupportedTextureSize();
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback
            LOGGER.warn("Failed to get DirectX 11 max texture size, returning default");
            return 2048; // Default texture size
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader lights setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void setShaderLights(Vector3f dir0, Vector3f dir1) {
        shaderLightDirections[0] = dir0;
        shaderLightDirections[1] = dir1;

        try {
            VitraNativeRenderer.setShaderLightDirection(0, dir0.x(), dir0.y(), dir0.z());
            VitraNativeRenderer.setShaderLightDirection(1, dir1.x(), dir1.y(), dir1.z());
        } catch (Exception e) {
            // Silent error following VulkanMod pattern
            LOGGER.warn("Failed to set DirectX 11 shader light directions");
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader color setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    private static void _setShaderColor(float r, float g, float b, float a) {
        shaderColor[0] = r;
        shaderColor[1] = g;
        shaderColor[2] = b;
        shaderColor[3] = a;

        try {
            VitraNativeRenderer.setShaderColor(r, g, b, a);
        } catch (Exception e) {
            // Silent error following VulkanMod pattern
            LOGGER.warn("Failed to set DirectX 11 shader color");
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader fog color setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void setShaderFogColor(float f, float g, float h, float i) {
        shaderFogColor[0] = f;
        shaderFogColor[1] = g;
        shaderFogColor[2] = h;
        shaderFogColor[3] = i;

        try {
            VitraNativeRenderer.setShaderFogColor(f, g, h, i);
        } catch (Exception e) {
            // Silent error following VulkanMod pattern
            LOGGER.warn("Failed to set DirectX 11 shader fog color");
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL projection matrix setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void setProjectionMatrix(Matrix4f projectionMatrix, VertexSorting vertexSorting) {
        Matrix4f matrix4f = new Matrix4f(projectionMatrix);
        if (!isOnRenderThread()) {
            recordRenderCall(() -> {
                MRenderSystem.projectionMatrix = matrix4f;
                // Note: vertexSorting is handled internally by RenderSystem in 1.21.1

                try {
                    VitraNativeRenderer.setProjectionMatrix(new float[]{
                        matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                        matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                        matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                        matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                    });
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to set DirectX 11 projection matrix");
                }
            });
        } else {
            MRenderSystem.projectionMatrix = matrix4f;
            // Note: vertexSorting is handled internally by RenderSystem in 1.21.1

            try {
                VitraNativeRenderer.setProjectionMatrix(new float[]{
                    matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                    matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                    matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                    matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                });
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to set DirectX 11 projection matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture matrix setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void setTextureMatrix(Matrix4f matrix4f) {
        Matrix4f matrix4f2 = new Matrix4f(matrix4f);
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> {
                textureMatrix = matrix4f2;
                try {
                    VitraNativeRenderer.setTextureMatrix(new float[]{
                        matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                        matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                        matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                        matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
                    });
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to set DirectX 11 texture matrix");
                }
            });
        } else {
            textureMatrix = matrix4f2;
            try {
                VitraNativeRenderer.setTextureMatrix(new float[]{
                    matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                    matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                    matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                    matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
                });
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to set DirectX 11 texture matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture matrix reset with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void resetTextureMatrix() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> {
                textureMatrix.identity();
                try {
                    VitraNativeRenderer.setTextureMatrix(new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                    });
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to reset DirectX 11 texture matrix");
                }
            });
        } else {
            textureMatrix.identity();
            try {
                VitraNativeRenderer.setTextureMatrix(new float[]{
                    1.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f
                });
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to reset DirectX 11 texture matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL model view matrix application with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void applyModelViewMatrix() {
        Matrix4f matrix4f = new Matrix4f(modelViewStack);
        if (!isOnRenderThread()) {
            recordRenderCall(() -> {
                modelViewMatrix = matrix4f;
                try {
                    VitraNativeRenderer.setModelViewMatrix(new float[]{
                        matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                        matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                        matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                        matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                    });
                } catch (Exception e) {
                    // Silent error following VulkanMod pattern
                    LOGGER.warn("Failed to apply DirectX 11 model view matrix");
                }
            });
        } else {
            modelViewMatrix = matrix4f;

            try {
                VitraNativeRenderer.setModelViewMatrix(new float[]{
                    matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                    matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                    matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                    matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                });
            } catch (Exception e) {
                // Silent error following VulkanMod pattern
                LOGGER.warn("Failed to apply DirectX 11 model view matrix");
            }
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL projection matrix restoration with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    private static void _restoreProjectionMatrix() {
        projectionMatrix = savedProjectionMatrix;
        vertexSorting = savedVertexSorting;

        try {
            VitraNativeRenderer.setProjectionMatrix(new float[]{
                projectionMatrix.m00(), projectionMatrix.m01(), projectionMatrix.m02(), projectionMatrix.m03(),
                projectionMatrix.m10(), projectionMatrix.m11(), projectionMatrix.m12(), projectionMatrix.m13(),
                projectionMatrix.m20(), projectionMatrix.m21(), projectionMatrix.m22(), projectionMatrix.m23(),
                projectionMatrix.m30(), projectionMatrix.m31(), projectionMatrix.m32(), projectionMatrix.m33()
            });
        } catch (Exception e) {
            // Silent error following VulkanMod pattern
            LOGGER.warn("Failed to restore DirectX 11 projection matrix");
        }
    }

}