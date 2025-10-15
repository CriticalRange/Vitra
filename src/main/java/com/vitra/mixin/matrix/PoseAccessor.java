package com.vitra.mixin.matrix;

import org.joml.Matrix3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for PoseStack.Pose to access trustedNormals field
 * Based on VulkanMod's approach
 */
@Mixin(value = com.mojang.blaze3d.vertex.PoseStack.Pose.class)
public interface PoseAccessor {

    @Accessor("trustedNormals")
    boolean trustedNormals();

    @Accessor("normal")
    Matrix3f normal();
}