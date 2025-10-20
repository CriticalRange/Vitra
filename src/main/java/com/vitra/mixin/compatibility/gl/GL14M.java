package com.vitra.mixin.compatibility.gl;

import com.vitra.render.jni.VitraNativeRenderer;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GL14.class)
public class GL14M {

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBlendFuncSeparate(@NativeType("GLenum") int sfactorRGB, @NativeType("GLenum") int dfactorRGB, @NativeType("GLenum") int sfactorAlpha, @NativeType("GLenum") int dfactorAlpha) {
        VitraNativeRenderer.blendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }
}
