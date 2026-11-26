package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.KeyEvent.*;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.*;
import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.WinHandler;

import com.termux.x11.R;
import com.termux.x11.extrakeys.ExtraKeysInfo;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends LoriePreferences {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    public static Handler handler = new Handler();
    private final Runnable connectRetry = this::tryConnect;
    FrameLayout frm;
    TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private DisplayManager displayManager;
    private boolean showIMEWhileExternalConnected = true;
    private boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;
    /** The display the system letterboxed us on instead of rotating, {@code null} until it does. */
    private Rect orientationDeniedAt = null;
    private String screenIdleTimeoutArmedMode = null; // numeric screenIdleTimeout mode the pending idle check reflects, or null if none pending
    private boolean x11SleepInhibited = false;
    private final Runnable screenIdleTimeoutCheck = this::checkScreenIdleTimeout;
    @Keep private long lastServerHeartbeat; // Kept for JNI activity.cpp compatibility
    /** Aspect ratios outside of the range the device is configured with are rejected by the system. */
    private static final float MIN_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMinAspectRatio", 1.f / 2.39f);
    private static final float MAX_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMaxAspectRatio", 2.39f);

    // public Prefs prefs;
    // LorieApp app;

    private boolean oldFullscreen = false, oldHideCutout = false;
    private OrientationEventListener orientationListener;
    //Custom code
    // private Notification mNotification;
    // private final int mNotificationId = 7892;
    // NotificationManager mNotificationManager;
    static InputMethodManager inputMethodManager;
    protected View lorieContentView;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);
    private DrawerLayout drawerLayout;
    private static boolean softKeyboardShown = false;
    public static Prefs prefs;
    //Custom code end

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!getLorieView().connected())
                return false;

            finishStartupDraw();
            return true;
        }
    };

    private void finishStartupDraw() {
        View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener);
        content.invalidate();
    }

    private static final Map<String, WeakReference<MainActivity>> instances = new HashMap<>();
    private String tag = "";
    private boolean redirected;

    /** Redirects a tagged intent that landed on plain MainActivity to MainActivityTagged. Returns the parsed tag, or null if it relaunched. */
    private String relaunchAsTaggedIfNeeded(Intent intent, boolean finishSelf) {
        // Tag comes from the launch Intent's data: either the host of a "termux-x11-window://<tag>"
        // URI, or a bare schemeless value ("-d <tag>") used as the tag directly.
        Uri docData = intent.getData();
        String tag = docData != null && docData.getHost() != null ? docData.getHost()
                : docData != null && docData.getScheme() == null ? docData.toString() : "";

        if (tag.isEmpty())
            return tag;

        if (finishSelf)
            finish();
        startTaggedActivity(new Intent(intent));
        return null;
    }

    public String getTag() {
        return tag;
    }

    /** The window registered under the given "-tag" argument, or the default window for "". */
    public static MainActivity getInstance(String tag) {
        WeakReference<MainActivity> ref = instances.get(tag == null ? "" : tag);
        return ref != null ? ref.get() : null;
    }

    /** Whichever window currently has input focus, or null if none does. */
    public static MainActivity getFocusedInstance() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && a.hasWindowFocus())
                return a;
        }
        return null;
    }

    /** All windows that are currently alive. */
    public static List<MainActivity> getInstances() {
        List<MainActivity> result = new ArrayList<>();
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                result.add(a);
        }
        return result;
    }

    /** Launches (or switches to) the MainActivityTagged window for the given intent's data. */
    private void startTaggedActivity(Intent intent) {
        intent.setClass(this, MainActivity.class);
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent, freeformActivityOptions());
    }

    /** ActivityOptions requesting freeform windowing mode. */
    private static Bundle freeformActivityOptions() {
        ActivityOptions opts = ActivityOptions.makeBasic();
        nativeSetLaunchWindowingModeFreeform(opts);
        return opts.toBundle();
    }

    /** The focused window, or any window that hasn't been garbage-collected yet, or null if none are alive. */
    public static MainActivity getFocusedOrAnyInstance() {
        MainActivity focused = getFocusedInstance();
        if (focused != null)
            return focused;
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                return a;
        }
        return null;
    }

    private final Set<Integer> pressedKeys = new LinkedHashSet<>();

    public void markKeyPressed(int keyCode) {
        pressedKeys.add(keyCode);
    }

    public void markKeyReleased(int keyCode) {
        pressedKeys.remove(keyCode);
    }

    /** The window that considers the given key code held down, if any. */
    public static MainActivity getInstanceWithPressedKey(int keyCode) {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && a.pressedKeys.contains(keyCode))
                return a;
        }
        return null;
    }

    /** Whether any window still has a key held down. */
    public static boolean anyInstanceHasPressedKeys() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && !a.pressedKeys.isEmpty())
                return true;
        }
        return false;
    }

    public static void clearAllPressedKeys() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                a.pressedKeys.clear();
        }
    }

    public static MainActivity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity)
                return (MainActivity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    //Custom code
    //////////// gamepad
    private void checkConnectedControllers() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    && !isIgnoredDevice(device)) {

                String msg = "Controller:🎮 " + device.getName() + " (ID:" + id + ")";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                Log.d("ControllerDebug", msg);
            }
        }
    }

    /// check fingerprint sensors that acts like gamepad
    private boolean isIgnoredDevice(InputDevice device) {
        if (device == null) return true;

        String name = device.getName().toLowerCase();

        // Ignore fingerprint or virtual devices that masquerade as gamepads
        return name.contains("uinput-fpc") ||
                name.contains("fingerprint") ||
                name.contains("fpc1020") ||   // common FPC models
                name.contains("goodix") ||     // Goodix sensors
                device.isVirtual();            // Ignore system-generated virtual inputs
    }

   private boolean isGamepadConnected() {
    int[] deviceIds = InputDevice.getDeviceIds();
    for (int id : deviceIds) {
        InputDevice device = InputDevice.getDevice(id);
        if (device == null) continue;
        if (isIgnoredDevice(device)) continue;

        if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
            }
        }
        return false;
    }

    public boolean isWineRunning() {
        try {
            // Fully qualify java.lang.Process to avoid conflict with android.os.Process
            java.lang.Process process = Runtime.getRuntime().exec("pgrep -f winhandler.exe");
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void setupTermuxActivityListener() {
        this.termuxActivityListener = new TermuxActivityListener() {

            @Override
            public void releaseSlider(boolean open) {
                // For MainActivity, we don't have a slider UI
                Log.d("MainActivity", "Slider released: " + open);
            }

            @Override
            public void onChangeOrientation(int orientation) {
                // Set orientation for MainActivity
                setRequestedOrientation(orientation);    
                // Also update the LorieView if connected
                if (getLorieView() != null) {
                    getLorieView().regenerate();
                }
            }
            @Override
            public void openSoftwareKeyboard() {
                // Toggle keyboard visibility
                toggleKeyboardVisibility();
            }

            @Override
            public void onExitApp() {
                // Exit the app
                // System.exit(0);
                finish();
                // finishAffinity();
            }
        };
    }

    private void removeFloatingButton() {
        // Implement if you add floating button functionality
    }

    private void showFloatingMenu() {
        // Implement if you add floating button functionality
    }

    // Don't override - use the existing method from LoriePreferences
    public void showInputControlsDialog() {
    // Use the existing method from LoriePreferences
        if (this instanceof LoriePreferences) {
            super.showInputControlsDialog();
        } else {
        // Fallback if needed
            if (inputControlsView != null) {
            // Show the input controls directly
                inputControlsView.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Input controls enabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

private void showPreferencesInDrawer() {
    try {
        FrameLayout prefContainer = findViewById(R.id.preferences_container);
        prefContainer.setVisibility(View.VISIBLE);    
        // Load drawer preferences fragment
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.preferences_container, new DrawerPreferenceFragment())
            .commit();
            
    } catch (Exception e) {
        Log.e("MainActivity", "Error showing preferences", e);
        Toast.makeText(this, "Failed to load preferences", Toast.LENGTH_SHORT).show();
    }
}

    // Update onBackPressed to handle drawer navigation
private long backPressedTime = 0;

@Override
public void onBackPressed() {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
        // Close drawer and lock it again
        drawerLayout.closeDrawer(GravityCompat.START);
        
        // Re-lock the drawer after closing
        drawerLayout.postDelayed(() -> {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }, 100);
        
        // Remove fragment
        FrameLayout prefContainer = findViewById(R.id.preferences_container);
        if (prefContainer != null) {
            prefContainer.removeAllViews();
            prefContainer.setVisibility(View.GONE);
        }
        
        // Give focus back to LorieView
        LorieView lorie = getLorieView();
        if (lorie != null) {
            lorie.requestFocus();
        }
    } else {
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            showPreferencesInDrawer();
            drawerLayout.openDrawer(GravityCompat.START);
        // Double tap to exit optional 
      /*  if (backPressedTime + 2000 > System.currentTimeMillis()) {
            //super.onBackPressed();
            finish();
        } else {
            Toast.makeText(this, "Press back 2 times to exit", Toast.LENGTH_SHORT).show();
        }        
        backPressedTime = System.currentTimeMillis();
        */
    }
}

    public void prepareToExit() {
        Log.d("MainActivity", "prepareToExit called from notification");

        // Run on UI thread to ensure proper execution
        runOnUiThread(() -> {
            try {
                // Close activity if it's still valid
                if (!isFinishing() && !isDestroyed()) {
                    finish();
                }

                // Exit process completely
                handler.postDelayed(() -> {
                    // System.exit(0);
                    finish();
                }, 100);

            } catch (Exception e) {
                Log.e("MainActivity", "Error in prepareToExit", e);
                // Fallback: just kill the process
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    //// touch fix
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Log.d("MainActivity", "dispatchTouchEvent - Action: " +
                MotionEvent.actionToString(ev.getAction()));

        // Don't handle touches when drawer is open
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            return super.dispatchTouchEvent(ev);
        }

        // If input controls are visible and have a profile, let them try to handle it first
        if (inputControlsView != null &&
                inputControlsView.getVisibility() == View.VISIBLE &&
                inputControlsView.getProfile() != null) {

            // Check if touch is within input controls bounds
            int[] location = new int[2];
            inputControlsView.getLocationOnScreen(location);

            float x = ev.getRawX();
            float y = ev.getRawY();

            if (x >= location[0] && x <= location[0] + inputControlsView.getWidth() &&
                    y >= location[1] && y <= location[1] + inputControlsView.getHeight()) {

                // Convert to view coordinates
                float viewX = x - location[0];
                float viewY = y - location[1];

                MotionEvent adjustedEvent = MotionEvent.obtain(ev);
                adjustedEvent.setLocation(viewX, viewY);

                boolean handled = inputControlsView.handleTouchEvent(adjustedEvent);
                adjustedEvent.recycle();

                if (handled) {
                    Log.d("MainActivity", "Input controls handled touch in dispatchTouchEvent");
                    return true;
                }
            }
        }

        // If not handled by input controls, pass to normal touch handling
        return super.dispatchTouchEvent(ev);
    }

    private static void closeSoftKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(getInstance().getWindow().getDecorView().getRootView().getWindowToken(), 0);
        softKeyboardShown = false;
    }

    private static void openSoftKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        softKeyboardShown = true;
    }

    private void setupInputController() {
        xServer = getLorieView();
        globalCursorSpeed = 1.0f;
        touchpadView = new TouchpadView(this, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setVisibility(View.GONE);
        // touchpadView.setBackground(getDrawable(R.drawable.touchpad_background));
        frm.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        frm.addView(inputControlsView);
        inputControlsManager = new InputControlsManager(this);
        // String shortcutPath = getIntent().getStringExtra("shortcut_path");
        // container = new Container(0);
        
    }

    //Custom code end

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity_content);
        drawerLayout = findViewById(R.id.drawer_layout);
        lorieContentView = findViewById(R.id.id_display_window);
        setupTermuxActivityListener();
        applyWindowSettings();
        showPreferencesInDrawer();

        frm = findViewById(R.id.frame);
        findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));
        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(this, lorieView));
        mLorieKeyListener = (v, k, e) -> {
        
        /// fixing controller binding and support
           if (e.getDevice() == null) {
            return mInputHandler.sendKeyEvent(e);
            }
       
             if (k == KEYCODE_BACK) {
                if (softKeyboardShown) {
                  if (e.getAction() == ACTION_UP) {
                    closeSoftKeyboard();
                  }           
                   return false;
                }
                
             }
            
        
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

             /// gamepad and touch 
        if (!isIgnoredDevice(dev) && isGamepadConnected()) {
              InputDevice device = e.getDevice();
    
              //Toast.makeText(this,"Handled Key: " + KeyEvent.keyCodeToString(e.getKeyCode()),Toast.LENGTH_SHORT).show();    
              // Toast.makeText(this, "Handled:=" + e, Toast.LENGTH_SHORT).show();
              // inputControlsView.dispatchKeyEvent(e);

            boolean handledByWine = false;
            boolean handledByX11 = false;
            boolean handledByInputHandler = false;

              if (isWineRunning()) {
                winHandler.onKeyEvent(e); // usually no return value
                handledByWine = true;
              }

            // call X11
            handledByX11 = inputControlsView.dispatchKeyEvent(e);
            // call Termux fallback input        
            handledByInputHandler = mInputHandler.sendKeyEvent(e);

            // Debug toast if needed
             //Toast.makeText(this, "Handled: wine=" + handledByWine + " x11=" + handledByX11 + " fallback=" + handledByInputHandler, Toast.LENGTH_SHORT).show();       
            return handledByWine || handledByX11 || handledByInputHandler;
        }      
          // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
                
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieView.setOnCapturedPointerListener((v, e) -> {
            if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                return false;
            }
            return mInputHandler.handleTouchEvent(lorieView, lorieView, e);
        });
        lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((screenWidth, screenHeight, inputTransform) ->
                mInputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));

       displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                setTerminalToolbarViewLayout();
            }
        };
        frm.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            String savedPos;
            int savedRotation;

            @Override
            public void onGlobalLayout() {
                Display d = frm.getDisplay();
                String pos = prefs.ekbarPosition.get();
                if ((d != null && savedRotation != d.getRotation()) || !Objects.equals(savedPos, pos)) {
                    savedRotation = d == null ? 0 : d.getRotation();
                    savedPos = pos;
                    setTerminalToolbarViewLayout();
                }
            }
        });

        lorieView.setOnTouchListener(null);
        lorieParent.setOnTouchListener(null);
        if (frm != null) {
            frm.setOnTouchListener(null);
        }

        // Set up proper touch handling on the FrameLayout
        frm.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d("TouchFix", "FrameLayout touch - Action: " +
                        MotionEvent.actionToString(event.getAction()) +
                        " at (" + event.getX() + ", " + event.getY() + ")");

                // Don't handle touches when drawer is open
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    return false;
                }

                // If input controls are visible and should handle this touch
                if (inputControlsView != null &&
                        inputControlsView.getVisibility() == View.VISIBLE &&
                        inputControlsView.getProfile() != null) {

                    // Get locations of views for coordinate conversion
                    int[] viewLocation = new int[2];
                    inputControlsView.getLocationOnScreen(viewLocation);
                    int[] frameLocation = new int[2];
                    frm.getLocationOnScreen(frameLocation);

                    // Calculate adjusted coordinates
                    float x = event.getX() - (viewLocation[0] - frameLocation[0]);
                    float y = event.getY() - (viewLocation[1] - frameLocation[1]);

                    // Create adjusted event
                    MotionEvent adjustedEvent = MotionEvent.obtain(event);
                    adjustedEvent.setLocation(x, y);

                    // Let input controls try to handle it
                    boolean handled = inputControlsView.handleTouchEvent(adjustedEvent);
                    adjustedEvent.recycle();

                    if (handled) {
                        Log.d("TouchFix", "Input controls handled touch");
                        return true;
                    }
                }

                // If not handled by input controls, pass to LorieView
                if (mInputHandler != null && lorieView != null) {
                    return mInputHandler.handleTouchEvent(lorieView, lorieView, event);
                }

                return false;
            }
        });

        // Set up direct touch handling for LorieView
        lorieView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d("TouchFix", "LorieView direct touch - Action: " +
                        MotionEvent.actionToString(event.getAction()));

                // Don't handle touches when drawer is open
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    return false;
                }

                // If input controls are visible, let frame layout handle it
                if (inputControlsView != null &&
                        inputControlsView.getVisibility() == View.VISIBLE &&
                        inputControlsView.getProfile() != null) {
                    return false;
                }

                // Handle normal LorieView touch
                if (mInputHandler != null) {
                    return mInputHandler.handleTouchEvent(v, v, event);
                }
                return false;
            }
        });

        // Set up hover listener for mouse support
        lorieView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    return false;
                }

                if (mInputHandler != null) {
                    return mInputHandler.handleTouchEvent(v, v, event);
                }
                return false;
            }
        });

        lorieView.setOnGenericMotionListener((v, e) -> {
            if (!isIgnoredDevice(e.getDevice()) && isGamepadConnected() && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                // Send to Wine if running
                if (isWineRunning()) {
                    winHandler.onGenericMotionEvent(e);
                }

                // Always send to X11
                boolean handledByX11 = inputControlsView.dispatchGenericMotionEvent(e);

                return true;
            }
            return false;
        });

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        ImeHeightProvider.assistActivity(this);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();
        setupInputController();
        checkConnectedControllers();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        winHandler = new WinHandler(this);
        lorieView.setWinHandler(winHandler);
        Executors.newSingleThreadExecutor().execute(() -> {
            winHandler.start();
        });

        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(screenIdleTimeoutCheck);
        winHandler.stop();
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && getLorieView().connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    } 

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (getLorieView().connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        int maxYDecrement = (pager.getVisibility() == View.VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((getLorieView().connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frm.getX(), maxX));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frm.getY(), maxY));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
            switch(e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    getLorieView().sendMouseEvent(0, 0, b, true, true);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    getLorieView().sendMouseEvent(0, 0, b, false, true);
                    v.setPressed(false);
                    break;
            }
            return true;
        }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationInWindow(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final ViewPager pager = getTerminalToolbarViewPager();
                        int[] offset = new int[2];
                        primaryLayer.getLocationInWindow(offset);
                        float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                        float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                        if (pager.getVisibility() == View.VISIBLE)
                            maxY -= pager.getHeight();

                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), frm.getX(), maxX));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), frm.getY(), maxY));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationInWindow(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    } 

    void connectToService(IBinder ibinder) {
        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("MainActivity", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    void disconnectService() {
        service = null;

        Log.v("Lorie", "Disconnected");
        runOnUiThread(() -> { getLorieView().connect(-1); clientConnectedStateChanged(); });
    }

    boolean tryConnect() {
        if (getLorieView().connected()) {
            handler.removeCallbacks(connectRetry);
            return false;
        }

        if (service == null) {
            boolean sent = getLorieView().requestConnection();
            scheduleConnect();
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                getLorieView().connect(fd.detachFd());
                finishStartupDraw();
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                scheduleConnect();
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            scheduleConnect();
        }
        return false;
    }

    void scheduleConnect() {
        handler.removeCallbacks(connectRetry);
        handler.postDelayed(connectRetry, 250);
    }

    void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key))
            return;

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {

        // There is no way back to the normal size from picture-in-picture, so the window is closed.
        if (isInPictureInPictureMode && !prefs.PIP.get()) {
            finish();
            return;
        }

        applyWindowSettings();
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && getLorieView().connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);

        lorieView.requestLayout();
        lorieView.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();

        orientationListener.enable();
        setTerminalToolbarView();
        getLorieView().requestFocus();

        lastServerHeartbeat = SystemClock.uptimeMillis();
    }

    @Override
    public void onPause() {
        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
        getLorieView().setKeyboardVisible(false);

        orientationListener.disable();
        super.onPause();

    }

    protected boolean isSettingsActivity() {
        return false;
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    // We can not define function-static variables in Java, so we are defining them outside a function
    private final X11ToolbarViewPager.PageAdapter mPageAdapter =
            new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e));
    private final X11ToolbarViewPager.OnPageChangeListener mOnPageListener = new X11ToolbarViewPager.OnPageChangeListener(this);
    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = !isInPictureInPictureMode && getLorieView().connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            if (pager.getAdapter() != mPageAdapter)
                pager.setAdapter(mPageAdapter);
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(mOnPageListener);
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        setTerminalToolbarViewLayout();
        getLorieView().requestFocus();
    }

    // Keep in sync with Surface.ROTATION_*
    public static final int PAGER_POSITION_TOP = 0, PAGER_POSITION_LEFT = 1, PAGER_POSITION_BOTTOM = 2, PAGER_POSITION_RIGHT = 3;
    public int getPagerPosition() {
        String _pos = prefs.ekbarPosition.get();
        int pos = "top".equals(_pos) ? PAGER_POSITION_TOP : "left".equals(_pos) ? PAGER_POSITION_LEFT : "bottom".equals(_pos) ? PAGER_POSITION_BOTTOM : "right".equals(_pos) ? PAGER_POSITION_RIGHT : 0;
        if (prefs.ekbarPositionIgnoreOrientation.get()) {
            Display dpy = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (dpy != null)
                pos = (pos + dpy.getRotation()) % 4;
        }
        return pos;
    }

    @SuppressLint("RtlHardcoded")
    private void setTerminalToolbarViewLayout() {
        handler.post(() -> {
            final ViewPager pager = getTerminalToolbarViewPager();
            boolean showNow = pager.getVisibility() == View.VISIBLE;
            ExtraKeysInfo extraKeysInfo = mExtraKeys == null ? null : mExtraKeys.getExtraKeysInfo();
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) pager.getLayoutParams();
            int pos = getPagerPosition();

            // The window is not resized for the keyboard, so a bar along a side has to end above it.
            layoutParams.width = (pos == PAGER_POSITION_LEFT || pos == PAGER_POSITION_RIGHT) ? frm.getHeight() - imeHeight : frm.getWidth();
            layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                    (extraKeysInfo == null ? 0 : extraKeysInfo.getMatrix().length));

            switch (pos) {
                case PAGER_POSITION_TOP:
                case PAGER_POSITION_BOTTOM:
                    layoutParams.gravity = (pos == PAGER_POSITION_TOP ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT;
                    // reset everything we set for "left" and "right"
                    pager.setPivotX(layoutParams.width / 2f);
                    pager.setPivotY(layoutParams.width / 2f);
                    pager.setRotation(0f);
                    pager.setTranslationX(0);
                    break;
                case PAGER_POSITION_LEFT:
                case PAGER_POSITION_RIGHT:
                    layoutParams.gravity = (pos == PAGER_POSITION_LEFT ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
                    pager.setPivotX(pos == PAGER_POSITION_LEFT ? 0 : layoutParams.width);
                    pager.setPivotY(0f);
                    pager.setRotation(90f * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    pager.setTranslationX(layoutParams.height * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    break;
            }
            pager.setLayoutParams(layoutParams);

            ekbarContentInset = prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0;
            applyContentInsets();
            makeSureHelpersAreVisibleAndInScreenBounds();
        });
    }

    private int ekbarContentInset = 0;
    private int imeHeight = 0;

    private void applyContentInsets() {
        int pos = getPagerPosition();
        // Only a bar at the bottom has to step aside for the keyboard.
        int bottomMargin = pos == PAGER_POSITION_BOTTOM ? imeHeight : 0;

        // A bottom bar reserving its own space always sits right above the keyboard via bottomMargin,
        // so content must stop there too, regardless of Reseed.
        boolean barReservesKeyboardSpace = pos == PAGER_POSITION_BOTTOM && ekbarContentInset > 0;
        int imeContentInset = barReservesKeyboardSpace ? bottomMargin : (prefs.Reseed.get() ? imeHeight : 0);

        getLorieView().setContentInsets(pos == PAGER_POSITION_LEFT ? ekbarContentInset : 0,
                pos == PAGER_POSITION_TOP ? ekbarContentInset : 0,
                pos == PAGER_POSITION_RIGHT ? ekbarContentInset : 0,
                imeContentInset + (pos == PAGER_POSITION_BOTTOM ? ekbarContentInset : 0));
        getLorieView().setObscuredBottom(imeHeight - imeContentInset);

        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.MarginLayoutParams pagerParams = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
        if (pagerParams.bottomMargin != bottomMargin) {
            pagerParams.bottomMargin = bottomMargin;
            pager.setLayoutParams(pagerParams);
        }
    }

    public void setImeHeight(int height) {
        // Reported on every insets dispatch, but relaying out the bar for it must not become a loop.
        if (imeHeight == height)
            return;

        imeHeight = height;
        setTerminalToolbarViewLayout();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && getLorieView().connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    int orientation, densityDpi;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            getLorieView().setKeyboardVisible(false);

        if (newConfig.densityDpi != densityDpi)
            orientationDeniedAt = null;

        orientation = newConfig.orientation;
        densityDpi = newConfig.densityDpi;
        applyWindowSettings();
        setTerminalToolbarView();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();

        // The system bars come back when the window loses focus.
        if (hasFocus)
            applyImmersiveMode();
            LorieView.markUserActivity();
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(!prefs.fullscreen.get() ? 0 :
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setWindowFlag(int flag, boolean enabled) {
        if (((getWindow().getAttributes().flags & flag) != 0) == enabled)
            return;

        if (enabled)
            getWindow().addFlags(flag);
        else
            getWindow().clearFlags(flag);
    }

    /** Updates active X11/D-Bus sleep inhibition state and re-evaluates keep-screen-on immediately. */
    void setX11SleepInhibited(boolean inhibited) {
        runOnUiThread(() -> {
            if (x11SleepInhibited == inhibited)
                return;
            if (x11SleepInhibited && !inhibited)
                LorieView.markUserActivity();
            x11SleepInhibited = inhibited;
            handler.removeCallbacks(screenIdleTimeoutCheck);
            screenIdleTimeoutArmedMode = null;
            checkScreenIdleTimeout();
        });
    }

    /** Refreshes user idle activity when an X11 client explicitly resets the X11 screen saver. */
    void onX11ScreenSaverReset() {
        runOnUiThread(() -> {
            if (!getLorieView().connected())
                return;
            LorieView.markUserActivity();
            if (!x11SleepInhibited) {
                handler.removeCallbacks(screenIdleTimeoutCheck);
                checkScreenIdleTimeout();
            }
        });
    }

    /** Keeps or drops the screen-on flag based on elapsed idle time, rescheduling itself for the remaining time. */
    private void checkScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        long systemTimeoutMs = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        long elapsed = (System.nanoTime() / 1_000_000L) - LorieView.getLastInputTimestamp();
        ScreenIdlePolicy.Decision decision = ScreenIdlePolicy.evaluate(
                getLorieView().connected(),
                x11SleepInhibited,
                mode,
                systemTimeoutMs,
                elapsed);
        screenIdleTimeoutArmedMode = decision.armedMode;
        setWindowFlag(FLAG_KEEP_SCREEN_ON, decision.keepScreenOn);
        if (decision.delayMs >= 0)
            handler.postDelayed(screenIdleTimeoutCheck, decision.delayMs);
    }

    /** Syncs screenIdleTimeout state/timer to the current preference without extending an already-scheduled check. */
    private void applyScreenIdleTimeout() {
        String mode = prefs.screenIdleTimeout.get();
        boolean connected = getLorieView().connected();
        if (!connected || x11SleepInhibited || "never".equals(mode) || "system".equals(mode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            screenIdleTimeoutArmedMode = null;
            ScreenIdlePolicy.Decision decision = ScreenIdlePolicy.evaluate(connected, x11SleepInhibited, mode, 0, 0);
            setWindowFlag(FLAG_KEEP_SCREEN_ON, decision.keepScreenOn);
        } else if (!mode.equals(screenIdleTimeoutArmedMode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    void applyWindowSettings() {
        Window window = getWindow();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();

        // Recreating would take the window out of picture-in-picture, so it waits for the normal size.
        if (!isInPictureInPictureMode && (oldHideCutout != hideCutout || oldFullscreen != fullscreen)) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (isInMultiWindowMode() ? "auto" : prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        // A display ignoring orientation requests letterboxes the window into the requested
        // proportions instead of rotating, leaving the rest of the screen unusable. The request is
        // retried once the display changes, the next one may well honour it.
        if (SDK_INT >= VERSION_CODES.R) {
            WindowManager wm = getWindowManager();
            Rect display = wm.getMaximumWindowMetrics().getBounds();
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !wm.getCurrentWindowMetrics().getBounds().equals(display))
                orientationDeniedAt = display;
            if (display.equals(orientationDeniedAt))
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (SDK_INT >= VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutoutMode = !hideCutout ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER :
                    (SDK_INT >= VERSION_CODES.R ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
            if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                attributes.layoutInDisplayCutoutMode = cutoutMode;
                window.setAttributes(attributes);
            }
        }

        setWindowFlag(FLAG_FULLSCREEN, fullscreen);
        applyScreenIdleTimeout();
        applyImmersiveMode();

        View contentChild = ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0);
        if (contentChild.getFitsSystemWindows() == fullscreen) {
            contentChild.setFitsSystemWindows(!fullscreen);
            ViewCompat.requestApplyInsets(contentChild);
        }
    }
    /*
    @Override
    public void onBackPressed() {
    }
    */
    private static float getSystemDimenFloat(String name, float fallback) {
        Resources resources = Resources.getSystem();
        TypedValue value = new TypedValue();
        int id = resources.getIdentifier(name, "dimen", "android");
        if (id != 0)
            resources.getValue(id, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : fallback;
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @RequiresApi(api = VERSION_CODES.O)
    @Override
    public void onUserLeaveHint() {
        if (!prefs.PIP.get() || !hasPipPermission(this) || !getLorieView().connected())
            return;

        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder();
        Rational aspectRatio = getLorieView().getScreenAspectRatio();
        if (aspectRatio != null) {
            float clamped = MathUtils.clamp(aspectRatio.floatValue(), MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO);
            if (clamped != aspectRatio.floatValue())
                // Truncating instead of rounding keeps the ratio from landing back outside of the range.
                aspectRatio = clamped > 1 ? new Rational((int) (clamped * 1000), 1000) : new Rational(1000, (int) (1000 / clamped));
            params.setAspectRatio(aspectRatio);
        }

        getLorieView().freezeDimensions(true);
        if (!enterPictureInPictureMode(params.build()))
            getLorieView().freezeDimensions(false);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        getLorieView().onPictureInPictureModeChanged(isInPictureInPictureMode);
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : prefs.adjustHeightForEK.get() ? 1.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        setTerminalToolbarView();
        if (!isInPictureInPictureMode)
            applyWindowSettings();

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     */
    public void toggleKeyboardVisibility() {
        Log.d("MainActivity", "Toggling keyboard visibility");
        LorieView view = getLorieView();
        if (!externalKeyboardConnected || showIMEWhileExternalConnected)
            view.toggleKeyboardVisible();
        else
            view.setKeyboardVisible(false);
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = getLorieView().connected();
                      // A picture-in-picture window has nothing to show without a client, and there is no way
            // back to the normal size from it, so the window is closed.
            if (!connected && isInPictureInPictureMode) {
                finish();
                return;
            }
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected?View.INVISIBLE:View.VISIBLE);
            getLorieView().setVisibility(connected?View.VISIBLE:View.INVISIBLE);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
            else
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

            applyWindowSettings();
        });
    }

    public void getRealMetrics(DisplayMetrics m) {
        if (getLorieView() != null && getLorieView().getDisplay() != null)
            getLorieView().getDisplay().getRealMetrics(m);
    }

    public void setCapturingEnabled(boolean enabled) {
        if (mInputHandler != null)
            mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            getLorieView().setKeyboardVisible(false);
        getLorieView().requestFocus();
    }

    //Custom code
    public static Prefs getPrefs() {
      if (prefs != null) {
        return prefs;
        }         
    return null;
    }

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;

    public MainActivity() {
        instance = this;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    public TouchInputHandler getInputHandler() {
        return mInputHandler;
    }
    //Custom code end
    private static native void nativeSetLaunchWindowingModeFreeform(Object activityOptions);

    static {
        System.loadLibrary("Xlorie");
    }

    //Custom code
    public static class DrawerPreferenceFragment extends PreferenceFragmentCompat
            implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

        private MainActivity activity;

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);
            activity = (MainActivity) context;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.drawer_preferences, rootKey);

            // Set listeners for all preferences
            PreferenceScreen screen = getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                if (p instanceof PreferenceCategory) {
                    PreferenceCategory category = (PreferenceCategory) p;
                    for (int j = 0; j < category.getPreferenceCount(); j++) {
                        Preference child = category.getPreference(j);
                        child.setOnPreferenceClickListener(this);
                        child.setOnPreferenceChangeListener(this);
                    }
                } else {
                    p.setOnPreferenceClickListener(this);
                    p.setOnPreferenceChangeListener(this);
                }
            }
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            String key = preference.getKey();
            if (key == null) return false;

            switch (key) {
                case "open_keyboard":
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    activity.toggleKeyboardVisibility();
                    return true;

                case "select_controller":
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    activity.showInputControlsDialog();
                    return true;

                case "exit":
                    activity.finish();
                    return true;
            }
            return false;
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey();
            if (key == null) return false;

            return false;
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            return onPreferenceClick(preference);
        }
    }
}
