package com.vitra.core.config;

/**
 * Supported rendering backends for Vitra
 */
public enum RendererType {
    /**
     * DirectX 11 backend - Windows only, primary supported backend
     */
    DIRECTX11("DirectX 11", true, false, false);

    private final String displayName;
    private final boolean supportsWindows;
    private final boolean supportsLinux;
    private final boolean supportsMacOS;

    RendererType(String displayName, boolean supportsWindows, boolean supportsLinux, boolean supportsMacOS) {
        this.displayName = displayName;
        this.supportsWindows = supportsWindows;
        this.supportsLinux = supportsLinux;
        this.supportsMacOS = supportsMacOS;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSupported() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return supportsWindows;
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return supportsLinux;
        } else if (os.contains("mac")) {
            return supportsMacOS;
        }

        return false; // Unknown OS
    }

    /**
     * Get the best available renderer for the current platform
     */
    public static RendererType getBestAvailable() {
        // Only DirectX 11 is supported
        if (DIRECTX11.isSupported()) {
            return DIRECTX11;
        }
        throw new RuntimeException("DirectX 11 is not supported on this platform. Vitra requires Windows.");
    }

    public boolean supportsWindows() { return supportsWindows; }
    public boolean supportsLinux() { return supportsLinux; }
    public boolean supportsMacOS() { return supportsMacOS; }
}