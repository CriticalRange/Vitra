package com.vitra.mixin.debug.crash_report;

import com.vitra.render.jni.VitraD3D11Renderer;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DirectX 11 System Report Crash Report Mixin
 *
 * Based on VulkanMod's SystemReportM but adapted for DirectX 11.
 * Adds DirectX 11 device information to Minecraft crash reports.
 *
 * Key responsibilities:
 * - Inject into SystemReport.appendToCrashReportString()
 * - Append DirectX 11 device details to crash report
 * - Include GPU name, driver version, device capabilities
 * - Aid in crash diagnosis and bug reporting
 *
 * When Minecraft crashes, the crash report will include a section like:
 * ```
 * -- Vitra Device Report --
 * GPU: NVIDIA GeForce RTX 3080
 * Driver: 31.0.15.4601
 * DirectX 11 Feature Level: 11.1
 * Video Memory: 10240 MB
 * ```
 *
 * This information is critical for diagnosing DirectX 11 specific issues
 * and helps users provide better bug reports.
 */
@Mixin(SystemReport.class)
public class SystemReportMixin {

    /**
     * Inject DirectX 11 device information into crash report
     *
     * This injection point is at RETURN, meaning it appends information
     * after all default system information has been added.
     *
     * The DirectX 11 device report includes:
     * - GPU vendor and model name
     * - Driver version
     * - DirectX feature level
     * - Video memory information
     * - Any DirectX debug layer warnings/errors
     *
     * @param stringBuilder Crash report string builder
     * @param ci Callback info for injection
     */
    @Inject(method = "appendToCrashReportString", at = @At("RETURN"))
    private void addVitraDeviceInfo(StringBuilder stringBuilder, CallbackInfo ci) {
        stringBuilder.append("\n\n -- Vitra Device Report --");

        try {
            // Get DirectX 11 device information
            String deviceInfo = VitraD3D11Renderer.getDeviceInfo();

            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                // Append each line of device info to crash report
                String[] lines = deviceInfo.split("\n");
                for (String line : lines) {
                    stringBuilder.append("\n\t").append(line);
                }
            } else {
                stringBuilder.append("\n\tDirectX 11 device information unavailable");
            }

            // Append debug statistics if available
            try {
                String debugStats = VitraD3D11Renderer.getDebugStats();
                if (debugStats != null && !debugStats.isEmpty()) {
                    stringBuilder.append("\n\n\t-- DirectX 11 Debug Statistics --");
                    String[] statLines = debugStats.split("\n");
                    for (String line : statLines) {
                        stringBuilder.append("\n\t").append(line);
                    }
                }
            } catch (Exception debugError) {
                // Debug stats not critical, ignore if unavailable
            }

        } catch (Exception e) {
            stringBuilder.append("\n\tError retrieving DirectX 11 device info: ")
                         .append(e.getMessage());
        }
    }
}
