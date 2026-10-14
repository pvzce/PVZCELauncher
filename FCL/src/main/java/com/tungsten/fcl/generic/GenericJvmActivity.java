package com.tungsten.fcl.generic;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
    private boolean firstLog = true;
    private boolean editingText;
    private int activePointer = -1;
    private int touchButton = FCLBridge.Button1;
    private final GameInputState inputState = new GameInputState((key, down) -> CallbackBridge.sendKeyPress(key, 0, down));

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
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xcc000000);
        panel.setVisibility(View.GONE);
        LinearLayout controls = new LinearLayout(this);
        button(controls, "Keys", () -> {
            releaseInput();
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        button(controls, "Log", () -> logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        button(controls, "Esc", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_ESC));
        button(controls, "Enter", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_ENTER));
        button(controls, "Space", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_SPACE));
        button(controls, "Exit", () -> new AlertDialog.Builder(this)
                .setMessage("Stop Java and close the launcher? Relaunch PVZCE Launcher for another session.")
                .setPositiveButton("Exit", (dialog, which) -> stopProcess()).setNegativeButton("Cancel", null).show());
        addKeyRow(panel, new String[] {"1", "2", "3", "4", "5"},
                new int[] {FCLKeycodes.KEY_1, FCLKeycodes.KEY_2, FCLKeycodes.KEY_3, FCLKeycodes.KEY_4, FCLKeycodes.KEY_5});
        addKeyRow(panel, new String[] {"S", "D", "F", "J", "K", "L"},
                new int[] {FCLKeycodes.KEY_S, FCLKeycodes.KEY_D, FCLKeycodes.KEY_F,
                        FCLKeycodes.KEY_J, FCLKeycodes.KEY_K, FCLKeycodes.KEY_L});
        LinearLayout commands = new LinearLayout(this);
        button(commands, "Chat T", () -> {
            CallbackBridge.sendKeyPress(FCLKeycodes.KEY_T, 't', 0, 0, true);
            CallbackBridge.sendKeyPress(FCLKeycodes.KEY_T, 0, false);
        });
        button(commands, "/", () -> {
            CallbackBridge.sendKeyPress(FCLKeycodes.KEY_SLASH, '/', 0, 0, true);
            CallbackBridge.sendKeyPress(FCLKeycodes.KEY_SLASH, 0, false);
        });
        button(commands, "Text", this::showTextInput);
        button(commands, "Backspace", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_BACKSPACE));
        button(commands, "Tab", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_TAB));
        addScrollableRow(panel, commands);
        LinearLayout mouse = new LinearLayout(this);
        button(mouse, "Touch L/R", () -> {
            releaseInput();
            touchButton = touchButton == FCLBridge.Button1 ? FCLBridge.Button3 : FCLBridge.Button1;
            onLog("Touch button: " + (touchButton == FCLBridge.Button1 ? "left" : "right") + "\n");
        });
        button(mouse, "Wheel +", () -> CallbackBridge.sendScroll(0, 1));
        button(mouse, "Wheel -", () -> CallbackBridge.sendScroll(0, -1));
        button(mouse, "F2", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_F2));
        button(mouse, "F3", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_F3));
        button(mouse, "F10", () -> CallbackBridge.sendKeyPress(FCLKeycodes.KEY_F10));
        addScrollableRow(panel, mouse);
        LinearLayout overlays = new LinearLayout(this);
        overlays.setOrientation(LinearLayout.VERTICAL);
        addScrollableRow(overlays, controls);
        overlays.addView(panel);
        root.addView(overlays, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        logScroll.setVisibility(View.GONE);
        setContentView(root);
        texture.setSurfaceTextureListener(this);
        texture.setOnTouchListener((view, event) -> {
            if (!started || exited) return true;
            // Mouse button transitions arrive through ACTION_BUTTON_PRESS/RELEASE below.
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                bridge.pushEventPointer(event.getX(), event.getY());
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                if (!grabbed) bridge.pushEventPointer(lastX, lastY);
                bridge.pushEventMouseButton(touchButton, true);
            } else if (action == MotionEvent.ACTION_MOVE && activePointer != -1) {
                int index = event.findPointerIndex(activePointer);
                if (index >= 0) {
                    float x = event.getX(index);
                    float y = event.getY(index);
                    if (grabbed) CallbackBridge.sendCursorDelta(x - lastX, y - lastY);
                    else bridge.pushEventPointer(x, y);
                    lastX = x;
                    lastY = y;
                }
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                    || (action == MotionEvent.ACTION_POINTER_UP
                    && event.getPointerId(event.getActionIndex()) == activePointer)) {
                if (activePointer != -1) bridge.pushEventMouseButton(touchButton, false);
                activePointer = -1;
            }
            return true;
        });
        texture.setOnGenericMotionListener((view, event) -> {
            if (!started || exited) return false;
            bridge.pushEventPointer(event.getX(), event.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            }
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS || event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                int button;
                switch (event.getActionButton()) {
                    case MotionEvent.BUTTON_PRIMARY:
                        button = FCLBridge.Button1;
                        break;
                    case MotionEvent.BUTTON_SECONDARY:
                        button = FCLBridge.Button3;
                        break;
                    case MotionEvent.BUTTON_TERTIARY:
                        button = FCLBridge.Button2;
                        break;
                    default:
                        return false;
                }
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

    private void addScrollableRow(LinearLayout parent, LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(row);
        parent.addView(scroll);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addKeyRow(LinearLayout parent, String[] labels, int[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setMotionEventSplittingEnabled(true);
        for (int i = 0; i < keys.length; i++) {
            final int key = keys[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setOnTouchListener((view, event) -> {
                if (!started || exited) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    inputState.press(key);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    inputState.release(key);
                }
                return true;
            });
            row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        parent.addView(row);
    }

    private void releaseInput() {
        inputState.reset();
        if (activePointer != -1 && bridge != null) bridge.pushEventMouseButton(touchButton, false);
        activePointer = -1;
    }

    private void showTextInput() {
        releaseInput();
        editingText = true;
        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Send text to game")
                .setMessage("Open the game's text field first (Chat T or /). Text is sent without Enter.")
                .setView(editor).setNegativeButton("Cancel", null).setPositiveButton("Send", null).create();
        dialog.setOnDismissListener(ignored -> editingText = false);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = editor.getText().toString();
                try {
                    GameInputState.checkedText(value);
                } catch (IllegalArgumentException error) {
                    editor.setError(error.getMessage());
                    return;
                }
                if (started && !exited) {
                    for (int i = 0; i < value.length(); i++) CallbackBridge.sendChar(value.charAt(i), 0);
                }
                dialog.dismiss();
            });
            editor.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
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
        if (started && !exited) releaseInput();
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
    public synchronized void onLog(String log) {
        if (bridge != null) {
            try (FileWriter writer = new FileWriter(bridge.getLogPath(), !firstLog)) {
                writer.write(log);
                firstLog = false;
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
        runOnUiThread(() -> {
            releaseInput();
            exited = true;
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
        if (!editingText && started && !exited && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) {
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
            releaseInput();
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && started && !exited) {
            releaseInput();
            CallbackBridge.resetInputState();
        }
    }

    @Override
    protected void onDestroy() {
        if (started && !exited) releaseInput();
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
