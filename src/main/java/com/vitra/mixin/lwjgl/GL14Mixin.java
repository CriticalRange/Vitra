package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * LWJGL GL14 mixin that intercepts OpenGL 1.4 operations at the driver level
 * Based on VulkanMod's approach but adapted for DirectX 11 backend
 *
 * OpenGL 1.4 features intercepted:
 * - Blending functions (glBlendEquation, glBlendFuncSeparate)
 */
@Mixin(GL14.class)
public class GL14Mixin {

    /**
     * @author Vitra
     * @reason Intercept blending equation for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBlendEquation(@NativeType("GLenum") int mode) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBlendEquation(mode);
    }

    /**
     * @author Vitra
     * @reason Intercept blend function separate (advanced blending) (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBlendFuncSeparate(@NativeType("GLenum") int sfactorRGB, @NativeType("GLenum") int dfactorRGB, @NativeType("GLenum") int sfactorAlpha, @NativeType("GLenum") int dfactorAlpha) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }
}