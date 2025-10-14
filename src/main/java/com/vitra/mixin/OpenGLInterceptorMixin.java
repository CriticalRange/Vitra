package com.vitra.mixin;

import com.vitra.render.opengl.GLInterceptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * OpenGL interception mixin that intercepts calls at Minecraft's GlStateManager level
 * This targets the actual OpenGL wrapper methods used by Minecraft 1.21.8
 */
public class OpenGLInterceptorMixin {

    /**
     * Mixin for GlStateManager class to intercept texture operations
     * Based on Mojang mappings for Minecraft 1.21.8
     */
    @Mixin(com.mojang.blaze3d.opengl.GlStateManager.class)
    public static class GlStateManagerMixin {

        @Inject(method = "_genTexture", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptGenTexture(CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // Create texture through our system
                ci.cancel();
            }
        }

        @Inject(method = "_bindTexture", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBindTexture(int texture, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glBindTexture(3553 /* GL_TEXTURE_2D */, texture);
                ci.cancel();
            }
        }

        @Inject(method = "_texImage2D", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptTexImage2D(int target, int level, int internalformat,
                                               int width, int height, int border,
                                               int format, int type, long pixels, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // Handle texture upload
                ci.cancel();
            }
        }

        @Inject(method = "_drawArrays", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDrawArrays(int mode, int first, int count, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glDrawArrays(mode, first, count);
                ci.cancel();
            }
        }

        @Inject(method = "_drawElements", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDrawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // Convert long indices to ByteBuffer for GLInterceptor
                ByteBuffer indicesBuffer = null; // Need to convert from long address
                GLInterceptor.glDrawElements(mode, count, type, indicesBuffer);
                ci.cancel();
            }
        }

        @Inject(method = "_enable", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptEnable(int cap, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glEnable(cap);
                ci.cancel();
            }
        }

        @Inject(method = "_disable", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDisable(int cap, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glDisable(cap);
                ci.cancel();
            }
        }

        @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptViewport(int x, int y, int width, int height, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glViewport(x, y, width, height);
                ci.cancel();
            }
        }

        @Inject(method = "_clearColor", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptClearColor(float r, float g, float b, float a, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glClearColor interceptor
                GLInterceptor.glClearColor(r, g, b, a);
                ci.cancel();
            }
        }

        @Inject(method = "_clear", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptClear(int mask, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glClear(mask);
                ci.cancel();
            }
        }

        // Buffer operations
        @Inject(method = "_glGenBuffers", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptGenBuffers(CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                ci.cancel();
            }
        }

        @Inject(method = "_glBindBuffer", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBindBuffer(int target, int buffer, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glBindBuffer(target, buffer);
                ci.cancel();
            }
        }

        @Inject(method = "_glBufferData", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBufferData(int target, long size, int usage, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // Handle buffer data
                ci.cancel();
            }
        }

        // ========================================================================
        // MISSING UNIFORM INTERCEPTORS (FIX: Missing - causes ray artifacts)
        // ========================================================================

        @Inject(method = "_uniform4f", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptUniform4f(int location, float v0, float v1, float v2, float v3, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glUniform4f interceptor
                GLInterceptor.glUniform4f(location, v0, v1, v2, v3);
                ci.cancel();
            }
        }

        @Inject(method = "_uniformMatrix4fv", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptUniformMatrix4fv(int location, int count, boolean transpose,
                                                    java.nio.FloatBuffer value, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glUniformMatrix4fv interceptor
                if (value != null) {
                    GLInterceptor.glUniformMatrix4fv(location, count, transpose, value);
                }
                ci.cancel();
            }
        }

        @Inject(method = "_uniform1i", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptUniform1i(int location, int v0, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glUniform1i interceptor
                GLInterceptor.glUniform1i(location, v0);
                ci.cancel();
            }
        }

        @Inject(method = "_uniform1f", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptUniform1f(int location, float v0, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glUniform1f interceptor
                GLInterceptor.glUniform1f(location, v0);
                ci.cancel();
            }
        }

        @Inject(method = "_useProgram", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptUseProgram(int program, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                // FIX: Call the missing glUseProgram interceptor
                GLInterceptor.glUseProgram(program);
                ci.cancel();
            }
        }
    }
}