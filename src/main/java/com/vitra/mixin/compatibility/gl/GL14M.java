package com.vitra.mixin.compatibility.gl;

import com.vitra.render.VitraRenderer;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GL14.class)
public class GL14M {

    // Helper to get renderer instance (with null-safety check)
    private static VitraRenderer getRenderer() {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer == null) {
            throw new IllegalStateException("VitraRenderer not initialized yet. Ensure renderer is initialized before OpenGL calls.");
        }
        return renderer;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBlendFuncSeparate(@NativeType("GLenum") int sfactorRGB, @NativeType("GLenum") int dfactorRGB, @NativeType("GLenum") int sfactorAlpha, @NativeType("GLenum") int dfactorAlpha) {
        getRenderer().blendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }
}
