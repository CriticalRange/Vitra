package com.vitra.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug mixin to log texture binding via RenderSystem.setShaderTexture
 */
@Mixin(RenderSystem.class)
public class RenderSystemTextureMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraTextureDebug");
    private static int setTextureCount = 0;

    @Shadow @Final private static int[] shaderTextures;

    @Inject(method = "_setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("HEAD"), remap = false)
    private static void logSetShaderTexture(int slot, ResourceLocation resourceLocation, CallbackInfo ci) {
        if (setTextureCount < 200) {
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            AbstractTexture texture = textureManager.getTexture(resourceLocation);
            int texId = texture != null ? texture.getId() : -1;

            LOGGER.info("[SET_SHADER_TEXTURE {}] Slot={}, ResourceLocation='{}', Resolved TexID={}",
                setTextureCount++, slot, resourceLocation, texId);
        }
    }

    @Inject(method = "_setShaderTexture(II)V", at = @At("HEAD"), remap = false)
    private static void logSetShaderTextureById(int slot, int textureId, CallbackInfo ci) {
        if (setTextureCount < 200) {
            LOGGER.info("[SET_SHADER_TEXTURE_ID {}] Slot={}, TexID={}",
                setTextureCount++, slot, textureId);
        }
    }

    /**
     * CRITICAL FIX: Bind texture to D3D11 when setShaderTexture is called
     * This is VulkanMod's approach - texture slot is determined by Minecraft's RenderSystem
     */
    @Inject(method = "_setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("RETURN"), remap = false)
    private static void bindTextureToD3D11(int slot, ResourceLocation resourceLocation, CallbackInfo ci) {
        try {
            // Get the texture ID that was just set in shaderTextures array
            int textureId = shaderTextures[slot];

            // Bind this texture to the corresponding D3D11 slot
            com.vitra.render.jni.VitraD3D11Renderer.setActiveTextureUnit(slot);
            com.vitra.render.D3D11Texture.bindTexture(textureId);

            if (setTextureCount < 30) {
                LOGGER.info("[D3D11_BIND_FROM_SHADER_TEXTURE] Bound TexID={} to D3D11 slot {}",
                    textureId, slot);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to bind texture to D3D11", e);
        }
    }

    @Inject(method = "_setShaderTexture(II)V", at = @At("RETURN"), remap = false)
    private static void bindTextureToD3D11ById(int slot, int textureId, CallbackInfo ci) {
        try {
            // Bind this texture to the corresponding D3D11 slot
            com.vitra.render.jni.VitraD3D11Renderer.setActiveTextureUnit(slot);
            com.vitra.render.D3D11Texture.bindTexture(textureId);

            if (setTextureCount < 30) {
                LOGGER.info("[D3D11_BIND_FROM_SHADER_TEXTURE_ID] Bound TexID={} to D3D11 slot {}",
                    textureId, slot);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to bind texture to D3D11", e);
        }
    }
}
