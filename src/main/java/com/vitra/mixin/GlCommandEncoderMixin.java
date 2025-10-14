package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.VitraMod;
import com.vitra.render.opengl.GLInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

        // Forward to DirectX 11 renderer
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                // Forward draw call to DirectX 11
                GLInterceptor.onDrawCall(baseVertex, firstIndex, count, indexType.ordinal(), instanceCount);
            } catch (Exception e) {
                LOGGER.error("Exception forwarding draw call to DirectX 11", e);
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
     * Reset draw call counter at the start of each frame
     */
    public static void resetDrawCallCount() {
        drawCallCount = 0;
    }

    public static int getDrawCallCount() {
        return drawCallCount;
    }
}
