package com.vitra.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.vitra.VitraMod;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * CRITICAL MIXIN: Primary rendering entry point for Minecraft 1.21.8
 *
 * This intercepts RenderType.draw(MeshData) which is THE main draw call in 1.21.8.
 * This replaces BufferUploader.drawWithShader which doesn't exist in 1.21.8.
 *
 * Following VulkanMod's aggressive @Overwrite strategy for complete control.
 */
@Mixin(RenderType.class)
public abstract class RenderTypeMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderTypeMixin");
    private static long drawCallCount = 0;

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL draw with DirectX 11 rendering
     *
     * This is the PRIMARY draw call in Minecraft 1.21.8. All rendering goes through here.
     *
     * Original signature (from Context7):
     * public void draw(MeshData arg0)
     *
     * MeshData structure:
     * - ByteBufferBuilder.Result vertexBuffer (vertex data)
     * - ByteBufferBuilder.Result indexBuffer (index data, optional)
     * - MeshData.DrawState drawState:
     *   - VertexFormat format (position, color, UV, normal, etc.)
     *   - int vertexCount
     *   - int indexCount
     *   - VertexFormat.Mode mode (QUADS, TRIANGLES, LINES, etc.)
     *   - VertexFormat.IndexType indexType (SHORT or INT)
     */
    @Overwrite
    public void draw(MeshData meshData) {
        drawCallCount++;

        if (meshData == null) {
            LOGGER.warn("draw() called with null MeshData!");
            return;
        }

        try {
            // Extract draw parameters
            MeshData.DrawState drawState = meshData.drawState();

            if (drawState == null) {
                LOGGER.warn("MeshData has null DrawState!");
                meshData.close();
                return;
            }

            int vertexCount = drawState.vertexCount();
            int indexCount = drawState.indexCount();

            if (vertexCount <= 0) {
                if (drawCallCount <= 10) {
                    LOGGER.debug("draw() called with 0 vertices, skipping");
                }
                meshData.close();
                return;
            }

            // Log first few draw calls for debugging
            if (drawCallCount <= 5) {
                LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                LOGGER.info("║  RENDERTYPE.DRAW() INTERCEPTED - DRAW CALL #{}            ", drawCallCount);
                LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                LOGGER.info("║ Vertex Count:  {}", vertexCount);
                LOGGER.info("║ Index Count:   {}", indexCount);
                LOGGER.info("║ Draw Mode:     {}", drawState.mode());
                LOGGER.info("║ Vertex Format: {}", drawState.format());
                LOGGER.info("║ Index Type:    {}", drawState.indexType());
                LOGGER.info("╚════════════════════════════════════════════════════════════╝");
            } else if (drawCallCount % 1000 == 0) {
                LOGGER.debug("RenderType.draw() call #{}: {} vertices, {} indices",
                    drawCallCount, vertexCount, indexCount);
            }

            // Check if DirectX 11 renderer is initialized
            if (VitraMod.getRenderer() == null || !VitraMod.getRenderer().isInitialized()) {
                if (drawCallCount <= 10) {
                    LOGGER.error("DirectX 11 renderer not initialized for draw call #{}!", drawCallCount);
                }
                meshData.close();
                return;
            }

            // Extract vertex and index buffers
            java.nio.ByteBuffer vertexBuffer = meshData.vertexBuffer();
            java.nio.ByteBuffer indexBuffer = meshData.indexBuffer(); // This may be null for non-indexed draws

            if (vertexBuffer == null) {
                LOGGER.warn("MeshData has null vertex buffer!");
                meshData.close();
                return;
            }

            // Convert VertexFormat.Mode to OpenGL primitive mode for DirectX translation
            int glMode = convertModeToGL(drawState.mode());

            // Set primitive topology
            VitraNativeRenderer.setPrimitiveTopology(glMode);

            // Calculate vertex size from format
            int vertexSize = drawState.format().getVertexSize();

            // Draw using our DirectX 11 JNI renderer
            // This method handles vertex/index buffer upload and drawing
            VitraNativeRenderer.drawMeshData(
                vertexBuffer,
                indexBuffer,
                vertexCount,
                indexCount,
                glMode,
                vertexSize
            );

            if (drawCallCount <= 3) {
                LOGGER.info("✓ Draw call #{} submitted to DirectX 11 successfully", drawCallCount);
            }

        } catch (Exception e) {
            LOGGER.error("Exception in RenderType.draw() - call #{}", drawCallCount, e);
        } finally {
            // CRITICAL: Always close MeshData to free memory
            try {
                meshData.close();
            } catch (Exception e) {
                LOGGER.error("Failed to close MeshData", e);
            }
        }
    }

    /**
     * Convert Minecraft's VertexFormat.Mode to OpenGL primitive mode constant
     *
     * Minecraft 1.21.8 VertexFormat.Mode enum values:
     * - LINES
     * - LINE_STRIP
     * - DEBUG_LINES
     * - DEBUG_LINE_STRIP
     * - TRIANGLES
     * - TRIANGLE_STRIP
     * - TRIANGLE_FAN
     * - QUADS
     */
    private int convertModeToGL(com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        // These are OpenGL constants that our DirectX 11 JNI translates
        switch (mode.toString()) {
            case "LINES":
                return 0x0001; // GL_LINES
            case "LINE_STRIP":
                return 0x0003; // GL_LINE_STRIP
            case "TRIANGLES":
                return 0x0004; // GL_TRIANGLES
            case "TRIANGLE_STRIP":
                return 0x0005; // GL_TRIANGLE_STRIP
            case "TRIANGLE_FAN":
                return 0x0006; // GL_TRIANGLE_FAN
            case "QUADS":
                return 0x0007; // GL_QUADS (converted to triangles in DirectX 11)
            case "DEBUG_LINES":
                return 0x0001; // GL_LINES
            case "DEBUG_LINE_STRIP":
                return 0x0003; // GL_LINE_STRIP
            default:
                LOGGER.warn("Unknown VertexFormat.Mode: {}, defaulting to TRIANGLES", mode);
                return 0x0004; // GL_TRIANGLES
        }
    }

    /**
     * Reset draw call counter (called from RenderSystemMixin each frame)
     *
     * Note: This must be private per Mixin rules, but we access it via reflection
     * from RenderSystemMixin.
     */
    private static void resetDrawCallCount() {
        drawCallCount = 0;
    }

    /**
     * Get current draw call count for debugging
     *
     * Note: This must be private per Mixin rules, but we can access it via reflection
     * if needed for debugging.
     */
    private static long getDrawCallCount() {
        return drawCallCount;
    }
}
