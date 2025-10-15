package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Method;

/**
 * LWJGL GL13 mixin that intercepts multitexturing operations
 * Handles glActiveTexture for multitexturing support
 */
@Mixin(GL13.class)
public class GL13Mixin {

    /**
     * @author Vitra
     * @reason Intercept active texture (multitexturing support)
     */
    @Overwrite(remap = false)
    public static void glActiveTexture(@NativeType("GLenum") int texture) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glActiveTexture(texture);
    }
}