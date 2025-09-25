package com.vitra.mixin;

import com.mojang.blaze3d.platform.Window;
import com.vitra.render.backend.BgfxWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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

        // Initialize our custom context that properly exposes Direct3D 11
        boolean contextSuccess = com.vitra.render.context.VitraContextManager.initializeCustomContext(windowHandle);

        if (contextSuccess) {
            LOGGER.info("✓ Custom Vitra context initialized");
        } else {
            LOGGER.error("✗ Failed to initialize custom Vitra context");
        }

        // Also register with BGFX wrapper
        BgfxWindow.getInstance().wrapWindow(window);
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void redirectUpdateDisplay(CallbackInfo ci) {
        LOGGER.debug("Intercepting updateDisplay - using custom Vitra context");

        // Cancel the original OpenGL swap and use our custom context instead
        ci.cancel();

        // Handle frame rendering through our custom context
        com.vitra.render.context.VitraContextManager.handleFrameRender();

        // Also call BGFX window handler
        BgfxWindow.getInstance().handleUpdateDisplay();
    }
}