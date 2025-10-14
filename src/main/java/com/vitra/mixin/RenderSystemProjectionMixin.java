package com.vitra.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept GameRenderer and sync projection matrix to DirectX 11
 *
 * CRITICAL FIX: In Minecraft 1.21.8, RenderSystem stores matrices differently:
 * - ModelView matrix: RenderSystem.getModelViewMatrix() returns Matrix4f directly
 * - Projection matrix: RenderSystem.getProjectionMatrixBuffer() returns GpuBufferSlice
 *
 * We need to intercept BEFORE Minecraft sends these to the GPU, so we can forward
 * them to our DirectX 11 constant buffer.
 */
@Mixin(GameRenderer.class)
public class RenderSystemProjectionMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderSystemProjectionMixin");

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Camera mainCamera;

    private static boolean loggedOnce = false;

    /**
     * Intercept RenderSystem state EARLY - right when the projection is being set up
     * This is called ONCE per frame during rendering initialization
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!VitraNativeRenderer.isInitialized()) {
            return;
        }

        try {
            GameRenderer gameRenderer = (GameRenderer)(Object)this;

            // Get FOV from Minecraft options
            double fovSetting = minecraft.options.fov().get();
            float fov = (float)fovSetting;

            // Get the projection matrix from GameRenderer
            // This gives us the perspective/orthographic projection
            Matrix4f projectionMatrix = gameRenderer.getProjectionMatrix(fov);

            // Get the model-view matrix from RenderSystem
            // This contains camera position and rotation
            Matrix4f modelViewMatrix = RenderSystem.getModelViewMatrix();

            if (projectionMatrix != null && modelViewMatrix != null) {
                // Calculate combined MVP matrix
                // JOML matrices are column-major, multiplication order: projection * modelView
                Matrix4f mvpMatrix = new Matrix4f(projectionMatrix);
                mvpMatrix.mul(modelViewMatrix);

                // Extract all matrices as float arrays (16 elements, column-major)
                float[] mvpData = new float[16];
                float[] modelViewData = new float[16];
                float[] projectionData = new float[16];

                mvpMatrix.get(mvpData);
                modelViewMatrix.get(modelViewData);
                projectionMatrix.get(projectionData);

                // Send ALL matrices to DirectX 11 constant buffer
                // Native code will transpose to row-major for DirectX
                VitraNativeRenderer.setTransformMatrices(mvpData, modelViewData, projectionData);

                // Log once per session for verification
                if (!loggedOnce) {
                    LOGGER.info("=================================================");
                    LOGGER.info("REAL PROJECTION MATRIX SYNCHRONIZED!");
                    LOGGER.info("FOV: {}, Projection available: true, ModelView available: true", fov);
                    LOGGER.info("This will now override the forced orthographic projection");
                    LOGGER.info("=================================================");
                    loggedOnce = true;
                }
            } else {
                if (!loggedOnce) {
                    LOGGER.warn("Projection or ModelView matrix is null!");
                    LOGGER.warn("Projection: {}, ModelView: {}", projectionMatrix, modelViewMatrix);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to sync real projection matrices", e);
        }
    }
}
