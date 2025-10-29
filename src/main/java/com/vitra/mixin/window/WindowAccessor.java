package com.vitra.mixin.window;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * DirectX Window Accessor Mixin
 *
 * Based on VulkanMod's WindowAccessor.
 * Provides access to Window's private fields for DirectX initialization.
 *
 * Key responsibilities:
 * - Expose WindowEventHandler for event handling integration
 * - Allow other mixins to access window internals safely
 *
 * This is an @Accessor mixin which generates getter methods at compile time
 * without modifying the target class bytecode directly.
 */
@Mixin(Window.class)
public interface WindowAccessor {

    /**
     * Get the window event handler
     *
     * Provides access to the WindowEventHandler for processing window events
     * like resize, focus, close, etc.
     *
     * @return WindowEventHandler instance
     */
    @Accessor
    WindowEventHandler getEventHandler();
}
