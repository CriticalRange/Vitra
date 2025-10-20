package com.vitra.mixin.debug;

import com.vitra.VitraMod;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * DirectX 11 Debug Screen Overlay Mixin
 *
 * Based on VulkanMod's DebugScreenOverlayM but adapted for DirectX 11.
 * Adds DirectX 11 specific information to Minecraft's F3 debug screen.
 *
 * Information displayed:
 * - Vitra mod version
 * - DirectX 11 device name (GPU)
 * - DirectX 11 driver version
 * - Native memory usage
 * - Device memory usage
 * - CPU information
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayM {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Font font;

    @Shadow
    private static long bytesToMegabytes(long bytes) {
        return 0;
    }

    @Shadow
    protected abstract List<String> getGameInformation();

    @Shadow
    protected abstract List<String> getSystemInformation();

    /**
     * Redirect system information list to include DirectX 11 details
     */
    @Redirect(method = "getSystemInformation",
              at = @At(value = "INVOKE",
                      target = "Lcom/google/common/collect/Lists;newArrayList([Ljava/lang/Object;)Ljava/util/ArrayList;"))
    private ArrayList<String> redirectList(Object[] elements) {
        ArrayList<String> strings = new ArrayList<>();

        // Memory information
        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;

        // Java and memory stats
        strings.add(String.format("Java: %s", System.getProperty("java.version")));
        strings.add(String.format("Mem: % 2d%% %03d/%03dMB",
            usedMemory * 100L / maxMemory,
            bytesToMegabytes(usedMemory),
            bytesToMegabytes(maxMemory)));
        strings.add(String.format("Allocated: % 2d%% %03dMB",
            totalMemory * 100L / maxMemory,
            bytesToMegabytes(totalMemory)));
        strings.add(String.format("Off-heap: " + getOffHeapMemory() + "MB"));

        // DirectX 11 specific information
        strings.add("");
        strings.add("Vitra " + VitraMod.VERSION);

        try {
            // Get DirectX 11 device information
            String deviceInfo = VitraNativeRenderer.getDeviceInfo();
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                String[] lines = deviceInfo.split("\n");
                for (String line : lines) {
                    strings.add(line);
                }
            } else {
                strings.add("GPU: DirectX 11 (device info unavailable)");
            }
        } catch (Exception e) {
            strings.add("GPU: DirectX 11 (error retrieving info)");
        }

        strings.add("");
        strings.add("");

        return strings;
    }

    /**
     * Get off-heap memory usage in megabytes
     */
    private long getOffHeapMemory() {
        return bytesToMegabytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
    }
}
