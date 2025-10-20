package com.vitra.mixin.render;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Shader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DirectX 11 ProgramManager compatibility mixin
 *
 * Prevents OpenGL shader program operations from being executed.
 * All shader linking, program creation, and program management is handled
 * by the DirectX 11 backend through VitraNativeRenderer.
 *
 * Based on VulkanMod's GlProgramManagerMixin.
 */
@Mixin(ProgramManager.class)
public class ProgramManagerM {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/ProgramManagerM");

    /**
     * Program ID counter - each shader gets a unique ID.
     * Following VulkanMod's pattern.
     */
    private static int programIdCounter = 1;

    /**
     * Cancel linkShader to prevent OpenGL shader linking.
     * DirectX 11 shaders are already compiled and linked in native code.
     *
     * @author Vitra
     * @reason DirectX 11 handles shader linking natively
     */
    @Inject(method = "linkShader", at = @At("HEAD"), cancellable = true)
    private static void cancelLinkShader(Shader shader, CallbackInfo ci) {
        LOGGER.debug("Intercepted linkShader for shader: {}", shader != null ? shader.getClass().getSimpleName() : "null");
        // Cancel the OpenGL linkShader operation
        // DirectX 11 shaders are compiled and linked separately
        ci.cancel();
    }

    /**
     * Override createProgram to return unique program IDs.
     * Each shader instance gets a unique ID for pipeline association.
     * Following VulkanMod's VkGlProgram.genProgramId() pattern.
     *
     * @author Vitra
     * @reason DirectX 11 uses programId for pipeline association in D3D11ProgramRegistry
     */
    @Overwrite
    public static int createProgram() {
        int id = programIdCounter;
        programIdCounter++;
        LOGGER.debug("Intercepted createProgram - returning unique ID: {}", id);
        return id;
    }

    /**
     * Override releaseProgram to prevent OpenGL program deletion.
     * DirectX 11 programs are managed by native code.
     *
     * @author Vitra
     * @reason DirectX 11 handles program lifecycle natively
     */
    @Overwrite
    public static void releaseProgram(Shader shader) {
        LOGGER.debug("Intercepted releaseProgram for shader: {}", shader != null ? shader.getClass().getSimpleName() : "null");
        // No-op - DirectX 11 programs are released by native code
    }

    /**
     * Override glUseProgram to prevent OpenGL program binding.
     * DirectX 11 handles shader binding through pipeline state.
     *
     * @author Vitra
     * @reason DirectX 11 uses pipeline state instead of program binding
     */
    @Overwrite
    public static void glUseProgram(int program) {
        LOGGER.trace("Intercepted glUseProgram({})", program);
        // No-op - DirectX 11 handles shader binding through pipeline state
    }
}
