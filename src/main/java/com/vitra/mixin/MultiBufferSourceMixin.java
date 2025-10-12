package com.vitra.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.vitra.render.bgfx.BgfxBufferCache;
import com.vitra.render.bgfx.BgfxDrawCallManager;
import com.vitra.render.bgfx.BgfxManagers;
import com.vitra.render.bgfx.Util;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts MultiBufferSource$BufferSource.endBatch() to submit BGFX draw calls.
 *
 * This is the main draw call interception point in Minecraft's rendering pipeline.
 * When endBatch() is called, all geometry for a RenderType has been built and is ready to draw.
 *
 * Uses ONLY BGFX native methods via BgfxDrawCallManager:
 * - drawCallManager.submitIndexed() -> bgfx_set_state() + bgfx_set_vertex_buffer() + bgfx_submit()
 */
@Mixin(MultiBufferSource.BufferSource.class)
public class MultiBufferSourceMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("MultiBufferSourceMixin");

    // BgfxDrawCallManager instance for submitting draws
    private static final BgfxDrawCallManager drawCallManager = new BgfxDrawCallManager();

    /**
     * Intercept endBatch(RenderType, BufferBuilder) to submit BGFX draw calls.
     *
     * Minecraft signature: endBatch(RenderType, BufferBuilder)V
     *
     * This is a PRIVATE method that's called internally when a batch is ready to draw.
     * We intercept it to extract the MeshData and submit to BGFX.
     *
     * Uses ONLY BGFX native methods via managers:
     * - BufferBuilderMixin.getMeshData() -> get MeshData from BufferBuilder
     * - BufferBuilderMixin.getVertexBufferHandle() -> cached BGFX buffer handle
     * - BufferBuilderMixin.getIndexBufferHandle() -> cached BGFX buffer handle
     * - GlStateManagerMixin.getStateTracker() -> BgfxStateTracker.getCurrentState()
     * - BgfxManagers.getShaderManager() -> BgfxShaderManager.getActiveProgram()
     * - BgfxDrawCallManager.submitIndexed() -> bgfx_submit()
     */
    @Inject(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
            at = @At("HEAD"), remap = false)
    private void onEndBatch(RenderType renderType, BufferBuilder bufferBuilder, CallbackInfo ci) {
        try {
            LOGGER.trace("endBatch() called for RenderType: {}", renderType);

            // Get MeshData from BufferBuilder (stored in BgfxBufferCache via BufferBuilderMixin)
            MeshData meshData = BgfxBufferCache.getMeshData(bufferBuilder);
            if (meshData == null) {
                LOGGER.trace("No MeshData found for BufferBuilder, skipping draw submission");
                return;
            }

            // Get BGFX buffer handles (created in BufferBuilderMixin, cached in BgfxBufferCache)
            short vertexBufferHandle = BgfxBufferCache.getVertexBufferHandle(meshData);
            short indexBufferHandle = BgfxBufferCache.getIndexBufferHandle(meshData);

            if (!Util.isValidHandle(vertexBufferHandle)) {
                LOGGER.trace("Invalid vertex buffer handle, skipping draw submission");
                return;
            }

            // Get draw parameters from MeshData using reflection
            // Note: MeshData fields are private in 1.21.8
            int vertexCount = 0;
            int indexCount = 0;

            try {
                // Access private drawState field
                java.lang.reflect.Field drawStateField = meshData.getClass().getDeclaredField("drawState");
                drawStateField.setAccessible(true);
                Object drawState = drawStateField.get(meshData);

                // Get draw parameters from DrawState
                if (drawState != null) {
                    java.lang.reflect.Method vertexCountMethod = drawState.getClass().getDeclaredMethod("vertexCount");
                    java.lang.reflect.Method indexCountMethod = drawState.getClass().getDeclaredMethod("indexCount");

                    vertexCount = (Integer) vertexCountMethod.invoke(drawState);
                    indexCount = (Integer) indexCountMethod.invoke(drawState);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to access MeshData drawState via reflection", e);
                return;
            }

            // Get current BGFX state from state tracker
            long state = GlStateManagerMixin.getStateTracker().getCurrentState();

            // Get active shader program from shader manager
            short programHandle = BgfxManagers.getShaderManager().getActiveProgram();

            if (!Util.isValidHandle(programHandle)) {
                LOGGER.trace("No active shader program, skipping draw submission");
                return;
            }

            // Submit draw call to BGFX
            if (indexCount > 0 && Util.isValidHandle(indexBufferHandle)) {
                // Indexed draw
                drawCallManager.submitIndexed(
                    0,                  // viewId (default view)
                    programHandle,      // BGFX shader program
                    vertexBufferHandle, // BGFX vertex buffer
                    indexBufferHandle,  // BGFX index buffer
                    state,              // BGFX render state
                    0,                  // startVertex
                    vertexCount,        // numVertices
                    0,                  // startIndex
                    indexCount          // numIndices
                );

                LOGGER.trace("Submitted indexed draw: verts={}, indices={}, program={}, state=0x{}",
                    vertexCount, indexCount, programHandle, Long.toHexString(state));
            } else {
                // Non-indexed draw
                drawCallManager.submitNonIndexed(
                    0,                  // viewId (default view)
                    programHandle,      // BGFX shader program
                    vertexBufferHandle, // BGFX vertex buffer
                    state,              // BGFX render state
                    0,                  // startVertex
                    vertexCount         // numVertices
                );

                LOGGER.trace("Submitted non-indexed draw: verts={}, program={}, state=0x{}",
                    vertexCount, programHandle, Long.toHexString(state));
            }

        } catch (Exception e) {
            LOGGER.error("Exception in endBatch interception", e);
        }
    }

    /**
     * Intercept endBatch() (no args) to flush all pending batches.
     *
     * Minecraft signature: endBatch()V
     *
     * This is the PUBLIC method that flushes all pending render batches.
     */
    @Inject(method = "endBatch()V", at = @At("HEAD"), remap = false)
    private void onEndBatchAll(CallbackInfo ci) {
        LOGGER.trace("endBatch() (all) called - flushing all render batches");
        // This will trigger multiple endBatch(RenderType, BufferBuilder) calls
    }

    /**
     * Private accessor for draw call manager - internal use only.
     * Mixin classes cannot have public static methods.
     */
    private static BgfxDrawCallManager getDrawCallManager() {
        return drawCallManager;
    }
}
