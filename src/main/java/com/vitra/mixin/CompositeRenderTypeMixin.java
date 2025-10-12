package com.vitra.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.vitra.render.bgfx.BgfxBufferCache;
import com.vitra.render.bgfx.BgfxDrawCallManager;
import com.vitra.render.bgfx.BgfxManagers;
import com.vitra.render.bgfx.Util;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CRITICAL MIXIN: Intercepts the actual draw call in Minecraft 1.21.8
 *
 * In Minecraft 1.21.8, the draw call happens in:
 * RenderType$CompositeRenderType.draw(MeshData)
 *
 * This is where Minecraft actually submits geometry to the GPU.
 * We intercept this to redirect to BGFX instead of OpenGL.
 *
 * Strategy:
 * 1. Get MeshData parameter (contains vertex/index buffers)
 * 2. Retrieve BGFX buffer handles from BgfxBufferCache
 * 3. Get current render state and active shader
 * 4. Submit indexed draw call to BGFX via BgfxDrawCallManager
 */
@Mixin(RenderType.CompositeRenderType.class)
public class CompositeRenderTypeMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("CompositeRenderTypeMixin");
    private static final BgfxDrawCallManager drawCallManager = new BgfxDrawCallManager();
    private static int drawCallCount = 0;

    /**
     * Intercept draw(MeshData) to submit BGFX draw calls.
     *
     * This is THE ACTUAL DRAW CALL in Minecraft 1.21.8.
     * Everything leads to this method.
     */
    @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/MeshData;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void onDraw(MeshData meshData, CallbackInfo ci) {
        drawCallCount++;

        // Log first few draw calls for debugging
        if (drawCallCount <= 10) {
            LOGGER.info("[@Inject WORKING] draw(MeshData) intercepted - call #{}", drawCallCount);
        }

        try {
            // CRITICAL: Cancel the original OpenGL draw call
            // We're going to do BGFX rendering instead
            ci.cancel();

            if (meshData == null) {
                LOGGER.warn("MeshData is null, skipping draw");
                return;
            }

            // Get BGFX buffer handles from cache
            short vertexBufferHandle = BgfxBufferCache.getVertexBufferHandle(meshData);
            short indexBufferHandle = BgfxBufferCache.getIndexBufferHandle(meshData);

            if (!Util.isValidHandle(vertexBufferHandle)) {
                if (drawCallCount <= 10) {
                    LOGGER.warn("Invalid vertex buffer handle for MeshData, skipping draw");
                }
                return;
            }

            // Get draw parameters from MeshData using reflection
            int vertexCount = 0;
            int indexCount = 0;

            try {
                // Access private drawState field
                java.lang.reflect.Field drawStateField = meshData.getClass().getDeclaredField("drawState");
                drawStateField.setAccessible(true);
                Object drawState = drawStateField.get(meshData);

                if (drawState != null) {
                    java.lang.reflect.Method vertexCountMethod = drawState.getClass().getDeclaredMethod("vertexCount");
                    java.lang.reflect.Method indexCountMethod = drawState.getClass().getDeclaredMethod("indexCount");

                    vertexCount = (Integer) vertexCountMethod.invoke(drawState);
                    indexCount = (Integer) indexCountMethod.invoke(drawState);

                    if (drawCallCount <= 10) {
                        LOGGER.info("Draw parameters: vertexCount={}, indexCount={}", vertexCount, indexCount);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to access MeshData drawState via reflection", e);
                return;
            }

            if (vertexCount == 0) {
                if (drawCallCount <= 10) {
                    LOGGER.warn("vertexCount is 0, skipping draw");
                }
                return;
            }

            // Get current BGFX state from state tracker
            long state = GlStateManagerMixin.getStateTracker().getCurrentState();

            // Get active shader program from shader manager
            short programHandle = BgfxManagers.getShaderManager().getActiveProgram();

            if (!Util.isValidHandle(programHandle)) {
                if (drawCallCount <= 10) {
                    LOGGER.warn("No active shader program, skipping draw");
                }
                return;
            }

            if (drawCallCount <= 10) {
                LOGGER.info("Submitting BGFX draw: program={}, vb={}, ib={}, state=0x{}",
                    programHandle, vertexBufferHandle, indexBufferHandle, Long.toHexString(state));
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

                if (drawCallCount <= 10 || drawCallCount % 100 == 0) {
                    LOGGER.info("BGFX indexed draw submitted: call #{}, verts={}, indices={}",
                        drawCallCount, vertexCount, indexCount);
                }
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

                if (drawCallCount <= 10 || drawCallCount % 100 == 0) {
                    LOGGER.info("BGFX non-indexed draw submitted: call #{}, verts={}",
                        drawCallCount, vertexCount);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Exception in draw(MeshData) interception", e);
        }
    }
}
