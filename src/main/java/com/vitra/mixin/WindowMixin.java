package com.vitra.mixin;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.vitra.VitraMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Priority 2: Window Creation Mixin
 * Captures GLFW window handle after creation and initializes DirectX 11 JNI
 */
@Mixin(Window.class)
public class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("WindowMixin");

    /**
     * Inject after Window constructor to capture window handle and initialize DirectX 11 JNI
     * Based on Context7: Window(WindowEventHandler, ScreenManager, DisplayData, String, String)
     */
    @Inject(
        method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/ScreenManager;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;)V",
        at = @At("RETURN")
    )
    private void onWindowCreated(
        WindowEventHandler eventHandler,
        ScreenManager screenManager,
        DisplayData displayData,
        String preferredFullscreenVideoMode,
        String title,
        CallbackInfo ci
    ) {
        Window thisWindow = (Window)(Object)this;
        long windowHandle = thisWindow.getWindow();

        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  WINDOW CREATION DETECTED                                  ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Window Handle:  0x{}", Long.toHexString(windowHandle));
        LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
        LOGGER.info("║ Window Title:   {}", title);
        LOGGER.info("║ Display Mode:   {}", preferredFullscreenVideoMode != null ? preferredFullscreenVideoMode : "Windowed");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        if (windowHandle != 0L) {
            try {
                long startTime = System.nanoTime();
                LOGGER.info("[TRACE] Attempting DirectX 11 JNI initialization...");

                // Initialize DirectX 11 JNI with the window handle
                if (VitraMod.getRenderer() != null) {
                    LOGGER.info("[TRACE] VitraRenderer found, calling initializeWithWindowHandle()");

                    boolean success = VitraMod.getRenderer().initializeWithWindowHandle(windowHandle);

                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                    if (success) {
                        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                        LOGGER.info("║  DIRECTX 11 JNI INITIALIZATION SUCCESS                      ║");
                        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                        LOGGER.info("║ Time Taken:  {} ms", elapsedMs);
                        LOGGER.info("║ Backend:     DirectX 11");
                        LOGGER.info("║ Thread:      {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

                        // Note: DirectX 11 state is synchronized internally by the renderer
                    } else {
                        LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                        LOGGER.error("║  DIRECTX 11 JNI INITIALIZATION FAILED                     ║");
                        LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                        LOGGER.error("║ Time Taken:  {} ms", elapsedMs);
                        LOGGER.error("║ Window:      0x{}", Long.toHexString(windowHandle));
                        LOGGER.error("║ Thread:      {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                        LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                        LOGGER.error("║ This is likely why you're seeing a gray/black screen!     ║");
                        LOGGER.error("║ Check DirectX 11 JNI callback logs above for the root cause.║");
                        LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                        System.err.println("[CRITICAL] DirectX 11 JNI initialization failed - check logs above!");
                    }
                } else {
                    LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                    LOGGER.error("║  VITRA RENDERER NOT AVAILABLE                              ║");
                    LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                    LOGGER.error("║ VitraMod.getRenderer() returned null");
                    LOGGER.error("║ Did VitraMod.init() fail to initialize the renderer?      ║");
                    LOGGER.error("║ Check logs for VitraMod initialization errors.            ║");
                    LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                    System.err.println("[CRITICAL] VitraRenderer not available!");
                }
            } catch (Exception e) {
                LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                LOGGER.error("║  EXCEPTION DURING DIRECTX 11 JNI INITIALIZATION             ║");
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ Exception: {}", e.getClass().getName());
                LOGGER.error("║ Message:   {}", e.getMessage());
                LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                LOGGER.error("Full stack trace:", e);
                System.err.println("[CRITICAL] Exception during DirectX 11 JNI init: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            LOGGER.error("╔════════════════════════════════════════════════════════════╗");
            LOGGER.error("║  INVALID WINDOW HANDLE                                     ║");
            LOGGER.error("╠════════════════════════════════════════════════════════════╣");
            LOGGER.error("║ Window handle is 0 (NULL)");
            LOGGER.error("║ GLFW window creation may have failed");
            LOGGER.error("║ Check GLFW error logs above for the cause");
            LOGGER.error("╚════════════════════════════════════════════════════════════╝");
            System.err.println("[CRITICAL] Invalid window handle (0)!");
        }
    }

    /**
     * Inject before Window.close() to ensure proper DirectX 11 JNI shutdown
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onWindowClose(CallbackInfo ci) {
        try {
            LOGGER.info("Window closing - ensuring DirectX 11 JNI shutdown");
            if (VitraMod.getRenderer() != null) {
                VitraMod.getRenderer().shutdown();
            }
        } catch (Exception e) {
            LOGGER.error("Exception during DirectX 11 JNI shutdown in WindowMixin", e);
        }
    }
}
