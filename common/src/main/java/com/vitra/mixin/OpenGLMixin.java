package com.vitra.mixin;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggressive OpenGL interception to prevent any OpenGL operations
 * that might create a parallel framebuffer
 */
@Mixin(GL11.class)
public class OpenGLMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("OpenGLMixin");

    /**
     * Intercept all glClear calls to prevent OpenGL framebuffer clearing
     */
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "glClear(I)V"), remap = false)
    private static void redirectGlClear(int mask) {
        LOGGER.debug("*** BLOCKED OpenGL glClear({}) - using BGFX DirectX 11 instead", mask);
        // Do nothing - let BGFX handle clearing
    }

    /**
     * Intercept glClearColor to prevent OpenGL background color setting
     */
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "glClearColor(FFFF)V"), remap = false)
    private static void redirectGlClearColor(float red, float green, float blue, float alpha) {
        LOGGER.debug("*** BLOCKED OpenGL glClearColor({}, {}, {}, {}) - using BGFX DirectX 11 instead", red, green, blue, alpha);
        // Do nothing - let BGFX handle clear color
    }
}