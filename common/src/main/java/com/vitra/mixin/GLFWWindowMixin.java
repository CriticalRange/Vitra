package com.vitra.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corrected VulkanMod approach with only existing Window constructor methods
 */
@Mixin(com.mojang.blaze3d.platform.Window.class)
public class GLFWWindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGLFWMixin");

    /**
     * Completely block ALL GLFW window hints to prevent OpenGL setup
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void blockAllGlfwWindowHints(int hint, int value) {
        LOGGER.info("BLOCKED GLFW window hint: {} = {} (Corrected VulkanMod approach)", hint, value);
    }

    /**
     * Force GLFW_NO_API before window creation
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void setNoApiBeforeWindowCreation(CallbackInfo ci) {
        LOGGER.info("=== FORCING GLFW_NO_API (Corrected VulkanMod approach) ===");
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        LOGGER.info("Set GLFW_CLIENT_API = GLFW_NO_API - No OpenGL context will be created");
    }

}