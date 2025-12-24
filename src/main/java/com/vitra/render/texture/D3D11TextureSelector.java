package com.vitra.render.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.jni.VitraD3D11Renderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * D3D11 Texture Binding System (VulkanMod VTextureSelector equivalent)
 *
 * Manages CPU-side texture binding state and maps OpenGL texture IDs to D3D11 SRVs.
 * This is CRITICAL for preventing the red triangle artifacts - without proper texture
 * binding, shaders sample from unbound SRVs causing undefined behavior.
 *
 * VulkanMod Pattern:
 * - Tracks bound textures per slot (Sampler0-Sampler15)
 * - Maps OpenGL texture IDs to native handles (Vulkan images → D3D11 SRVs)
 * - Binds textures to descriptor sets during pipeline binding
 *
 * D3D11 Adaptation:
 * - Maps OpenGL texture IDs to D3D11 texture handles
 * - Calls PSSetShaderResources() to bind SRVs
 * - Manages sampler state objects
 */
public class D3D11TextureSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11TextureSelector.class);

    // Maximum texture units (D3D11 supports up to 128, but Minecraft uses max 16)
    private static final int MAX_TEXTURE_UNITS = 16;

    // CPU-side tracking of bound textures (VulkanMod pattern)
    private static final long[] boundTextureHandles = new long[MAX_TEXTURE_UNITS];
    private static int activeTextureUnit = 0;

    // Map OpenGL texture ID → D3D11 texture handle
    // This is populated when textures are created via AbstractTextureMixin
    private static final Map<Integer, Long> glTexIdToD3D11Handle = new HashMap<>();

    // Sampler name → slot mapping (from VulkanMod)
    private static final Map<String, Integer> samplerNameToSlot = new HashMap<>();
    static {
        samplerNameToSlot.put("Sampler0", 0);
        samplerNameToSlot.put("Sampler1", 1);
        samplerNameToSlot.put("Sampler2", 2);
        samplerNameToSlot.put("Sampler3", 3);
        samplerNameToSlot.put("Sampler4", 4);
        samplerNameToSlot.put("Sampler5", 5);
        samplerNameToSlot.put("Sampler6", 6);
        samplerNameToSlot.put("Sampler7", 7);
        samplerNameToSlot.put("Sampler8", 8);
        samplerNameToSlot.put("Sampler9", 9);
        samplerNameToSlot.put("Sampler10", 10);
        samplerNameToSlot.put("Sampler11", 11);
        samplerNameToSlot.put("Sampler12", 12);
        samplerNameToSlot.put("Sampler13", 13);
        samplerNameToSlot.put("Sampler14", 14);
        samplerNameToSlot.put("Sampler15", 15);
    }

    /**
     * Register OpenGL texture ID → D3D11 handle mapping
     * Called when textures are created via AbstractTextureMixin
     */
    public static void registerTexture(int glTexId, long d3d11Handle) {
        if (glTexId <= 0 || d3d11Handle == 0) {
            LOGGER.warn("Invalid texture registration: glTexId={}, d3d11Handle=0x{}",
                       glTexId, Long.toHexString(d3d11Handle));
            return;
        }

        glTexIdToD3D11Handle.put(glTexId, d3d11Handle);
        LOGGER.debug("Registered texture: GL ID {} → D3D11 handle 0x{}", glTexId, Long.toHexString(d3d11Handle));
    }

    /**
     * Unregister texture (called when texture is destroyed)
     */
    public static void unregisterTexture(int glTexId) {
        Long handle = glTexIdToD3D11Handle.remove(glTexId);
        if (handle != null) {
            LOGGER.debug("Unregistered texture: GL ID {} (was D3D11 handle 0x{})", glTexId, Long.toHexString(handle));
        }
    }

    /**
     * Set active texture unit (equivalent to glActiveTexture)
     * VulkanMod: VTextureSelector._activeTexture()
     */
    public static void setActiveTextureUnit(int unit) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            LOGGER.warn("Invalid texture unit: {}, clamping to [0, {})", unit, MAX_TEXTURE_UNITS);
            unit = Math.max(0, Math.min(unit, MAX_TEXTURE_UNITS - 1));
        }
        activeTextureUnit = unit;
    }

    /**
     * Bind texture to current active unit (equivalent to glBindTexture)
     * VulkanMod: VTextureSelector.bindTexture()
     */
    public static void bindTexture(int glTexId) {
        bindTextureToUnit(activeTextureUnit, glTexId);
    }

    /**
     * Bind texture to specific unit
     * VulkanMod: VTextureSelector.bindTexture(int, VulkanImage)
     */
    public static void bindTextureToUnit(int unit, int glTexId) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            LOGGER.warn("Cannot bind texture to invalid unit: {}", unit);
            return;
        }

        // Lookup D3D11 handle
        Long d3d11Handle = glTexIdToD3D11Handle.get(glTexId);
        if (d3d11Handle == null) {
            // Texture not registered yet (may be created lazily)
            LOGGER.trace("Texture GL ID {} not yet registered, binding will be deferred", glTexId);
            boundTextureHandles[unit] = 0;  // Mark as unbound
            return;
        }

        // Update CPU-side tracking
        boundTextureHandles[unit] = d3d11Handle;

        LOGGER.info("[D3D11_TEXTURE_SELECTOR_BIND] Unit {}: GL ID {} → D3D11 handle 0x{}",
                    unit, glTexId, Long.toHexString(d3d11Handle));
    }

    /**
     * CRITICAL: Bind all shader textures to D3D11 pipeline
     * This is the equivalent of VulkanMod's VTextureSelector.bindShaderTextures()
     *
     * Called from:
     * - ShaderInstanceMixin.apply() after pipeline binding
     * - D3D11Pipeline.bind() during pipeline activation
     *
     * VulkanMod does this in ShaderInstanceM.java:242 → bindPipeline() → VTextureSelector.bindShaderTextures(pipeline)
     */
    public static void bindShaderTextures() {
        int boundCount = 0;
        int unboundCount = 0;

        // Bind all texture units that have textures bound
        for (int slot = 0; slot < MAX_TEXTURE_UNITS; slot++) {
            long d3d11Handle = boundTextureHandles[slot];

            if (d3d11Handle != 0) {
                // Bind via JNI
                VitraD3D11Renderer.bindTextureToSlot(slot, d3d11Handle);
                boundCount++;
            } else {
                unboundCount++;
            }
        }

        if (boundCount > 0) {
            LOGGER.info("[D3D11_TEXTURE_SELECTOR] Bound {} textures to shader ({} slots unbound)", boundCount, unboundCount);
        }
    }

    /**
     * Bind textures for a specific shader based on its sampler map
     * VulkanMod Pattern: Iterates pipeline.getImageDescriptors() and binds textures
     *
     * @param samplerMap Map of sampler name → texture object (from ShaderInstance)
     */
    public static void bindShaderTexturesFromSamplerMap(Map<String, Object> samplerMap) {
        if (samplerMap == null || samplerMap.isEmpty()) {
            LOGGER.trace("No samplers in shader, skipping texture binding");
            return;
        }

        int boundCount = 0;

        for (Map.Entry<String, Object> entry : samplerMap.entrySet()) {
            String samplerName = entry.getKey();
            Object samplerValue = entry.getValue();

            // Get slot index from sampler name
            Integer slot = samplerNameToSlot.get(samplerName);
            if (slot == null) {
                LOGGER.warn("Unknown sampler name: {}, skipping", samplerName);
                continue;
            }

            // Determine texture ID based on sampler type
            int glTexId = 0;

            if (samplerValue instanceof Integer) {
                // Direct texture ID
                glTexId = (Integer) samplerValue;
            // DEPRECATED for 26.1: AbstractTexture.getId() no longer exists
            // } else if (samplerValue instanceof AbstractTexture) {
            //     // AbstractTexture object - get its ID
            //     glTexId = ((AbstractTexture) samplerValue).getId();
            } else {
                LOGGER.warn("Unsupported sampler type for {}: {}", samplerName,
                           samplerValue != null ? samplerValue.getClass().getName() : "null");
                continue;
            }

            // Get texture from RenderSystem if it's registered there
            // DEPRECATED for 26.1: RenderSystem.getShaderTexture() no longer exists
            // if (glTexId == 0) {
            //     glTexId = RenderSystem.getShaderTexture(slot);
            // }

            if (glTexId > 0) {
                // Bind texture to this slot
                bindTextureToUnit(slot, glTexId);
                boundCount++;

                LOGGER.trace("Bound sampler '{}' (slot {}) to GL texture ID {}", samplerName, slot, glTexId);
            } else {
                LOGGER.trace("Sampler '{}' (slot {}) has no texture bound", samplerName, slot);
            }
        }

        // Now bind all tracked textures to GPU
        bindShaderTextures();

        LOGGER.debug("Bound {} textures from sampler map (total {} samplers)", boundCount, samplerMap.size());
    }

    /**
     * Reset all texture bindings (called at frame start)
     * VulkanMod: Done via resetDescriptors() in Renderer.beginFrame()
     */
    public static void resetTextureBindings() {
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            boundTextureHandles[i] = 0;
        }
        activeTextureUnit = 0;

        LOGGER.trace("Reset all texture bindings");
    }

    /**
     * Get currently bound texture handle for a slot (for debugging)
     */
    public static long getBoundTextureHandle(int slot) {
        if (slot < 0 || slot >= MAX_TEXTURE_UNITS) return 0;
        return boundTextureHandles[slot];
    }

    /**
     * Get texture unit count (for debugging)
     */
    public static int getMaxTextureUnits() {
        return MAX_TEXTURE_UNITS;
    }

    /**
     * Debug: Print current texture bindings
     */
    public static void debugPrintBindings() {
        StringBuilder sb = new StringBuilder("Current texture bindings:\n");
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            if (boundTextureHandles[i] != 0) {
                sb.append(String.format("  Slot %d: D3D11 handle 0x%x\n", i, boundTextureHandles[i]));
            }
        }
        LOGGER.info(sb.toString());
    }
}
