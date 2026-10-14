package com.tungsten.fcl.generic;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mio.service.GameService;
import com.tungsten.fcl.game.sdl.SdlBridge;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.bridge.FCLBridgeCallback;
import com.tungsten.fclauncher.keycodes.AndroidKeycodeMap;
import com.tungsten.fclauncher.keycodes.FCLKeycodes;
import com.tungsten.fclauncher.keycodes.LwjglGlfwKeycode;
import com.tungsten.fcllibrary.component.FCLActivity;

import org.libsdl.app.SDLActivity;
import org.lwjgl.glfw.CallbackBridge;

import java.io.FileWriter;
import java.io.IOException;

/** Minimal general-purpose GLFW window. Does not read/write Minecraft options or profiles. */
public class GenericJvmActivity extends FCLActivity implements TextureView.SurfaceTextureListener, FCLBridgeCallback {
    public static FCLBridge pendingBridge;
    private FCLBridge bridge;
    private boolean started;
    private boolean exited;
    private TextureView texture;
    private FrameLayout root;
    private Surface surface;
    private TextView logs;
    private ScrollView logScroll;
    private float lastX;
    private float lastY;
    private boolean grabbed;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        bridge = pendingBridge;
        pendingBridge = null;
        if (bridge == null) {
            new AlertDialog.Builder(this).setMessage("The Java session is unavailable. Return to the launcher and launch again.")
                    .setPositiveButton("Close", (dialog, which) -> finish()).setCancelable(false).show();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        logs = new TextView(this);
        logs.setTextColor(0xffffffff);
        logs.setBackgroundColor(0xcc000000);
        logs.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logs);
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(-1, 220, Gravity.BOTTOM);
        root.addView(logScroll, logParams);
        LinearLayout controls = new LinearLayout(this);
        button(controls, "Log", () -> logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        button(controls, "Esc", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_ESC));
        button(controls, "Enter", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_ENTER));
        button(controls, "Space", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_SPACE));
        button(controls, "Exit", () -> new AlertDialog.Builder(this)
                .setMessage("Stop Java and close the launcher? Relaunch PVZCE Launcher for another session.")
                .setPositiveButton("Exit", (dialog, which) -> stopProcess()).setNegativeButton("Cancel", null).show());
        root.addView(controls, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        setContentView(root);
        texture.setSurfaceTextureListener(this);
        texture.setOnTouchListener((view, event) -> {
            if (!started || exited) return true;
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!grabbed) bridge.pushEventPointer(x, y);
                    bridge.pushEventMouseButton(FCLBridge.Button1, true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (grabbed) CallbackBridge.sendCursorDelta(x - lastX, y - lastY);
                    else bridge.pushEventPointer(x, y);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    bridge.pushEventMouseButton(FCLBridge.Button1, false);
                    break;
                default:
                    break;
            }
            lastX = x;
            lastY = y;
            return true;
        });
        texture.setOnGenericMotionListener((view, event) -> {
            if (!started || exited) return false;
            bridge.pushEventPointer(event.getX(), event.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            }
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS || event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                int button = event.getActionButton() == MotionEvent.BUTTON_SECONDARY ? FCLBridge.Button3 : FCLBridge.Button1;
                bridge.pushEventMouseButton(button, event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS);
            }
            return true;
        });
        try {
            GameService.start(this);
        } catch (RuntimeException error) {
            onLog("Foreground service unavailable: " + error + "\n");
        }
    }

    private void button(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> {
            if (started && !exited) action.run();
        });
        layout.addView(button);
    }

    private void resize(SurfaceTexture target, int width, int height) {
        target.setDefaultBufferSize(width, height);
        CallbackBridge.windowWidth = width;
        CallbackBridge.windowHeight = height;
        SdlBridge.syncResolution(width, height);
        bridge.pushEventWindow(width, height);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture target, int width, int height) {
        surface = new Surface(target);
        bridge.setSurfaceDestroyed(false);
        SdlBridge.prepareSurface(this, surface, root, this);
        resize(target, width, height);
        if (!started) {
            started = true;
            bridge.execute(surface, this);
        } else {
            CallbackBridge.setupBridgeWindow(surface);
        }
        bridge.setSurfaceTexture(target);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture target, int width, int height) {
        resize(target, width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture target) {
        bridge.setSurfaceDestroyed(true);
        if (SdlBridge.getSdlEnabled() && SDLActivity.getSDLSurface() != null) SDLActivity.getSDLSurface().surfaceDestroyed();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture target) {
    }

    @Override
    public void onCursorModeChange(int mode) {
        grabbed = mode == FCLBridge.CursorDisabled;
    }

    @Override
    public void onLog(String log) {
        if (bridge != null) {
            try (FileWriter writer = new FileWriter(bridge.getLogPath(), true)) {
                writer.write(log);
            } catch (IOException ignore) {
                // The on-screen log remains available when file output fails.
            }
        }
        runOnUiThread(() -> {
            if (logs == null) return;
            String text = logs.getText().toString() + log;
            logs.setText(text.substring(Math.max(0, text.length() - 16000)));
        });
    }

    @Override
    public void onExit(int code) {
        exited = true;
        runOnUiThread(() -> {
            logScroll.setVisibility(View.VISIBLE);
            GameService.stop(this);
            new AlertDialog.Builder(this).setTitle("Java exited: " + code)
                    .setMessage("Log: " + bridge.getLogPath() + "\nClose and reopen PVZCE Launcher for another session.")
                    .setCancelable(false).setPositiveButton("Close", (dialog, which) -> stopProcess()).show();
        });
    }

    private void stopProcess() {
        GameService.stop(this);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (started && !exited && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
            int key = AndroidKeycodeMap.convertKeycode(event.getKeyCode());
            if (key != FCLKeycodes.KEY_UNKNOWN) {
                CallbackBridge.holdingShift = event.isShiftPressed();
                CallbackBridge.holdingCtrl = event.isCtrlPressed();
                CallbackBridge.holdingAlt = event.isAltPressed();
                CallbackBridge.sendKeyPress(key, (char) event.getUnicodeChar(), event.getScanCode(),
                        CallbackBridge.getCurrentMods(), event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        if (started && !exited) {
            CallbackBridge.resetInputState();
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started && !exited) CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
    }

    @Override
    protected void onDestroy() {
        GameService.stop(this);
        SdlBridge.reset();
        if (surface != null) surface.release();
        if (started && !exited && isFinishing()) stopProcess();
        super.onDestroy();
    }

    @Keep
    public int messageboxShowMessageBox(int flags, String title, String message, int[] buttonFlags,
                                       int[] buttonIds, String[] buttonTexts, int[] colors) {
        return SDLActivity.messageboxShowMessageBox(this, flags, title, message, buttonFlags, buttonIds, buttonTexts, colors);
    }
}
