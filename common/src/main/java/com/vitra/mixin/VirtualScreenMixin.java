package com.vitra.mixin;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.vitra.VitraMod;
import com.vitra.render.backend.BgfxWindow;
import net.minecraft.client.renderer.VirtualScreen;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to intercept window creation and initialize BGFX before GLFW
 */
@Mixin(VirtualScreen.class)
public class VirtualScreenMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraVirtualScreenMixin");

    /**
     * Initialize BGFX D3D11 before window creation
     */
    @Inject(method = "newWindow", at = @At("RETURN"))
    private void initializeBgfxAfterWindow(CallbackInfoReturnable<Window> cir) {
        LOGGER.info("Window created successfully - initializing BGFX D3D11 with actual window handle");

        try {
            Window window = cir.getReturnValue();
            if (window != null) {
                // Get the GLFW window handle - use reflection to access internal handle since GLFW_NO_API makes getWindow() return 0
                long glfwWindow = window.getWindow();
                LOGGER.info("window.getWindow() returned: 0x{} (expected 0 with GLFW_NO_API)", Long.toHexString(glfwWindow));

                if (glfwWindow == 0) {
                    // Use reflection to get the actual GLFW window handle from Window object
                    try {
                        java.lang.reflect.Field windowField = window.getClass().getDeclaredField("window");
                        windowField.setAccessible(true);
                        glfwWindow = (Long) windowField.get(window);
                        LOGGER.info("Retrieved GLFW window handle via reflection: 0x{}", Long.toHexString(glfwWindow));
                    } catch (Exception e) {
                        LOGGER.error("Failed to get GLFW window handle via reflection", e);
                        return;
                    }
                }

                long windowHandle = 0;
                if (glfwWindow != 0) {
                    // Get the native Windows HWND from GLFW window handle
                    windowHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                    LOGGER.info("GLFW window: 0x{}, Native Win32 HWND: 0x{}", Long.toHexString(glfwWindow), Long.toHexString(windowHandle));
                } else {
                    LOGGER.error("Unable to obtain GLFW window handle");
                    return;
                }

                // Initialize BGFX D3D11 with the actual window handle
                if (VitraMod.getRenderer() != null && windowHandle != 0) {
                    // Set the window handle for BGFX initialization
                    boolean success = initializeBgfxWithWindowHandle(windowHandle);
                    if (success) {
                        LOGGER.info("Successfully initialized BGFX D3D11 with window handle");

                        // Register the window with our BGFX wrapper
                        BgfxWindow.getInstance().wrapWindow(window);
                    } else {
                        LOGGER.warn("BGFX D3D11 initialization failed with window handle");
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Exception during BGFX D3D11 window initialization", e);
        }
    }

    private boolean initializeBgfxWithWindowHandle(long windowHandle) {
        try {
            if (VitraMod.getRenderer() != null) {
                // Force initialization with the window handle
                return VitraMod.getRenderer().initializeWithWindowHandle(windowHandle);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize BGFX with window handle", e);
        }
        return false;
    }
}