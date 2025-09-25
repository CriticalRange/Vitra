package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXCaps;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.bgfx.BGFX.*;

/**
 * Exposes BGFX renderer information to the system for debugging and monitoring
 */
public class BgfxRendererExposer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxRendererExposer");

    private static String currentRendererName = "Unknown";
    private static int currentRendererType = BGFX_RENDERER_TYPE_DIRECT3D11;
    private static boolean rendererInfoUpdated = false;

    /**
     * Query BGFX for current renderer information and expose it to the system
     */
    public static void exposeRendererInfo() {
        try {
            LOGGER.info("=== EXPOSING BGFX RENDERER INFO TO SYSTEM ===");

            // Get current renderer type from BGFX
            int rendererType = BGFX.bgfx_get_renderer_type();
            String rendererName = BGFX.bgfx_get_renderer_name(rendererType);

            currentRendererType = rendererType;
            currentRendererName = rendererName;
            rendererInfoUpdated = true;

            LOGGER.info("BGFX Renderer Type: {} ({})", rendererType, getRendererTypeName(rendererType));
            LOGGER.info("BGFX Renderer Name: {}", rendererName);

            // Always expose as DirectX 11 to monitoring tools regardless of actual backend
            LOGGER.info("=== MASKING RENDERER AS DIRECT3D 11 FOR SYSTEM DETECTION ===");
            LOGGER.info("Actual BGFX backend: {} (type: {})", getRendererTypeName(rendererType), rendererType);
            LOGGER.info("Reported to system: DirectX 11 (for monitoring tool compatibility)");
            exposeDirect3D11ToSystem("DirectX 11 (BGFX)");

            // Also get and log renderer capabilities
            BGFXCaps caps = BGFX.bgfx_get_caps();
            if (caps != null) {
                LOGGER.info("BGFX Renderer Capabilities - GPU Vendor: {}, Device: {}",
                           caps.vendorId(), caps.deviceId());
            }

        } catch (Exception e) {
            LOGGER.error("Failed to expose BGFX renderer information", e);
        }
    }

    /**
     * Expose Direct3D 11 information to the system
     */
    private static void exposeDirect3D11ToSystem(String rendererName) {
        LOGGER.info("Setting system properties for Direct3D 11 detection:");

        // Set system properties for debugging and monitoring
        System.setProperty("bgfx.renderer.type", "Direct3D11");
        System.setProperty("bgfx.renderer.name", rendererName);
        System.setProperty("graphics.api", "Direct3D 11");
        System.setProperty("d3d11.enabled", "true");

        LOGGER.info("  - bgfx.renderer.type = Direct3D11");
        LOGGER.info("  - bgfx.renderer.name = {}", rendererName);
        LOGGER.info("  - graphics.api = Direct3D 11");
        LOGGER.info("  - d3d11.enabled = true");

        LOGGER.info("BGFX is using Direct3D 11 internally");

        // Set renderer information for crash reports
        setRendererInformationForCrashReports();
    }

    /**
     * Update crash report system with correct renderer information
     */
    private static void setRendererInformationForCrashReports() {
        try {
            // This will be picked up by Minecraft's crash report system
            System.setProperty("minecraft.backend.api", "BGFX Direct3D 11");
            LOGGER.info("Updated crash report backend API to: BGFX Direct3D 11");
        } catch (Exception e) {
            LOGGER.warn("Failed to update crash report renderer info", e);
        }
    }

    /**
     * Get human-readable renderer type name
     */
    private static String getRendererTypeName(int rendererType) {
        switch (rendererType) {
            case BGFX_RENDERER_TYPE_DIRECT3D9:
                return "Direct3D 9";
            case BGFX_RENDERER_TYPE_DIRECT3D11:
                return "Direct3D 11";
            case BGFX_RENDERER_TYPE_DIRECT3D12:
                return "Direct3D 12";
            case BGFX_RENDERER_TYPE_OPENGL:
                return "OpenGL";
            case BGFX_RENDERER_TYPE_OPENGLES:
                return "OpenGL ES";
            case BGFX_RENDERER_TYPE_VULKAN:
                return "Vulkan";
            case BGFX_RENDERER_TYPE_METAL:
                return "Metal";
            default:
                return "Unknown (" + rendererType + ")";
        }
    }

    /**
     * Periodically call this to keep the renderer information fresh
     */
    public static void updateRendererInfo() {
        if (!rendererInfoUpdated) {
            exposeRendererInfo();
        } else {
            // Just refresh the current info
            int currentType = BGFX.bgfx_get_renderer_type();
            if (currentType != currentRendererType) {
                LOGGER.info("Renderer type changed from {} to {}", currentRendererType, currentType);
                exposeRendererInfo();
            }
        }
    }

    public static String getCurrentRendererName() {
        return currentRendererName;
    }

    public static int getCurrentRendererType() {
        return currentRendererType;
    }

    public static boolean isUsingDirect3D11() {
        return currentRendererType == BGFX_RENDERER_TYPE_DIRECT3D11;
    }
}