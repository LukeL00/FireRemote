package com.luke.fireremote;

import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Runs ON the Fire Stick (started via app_process from an ADB shell).
 * Reads key codes from stdin, one per line, and injects them instantly.
 * Much faster than `input keyevent`, which starts a new Java VM per press.
 */
public final class Injector {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Method getInstance = InputManager.class.getDeclaredMethod("getInstance");
        Object im = getInstance.invoke(null);
        Method inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);

        System.out.println("READY");
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int code;
            try { code = Integer.parseInt(line); } catch (NumberFormatException e) { continue; }
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            inject.invoke(im, down, 0); // 0 = async
            inject.invoke(im, up, 0);
        }
    }
}
