package com.vitra.mixin;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.Window;

/**
 * Priority 1: GLFW/OpenGL Context Prevention Mixin
 *
 * This mixin PREVENTS Minecraft from creating an OpenGL context entirely.
 * BGFX DirectX 11 will manage the rendering context using only the window handle.
 *
 * Key interceptions:
 * 1. Set GLFW_CLIENT_API to GLFW_NO_API before window creation
 *
 * CRITICAL: Minecraft 1.21.8 doesn't call glfwMakeContextCurrent or GL.createCapabilities
 * in the Window constructor anymore, so we only need to block window hints.
 */
@Mixin(value = Window.class, priority = 100)
public class GLFWContextMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GLFWContextMixin");

    /**
     * CRITICAL FIX: Block ALL glfwWindowHint() calls from Minecraft.
     *
     * VulkanMod Strategy:
     * - Minecraft normally calls glfwWindowHint() with OpenGL-specific hints
     *   (GLFW_CONTEXT_VERSION_MAJOR, GLFW_OPENGL_PROFILE, etc.)
     * - These OpenGL hints contaminate window creation even if we set GLFW_NO_API at HEAD
     * - Solution: Block ALL window hints, then set ONLY GLFW_NO_API right before glfwCreateWindow()
     *
     * This ensures GLFW creates a window with NO graphics API context at all.
     */
    @Redirect(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/ScreenManager;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"),
        remap = false
    )
    private void blockAllWindowHints(int hint, int value) {
        // Block ALL window hints from Minecraft
        // We only want GLFW_NO_API, which we set manually below
        LOGGER.debug("BLOCKED glfwWindowHint({}, {}) - preventing OpenGL context creation", hint, value);
    }

    /**
     * Set ONLY GLFW_NO_API right before glfwCreateWindow().
     *
     * This is the ONLY window hint we want to set. It tells GLFW to create
     * a window without any graphics API context (no OpenGL, no Vulkan).
     * BGFX will create its own DirectX 11 context after window creation.
     */
    @Inject(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/ScreenManager;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"),
        remap = false
    )
    private void setOnlyNoApiHint(CallbackInfo ci) {
        LOGGER.info("===== VITRA: Setting GLFW_CLIENT_API = GLFW_NO_API =====");
        LOGGER.info("This prevents GLFW from creating an OpenGL context");
        LOGGER.info("BGFX will create its own DirectX 11 context instead");

        // Tell GLFW not to create ANY graphics API context (OpenGL/OpenGL ES/Vulkan)
        // BGFX will create its own DirectX 11 context after window creation
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);

        LOGGER.info("GLFW window hint set: GLFW_CLIENT_API = GLFW_NO_API");
    }

    /**
     * Verify that no OpenGL context was created after constructor finishes.
     * Must inject at RETURN (after super()) to avoid static requirement.
     */
    @Inject(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/ScreenManager;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;)V",
        at = @At("RETURN")
    )
    private void onWindowConstructorReturn(CallbackInfo ci) {
        try {
            LOGGER.info("Window constructor completed - checking for OpenGL context");

            // Check if an OpenGL context was created despite our interception
            long currentContext = GLFW.glfwGetCurrentContext();
            if (currentContext != 0) {
                LOGGER.warn("WARNING: OpenGL context is active (0x{}) despite interception! BGFX may conflict.",
                    Long.toHexString(currentContext));
                LOGGER.warn("Attempting to clear OpenGL context...");
                GLFW.glfwMakeContextCurrent(0);
                LOGGER.info("Cleared OpenGL context - BGFX should now have exclusive rendering control");
            } else {
                LOGGER.info("SUCCESS: No OpenGL context active - BGFX has exclusive rendering control");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check/clear OpenGL context", e);
        }
    }
}
