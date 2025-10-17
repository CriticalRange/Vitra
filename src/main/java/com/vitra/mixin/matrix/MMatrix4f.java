package com.vitra.mixin.matrix;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 Matrix4f mixin following VulkanMod standards.
 * Ensures proper depth buffer handling for DirectX 11 by forcing zZeroToOne=true.
 *
 * Pattern based on VulkanMod's MMatrix4f with DirectX 11 depth buffer considerations.
 * DirectX 11 uses a [0,1] normalized device coordinate range for Z, unlike OpenGL's [-1,1].
 */
@Mixin(Matrix4f.class)
public abstract class MMatrix4f {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/Matrix4f");

    @Shadow public abstract Matrix4f perspective(float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne);
    @Shadow public abstract Matrix4f ortho(float left, float right, float bottom, float top, float zNear, float zFar, boolean zZeroToOne);

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 requires zZeroToOne=true for proper depth buffer handling
     */
    @Overwrite(remap = false)
    public Matrix4f setOrtho(float left, float right, float bottom, float top, float zNear, float zFar) {
        try {
            return new Matrix4f().setOrtho(left, right, bottom, top, zNear, zFar, true);
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback with logging
            LOGGER.warn("Failed to create DirectX 11 orthographic matrix, using default");
            return new Matrix4f().setOrtho(left, right, bottom, top, zNear, zFar, false);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 requires zZeroToOne=true for proper depth buffer handling
     */
    @Overwrite(remap = false)
    public Matrix4f ortho(float left, float right, float bottom, float top, float zNear, float zFar) {
        try {
            return this.ortho(left, right, bottom, top, zNear, zFar, true);
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback with logging
            LOGGER.warn("Failed to create DirectX 11 orthographic matrix, using default");
            return this.ortho(left, right, bottom, top, zNear, zFar, false);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 requires zZeroToOne=true for proper depth buffer handling
     */
    @Overwrite(remap = false)
    public Matrix4f perspective(float fovy, float aspect, float zNear, float zFar) {
        try {
            return this.perspective(fovy, aspect, zNear, zFar, true);
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback with logging
            LOGGER.warn("Failed to create DirectX 11 perspective matrix, using default");
            return this.perspective(fovy, aspect, zNear, zFar, false);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 requires zZeroToOne=true for proper depth buffer handling
     */
    @Overwrite(remap = false)
    public Matrix4f setPerspective(float fovy, float aspect, float zNear, float zFar) {
        try {
            return new Matrix4f().setPerspective(fovy, aspect, zNear, zFar, true);
        } catch (Exception e) {
            // Follow VulkanMod pattern: silent fallback with logging
            LOGGER.warn("Failed to create DirectX 11 perspective matrix, using default");
            return new Matrix4f().setPerspective(fovy, aspect, zNear, zFar, false);
        }
    }
}