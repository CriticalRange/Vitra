package com.vitra.render.texture;

public class SpriteUpdateUtil {
    private static boolean doUpload = false;

    public static void setDoUpload(boolean doUpload) {
        SpriteUpdateUtil.doUpload = doUpload;
    }

    public static boolean shouldDoUpload() {
        return doUpload;
    }
}