package com.vitra.util;

/**
 * System Information Utility
 *
 * Provides CPU and system information for debug screens and crash reports.
 * Uses standard Java APIs instead of external libraries like OSHI.
 */
public class SystemInfo {
    /**
     * CPU information string (model name and core count)
     * Example: "Intel Core i7-10700K (16 cores)"
     */
    public static final String cpuInfo;

    static {
        cpuInfo = getCpuInfo();
    }

    /**
     * Get CPU information using standard Java APIs
     *
     * @return CPU model name and core count
     */
    private static String getCpuInfo() {
        try {
            // Get CPU name from system property (may not be available on all platforms)
            String cpuName = System.getenv("PROCESSOR_IDENTIFIER");

            // Get available processors (logical cores)
            int processors = Runtime.getRuntime().availableProcessors();

            if (cpuName != null && !cpuName.isEmpty()) {
                // Clean up CPU name (remove extra whitespace)
                cpuName = cpuName.replaceAll("\\s+", " ").trim();
                return String.format("%s (%d cores)", cpuName, processors);
            } else {
                // Fallback if PROCESSOR_IDENTIFIER not available
                return String.format("Unknown CPU (%d cores)", processors);
            }
        } catch (Exception e) {
            // If anything fails, provide basic fallback
            return "CPU information unavailable";
        }
    }
}
