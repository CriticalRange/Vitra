package com.vitra.mixin.util;

import org.joml.Matrix4f;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Matrix helper mixin for DirectX 11 matrix operations
 * Provides optimized matrix transformations for DirectX 11 coordinate system
 */
@Mixin(Matrix4f.class)
public class MatrixHelperMixin {

    private static int matrixOperationCount = 0;
    private static long totalMatrixTime = 0;

    @Inject(method = "mul(Lcom/mojang/math/Matrix4f;)Lcom/mojang/math/Matrix4f;", at = @At("HEAD"))
    private void onMatrixMultiply(Matrix4f other, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            matrixOperationCount++;
            long startTime = System.nanoTime();

            // Optimize matrix multiplication for DirectX 11
            VitraRenderer.optimizeMatrixMultiplication((Matrix4f)(Object)this, other);

            totalMatrixTime += System.nanoTime() - startTime;
        }
    }

    @Inject(method = "invert", at = @At("HEAD"))
    private void onMatrixInvert(CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize matrix inversion for DirectX 11
            VitraRenderer.optimizeMatrixInversion((Matrix4f)(Object)this);
        }
    }

    @Inject(method = "transpose", at = @At("HEAD"))
    private void onMatrixTranspose(CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize matrix transpose for DirectX 11
            VitraRenderer.optimizeMatrixTranspose((Matrix4f)(Object)this);
        }
    }

    @Inject(method = "setOrthographic", at = @At("HEAD"))
    private void onSetOrthographic(float f, float g, float h, float i, float j, float k, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Adjust orthographic projection for DirectX 11 coordinate system
            VitraRenderer.adjustOrthographicProjection(f, g, h, i, j, k);
        }
    }

    @Inject(method = "setPerspective", at = @At("HEAD"))
    private void onSetPerspective(float f, float g, float h, float i, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Adjust perspective projection for DirectX 11 coordinate system
            VitraRenderer.adjustPerspectiveProjection(f, g, h, i);
        }
    }

    @Inject(method = "translation", at = @At("HEAD"))
    private static void onTranslation(float x, float y, float z, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize translation matrix for DirectX 11
            VitraRenderer.optimizeTranslationMatrix(x, y, z);
        }
    }

    @Inject(method = "scale", at = @At("HEAD"))
    private static void onScale(float x, float y, float z, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize scale matrix for DirectX 11
            VitraRenderer.optimizeScaleMatrix(x, y, z);
        }
    }

    @Inject(method = "rotationX", at = @At("HEAD"))
    private static void onRotationX(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize rotation matrix for DirectX 11
            VitraRenderer.optimizeRotationMatrix(angle, 1, 0, 0);
        }
    }

    @Inject(method = "rotationY", at = @At("HEAD"))
    private static void onRotationY(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize rotation matrix for DirectX 11
            VitraRenderer.optimizeRotationMatrix(angle, 0, 1, 0);
        }
    }

    @Inject(method = "rotationZ", at = @At("HEAD"))
    private static void onRotationZ(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize rotation matrix for DirectX 11
            VitraRenderer.optimizeRotationMatrix(angle, 0, 0, 1);
        }
    }

    /**
     * Get matrix operation statistics
     */
    public static int getMatrixOperationCount() {
        return matrixOperationCount;
    }

    public static long getTotalMatrixTime() {
        return totalMatrixTime;
    }

    /**
     * Reset matrix statistics
     */
    public static void resetMatrixStats() {
        matrixOperationCount = 0;
        totalMatrixTime = 0;
    }

    /**
     * Check if matrix is DirectX 11 optimized
     */
    public static boolean isDirectX11Optimized(Matrix4f matrix) {
        return VitraConfig.getInstance().isDirectX11Enabled() &&
               VitraRenderer.isMatrixDirectX11Optimized(matrix);
    }
}