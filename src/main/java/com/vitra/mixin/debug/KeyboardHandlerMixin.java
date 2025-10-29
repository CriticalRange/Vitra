package com.vitra.mixin.debug;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DirectX Keyboard Handler Debug Mixin
 *
 * Based on VulkanMod's KeyboardHandlerM but adapted for DirectX.
 * Adds additional debug key handling for chunk debugging features.
 *
 * Key responsibilities:
 * - Inject into keyPress method to add F7+key debug shortcuts
 * - Enable chunk debug visualization with F7 modifier
 * - Support frustum capture and chunk debugging features
 *
 * Debug key combinations:
 * - F7 + U: Capture frustum for debugging
 * - F7 + other keys: Handled by handleChunkDebugKeys()
 *
 * This mixin ensures that Vitra's debug features are accessible via
 * keyboard shortcuts consistent with VulkanMod's interface.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Shadow protected abstract boolean handleChunkDebugKeys(int key);

    @Shadow private boolean handledDebugKey;

    /**
     * Inject into keyPress to add F7 modifier for chunk debug keys
     *
     * When F7 is held down, additional debug keys become available:
     * - U: Capture frustum for rendering debugging
     * - Other keys: Delegated to handleChunkDebugKeys()
     *
     * This injection point is after the first InputConstants.isKeyDown check,
     * allowing us to add our custom debug key handling without breaking
     * Minecraft's existing debug key logic.
     *
     * @param window GLFW window handle
     * @param key GLFW key code
     * @param scancode Platform-specific scancode
     * @param action GLFW action (PRESS, RELEASE, REPEAT)
     * @param mods GLFW modifier keys (SHIFT, CTRL, ALT, etc.)
     * @param ci Callback info for injection
     */
    @Inject(method = "keyPress", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;isKeyDown(JI)Z", ordinal = 0, shift = At.Shift.AFTER))
    private void chunkDebug(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        // GLFW key 296 -> F7 (debug modifier key)
        // When F7 is held, handleChunkDebugKeys processes additional debug shortcuts
        // Example: F7 + U -> Capture frustum
        this.handledDebugKey |= InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), 296)
                && this.handleChunkDebugKeys(key);
    }
}
