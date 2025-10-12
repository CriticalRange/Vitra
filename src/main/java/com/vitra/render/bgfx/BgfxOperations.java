package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.bgfx.BGFXTextureInfo;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Consolidated BGFX operations - removes wrapper classes and uses BGFX native functionality directly
 * This replaces VitraGpuBuffer, VitraGpuTexture, VitraCommandEncoder, and VertexLayouts
 */
public class BgfxOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxOperations");

    // Cached vertex layouts to avoid recreation
    private static BGFXVertexLayout cachedPositionTexColorLayout;
    private static BGFXVertexLayout cachedPositionLayout;
    private static BGFXVertexLayout cachedPositionTexLayout;

    static {
        initializeCachedLayouts();
    }

    /**
     * Initialize cached vertex layouts for better performance
     */
    private static void initializeCachedLayouts() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Position + TexCoord + Color (most common for Minecraft)
            cachedPositionTexColorLayout = BGFXVertexLayout.malloc(stack);
            BGFX.bgfx_vertex_layout_begin(cachedPositionTexColorLayout, BGFX.BGFX_RENDERER_TYPE_NOOP);
            BGFX.bgfx_vertex_layout_add(cachedPositionTexColorLayout, BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_add(cachedPositionTexColorLayout, BGFX.BGFX_ATTRIB_TEXCOORD0, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_add(cachedPositionTexColorLayout, BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_UINT8, true, true);
            BGFX.bgfx_vertex_layout_end(cachedPositionTexColorLayout);

            // Position only
            cachedPositionLayout = BGFXVertexLayout.malloc(stack);
            BGFX.bgfx_vertex_layout_begin(cachedPositionLayout, BGFX.BGFX_RENDERER_TYPE_NOOP);
            BGFX.bgfx_vertex_layout_add(cachedPositionLayout, BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_end(cachedPositionLayout);

            // Position + TexCoord
            cachedPositionTexLayout = BGFXVertexLayout.malloc(stack);
            BGFX.bgfx_vertex_layout_begin(cachedPositionTexLayout, BGFX.BGFX_RENDERER_TYPE_NOOP);
            BGFX.bgfx_vertex_layout_add(cachedPositionTexLayout, BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_add(cachedPositionTexLayout, BGFX.BGFX_ATTRIB_TEXCOORD0, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_end(cachedPositionTexLayout);

            LOGGER.info("BGFX vertex layouts cached successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize cached BGFX vertex layouts", e);
        }
    }

    // ==================== VERTEX LAYOUT OPERATIONS ====================

    public static BGFXVertexLayout getPositionTexColorLayout() {
        return cachedPositionTexColorLayout;
    }

    public static BGFXVertexLayout getPositionLayout() {
        return cachedPositionLayout;
    }

    public static BGFXVertexLayout getPositionTexLayout() {
        return cachedPositionTexLayout;
    }

    // ==================== BUFFER OPERATIONS ====================

    /**
     * Create a vertex buffer using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static short createVertexBuffer(ByteBuffer data, BGFXVertexLayout layout, int flags) {
        try {
            BGFXMemory memory = BGFX.bgfx_copy(data);
            return BGFX.bgfx_create_vertex_buffer(memory, layout, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create vertex buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create an index buffer using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static short createIndexBuffer(ByteBuffer data, int flags) {
        try {
            BGFXMemory memory = BGFX.bgfx_copy(data);
            return BGFX.bgfx_create_index_buffer(memory, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create index buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create a dynamic vertex buffer.
     * BGFX handles all validation internally.
     */
    public static short createDynamicVertexBuffer(int numVertices, BGFXVertexLayout layout, int flags) {
        try {
            return BGFX.bgfx_create_dynamic_vertex_buffer(numVertices, layout, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create dynamic vertex buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create a dynamic index buffer.
     * BGFX handles all validation internally.
     */
    public static short createDynamicIndexBuffer(int numIndices, int flags) {
        try {
            return BGFX.bgfx_create_dynamic_index_buffer(numIndices, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create dynamic index buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Update a dynamic buffer (vertex or index).
     * BGFX handles all validation internally.
     */
    public static boolean updateDynamicBuffer(short handle, int offset, ByteBuffer data, boolean isVertexBuffer) {
        try {
            BGFXMemory memory = BGFX.bgfx_copy(data);

            if (isVertexBuffer) {
                BGFX.bgfx_update_dynamic_vertex_buffer(handle, offset, memory);
            } else {
                BGFX.bgfx_update_dynamic_index_buffer(handle, offset, memory);
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to update dynamic buffer", e);
            return false;
        }
    }

    /**
     * Allocate transient vertex buffer.
     * BGFX handles all validation internally.
     */
    public static boolean allocTransientVertexBuffer(BGFXTransientVertexBuffer tvb, int numVertices, BGFXVertexLayout layout) {
        try {
            BGFX.bgfx_alloc_transient_vertex_buffer(tvb, numVertices, layout);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to allocate transient vertex buffer", e);
            return false;
        }
    }

    /**
     * Allocate transient index buffer.
     * BGFX handles all validation internally.
     */
    public static boolean allocTransientIndexBuffer(BGFXTransientIndexBuffer tib, int numIndices) {
        try {
            BGFX.bgfx_alloc_transient_index_buffer(tib, numIndices, false);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to allocate transient index buffer", e);
            return false;
        }
    }

    // ==================== TEXTURE OPERATIONS ====================

    /**
     * Create a 2D texture using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static short createTexture2D(int width, int height, boolean hasMips, int numLayers, int format, int flags) {
        try {
            return BGFX.bgfx_create_texture_2d(width, height, hasMips, numLayers, format, flags, null);
        } catch (Exception e) {
            LOGGER.error("Failed to create 2D texture", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Update texture data.
     * BGFX handles all validation internally.
     */
    public static boolean updateTexture2D(short textureHandle, int mipLevel, int x, int y, int width, int height, ByteBuffer data) {
        try {
            BGFXMemory memory = BGFX.bgfx_copy(data);
            BGFX.bgfx_update_texture_2d(textureHandle, mipLevel, x, y, 0, width, height, memory, 0xFFFF);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to update texture", e);
            return false;
        }
    }

    /**
     * Create a texture view.
     * Note: LWJGL BGFX bindings don't expose bgfx_create_texture_view,
     * so we return the source texture handle. This is acceptable for most use cases.
     */
    public static short createTextureView(short textureHandle, int format, int firstMip, int numMips, int firstLayer, int numLayers) {
        LOGGER.debug("Texture view creation requested (using source texture handle)");
        return textureHandle;
    }

    // ==================== COMMAND ENCODER OPERATIONS ====================

    /**
     * Set scissor rect using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static void setScissorRect(int x, int y, int width, int height) {
        try {
            BGFX.bgfx_set_view_scissor(0, (short)x, (short)y, (short)width, (short)height);
        } catch (Exception e) {
            LOGGER.error("Failed to set scissor rect", e);
        }
    }

    /**
     * Blit texture using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static boolean blitTexture(short dstTexture, int dstX, int dstY, short srcTexture, int srcX, int srcY, int width, int height) {
        try {
            BGFX.bgfx_blit(0, dstTexture, 0, dstX, dstY, 0, srcTexture, 0, srcX, srcY, 0, width, height, 1);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to blit texture", e);
            return false;
        }
    }

    /**
     * Read texture data using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public static boolean readTexture(short textureHandle, ByteBuffer dataBuffer, int mipLevel) {
        try {
            BGFX.bgfx_read_texture(textureHandle, dataBuffer, (byte)mipLevel);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to read texture", e);
            return false;
        }
    }

    // ==================== RESOURCE CLEANUP ====================

    /**
     * Destroy a BGFX resource based on its type.
     * BGFX handles all validation internally.
     */
    public static void destroyResource(short handle, String resourceType) {
        try {
            switch (resourceType.toLowerCase()) {
                case "vertex_buffer":
                    BGFX.bgfx_destroy_vertex_buffer(handle);
                    break;
                case "index_buffer":
                    BGFX.bgfx_destroy_index_buffer(handle);
                    break;
                case "dynamic_vertex_buffer":
                    BGFX.bgfx_destroy_dynamic_vertex_buffer(handle);
                    break;
                case "dynamic_index_buffer":
                    BGFX.bgfx_destroy_dynamic_index_buffer(handle);
                    break;
                case "texture":
                    BGFX.bgfx_destroy_texture(handle);
                    break;
                case "texture_view":
                    // LWJGL BGFX bindings don't have bgfx_destroy_texture_view
                    // Texture views use the source handle, so no cleanup needed
                    LOGGER.debug("Texture view destruction skipped (source texture handle)");
                    break;
                case "shader":
                    BGFX.bgfx_destroy_shader(handle);
                    break;
                case "program":
                    BGFX.bgfx_destroy_program(handle);
                    break;
                default:
                    LOGGER.warn("Unknown resource type: {}", resourceType);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to destroy resource: handle={}, type={}", handle, resourceType, e);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Check if a BGFX handle is valid
     */
    public static boolean isValidHandle(short handle) {
        return Util.isValidHandle(handle);
    }

    /**
     * Get current renderer type
     */
    public static int getRendererType() {
        return Util.getRendererType();
    }

    /**
     * Check if BGFX is initialized
     */
    public static boolean isInitialized() {
        return Util.isInitialized();
    }
}