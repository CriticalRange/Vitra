package com.vitra.mixin.texture.update;

import com.vitra.render.VitraRenderer;
import com.vitra.render.texture.SpriteUpdateUtil;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TickableTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

/**
 * Adapted from VulkanMod's MTextureManager
 * Ensures animated textures are properly updated and transitioned to shader-readable state
 */
@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {

    @Shadow @Final private Set<TickableTexture> tickableTextures;

    /**
     * @author Vitra
     * @reason Add texture layout transition after animated texture updates (VulkanMod pattern)
     */
    @Overwrite
    public void tick() {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null && !renderer.isInitialized())
            return;

        // Tick all animated textures (water, lava, portals, etc.)
        for (TickableTexture tickable : this.tickableTextures) {
            tickable.tick();
        }

        // Transition textures to shader-readable state (critical for DirectX 11)
        SpriteUpdateUtil.transitionLayouts();
    }
}
