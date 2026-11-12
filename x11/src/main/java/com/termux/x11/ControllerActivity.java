package com.termux.x11;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.net.Uri;
import android.os.Build;
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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.hardware.display.DisplayManager;
import android.view.OrientationEventListener;
import androidx.annotation.Keep;

import androidx.annotation.NonNull;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.*;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.*;

import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import com.termux.x11.R;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class ControllerActivity extends AppCompatActivity {
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
    private final Runnable screenIdleTimeoutCheck = this::checkScreenIdleTimeout;
    @Keep private long lastServerHeartbeat; // Kept for JNI activity.cpp compatibility
    /** Aspect ratios outside of the range the device is configured with are rejected by the system. */
    private static final float MIN_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMinAspectRatio", 1.f / 2.39f);
    private static final float MAX_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMaxAspectRatio", 2.39f);

    public Prefs prefs;
    LorieApp app;

    private boolean oldFullscreen = false, oldHideCutout = false;
    private OrientationEventListener orientationListener;

    ////////////////////////////// addcede //////////////////////////
    private DrawerLayout drawerLayout;   
    private static boolean softKeyboardShown = false;

    private HudService hudService;
    private boolean isBound = false;

    private ServiceConnection hudConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HudService.LocalBinder binder = (HudService.LocalBinder) service;
            hudService = binder.getService();
            // If activity is resumed, attach immediately
            if (isResumed) {
                hudService.attachToActivity(ControllerActivity.this);
            }
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            hudService = null;
        }
    };

    private boolean isResumed = false;

    // Call this when the HUD preference is enabled
    public void startHudService() {
        Intent intent = new Intent(this, HudService.class);
        startService(intent);
        bindService(intent, hudConnection, Context.BIND_AUTO_CREATE);
    }

    // Call this when the HUD preference is disabled
    public void stopHudService() {
        if (isBound) {
            unbindService(hudConnection);
            isBound = false;
        }
        Intent intent = new Intent(this, HudService.class);
        stopService(intent);
    }

    // Called from onStart to start HUD if preference is enabled
    private void startHudIfEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean hudEnabled = prefs.getBoolean("hud_enabled", false);
        if (hudEnabled) {
            startHudService();
        }
    }

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
               name.contains("goodix")   ||  // Goodix sensors
               device.isVirtual();          // Ignore system-generated virtual inputs
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
            public void onX11PreferenceSwitchChange(boolean isOpen) {
                // Handle preference switch change
                if (isOpen) {
                    // Open preferences
                    startActivity(new Intent(ControllerActivity.this, LoriePreferences.class));
                }
            }

            @Override
            public void releaseSlider(boolean open) {
                // For ControllerActivity, we don't have a slider UI
                Log.d("ControllerActivity", "Slider released: " + open);
            }

            @Override
            public void onChangeOrientation(int orientation) {
                // Set orientation for ControllerActivity
                setRequestedOrientation(orientation);
                
                // Also update the LorieView if connected
                if (getLorieView() != null) {
                    getLorieView().regenerate();
                }
            }

            @Override
            public void reInstallX11StartScript(Activity activity) {
                // Use intent to communicate with Termux app
                Intent intent = new Intent();
                intent.setAction("com.termux.action.INSTALL_X11");
                intent.setPackage("com.termux.x11");
                try {
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Log.e("ControllerActivity", "Failed to launch Termux installer", e);
                    Toast.makeText(activity, "Please install Termux app first", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void changePreference(String key) {
                // Handle preference change in ControllerActivity
                onPreferencesChanged(key);
            }

            @Override
            public List<ProcessInfo> collectProcessorInfo(String tag) {
                // Return real Android process list instead of empty placeholder
                return getAndroidProcessList();
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

    private void startDebugMode() {
        // Start debug mode
        Toast.makeText(this, "Debug mode started", Toast.LENGTH_SHORT).show();
        LogcatLogger.stop();
        LogcatLogger.start(this);
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
            Log.e("ControllerActivity", "Error showing preferences", e);
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
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Log.d("ControllerActivity", "dispatchTouchEvent - Action: " + 
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
                    Log.d("ControllerActivity", "Input controls handled touch in dispatchTouchEvent");
                    return true;
                }
            }
        }
        
        // If not handled by input controls, pass to normal touch handling
        return super.dispatchTouchEvent(ev);
    }

    //////////////////Winlator Ludashi
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isSidebarKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                onBackPressed();
            return true;
        }

        if (inputControlsView != null && inputControlsView.onKeyEvent(event)) return true;
        if (winHandler != null && winHandler.onKeyEvent(event)) return true;
        if (xServer != null && xServer.keyboard.onKeyEvent(event)) return true;
        return !ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event);
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    ///////////////////////////////

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs.recheckStoringSecondaryDisplayPreferences();
            if (ACTION_START.equals(intent.getAction())) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                    onReceiveConnection(intent);
                } catch (Exception e) {
                    Log.e("ControllerActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("ControllerActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    @SuppressLint("StaticFieldLeak")
    private static ControllerActivity instance;

    public ControllerActivity() {
        instance = this;
    }

    public static Prefs getPrefs() {
      if (prefs != null) {
        return prefs;
        }         
    return null;
    }

    public static ControllerActivity getInstance() {

    return instance;
    }

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        //startDebugMode();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        prefs = new Prefs(this);
        // prefs = new Prefs(this.getApplicationContext());
                
        prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);

        // setContentView(R.layout.main_activity);

        frm = findViewById(R.id.frame);
        
        setupTermuxActivityListener();
        setContentView(R.layout.main_activity_content);
        applyWindowSettings();
        drawerLayout = findViewById(R.id.drawer_layout);
        lorieContentView = findViewById(R.id.id_display_window);
        frm = findViewById(R.id.frame);
        
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        showPreferencesInDrawer();
        
        // support drawer
        // Set up the preferences button to open the drawer
        findViewById(R.id.preferences_button).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                // Show the drawer with settings
                showPreferencesInDrawer();
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
     
        // findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));
        // findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();
        
        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
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

                return handledByWine || handledByX11 || handledByInputHandler;
            }      
            
            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
                
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
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

        // Clear existing listeners to avoid conflicts
        lorieView.setOnTouchListener(null);
        lorieParent.setOnTouchListener(null);
        if (frm != null) {
            frm.setOnTouchListener(null);
            
            // Set up proper touch handling on the FrameLayout
            frm.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    Log.d("TouchFix", "FrameLayout touch - Action: " + 
                          MotionEvent.actionToString(event.getAction()) +
                          " at (" + event.getX() + ", " + event.getY() + ")");
                    
                    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        return false;
                    }
                    
                    if (inputControlsView != null && 
                        inputControlsView.getVisibility() == View.VISIBLE &&
                        inputControlsView.getProfile() != null) {
                        
                        int[] viewLocation = new int[2];
                        inputControlsView.getLocationOnScreen(viewLocation);
                        int[] frameLocation = new int[2];
                        frm.getLocationOnScreen(frameLocation);
                        
                        float x = event.getX() - (viewLocation[0] - frameLocation[0]);
                        float y = event.getY() - (viewLocation[1] - frameLocation[1]);
                        
                        MotionEvent adjustedEvent = MotionEvent.obtain(event);
                        adjustedEvent.setLocation(x, y);
                        
                        boolean handled = inputControlsView.handleTouchEvent(adjustedEvent);
                        adjustedEvent.recycle();
                        
                        if (handled) {
                            return true;
                        }
                    }
                    
                    if (mInputHandler != null && lorieView != null) {
                        return mInputHandler.handleTouchEvent(lorieView, lorieView, event);
                    }
                    
                    return false;
                }
            });
        }
        
        // Set up direct touch handling for LorieView
        lorieView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    return false;
                }
                
                if (inputControlsView != null && 
                    inputControlsView.getVisibility() == View.VISIBLE &&
                    inputControlsView.getProfile() != null) {
                    return false;
                }
                
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
                if (isWineRunning()) {
                    winHandler.onGenericMotionEvent(e);
                }
                boolean handledByX11 = inputControlsView.dispatchGenericMotionEvent(e);
                return true;
            }
            return false;
        });

        registerReceiver(receiver, new IntentFilter(ACTION_START) {{
            addAction(ACTION_PREFERENCES_CHANGED);
            addAction(ACTION_STOP);
            addAction(ACTION_CUSTOM);
        }}, SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        ImeHeightProvider.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);
        setupInputController();
        checkConnectedControllers();
        initStylusAuxButtons();
        initMouseAuxButtons();

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
        
        onReceiveConnection(getIntent());
        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
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

    @Override
    protected void onStart() {
        super.onStart();
        startHudIfEnabled();   // start & bind if enabled
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unbind but do NOT stop service – let it run in background
        if (isBound) {
            unbindService(hudConnection);
            isBound = false;
        }
    }

    @Override
    public void onPause() {
        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        for (StatusBarNotification notification : mNotificationManager.getActiveNotifications()) {
            if (notification.getId() == mNotificationId) {
                mNotificationManager.cancel(mNotificationId);
            }
        }
     
        super.onPause();
        isResumed = false;

        if (!isInPictureInPictureMode()) {
            // finish();

            // stop hud
            if (isBound && hudService != null) {
                hudService.detach();
            }
            stopHudService();  
            LogcatLogger.stop();
        }
    }

    @Override
    protected boolean isSettingsActivity() {
        return false;
    }
    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
            if (pager == null) {
        return;
    }
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = !isInPictureInPictureMode && LorieView.connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e)));
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        ekbarContentInset = prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0;
        applyContentInsets();
        getLorieView().requestFocus();
    }

    private int ekbarContentInset = 0;
    private int imeHeight = 0;
    private int captionHeight = 0;

    private void applyContentInsets() {
        int imeContentInset = prefs.Reseed.get() ? imeHeight : 0;
        getLorieView().setContentInsets(0, captionHeight, 0, ekbarContentInset + imeContentInset);
         getLorieView().setObscuredBottom(imeHeight - imeContentInset);

        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.MarginLayoutParams pagerParams = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
        if (pagerParams.bottomMargin != imeHeight) {
            pagerParams.bottomMargin = imeHeight;
            pager.setLayoutParams(pagerParams);
        }
    }

    public void setImeHeight(int height) {
        imeHeight = height;
        applyContentInsets();
    }

    // The window header of desktop windowing can not be hidden, so its space has to be given up even
    // in fullscreen mode, where fitsSystemWindows does not apply system insets.
    public void setCaptionHeight(int height) {
        captionHeight = height;
        applyContentInsets();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && LorieView.connected() && saveState)
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

    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        NotificationCompat.Builder builder =  new NotificationCompat.Builder(this, getNotificationChannel(mNotificationManager))
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }

    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);
        String channelName = getResources().getString(R.string.app_name);
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (SDK_INT >= VERSION_CODES.Q)
            channel.setAllowBubbles(false);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

        int orientation, densityDpi; 

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
            if (newConfig.densityDpi != densityDpi)
            orientationDeniedAt = null;
            

        orientation = newConfig.orientation;
        densityDpi = newConfig.densityDpi;
        applyWindowSettings();
                if (termuxActivityListener != null) {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
            boolean forceLandscape = p.getBoolean("forceLandscape", false);
            if (!forceLandscape) {
                termuxActivityListener.onChangeOrientation(newConfig.orientation);
            } else {
                termuxActivityListener.onChangeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            handler.postDelayed(() -> {
                getLorieView().regenerate();
            }, 1000);
        }
        setTerminalToolbarView();
    }

    /**
     * Returns a list of all processes owned by this app's UID.
     * Uses /proc filesystem (no root required).
     */
    private List<ProcessInfo> getAndroidProcessList() {
        List<ProcessInfo> list = new ArrayList<>();
        int myUid = android.os.Process.myUid();
        File proc = new File("/proc");
        File[] files = proc.listFiles();
        if (files == null) return list;

        for (File file : files) {
            if (!file.isDirectory()) continue;
            String name = file.getName();
            if (!name.matches("\\d+")) continue; // only numeric PIDs
            int pid = Integer.parseInt(name);

            ProcessInfo info = readProcessInfo(pid, myUid);
            if (info != null) list.add(info);
        }
        return list;
    }

    /**
     * Reads process info from /proc/[pid]/status and /proc/[pid]/statm.
     * Returns null if process is not owned by myUid or if status cannot be read.
     */
    private ProcessInfo readProcessInfo(int pid, int myUid) {
        // Read UID and process name from /proc/[pid]/status
        File statusFile = new File("/proc/" + pid + "/status");
        if (!statusFile.exists()) return null;

        String procName = null;
        int uid = -1;

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    uid = Integer.parseInt(parts[1]); // real UID
                    if (uid != myUid) return null;    // filter by our UID
                } else if (line.startsWith("Name:")) {
                    procName = line.substring(5).trim();
                }
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }

        if (procName == null) procName = "unknown";

        // Read memory (RSS) from /proc/[pid]/statm
        long memoryBytes = 0;
        File statmFile = new File("/proc/" + pid + "/statm");
        if (statmFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(statmFile))) {
                String[] parts = reader.readLine().split("\\s+");
                if (parts.length >= 2) {
                    long pages = Long.parseLong(parts[1]); // RSS in pages
                    memoryBytes = pages * 4096; // assume 4KB page size
                } else if (parts.length >= 1) {
                    long pages = Long.parseLong(parts[0]); // total program size
                    memoryBytes = pages * 4096;
                }
            } catch (IOException | NumberFormatException ignored) {}
        }

        // affinityMask: set to all CPUs (irrelevant for Android processes but required by constructor)
        int numCores = Runtime.getRuntime().availableProcessors();
        int affinityMask = (1 << numCores) - 1; // all CPUs

        return new ProcessInfo(pid, procName, memoryBytes, affinityMask, false);
    }
    
    
    public static class DrawerPreferenceFragment extends PreferenceFragmentCompat 
            implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

        private ControllerActivity activity;

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);
            activity = (ControllerActivity) context;
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

            // Sync HUD switch with actual service state (optional)
            SwitchPreferenceCompat hudSwitch = findPreference("hud_enabled");
            if (hudSwitch != null) {
                // You could check if HudService is running and set the switch accordingly
            }
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            String key = preference.getKey();
            if (key == null) return false;

            switch (key) {
                case "full_settings":
                    Intent settingsIntent = new Intent(activity, LoriePreferences.class);
                    activity.startActivity(settingsIntent);
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    return true;

                case "select_controller":
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    activity.showInputControlsDialog();
                    return true;

                case "start_debug":
                    activity.drawerLayout.closeDrawer(GravityCompat.START);
                    activity.startDebugMode();
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

            switch (key) {
                case "hud_enabled":   // must match the key in XML
                    boolean enable = (Boolean) newValue;
                    if (enable) {
                        startHudService();
                    } else {
                        stopHudService();
                    }
                    return true;
            }
            return false;
        }

        private void startHudService() {
            activity.startHudService();
            Toast.makeText(activity, "HUD started", Toast.LENGTH_SHORT).show();
            activity.drawerLayout.closeDrawer(GravityCompat.START);
        }

        private void stopHudService() {
            activity.stopHudService();    
            Toast.makeText(activity, "HUD stopped", Toast.LENGTH_SHORT).show();
            activity.drawerLayout.closeDrawer(GravityCompat.START);
        }
    }
}
