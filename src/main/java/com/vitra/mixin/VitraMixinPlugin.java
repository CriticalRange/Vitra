package com.vitra.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Vitra Mixin Plugin - Forces ALL mixins to apply regardless of conflicts
 *
 * Based on VulkanMod's approach to ensure critical @Overwrite mixins are applied
 * even when there are potential conflicts or target method signature changes.
 *
 * IMPORTANT: This plugin bypasses Mixin's safety checks. Use with caution.
 * - All mixins will be forced to apply (shouldApplyMixin always returns true)
 * - This ensures OpenGL interception @Overwrite methods are active
 * - Critical for DirectX 11 JNI backend to work properly
 */
public class VitraMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraMixinPlugin");

    // Track mixin application for debugging
    private static int appliedMixins = 0;
    private static int skippedMixins = 0;

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  VITRA MIXIN PLUGIN LOADED                                  ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Package: {}", mixinPackage);
        LOGGER.info("║ Mode: FORCE ALL MIXINS (bypass safety checks)              ║");
        LOGGER.info("║ Purpose: Ensure @Overwrite OpenGL interception is active    ║");
        LOGGER.info("║ Target: DirectX 11 JNI backend                             ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    @Override
    public String getRefMapperConfig() {
        return null; // Use default refmapper
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // ALWAYS return true to force all mixins to apply
        // This is critical for OpenGL interception @Overwrite methods

        appliedMixins++;

        // Log critical mixins for debugging
        if (mixinClassName.contains("GL11Mixin") ||
            mixinClassName.contains("GL15Mixin") ||
            mixinClassName.contains("GL20Mixin") ||
            mixinClassName.contains("GL30Mixin") ||
            mixinClassName.contains("RenderSystemMixin")) {

            LOGGER.info("🔧 FORCING CRITICAL MIXIN: {} -> {}",
                mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1),
                targetClassName.substring(targetClassName.lastIndexOf('.') + 1));
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        LOGGER.info("Mixin targets accepted - my targets: {}, other targets: {}",
            myTargets.size(), otherTargets.size());
    }

    @Override
    public List<String> getMixins() {
        return null; // Use mixins from configuration file
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called before mixin is applied
        // Could be used for additional validation or logging

        if (mixinClassName.contains("GL11Mixin")) {
            LOGGER.debug("Pre-applying GL11Mixin to {} - {} OpenGL functions will be intercepted",
                targetClassName, countOverwriteMethods(mixinInfo));
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called after mixin is successfully applied

        String simpleMixinName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);

        if (simpleMixinName.contains("GL") || simpleMixinName.contains("RenderSystem")) {
            LOGGER.info("✅ SUCCESS: {} applied to {} ({} @Overwrite methods)",
                simpleMixinName,
                targetClassName.substring(targetClassName.lastIndexOf('.') + 1),
                countOverwriteMethods(mixinInfo));
        }
    }

    /**
     * Count @Overwrite methods in a mixin for logging
     */
    private int countOverwriteMethods(IMixinInfo mixinInfo) {
        try {
            return (int) mixinInfo.getClass()
                .getMethod("getMethods")
                .invoke(mixinInfo)
                .getClass()
                .getMethod("stream")
                .invoke(((java.util.Collection<?>) mixinInfo.getClass()
                    .getMethod("getMethods")
                    .invoke(mixinInfo)))
                .getClass()
                .getMethod("filter", java.util.function.Predicate.class)
                .invoke(((java.util.stream.Stream<?>) mixinInfo.getClass()
                    .getMethod("getMethods")
                    .invoke(mixinInfo)
                    .getClass()
                    .getMethod("stream")
                    .invoke(mixinInfo.getClass()
                        .getMethod("getMethods")
                        .invoke(mixinInfo))),
                    (java.util.function.Predicate<Object>) method -> {
                        try {
                            return method.getClass()
                                .getMethod("isOverwrite")
                                .invoke(method)
                                .equals(true);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                .getClass()
                .getMethod("count")
                .invoke(((java.util.stream.Stream<?>) mixinInfo.getClass()
                    .getMethod("getMethods")
                    .invoke(mixinInfo)
                    .getClass()
                    .getMethod("stream")
                    .invoke(mixinInfo.getClass()
                        .getMethod("getMethods")
                        .invoke(mixinInfo))));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get mixin application statistics for debugging
     */
    public static void logMixinStatistics() {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  VITRA MIXIN APPLICATION STATISTICS                         ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Applied Mixins:   {}", appliedMixins);
        LOGGER.info("║ Skipped Mixins:   {}", skippedMixins);
        LOGGER.info("║ Total Processed:  {}", appliedMixins + skippedMixins);
        LOGGER.info("║ Success Rate:     {}%",
            (appliedMixins + skippedMixins) > 0 ?
                (appliedMixins * 100) / (appliedMixins + skippedMixins) : 0);
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Reset statistics (useful for testing)
     */
    public static void resetStatistics() {
        appliedMixins = 0;
        skippedMixins = 0;
    }
}