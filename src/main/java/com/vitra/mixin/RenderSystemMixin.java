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
 * RenderSystem Mixin for DirectX 11 (Minecraft 1.21.1)
 *
 * Following VulkanMod's proven approach for Minecraft 1.21.1:
 * - Use @Overwrite for critical initialization methods (initRenderer, setupDefaultState)
 * - Handle matrix operations for 3D rendering
 * - Support lighting and color operations
 * - Intercept texture binding and shader operations
 *
 * This mixin replaces OpenGL calls with DirectX 11 JNI equivalents.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderSystemMixin");

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
     * @author Vitra
     * @reason Replace OpenGL renderer initialization with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void initRenderer(int debugVerbosity, boolean debugSync) {
        LOGGER.info("RenderSystem.initRenderer intercepted - initializing DirectX 11 JNI (1.21.1)");
        VitraNativeRenderer.initializeDirectX(0, 1920, 1080, debugVerbosity > 0);

        if (renderThread != null) {
            renderThread.setPriority(Thread.NORM_PRIORITY + 2);
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
     * @author Vitra
     * @reason Replace OpenGL max texture size query with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static int maxSupportedTextureSize() {
        return VitraNativeRenderer.getMaxTextureSize();
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL shader lights setup with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void setShaderLights(Vector3f dir0, Vector3f dir1) {
        shaderLightDirections[0] = dir0;
        shaderLightDirections[1] = dir1;

        VitraNativeRenderer.setShaderLightDirection(0, dir0.x(), dir0.y(), dir0.z());
        VitraNativeRenderer.setShaderLightDirection(1, dir1.x(), dir1.y(), dir1.z());
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

        VitraNativeRenderer.setShaderColor(r, g, b, a);
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

        VitraNativeRenderer.setShaderFogColor(f, g, h, i);
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
                RenderSystemMixin.projectionMatrix = matrix4f;
                // Note: vertexSorting is handled internally by RenderSystem in 1.21.1

                VitraNativeRenderer.setProjectionMatrix(new float[]{
                    matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                    matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                    matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                    matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                });
            });
        } else {
            RenderSystemMixin.projectionMatrix = matrix4f;
            // Note: vertexSorting is handled internally by RenderSystem in 1.21.1

            VitraNativeRenderer.setProjectionMatrix(new float[]{
                matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
            });
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
                VitraNativeRenderer.setTextureMatrix(new float[]{
                    matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                    matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                    matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                    matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
                });
            });
        } else {
            textureMatrix = matrix4f2;
            VitraNativeRenderer.setTextureMatrix(new float[]{
                matrix4f2.m00(), matrix4f2.m01(), matrix4f2.m02(), matrix4f2.m03(),
                matrix4f2.m10(), matrix4f2.m11(), matrix4f2.m12(), matrix4f2.m13(),
                matrix4f2.m20(), matrix4f2.m21(), matrix4f2.m22(), matrix4f2.m23(),
                matrix4f2.m30(), matrix4f2.m31(), matrix4f2.m32(), matrix4f2.m33()
            });
        }
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture matrix reset with DirectX 11 equivalent
     */
    @Overwrite(remap = false)
    public static void resetTextureMatrix() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> textureMatrix.identity());
        } else {
            textureMatrix.identity();
            VitraNativeRenderer.setTextureMatrix(new float[]{
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            });
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
                VitraNativeRenderer.setModelViewMatrix(new float[]{
                    matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                    matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                    matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                    matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
                });
            });
        } else {
            modelViewMatrix = matrix4f;

            VitraNativeRenderer.setModelViewMatrix(new float[]{
                matrix4f.m00(), matrix4f.m01(), matrix4f.m02(), matrix4f.m03(),
                matrix4f.m10(), matrix4f.m11(), matrix4f.m12(), matrix4f.m13(),
                matrix4f.m20(), matrix4f.m21(), matrix4f.m22(), matrix4f.m23(),
                matrix4f.m30(), matrix4f.m31(), matrix4f.m32(), matrix4f.m33()
            });
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

        VitraNativeRenderer.setProjectionMatrix(new float[]{
            projectionMatrix.m00(), projectionMatrix.m01(), projectionMatrix.m02(), projectionMatrix.m03(),
            projectionMatrix.m10(), projectionMatrix.m11(), projectionMatrix.m12(), projectionMatrix.m13(),
            projectionMatrix.m20(), projectionMatrix.m21(), projectionMatrix.m22(), projectionMatrix.m23(),
            projectionMatrix.m30(), projectionMatrix.m31(), projectionMatrix.m32(), projectionMatrix.m33()
        });
    }

  }
