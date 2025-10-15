package com.vitra.mixin;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Priority 4: Frame Presentation Mixin
 *
 * CRITICAL: This mixin replaces Minecraft's OpenGL frame presentation with DirectX 11 JNI.
 *
 * VulkanMod Strategy (simplified):
 * - Use @Overwrite to replace updateDisplay() method
 * - Just call RenderSystem.flipFrame() (which has glfwSwapBuffers() redirected in RenderSystemMixin)
 * - DirectX 11 frame submission happens automatically via the @Overwrite in RenderSystemMixin
 *
 * This ensures:
 * - NO OpenGL context calls at all
 * - Only DirectX 11 presents frames (via replaced flipFrame())
 * - No GL ERROR 65546 messages
 * - Simpler, cleaner code following VulkanMod's proven pattern
 */
@Mixin(Window.class)
public class WindowUpdateDisplayMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("WindowUpdateDisplayMixin");

    @Shadow
    @Final
    private long window;

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL presentation with DirectX 11 JNI
     *
     * Original Minecraft code:
     * - Calls RenderSystem.flipFrame(window, tracyCapture) which does glfwSwapBuffers() and glfwPollEvents()
     *
     * Our replacement (following VulkanMod's pattern):
     * - Just call RenderSystem.flipFrame()
     * - The glfwSwapBuffers() call INSIDE flipFrame() is replaced with DirectX 11 frame submission in RenderSystemMixin
     * - This is simpler and more robust than calling DirectX 11 frame submission here
     *
     * Why this works:
     * - RenderSystemMixin has @Overwrite that replaces flipFrame() → calls DirectX 11 frame submission
     * - flipFrame() still handles event polling, Tracy capture, and frame timing
     * - We get all the benefits without duplicating frame submission logic
     *
     * Minecraft 1.21.8 signature: updateDisplay(TracyFrameCapture)
     */
    @Overwrite
    public void updateDisplay(com.mojang.blaze3d.TracyFrameCapture tracyCapture) {
        // Call Minecraft's flipFrame() - glfwSwapBuffers() inside it is replaced with DirectX 11
        // This handles:
        // 1. DirectX 11 frame submission (via replaced flipFrame())
        // 2. Event polling (glfwPollEvents())
        // 3. Tracy frame capture (if enabled)
        RenderSystem.flipFrame(this.window, tracyCapture);

        LOGGER.trace("Window.updateDisplay() called RenderSystem.flipFrame() (glfwSwapBuffers replaced with DirectX 11)");
    }
}
