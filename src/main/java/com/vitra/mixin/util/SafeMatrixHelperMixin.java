package com.vitra.mixin.util;

import org.joml.Matrix4f;
import com.vitra.render.VitraRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AGGRESSIVE Matrix helper mixin - NO SAFETY NETS
 * Direct DirectX 11 optimization without fallback
 * If this fails, let it crash - that's the point
 */
@Mixin(Matrix4f.class)
public class SafeMatrixHelperMixin {

    @Inject(method = "mul(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;", at = @At("HEAD"))
    private void onMatrixMultiply(Matrix4f other, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeMatrixMultiplication(this, other);
        }
    }

    @Inject(method = "invert", at = @At("HEAD"))
    private void onMatrixInvert(CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeMatrixInversion(this);
        }
    }

    @Inject(method = "transpose", at = @At("HEAD"))
    private void onMatrixTranspose(CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeMatrixTranspose(this);
        }
    }

    @Inject(method = "setOrthographic", at = @At("HEAD"))
    private void onSetOrthographic(float left, float right, float bottom, float top, float zNear, float zFar, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().adjustOrthographicProjection(left, right, bottom, top, zNear, zFar);
        }
    }

    @Inject(method = "setPerspective", at = @At("HEAD"))
    private void onSetPerspective(float fovy, float aspect, float zNear, float zFar, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().adjustPerspectiveProjection(fovy, aspect, zNear, zFar);
        }
    }

    @Inject(method = "translation", at = @At("HEAD"))
    private static void onTranslation(float x, float y, float z, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeTranslationMatrix(x, y, z);
        }
    }

    @Inject(method = "scale", at = @At("HEAD"))
    private static void onScale(float x, float y, float z, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeScaleMatrix(x, y, z);
        }
    }

    @Inject(method = "rotationX", at = @At("HEAD"))
    private static void onRotationX(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeRotationMatrix(angle, 1, 0, 0);
        }
    }

    @Inject(method = "rotationY", at = @At("HEAD"))
    private static void onRotationY(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeRotationMatrix(angle, 0, 1, 0);
        }
    }

    @Inject(method = "rotationZ", at = @At("HEAD"))
    private static void onRotationZ(float angle, CallbackInfoReturnable<Matrix4f> cir) {
        // Direct optimization - no safety checks
        if (VitraRenderer.getInstance() != null) {
            VitraRenderer.getInstance().optimizeRotationMatrix(angle, 0, 0, 1);
        }
    }
}