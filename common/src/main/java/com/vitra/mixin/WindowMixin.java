package com.vitra.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.backend.BgfxWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(Window.class)
public class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraWindowMixin");

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void ensureGlfwInitialized(CallbackInfo ci) {
        // Initialize GLFW immediately before Window constructor uses it
        if (!org.lwjgl.glfw.GLFW.glfwInit()) {
            LOGGER.error("Failed to initialize GLFW for BGFX DirectX 11 in Window constructor");
            throw new RuntimeException("GLFW initialization failed for BGFX DirectX 11");
        }
        LOGGER.info("GLFW ensured to be initialized for BGFX DirectX 11 window creation");
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onWindowInit(CallbackInfo ci) {
        LOGGER.info("Window created - initializing custom Vitra context");

        // Get the window handle
        Window window = (Window)(Object)this;
        long windowHandle = window.getWindow();

        // Skip VitraContextManager initialization - BgfxRenderContext handles BGFX initialization
        // VitraContextManager.initializeCustomContext() interferes with DirectX 11 backend selection
        LOGGER.info("✓ Skipping VitraContextManager - BgfxRenderContext will handle BGFX DirectX 11 initialization");

        // Also register with BGFX wrapper
        BgfxWindow.getInstance().wrapWindow(window);
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void redirectUpdateDisplay(CallbackInfo ci) {
        LOGGER.debug("*** WINDOW MIXIN INTERCEPT: Blocking original updateDisplay - using BGFX DirectX 11 instead ***");

        // Cancel the original OpenGL swap and use our BGFX DirectX 11 instead
        ci.cancel();

        // Only use the working BGFX window handler (no duplicate frame submission)
        BgfxWindow.getInstance().handleUpdateDisplay();
    }

    @Redirect(method = "updateDisplay", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(JLcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void redirectFlipFrame(long window, TracyFrameCapture frameCapture) {
        LOGGER.debug("*** BLOCKED RenderSystem.flipFrame() inside updateDisplay - preventing glfwSwapBuffers ***");
        LOGGER.debug("This eliminates the dual framebuffer issue causing dual RivaTuner attachments");

        // Use BGFX frame submission instead of OpenGL swap
        BgfxWindow.getInstance().handleUpdateDisplay();

        // Handle Tracy frame capture if provided
        if (frameCapture != null) {
            frameCapture.endFrame();
        }

        // Poll events like original flipFrame does
        org.lwjgl.glfw.GLFW.glfwPollEvents();
    }
}