package com.vitra.mixin.debug;

import com.mojang.blaze3d.platform.GlUtil;
import com.vitra.render.VitraRenderer;
import com.vitra.util.SystemInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Renderer-agnostic Debug Info Mixin
 *
 * Based on VulkanMod's GlDebugInfoM but adapted for DirectX 11.
 * Overrides OpenGL debug info methods to return DirectX 11 device information.
 *
 * Key responsibilities:
 * - Override GlUtil.getVendor() to return DirectX 11 GPU vendor
 * - Override GlUtil.getRenderer() to return DirectX 11 GPU name
 * - Override GlUtil.getOpenGLVersion() to return DirectX 11 driver version
 * - Override GlUtil.getCpuInfo() to return CPU information
 *
 * This ensures that when Minecraft queries for OpenGL information (e.g., in crash reports,
 * F3 debug screen, logs), it receives DirectX 11 device information instead.
 */
@Mixin(GlUtil.class)
public class GlDebugInfoMixin {

    // Helper to get renderer instance (with null-safety check)
    private static VitraRenderer getVitraRenderer() {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VitraRenderer not initialized yet. Ensure renderer is initialized before OpenGL calls.");
        }
        return renderer;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL vendor query with DirectX 11 GPU vendor
     *
     * Returns the GPU vendor ID string from DirectX 11 device.
     * Examples: "NVIDIA", "AMD", "Intel"
     *
     * @return DirectX 11 GPU vendor name or "n/a" if unavailable
     */
    @Overwrite
    public static String getVendor() {
        try {
            String deviceInfo = getVitraRenderer().getDeviceInfo();
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                // Parse device info for vendor line
                // Expected format: "GPU: <vendor> <model>"
                String[] lines = deviceInfo.split("\n");
                for (String line : lines) {
                    if (line.startsWith("GPU:")) {
                        // Extract vendor from "GPU: NVIDIA GeForce RTX 3080"
                        String gpuName = line.substring(4).trim();
                        if (gpuName.contains(" ")) {
                            return gpuName.split(" ")[0]; // Return first word (vendor)
                        }
                        return gpuName;
                    }
                }
            }
        } catch (Exception e) {
            // Silent failure - return fallback
        }
        return "DirectX 11";
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL renderer query with DirectX 11 GPU name
     *
     * Returns the full GPU device name from DirectX 11.
     * Example: "NVIDIA GeForce RTX 3080"
     *
     * @return DirectX 11 GPU device name or "n/a" if unavailable
     */
    @Overwrite
    public static String getRenderer() {
        try {
            String deviceInfo = getVitraRenderer().getDeviceInfo();
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                // Parse device info for GPU line
                String[] lines = deviceInfo.split("\n");
                for (String line : lines) {
                    if (line.startsWith("GPU:")) {
                        return line.substring(4).trim();
                    }
                }
            }
        } catch (Exception e) {
            // Silent failure - return fallback
        }
        return "DirectX 11 (device unavailable)";
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL version query with DirectX 11 driver version
     *
     * Returns the DirectX 11 driver version from the device.
     * Example: "31.0.15.4601" (NVIDIA driver version)
     *
     * @return DirectX 11 driver version or "n/a" if unavailable
     */
    @Overwrite
    public static String getOpenGLVersion() {
        try {
            String deviceInfo = getVitraRenderer().getDeviceInfo();
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                // Parse device info for driver version line
                String[] lines = deviceInfo.split("\n");
                for (String line : lines) {
                    if (line.startsWith("Driver:") || line.contains("Driver Version:")) {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex >= 0) {
                            return line.substring(colonIndex + 1).trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silent failure - return fallback
        }
        return "DirectX 11";
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Return CPU information from SystemInfo
     *
     * Returns CPU model and core count.
     * Example: "Intel Core i7-10700K (16 cores)"
     *
     * @return CPU information string
     */
    @Overwrite
    public static String getCpuInfo() {
        return SystemInfo.cpuInfo != null ? SystemInfo.cpuInfo : "n/a";
    }
}
