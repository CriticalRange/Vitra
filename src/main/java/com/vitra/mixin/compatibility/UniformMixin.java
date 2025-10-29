package com.vitra.mixin.compatibility;

import com.mojang.blaze3d.shaders.Uniform;
import com.vitra.render.VitraRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DirectX Uniform Upload Interception Mixin
 *
 * Based on VulkanMod's UniformM - intercepts Minecraft's Uniform.upload() calls
 * and redirects them to DirectX constant buffer updates instead of OpenGL glUniform calls.
 *
 * Critical mixin for rendering:
 * - Without this, no uniforms reach the GPU
 * - Shaders execute with uninitialized constant buffers
 * - Result: Yellow screen, black screen, or garbage rendering
 *
 * Data flow:
 * 1. Minecraft calls uniform.upload() (e.g., MODEL_VIEW_MATRIX.upload())
 * 2. This mixin CANCELS the OpenGL glUniform* call
 * 3. Instead, we trigger constant buffer update in VitraRenderer
 * 4. VitraRenderer calls suppliers to get current uniform values
 * 5. Values are copied to constant buffers via Map/Unmap
 * 6. Constant buffers are bound to shader stages
 *
 * Why cancel OpenGL calls?
 * - OpenGL glUniform* are no-ops in DirectX backend
 * - Prevents wasted CPU cycles
 * - Ensures all uniform updates go through our system
 */
@Mixin(Uniform.class)
public class UniformMixin {

    /**
     * Intercept Uniform.upload() - THE CRITICAL HOOK
     *
     * This is called every time Minecraft updates a shader uniform.
     * Examples:
     * - RenderSystem.setModelViewMatrix() → MODEL_VIEW_MATRIX.upload()
     * - RenderSystem.setShaderColor() → COLOR_MODULATOR.upload()
     * - Fog changes → FOG_START/FOG_END.upload()
     *
     * @param ci Callback info (used to cancel original OpenGL call)
     */
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    public void cancelUpload(CallbackInfo ci) {
        // Cancel OpenGL glUniform call
        ci.cancel();

        // Mark uniforms as dirty - will be uploaded before next draw call
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null) {
            renderer.markUniformsDirty();
        }
    }

    /**
     * Intercept uploadInteger - for sampler indices and integer uniforms
     */
    @Inject(method = "uploadInteger", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cancelUploadInteger(CallbackInfo ci) {
        ci.cancel();

        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null) {
            renderer.markUniformsDirty();
        }
    }

    /**
     * Return dummy uniform location.
     * DirectX doesn't use uniform locations - constant buffers use offsets instead.
     *
     * @author Vitra (based on VulkanMod)
     * @reason DirectX doesn't have uniform locations
     */
    @Overwrite
    public static int glGetUniformLocation(int program, CharSequence name) {
        return 1; // Dummy location (non-zero to indicate "success")
    }

    /**
     * Return dummy attribute location.
     * DirectX doesn't use attribute locations - input layout uses semantics instead.
     *
     * @author Vitra (based on VulkanMod)
     * @reason DirectX doesn't have attribute locations
     */
    @Overwrite
    public static int glGetAttribLocation(int program, CharSequence name) {
        return 0; // Dummy location
    }
}
