package com.zhousl.aether.agentmode;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IAetherAgentModeService {
    int createDisplay(String name, int width, int height, int density, in Surface surface);
    int createOwnedDisplay(String name, int width, int height, int density);
    void releaseDisplay(int displayId);
    void launchPackage(String packageName, int displayId);
    void runInputCommand(String command);
    void tap(int displayId, int x, int y);
    void swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);
    void key(int displayId, String keyCode);
    void text(int displayId, String text);
    byte[] capturePng(int displayId);
    ParcelFileDescriptor capturePngPipe(int displayId);
    String listDisplaysJson();
}
