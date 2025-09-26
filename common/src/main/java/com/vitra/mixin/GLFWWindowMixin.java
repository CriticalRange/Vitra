package com.vitra.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corrected VulkanMod approach with only existing Window constructor methods
 */
@Mixin(com.mojang.blaze3d.platform.Window.class)
public class GLFWWindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGLFWMixin");

    /**
     * Force GLFW_NO_API to prevent OpenGL context creation that interferes with BGFX DirectX 11
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void forceNoApiForBgfxDirectX11(int hint, int value) {
        String hintName = getGlfwHintName(hint);
        String valueName = getGlfwValueName(hint, value);

        if (hint == GLFW.GLFW_CLIENT_API) {
            LOGGER.info("GLFW-BGFX DEBUG: Intercepting {} ({}) = {} → FORCING GLFW_NO_API", hintName, hint, valueName);
            LOGGER.info("REASON: OpenGL context creation prevents BGFX from selecting DirectX 11 backend");
            GLFW.glfwWindowHint(hint, GLFW.GLFW_NO_API);
        } else if (hint == GLFW.GLFW_CONTEXT_CREATION_API || hint == 139275) {
            LOGGER.info("GLFW-BGFX DEBUG: Intercepting {} ({}) = {} → SKIPPING (NO_API mode)", hintName, hint, valueName);
            // Skip context creation API hints in NO_API mode
        } else {
            LOGGER.info("GLFW-BGFX DEBUG: Allowing {} ({}) = {} ({})", hintName, hint, valueName, value);
            GLFW.glfwWindowHint(hint, value);
        }
    }

    /**
     * Debug log window creation for GLFW-BGFX Direct3D 11 integration
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void debugWindowCreation(CallbackInfo ci) {
        LOGGER.info("=== GLFW-BGFX DEBUG: Creating GLFW window ===");
        LOGGER.info("GLFW_NO_API enforced - no OpenGL context, BGFX will create DirectX 11 independently");
    }

    /**
     * Debug log window creation completion for GLFW-BGFX integration
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", shift = At.Shift.AFTER))
    private void debugWindowCreationComplete(CallbackInfo ci) {
        LOGGER.info("GLFW-BGFX DEBUG: GLFW window created - OpenGL context available, BGFX will use window handle for Direct3D 11");
    }

    private String getGlfwHintName(int hint) {
        return switch (hint) {
            case GLFW.GLFW_CLIENT_API -> "GLFW_CLIENT_API";
            case GLFW.GLFW_CONTEXT_VERSION_MAJOR -> "GLFW_CONTEXT_VERSION_MAJOR";
            case GLFW.GLFW_CONTEXT_VERSION_MINOR -> "GLFW_CONTEXT_VERSION_MINOR";
            case GLFW.GLFW_OPENGL_PROFILE -> "GLFW_OPENGL_PROFILE";
            case GLFW.GLFW_OPENGL_FORWARD_COMPAT -> "GLFW_OPENGL_FORWARD_COMPAT";
            case GLFW.GLFW_CONTEXT_CREATION_API -> "GLFW_CONTEXT_CREATION_API";
            case GLFW.GLFW_RESIZABLE -> "GLFW_RESIZABLE";
            case GLFW.GLFW_VISIBLE -> "GLFW_VISIBLE";
            case GLFW.GLFW_DECORATED -> "GLFW_DECORATED";
            case GLFW.GLFW_FOCUSED -> "GLFW_FOCUSED";
            case GLFW.GLFW_SAMPLES -> "GLFW_SAMPLES";
            case GLFW.GLFW_RED_BITS -> "GLFW_RED_BITS";
            case GLFW.GLFW_GREEN_BITS -> "GLFW_GREEN_BITS";
            case GLFW.GLFW_BLUE_BITS -> "GLFW_BLUE_BITS";
            case GLFW.GLFW_ALPHA_BITS -> "GLFW_ALPHA_BITS";
            case GLFW.GLFW_DEPTH_BITS -> "GLFW_DEPTH_BITS";
            case GLFW.GLFW_STENCIL_BITS -> "GLFW_STENCIL_BITS";
            default -> "UNKNOWN_" + hint;
        };
    }

    private String getGlfwValueName(int hint, int value) {
        if (hint == GLFW.GLFW_CLIENT_API) {
            return switch (value) {
                case GLFW.GLFW_OPENGL_API -> "GLFW_OPENGL_API";
                case GLFW.GLFW_OPENGL_ES_API -> "GLFW_OPENGL_ES_API";
                case GLFW.GLFW_NO_API -> "GLFW_NO_API";
                default -> "UNKNOWN_API_" + value;
            };
        } else if (hint == GLFW.GLFW_OPENGL_PROFILE) {
            return switch (value) {
                case GLFW.GLFW_OPENGL_ANY_PROFILE -> "GLFW_OPENGL_ANY_PROFILE";
                case GLFW.GLFW_OPENGL_CORE_PROFILE -> "GLFW_OPENGL_CORE_PROFILE";
                case GLFW.GLFW_OPENGL_COMPAT_PROFILE -> "GLFW_OPENGL_COMPAT_PROFILE";
                default -> "UNKNOWN_PROFILE_" + value;
            };
        } else if (hint == GLFW.GLFW_TRUE || hint == GLFW.GLFW_FALSE) {
            return value == GLFW.GLFW_TRUE ? "GLFW_TRUE" : "GLFW_FALSE";
        } else {
            return String.valueOf(value);
        }
    }

}