package com.vitra.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to disable GLFW OpenGL context creation for BGFX D3D11 compatibility
 */
@Mixin(value = GLFW.class, remap = false)
public class GLFWWindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGLFWMixin");

    /**
     * Set GLFW_NO_API hint before glfwCreateWindow to disable OpenGL context
     */
    @Inject(method = "glfwCreateWindow(IILjava/nio/ByteBuffer;JJ)J", at = @At("HEAD"))
    private static void disableGLFWOpenGLContext(int width, int height, java.nio.ByteBuffer title, long monitor, long share, CallbackInfoReturnable<Long> cir) {
        LOGGER.info("Intercepting glfwCreateWindow - disabling OpenGL context for BGFX D3D11 compatibility");

        try {
            // Set GLFW_CLIENT_API to GLFW_NO_API to disable OpenGL context creation
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            LOGGER.info("Successfully set GLFW_NO_API - window will be created without OpenGL context");
        } catch (Exception e) {
            LOGGER.error("Failed to set GLFW_NO_API hint", e);
        }
    }
}