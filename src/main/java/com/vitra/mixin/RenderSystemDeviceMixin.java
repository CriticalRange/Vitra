package com.vitra.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.DynamicUniforms;
import com.vitra.render.bgfx.VitraGpuDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Priority 3: Device Replacement Mixin
 * Replaces RenderSystem.getDevice() to return VitraGpuDevice (BGFX) instead of GlDevice (OpenGL)
 *
 * CRITICAL: This ensures all rendering code uses BGFX instead of OpenGL
 *
 * STRATEGY: Following VulkanMod's approach - use @Overwrite for complete replacement
 * This ensures NO OpenGL device code executes at all
 */
@Mixin(RenderSystem.class)
public class RenderSystemDeviceMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderSystemDeviceMixin");
    private static boolean deviceReplacementLogged = false;
    private static boolean dynamicUniformsLogged = false;

    @Shadow
    private static DynamicUniforms dynamicUniforms;

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL device with BGFX device
     *
     * Original: Returns GlDevice (OpenGL rendering device)
     * Replacement: Returns VitraGpuDevice (BGFX DirectX 11 device)
     */
    @Overwrite(remap = false)
    public static GpuDevice getDevice() {
        GpuDevice vitraDevice = VitraGpuDevice.getInstance();

        if (!deviceReplacementLogged) {
            LOGGER.info("RenderSystem.getDevice() - returning VitraGpuDevice (BGFX DirectX 11) instead of GlDevice (OpenGL)");
            deviceReplacementLogged = true;
        }

        return vitraDevice;
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL DynamicUniforms initialization with BGFX version
     *
     * Original: Returns dynamicUniforms field or throws IllegalStateException if not initialized
     * Replacement: Creates and returns DynamicUniforms for BGFX device
     *
     * CRITICAL: We must set the static field `dynamicUniforms` because flipFrame() accesses it directly
     * instead of calling getDynamicUniforms().
     */
    @Overwrite(remap = false)
    public static DynamicUniforms getDynamicUniforms() {
        // If dynamicUniforms is already initialized, return it
        if (dynamicUniforms != null) {
            return dynamicUniforms;
        }

        if (!dynamicUniformsLogged) {
            LOGGER.info("RenderSystem.getDynamicUniforms() - creating DynamicUniforms for BGFX device");
            dynamicUniformsLogged = true;
        }

        // Create a new DynamicUniforms instance and set the static field
        // This is critical because flipFrame() directly accesses the field
        dynamicUniforms = new DynamicUniforms();

        return dynamicUniforms;
    }
}
