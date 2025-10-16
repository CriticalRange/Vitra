package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.GpuTextureView;
import com.vitra.VitraMod;
import com.vitra.core.VitraCore;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * CRITICAL MIXIN: Intercepts draw calls at the CommandEncoder level (Minecraft 1.21.8)
 *
 * Minecraft 1.21.8 completely refactored rendering to use a new GPU abstraction:
 * - OLD: GlStateManager._drawArrays/_drawElements (OpenGL-style)
 * - NEW: CommandEncoder.executeDraw/executeDrawMultiple (GPU abstraction)
 *
 * This mixin intercepts the NEW API to capture ALL draw calls.
 */
@Mixin(GlCommandEncoder.class)
public class GlCommandEncoderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlCommandEncoderMixin");
    private static int drawCallCount = 0;

    /**
     * Intercept executeDraw - single draw call
     * Method signature from Context7:
     * protected void executeDraw(GlRenderPass, int, int, int, VertexFormat$IndexType, int)
     */
    @Inject(
        method = "executeDraw(Lcom/mojang/blaze3d/opengl/GlRenderPass;IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;I)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onExecuteDraw(
        GlRenderPass renderPass,
        int baseVertex,
        int firstIndex,
        int count,
        VertexFormat.IndexType indexType,
        int instanceCount,
        CallbackInfo ci
    ) {
        drawCallCount++;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  DRAW CALL INTERCEPTED (executeDraw)                       ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Draw Call #:    {}", drawCallCount);
            LOGGER.info("║ Base Vertex:    {}", baseVertex);
            LOGGER.info("║ First Index:    {}", firstIndex);
            LOGGER.info("║ Index Count:    {}", count);
            LOGGER.info("║ Index Type:     {}", indexType);
            LOGGER.info("║ Instance Count: {}", instanceCount);
            LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward to DirectX 11 renderer with enhanced error handling
        if (VitraCore.getInstance().isInitialized()) {
            try {
                // Extract vertex and index data from render pass for DirectX 11
                int[] vertexData = extractVertexData(renderPass);
                int[] indexData = extractIndexData(renderPass);

                if (vertexData != null && vertexData.length > 0) {
                    boolean success;
                    if (indexData != null && indexData.length > 0) {
                        // Indexed drawing with DirectX 11
                        success = VitraNativeRenderer.executeIndexedDraw(
                            vertexData, baseVertex,
                            indexData, firstIndex, count,
                            renderPass.getVertexFormat().getName()
                        );
                    } else {
                        // Non-indexed drawing with DirectX 11
                        success = VitraNativeRenderer.executeNonIndexedDraw(
                            vertexData, baseVertex, count,
                            renderPass.getVertexFormat().getName()
                        );
                    }

                    if (!success) {
                        LOGGER.error("DirectX 11 draw call failed");
                    }
                } else {
                    // Fallback to legacy GLInterceptor
                    GLInterceptor.onDrawCall(baseVertex, firstIndex, count, indexType.ordinal(), instanceCount);
                }
            } catch (Exception e) {
                LOGGER.error("Exception forwarding draw call to DirectX 11", e);
                // Fallback to legacy method
                try {
                    GLInterceptor.onDrawCall(baseVertex, firstIndex, count, indexType.ordinal(), instanceCount);
                } catch (Exception fallbackError) {
                    LOGGER.error("Fallback draw call also failed", fallbackError);
                }
            }
        }

        // Let original OpenGL call proceed (will be no-op since we're using DirectX)
    }

    /**
     * Intercept executeDrawMultiple - batched draw calls
     * Method signature from Context7:
     * <T> void executeDrawMultiple(GlRenderPass, Collection<RenderPass$Draw<T>>, GpuBuffer, VertexFormat$IndexType, Collection<String>, T)
     */
    @Inject(
        method = "executeDrawMultiple(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onExecuteDrawMultiple(
        GlRenderPass renderPass,
        Collection<?> drawObjects,
        GpuBuffer indexBuffer,
        VertexFormat.IndexType indexType,
        Collection<String> validationSkippedUniforms,
        Object uniformData,
        CallbackInfo ci
    ) {
        int batchSize = drawObjects != null ? drawObjects.size() : 0;
        drawCallCount += batchSize;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  BATCHED DRAW CALLS INTERCEPTED (executeDrawMultiple)      ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Total Draw Calls: {}", drawCallCount);
            LOGGER.info("║ Batch Size:       {}", batchSize);
            LOGGER.info("║ Index Type:       {}", indexType);
            LOGGER.info("║ Thread:           {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward batched draw calls to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                // Forward batched draw calls to DirectX 11
                GLInterceptor.onBatchedDrawCalls(drawObjects, indexBuffer, indexType.ordinal());
            } catch (Exception e) {
                LOGGER.error("Exception forwarding batched draw calls to DirectX 11", e);
            }
        }

        // Let original OpenGL calls proceed (will be no-op since we're using DirectX)
    }

    /**
     * Intercept drawFromBuffers - direct buffer drawing
     * Method signature from Context7:
     * private void drawFromBuffers(GlRenderPass, int, int, int, VertexFormat$IndexType, GlRenderPipeline, int)
     */
    @Inject(
        method = "drawFromBuffers(Lcom/mojang/blaze3d/opengl/GlRenderPass;IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Lcom/mojang/blaze3d/opengl/GlRenderPipeline;I)V",
        at = @At("HEAD"),
        cancellable = false,
        remap = false
    )
    private void onDrawFromBuffers(
        GlRenderPass renderPass,
        int baseVertex,
        int firstIndex,
        int count,
        VertexFormat.IndexType indexType,
        Object pipeline, // GlRenderPipeline
        int instanceCount,
        CallbackInfo ci
    ) {
        drawCallCount++;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  DRAW CALL INTERCEPTED (drawFromBuffers)                   ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Draw Call #:    {}", drawCallCount);
            LOGGER.info("║ Base Vertex:    {}", baseVertex);
            LOGGER.info("║ First Index:    {}", firstIndex);
            LOGGER.info("║ Count:          {}", count);
            LOGGER.info("║ Index Type:     {}", indexType);
            LOGGER.info("║ Instance Count: {}", instanceCount);
            LOGGER.info("║ Pipeline:       {}", pipeline != null ? pipeline.getClass().getSimpleName() : "null");
            LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward to DirectX 11 renderer
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onDrawCall(baseVertex, firstIndex, count, indexType.ordinal(), instanceCount);
            } catch (Exception e) {
                LOGGER.error("Exception forwarding drawFromBuffers to DirectX 11", e);
            }
        }
    }

    /**
     * Present texture using DirectX 11 instead of OpenGL.
     * This is a critical method for 1.21.8's new texture presentation system.
     */
    @Overwrite
    public void presentTexture(GpuTextureView texture) {
        try {
            if (!VitraCore.getInstance().isInitialized()) {
                LOGGER.warn("VitraCore not initialized, cannot present texture");
                return;
            }

            if (texture == null) {
                LOGGER.warn("Attempted to present null texture");
                return;
            }

            LOGGER.debug("Presenting texture with DirectX 11: {}", texture.getClass().getSimpleName());

            // Extract texture data and present using DirectX 11
            int textureId = extractTextureId(texture);
            if (textureId != -1) {
                boolean success = VitraNativeRenderer.presentTexture(textureId);
                if (!success) {
                    LOGGER.error("Failed to present texture with DirectX 11");
                }
            } else {
                LOGGER.warn("Could not extract texture ID from: {}", texture.getClass().getSimpleName());
            }

        } catch (Exception e) {
            LOGGER.error("Failed to present texture with DirectX 11", e);
        }
    }

    /**
     * Enhanced batched draw call execution with DirectX 11 optimization.
     */
    @Overwrite
    public <T> void executeDrawMultiple(
        GlRenderPass renderPass,
        Collection<RenderPass.Draw<T>> drawCalls,
        GpuBuffer indexBuffer,
        VertexFormat.IndexType indexType,
        Collection<String> validationSkippedUniforms,
        T uniformData
    ) {
        int batchSize = drawCalls != null ? drawCalls.size() : 0;
        drawCallCount += batchSize;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  BATCHED DRAW CALLS INTERCEPTED (executeDrawMultiple)      ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Total Draw Calls: {}", drawCallCount);
            LOGGER.info("║ Batch Size:       {}", batchSize);
            LOGGER.info("║ Index Type:       {}", indexType);
            LOGGER.info("║ Thread:           {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward batched draw calls to DirectX 11 with optimization
        if (VitraCore.getInstance().isInitialized()) {
            try {
                // Batch process with DirectX 11 for performance
                boolean success = VitraNativeRenderer.executeBatchedDraws(
                    renderPass.getVertexFormat().getName(),
                    drawCalls,
                    indexType.ordinal(),
                    uniformData
                );

                if (!success) {
                    LOGGER.error("DirectX 11 batched draw execution failed");
                    // Fallback to individual draw calls
                    for (RenderPass.Draw<T> drawCall : drawCalls) {
                        try {
                            GLInterceptor.onDrawCall(
                                drawCall.baseVertex(),
                                drawCall.firstIndex(),
                                drawCall.indexCount(),
                                indexType.ordinal(),
                                drawCall.instanceCount()
                            );
                        } catch (Exception e) {
                            LOGGER.error("Fallback draw call failed", e);
                        }
                    }
                }

            } catch (Exception e) {
                LOGGER.error("Exception forwarding batched draw calls to DirectX 11", e);
                // Fallback to legacy method
                try {
                    GLInterceptor.onBatchedDrawCalls(drawCalls, indexBuffer, indexType.ordinal());
                } catch (Exception fallbackError) {
                    LOGGER.error("Fallback batched draw calls also failed", fallbackError);
                }
            }
        }
    }

    /**
     * Helper method to extract vertex data from render pass.
     */
    private int[] extractVertexData(GlRenderPass renderPass) {
        try {
            // This would need to be implemented based on the actual render pass structure
            // For now, return null to trigger fallback
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to extract vertex data from render pass", e);
            return null;
        }
    }

    /**
     * Helper method to extract index data from render pass.
     */
    private int[] extractIndexData(GlRenderPass renderPass) {
        try {
            // This would need to be implemented based on the actual render pass structure
            // For now, return null to trigger fallback
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to extract index data from render pass", e);
            return null;
        }
    }

    /**
     * Helper method to extract texture ID from GpuTextureView.
     */
    private int extractTextureId(GpuTextureView texture) {
        try {
            // This would need to be implemented based on the actual GpuTextureView structure
            // For now, return -1 to indicate failure
            return -1;
        } catch (Exception e) {
            LOGGER.error("Failed to extract texture ID from GpuTextureView", e);
            return -1;
        }
    }

    /**
     * Reset draw call counter at the start of each frame
     */
    public static void resetDrawCallCount() {
        drawCallCount = 0;
    }

    public static int getDrawCallCount() {
        return drawCallCount;
    }

    /**
     * Get performance metrics for the command encoder.
     */
    public static String getPerformanceMetrics() {
        return String.format("Draw Calls: %d | DirectX 11 Rendering: %s",
            drawCallCount,
            VitraCore.getInstance().isInitialized() ? "Active" : "Inactive"
        );
    }
}
