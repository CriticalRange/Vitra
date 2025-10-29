package com.vitra.render.texture;

import java.util.HashSet;
import java.util.Set;

/**
 * Adapted from VulkanMod's SpriteUpdateUtil
 * Manages animated texture uploads and ensures textures are in correct state for rendering
 */
public class SpriteUpdateUtil {
    private static boolean doUpload = true;
    private static final Set<Integer> transitionedTextures = new HashSet<>();

    public static void setDoUpload(boolean b) {
        doUpload = b;
    }

    public static boolean doUploadFrame() {
        return doUpload;
    }

    public static void addTransitionedTexture(int textureId) {
        transitionedTextures.add(textureId);
    }

    /**
     * Transition textures to shader-readable state after animated texture uploads
     * In DirectX 11, this ensures textures are properly bound and accessible
     */
    public static void transitionLayouts() {
        if (!doUpload || transitionedTextures.isEmpty()) {
            return;
        }

        // For DirectX 11, we don't need explicit layout transitions like Vulkan
        // but we do need to ensure textures are properly bound as shader resources
        // The texture binding is handled automatically by D3D11Texture.bindTexture()

        transitionedTextures.clear();
    }
}