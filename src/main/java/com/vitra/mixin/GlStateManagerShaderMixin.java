package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CRITICAL MIXIN: Prevents OpenGL shader compilation and linking in Minecraft 1.21.8
 *
 * Architecture difference from VulkanMod (1.21.1):
 * - 1.21.1 had: ProgramManager.linkShader() - single method to cancel
 * - 1.21.8 has: GlStateManager.glCompileShader() + glLinkProgram() - two methods to cancel
 *
 * In Minecraft 1.21.8, shader compilation happens through GlStateManager which directly
 * calls OpenGL functions. We must cancel BOTH compilation and linking to prevent freezes.
 *
 * Shader Pipeline in 1.21.8:
 * 1. GlStateManager.glCreateShader() - Create shader object
 * 2. GlStateManager.glShaderSource() - Set shader source code
 * 3. GlStateManager.glCompileShader() - *** BLOCKS HERE *** Compile shader
 * 4. GlStateManager.glCreateProgram() - Create program object
 * 5. GlStateManager.glAttachShader() - Attach compiled shaders
 * 6. GlStateManager.glLinkProgram() - *** BLOCKS HERE *** Link program
 *
 * We cancel steps 3 and 6 because BGFX uses pre-compiled shader binaries.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerShaderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlStateManagerShaderMixin");
    private static int compileCount = 0;
    private static int linkCount = 0;

    /**
     * Cancel OpenGL shader compilation.
     * This is where the game freezes - glCompileShader() is a blocking OpenGL call.
     *
     * BGFX uses pre-compiled .bin shader files, so we don't need OpenGL compilation.
     */
    @Inject(method = "glCompileShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlCompileShader(int shader, CallbackInfo ci) {
        compileCount++;

        if (compileCount <= 10) {
            LOGGER.info("[@Inject WORKING] glCompileShader() cancelled - call #{} (shader ID: {})",
                compileCount, shader);
        }

        // Cancel the OpenGL shader compilation operation
        ci.cancel();
    }

    /**
     * Cancel OpenGL program linking.
     * This is the second blocking point where the game can freeze.
     *
     * BGFX handles program creation internally, we don't need OpenGL linking.
     */
    @Inject(method = "glLinkProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlLinkProgram(int program, CallbackInfo ci) {
        linkCount++;

        if (linkCount <= 10) {
            LOGGER.info("[@Inject WORKING] glLinkProgram() cancelled - call #{} (program ID: {})",
                linkCount, program);
        }

        // Cancel the OpenGL program linking operation
        ci.cancel();
    }

    /**
     * Intercept glGetShaderi to fake successful compilation status.
     * Minecraft checks GL_COMPILE_STATUS after calling glCompileShader().
     * We return success (1) to prevent Minecraft from detecting our cancellation.
     */
    @Inject(method = "glGetShaderi", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGetShaderi(int shader, int pname, CallbackInfoReturnable<Integer> cir) {
        // GL_COMPILE_STATUS = 0x8B81 = 35713
        if (pname == 0x8B81) {
            if (compileCount <= 10) {
                LOGGER.info("[@Inject WORKING] glGetShaderi(GL_COMPILE_STATUS) faking success for shader {}", shader);
            }
            cir.setReturnValue(1); // Return GL_TRUE (compilation successful)
        }
    }

    /**
     * Intercept glGetProgrami to fake successful link status.
     * Minecraft checks GL_LINK_STATUS after calling glLinkProgram().
     * We return success (1) to prevent Minecraft from detecting our cancellation.
     */
    @Inject(method = "glGetProgrami", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGlGetProgrami(int program, int pname, CallbackInfoReturnable<Integer> cir) {
        // GL_LINK_STATUS = 0x8B82 = 35714
        if (pname == 0x8B82) {
            if (linkCount <= 10) {
                LOGGER.info("[@Inject WORKING] glGetProgrami(GL_LINK_STATUS) faking success for program {}", program);
            }
            cir.setReturnValue(1); // Return GL_TRUE (linking successful)
        }
    }
}
