package com.vitra.render.backend;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.vitra.VitraMod;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX D3D11 window wrapper that handles BGFX frame submission
 */
public class BgfxWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxWindow");
    private static BgfxWindow instance;

    private Window wrappedWindow;
    private boolean bgfxInitialized = false;

    private BgfxWindow() {
        // Private constructor for singleton
    }

    public static BgfxWindow getInstance() {
        if (instance == null) {
            instance = new BgfxWindow();
        }
        return instance;
    }

    public void wrapWindow(Window window) {
        this.wrappedWindow = window;
        LOGGER.info("Wrapped GLFW window with BGFX D3D11 handler");
    }

    public void handleUpdateDisplay() {
        if (!bgfxInitialized) {
            LOGGER.info("First frame - ensuring BGFX D3D11 is ready");
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                bgfxInitialized = true;
                LOGGER.info("BGFX D3D11 confirmed ready for rendering");
            }
        }

        // Handle BGFX frame submission
        try {
            if (bgfxInitialized && VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                // Touch view 0 to ensure it renders this frame
                BGFX.bgfx_touch(0);
                // Submit BGFX frame instead of OpenGL swap
                BGFX.bgfx_frame(false);
            } else {
            }
        } catch (Exception e) {
            LOGGER.error("Error during BGFX frame submission", e);
        }
    }

    public void shutdown() {
        LOGGER.info("Shutting down BGFX window wrapper");
        bgfxInitialized = false;

        if (VitraMod.getRenderer() != null) {
            VitraMod.getRenderer().shutdown();
        }
    }
}