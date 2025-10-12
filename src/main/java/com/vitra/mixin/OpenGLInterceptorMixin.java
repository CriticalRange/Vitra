package com.vitra.mixin;

import com.vitra.render.opengl.GLInterceptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * OpenGL interception mixin that intercepts calls at the Minecraft call level
 * This approach intercepts where Minecraft actually calls OpenGL functions
 */
public class OpenGLInterceptorMixin {

    /**
     * Mixin for GL11 class to intercept texture operations
     */
    @Mixin(GL11.class)
    public static class Gl11Mixin {

        @Inject(method = "glGenTextures", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptGenTextures(IntBuffer textures, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glGenTextures(textures);
                ci.cancel();
            }
        }

        @Inject(method = "glBindTexture", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBindTexture(int target, int texture, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glBindTexture(target, texture);
                ci.cancel();
            }
        }

        @Inject(method = "glTexImage2D", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptTexImage2D(int target, int level, int internalformat,
                                                int width, int height, int border,
                                                int format, int type, ByteBuffer pixels, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
                ci.cancel();
            }
        }

        @Inject(method = "glDrawArrays", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDrawArrays(int mode, int first, int count, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glDrawArrays(mode, first, count);
                ci.cancel();
            }
        }

        @Inject(method = "glDrawElements", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDrawElements(int mode, int count, int type, ByteBuffer indices, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glDrawElements(mode, count, type, indices);
                ci.cancel();
            }
        }

        @Inject(method = "glEnable", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptEnable(int cap, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glEnable(cap);
                ci.cancel();
            }
        }

        @Inject(method = "glDisable", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptDisable(int cap, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glDisable(cap);
                ci.cancel();
            }
        }

        @Inject(method = "glViewport", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptViewport(int x, int y, int width, int height, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glViewport(x, y, width, height);
                ci.cancel();
            }
        }

        @Inject(method = "glClear", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptClear(int mask, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glClear(mask);
                ci.cancel();
            }
        }
    }

    /**
     * Mixin for GL15 class to intercept buffer operations
     */
    @Mixin(GL15.class)
    public static class Gl15Mixin {

        @Inject(method = "glGenBuffers", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptGenBuffers(IntBuffer buffers, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glGenBuffers(buffers);
                ci.cancel();
            }
        }

        @Inject(method = "glBindBuffer", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBindBuffer(int target, int buffer, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glBindBuffer(target, buffer);
                ci.cancel();
            }
        }

        @Inject(method = "glBufferData", at = @At("HEAD"), cancellable = true, remap = false)
        private static void interceptBufferData(int target, long size, ByteBuffer data, int usage, CallbackInfo ci) {
            if (GLInterceptor.isActive()) {
                GLInterceptor.glBufferData(target, size, data, usage);
                ci.cancel();
            }
        }
    }
}