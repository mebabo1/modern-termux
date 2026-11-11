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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.KeyEvent.*;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.*;

// ================= COM.TERMUX.X11 IMPORTS =================
import com.termux.x11.HudService;
import com.termux.x11.LorieApp;
import com.termux.x11.LoriePreferences;
import com.termux.x11.LorieView;
import com.termux.x11.Prefs;
import com.termux.x11.controller.ControlsEditorActivity;
import com.termux.x11.controller.ExternalControllerBindingsActivity;
import com.termux.x11.controller.InputControllerActivity;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.extrakeys.ExtraKeysInfo;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
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
                hudService.attachToActivity(MainActivity.this);
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

    /// DRAWER

    private void setupTermuxActivityListener() {
        this.termuxActivityListener = new TermuxActivityListener() {
            @Override
            public void onX11PreferenceSwitchChange(boolean isOpen) {
                // Handle preference switch change
                if (isOpen) {
                    // Open preferences
                    startActivity(new Intent(MainActivity.this, LoriePreferences.class));
                }
            }

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
            public void reInstallX11StartScript(Activity activity) {
                // Use intent to communicate with Termux app
                Intent intent = new Intent();
                intent.setAction("com.termux.action.INSTALL_X11");
                intent.setPackage("com.termux.x11");
                try {
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to launch Termux installer", e);
                    Toast.makeText(activity, "Please install Termux app first", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void changePreference(String key) {
                // Handle preference change in MainActivity
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
        }
    }

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
                    Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    ////////////////////////////////////////////////////////
