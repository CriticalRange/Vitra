package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlRenderPass;
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
 * CRITICAL MIXIN: Intercepts RenderPass draw operations (Minecraft 1.21.8)
 *
 * RenderPass is the new abstraction for rendering operations in Minecraft 1.21.8.
 * It replaces the old immediate-mode OpenGL calls.
 *
 * Key methods to intercept:
 * - draw(int, int) - Basic draw call
 * - drawIndexed(int, int, int, int) - Indexed draw call
 * - drawMultipleIndexed(...) - Batched indexed draw calls
 * - setVertexBuffer(int, GpuBuffer) - Bind vertex buffer
 * - setIndexBuffer(GpuBuffer, VertexFormat$IndexType) - Bind index buffer
 */
@Mixin(GlRenderPass.class)
public class GlRenderPassMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlRenderPassMixin");
    private static int drawCallCount = 0;

    /**
     * Intercept draw - basic non-indexed draw call
     * Method signature: void draw(int offset, int count)
     */
    @Inject(
        method = "draw(II)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onDraw(int offset, int count, CallbackInfo ci) {
        drawCallCount++;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  RENDER PASS DRAW CALL (draw)                              ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Draw Call #: {}", drawCallCount);
            LOGGER.info("║ Offset:      {}", offset);
            LOGGER.info("║ Count:       {}", count);
            LOGGER.info("║ Thread:      {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onDrawArrays(offset, count);
            } catch (Exception e) {
                LOGGER.error("Exception forwarding draw to DirectX 11", e);
            }
        }
    }

    /**
     * Intercept drawIndexed - indexed draw call
     * Method signature: void drawIndexed(int baseVertex, int firstIndex, int count, int instanceCount)
     */
    @Inject(
        method = "drawIndexed(IIII)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onDrawIndexed(int baseVertex, int firstIndex, int count, int instanceCount, CallbackInfo ci) {
        drawCallCount++;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  RENDER PASS DRAW CALL (drawIndexed)                       ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Draw Call #:    {}", drawCallCount);
            LOGGER.info("║ Base Vertex:    {}", baseVertex);
            LOGGER.info("║ First Index:    {}", firstIndex);
            LOGGER.info("║ Count:          {}", count);
            LOGGER.info("║ Instance Count: {}", instanceCount);
            LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onDrawIndexed(baseVertex, firstIndex, count, instanceCount);
            } catch (Exception e) {
                LOGGER.error("Exception forwarding drawIndexed to DirectX 11", e);
            }
        }
    }

    /**
     * Intercept drawMultipleIndexed - batched indexed draw calls
     * Method signature: <T> void drawMultipleIndexed(Collection<RenderPass$Draw<T>>, GpuBuffer, VertexFormat$IndexType, Collection<String>, T)
     */
    @Inject(
        method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onDrawMultipleIndexed(
        Collection<?> objects,
        GpuBuffer indexBuffer,
        VertexFormat.IndexType indexType,
        Collection<String> validationSkippedUniforms,
        Object uniformData,
        CallbackInfo ci
    ) {
        int batchSize = objects != null ? objects.size() : 0;
        drawCallCount += batchSize;

        if (drawCallCount <= 100 || drawCallCount % 100 == 0) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  RENDER PASS BATCHED DRAW (drawMultipleIndexed)            ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Total Draw Calls: {}", drawCallCount);
            LOGGER.info("║ Batch Size:       {}", batchSize);
            LOGGER.info("║ Index Type:       {}", indexType);
            LOGGER.info("║ Index Buffer:     {}", indexBuffer != null ? "Present" : "null");
            LOGGER.info("║ Thread:           {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // Forward to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onBatchedDrawCalls(objects, indexBuffer, indexType.ordinal());
            } catch (Exception e) {
                LOGGER.error("Exception forwarding drawMultipleIndexed to DirectX 11", e);
            }
        }
    }

    /**
     * Intercept setVertexBuffer - bind vertex buffer to slot
     * Method signature: void setVertexBuffer(int index, GpuBuffer buffer)
     */
    @Inject(
        method = "setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBuffer;)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onSetVertexBuffer(int index, GpuBuffer buffer, CallbackInfo ci) {
        LOGGER.debug("RenderPass.setVertexBuffer(slot={}, buffer={})", index, buffer != null ? "Present" : "null");

        // Forward to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onBindVertexBuffer(index, buffer);
            } catch (Exception e) {
                LOGGER.error("Exception forwarding setVertexBuffer to DirectX 11", e);
            }
        }
    }

    /**
     * Intercept setIndexBuffer - bind index buffer
     * Method signature: void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat$IndexType indexType)
     */
    @Inject(
        method = "setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onSetIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        LOGGER.debug("RenderPass.setIndexBuffer(buffer={}, type={})", indexBuffer != null ? "Present" : "null", indexType);

        // Forward to DirectX 11
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                GLInterceptor.onBindIndexBuffer(indexBuffer, indexType.ordinal());
            } catch (Exception e) {
                LOGGER.error("Exception forwarding setIndexBuffer to DirectX 11", e);
            }
        }
    }

    /**
     * Reset draw call counter
     */
    public static void resetDrawCallCount() {
        drawCallCount = 0;
    }

    public static int getDrawCallCount() {
        return drawCallCount;
    }
}
