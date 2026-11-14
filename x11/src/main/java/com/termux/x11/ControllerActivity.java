package com.termux.x11;

// Android System
import android.Manifest;
import android.annotation.Keep;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

// AndroidX & Support
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.viewpager.widget.ViewPager;

// Java Standard Libraries
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

// Project Internal (Termux:X11)
import com.termux.x11.BuildConfig;
import com.termux.x11.R;
import com.termux.x11.controller.InputControllerActivity;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.Callback;
import com.termux.x11.controller.core.DownloadProgressDialog;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

// Static Imports
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.system.Os.getuid;

@Keep
@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class ControllerActivity extends MainActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private DrawerLayout drawerLayout;   
    private static boolean softKeyboardShown = false;

    private HudService hudService;
    private boolean isBound = false;
    private boolean isResumed = false;

    static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED";
    private static Prefs prefs = null;
    private final Handler handler = new Handler(Looper.getMainLooper()); // 핸들러 선언 필수

    protected LorieView xServer;
    protected InputControlsManager inputControlsManager;
    protected InputControlsView inputControlsView;
    protected TouchpadView touchpadView;
    protected WinHandler winHandler;
    protected float globalCursorSpeed = 1.0f;
    protected TermuxActivityListener termuxActivityListener; // 필요시 유지 또는 드로어로 대체

    public static final int OPEN_FILE_REQUEST_CODE = 102;
    protected ControlsProfile profile;
    protected DownloadProgressDialog preloaderDialog;
    protected Callback<Uri> openFileCallback;
    protected Runnable editInputControlsCallback;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction()) &&
                    intent.getBooleanExtra("fromBroadcast", false))
                updatePreferencesLayout();
        }
    };

    private final ContentObserver accessibilityObserver = new ContentObserver(handler) {
        private final Runnable updateLayout = () -> updatePreferencesLayout();

        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(updateLayout);
            handler.postDelayed(updateLayout, 200);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }
    };

    private void updatePreferencesLayout() {
        getSupportFragmentManager().getFragments().forEach(fragment -> {
            if (fragment instanceof LoriePreferenceFragment)
                ((LoriePreferenceFragment) fragment).updatePreferencesLayout();
        });
    }

    private ServiceConnection hudConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HudService.LocalBinder binder = (HudService.LocalBinder) service;
            hudService = binder.getService();
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

    public void startHudService() {
        Intent intent = new Intent(this, HudService.class);
        startService(intent);
        bindService(intent, hudConnection, Context.BIND_AUTO_CREATE);
    }

    public void stopHudService() {
        if (isBound) {
            unbindService(hudConnection);
            isBound = false;
        }
        Intent intent = new Intent(this, HudService.class);
        stopService(intent);
    }

    private void startHudIfEnabled() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean hudEnabled = sharedPrefs.getBoolean("hud_enabled", false);
        if (hudEnabled) {
            startHudService();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs();

        if (isSettingsActivity()) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new LoriePreferenceFragment(null))
                    .commit();
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeButtonEnabled(true);
            }
        }

        Uri ENABLED_ACCESSIBILITY_SERVICES = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Uri ACCESSIBILITY_ENABLED = Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED);
        getContentResolver().registerContentObserver(ENABLED_ACCESSIBILITY_SERVICES, true, accessibilityObserver);
        getContentResolver().registerContentObserver(ACCESSIBILITY_ENABLED, true, accessibilityObserver);
        
        startHudIfEnabled();
    }

    protected boolean isSettingsActivity() {
        return true;
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        if (hudService != null) {
            hudService.attachToActivity(this);
        }
        
        IntentFilter filter = new IntentFilter(ACTION_PREFERENCES_CHANGED);
        registerReceiver(receiver, filter, SDK_INT >= Build.VERSION_CODES.TIRAMISU ? RECEIVER_NOT_EXPORTED : 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isResumed = false;
        if (hudService != null) {
            hudService.detachFromActivity(this);
        }
        unregisterReceiver(receiver);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            updatePreferencesLayout();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0)
                finish();
            else
                onBackPressed();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showFragment(PreferenceFragmentCompat fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        final LoriePreferenceFragment fragment = new LoriePreferenceFragment(pref.getFragment());
        fragment.setTargetFragment(caller, 0);
        showFragment(fragment);
        return true;
    }

    //Gamepad

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
                if (isOpen) {
                    if (drawerLayout != null) {
                        drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                    }
                }
            }

            @Override
            public void releaseSlider(boolean open) {
                Log.d("ControllerActivity", "Slider released: " + open);
            }

            @Override
            public void onChangeOrientation(int orientation) {
                setRequestedOrientation(orientation);
                if (getLorieView() != null) {
                    getLorieView().regenerate();
                }
            }

            @Override
            public void reInstallX11StartScript(Activity activity) {
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
                onPreferencesChanged(key);
            }

            @Override
            public List<ProcessInfo> collectProcessorInfo(String tag) {
                return getAndroidProcessList();
            }

            @Override
            public void onExitApp() {
                finish();
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
        if (inputControlsView != null) {
            inputControlsView.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Input controls enabled", Toast.LENGTH_SHORT).show();
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

    //////////////add

    public static class LoriePreferenceFragment extends PreferenceFragmentCompat implements OnPreferenceChangeListener {
        private final Runnable updateLayout = this::updatePreferencesLayout;
        private static final Method onSetInitialValue;
        static {
            try {
                onSetInitialValue = Preference.class.getDeclaredMethod("onSetInitialValue", boolean.class, Object.class);
                onSetInitialValue.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        void onSetInitialValue(Preference p) {
            try {
                onSetInitialValue.invoke(p, false, null);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        final String root;
        public LoriePreferenceFragment() {
            this(null);
        }

        public LoriePreferenceFragment(String root) {
            this.root = root;
        }

        @Override
        public void onResume() {
            super.onResume();
            ((LoriePreferences) getActivity()).getSupportActionBar().setTitle(getPreferenceScreen().getTitle());
        }

        private void with(CharSequence key, Consumer<Preference> action) {
            Preference p = findPreference(key);
            if (p != null)
                action.accept(p);
        }

        @Override @SuppressLint("ApplySharedPref")
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            getPreferenceManager().setPreferenceDataStore(prefs);

            if ((Integer.parseInt(prefs.touchMode.get()) - 1) > 2)
                prefs.touchMode.put("1");

            setPreferencesFromResource(R.xml.preferences, root == null ? "main" : root);

            PreferenceScreen screen = getPreferenceScreen();
            for (int i=0; i<screen.getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                p.setOnPreferenceChangeListener(this);
                p.setPreferenceDataStore(prefs);

                if (p instanceof ListPreference) {
                    ListPreference list = (ListPreference) p;
                    list.setEntries(prefs.keys.get(p.getKey()).asList().getEntries());
                    list.setEntryValues(prefs.keys.get(p.getKey()).asList().getValues());
                    list.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                }
            }

            with("showAdditionalKbd", p -> p.setLayoutResource(R.layout.preference));
            with("version", p -> p.setSummary(BuildConfig.VERSION_NAME));

            if (!SamsungDexUtils.available())
                setVisible("dexMetaKeyCapture", false);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
                setVisible("hideCutout", false);

            boolean stylusAvailable = Arrays.stream(InputDevice.getDeviceIds())
                    .mapToObj(InputDevice::getDevice)
                    .filter(Objects::nonNull)
                    .anyMatch(d -> d.supportsSource(InputDevice.SOURCE_STYLUS));

            setVisible("showStylusClickOverride", stylusAvailable);
            setVisible("stylusIsMouse", stylusAvailable);
            setVisible("stylusButtonContactModifierMode", stylusAvailable);
        }

        private void setVisible(CharSequence key, boolean value) {
            Preference p = findPreference(key);
            if (p != null)
                p.setVisible(value);
        }

        private void setEnabled(CharSequence key, boolean value) {
            Preference p = findPreference(key);
            if (p != null)
                p.setEnabled(value);
        }

        @SuppressWarnings("ConstantConditions")
        void updatePreferencesLayout() {
            if (getContext() == null)
                return;

            for (String key : prefs.keys.keySet()) {
                Preference p = findPreference(key);
                if (p != null)
                    onSetInitialValue(p);
            }

            String displayResMode = prefs.displayResolutionMode.get();
            setVisible("displayScale", displayResMode.contentEquals("scaled"));
            setVisible("displayResolutionExact", displayResMode.contentEquals("exact"));
            setVisible("displayResolutionCustom", displayResMode.contentEquals("custom"));

            setEnabled("dexMetaKeyCapture", !prefs.enableAccessibilityServiceAutomatically.get());
            setEnabled("enableAccessibilityServiceAutomatically", !prefs.dexMetaKeyCapture.get());
            setEnabled("pauseKeyInterceptingWithEsc", prefs.dexMetaKeyCapture.get() ||
                    prefs.enableAccessibilityServiceAutomatically.get() ||
                    KeyInterceptor.isLaunched());
            setEnabled("enableAccessibilityServiceAutomatically", prefs.enableAccessibilityServiceAutomatically.get() || KeyInterceptor.isLaunched());
            setEnabled("filterOutWinkey", prefs.enableAccessibilityServiceAutomatically.get() || KeyInterceptor.isLaunched());

            boolean displayStretchEnabled = "exact".contentEquals(prefs.displayResolutionMode.get()) || "custom".contentEquals(prefs.displayResolutionMode.get());
            setEnabled("displayStretch", displayStretchEnabled);
            setEnabled("adjustResolution", displayStretchEnabled);

            setEnabled("scaleTouchpad", "1".equals(prefs.touchMode.get()) && !"native".equals(prefs.displayResolutionMode.get()));
            setEnabled("showMouseHelper", "1".equals(prefs.touchMode.get()));

            boolean requestNotificationPermissionVisible =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(requireContext(), POST_NOTIFICATIONS) == PERMISSION_DENIED;
            setVisible("requestNotificationPermission", requestNotificationPermissionVisible);
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            updatePreferencesLayout();
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference p) {
            if (p.getKey() == null)
                return super.onPreferenceTreeClick(p);

            if ("version".contentEquals(p.getKey())) {
                Context ctx = getContext();
                if (ctx != null) {
                    ((ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(p.getSummary(), p.getSummary()));
                    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && "requestNotificationPermission".contentEquals(p.getKey())) {
                ActivityCompat.requestPermissions(requireActivity(), new String[]{POST_NOTIFICATIONS}, 101);
                return true;
            }

            updatePreferencesLayout();
            return super.onPreferenceTreeClick(p);
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey();
            Log.e("Preferences", "changed preference: " + key);
            handler.removeCallbacks(updateLayout);
            handler.postDelayed(updateLayout, 50);

            if ("displayScale".contentEquals(key)) {
                int scale = (Integer) newValue;
                if (scale % 10 != 0) {
                    scale = Math.round( ( (float) scale ) / 10 ) * 10;
                    ((SeekBarPreference) preference).setValue(scale);
                    return false;
                }
            }

            if ("displayResolutionCustom".contentEquals(key)) {
                String value = (String) newValue;
                try {
                    String[] resolution = value.split("x");
                    int width = Integer.parseInt(resolution[0]);
                    int height = Integer.parseInt(resolution[1]);
                    if (width <= 0 || height <= 0)
                        throw new NumberFormatException();
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    Toast.makeText(getActivity(), "Wrong resolution format", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            if ("showAdditionalKbd".contentEquals(key) && (Boolean) newValue)
                prefs.additionalKbdVisible.put(true);

            if ("enableAccessibilityServiceAutomatically".contentEquals(key)) {
                if (!((Boolean) newValue))
                    KeyInterceptor.shutdown(false);
                if (requireContext().checkSelfPermission(WRITE_SECURE_SETTINGS) != PERMISSION_GRANTED) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Permission denied")
                            .setMessage("Android requires WRITE_SECURE_SETTINGS permission to start accessibility service automatically.\n" +
                                    "Please, launch this command using ADB:\n" +
                                    "adb shell pm grant " + requireContext().getPackageName() + " android.permission.WRITE_SECURE_SETTINGS")
                            .setNegativeButton("OK", null)
                            .create()
                            .show();
                    return false;
                }
            }

            requireContext().sendBroadcast(new Intent(ACTION_PREFERENCES_CHANGED) {{
                putExtra("key", key);
                putExtra("fromBroadcast", true);
                setPackage(requireContext().getPackageName());
            }});

            return true;
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if ("extra_keys_config".contentEquals(preference.getKey())) {
                @SuppressLint("InflateParams")
                View view = getLayoutInflater().inflate(R.layout.extra_keys_config, null, false);
                EditText config = view.findViewById(R.id.extra_keys_config);
                config.setTypeface(Typeface.MONOSPACE);
                config.setText(prefs.extra_keys_config.get());
                TextView desc = view.findViewById(R.id.extra_keys_config_description);
                desc.setLinksClickable(true);
                desc.setText(R.string.extra_keys_config_desc);
                desc.setMovementMethod(LinkMovementMethod.getInstance());
                new android.app.AlertDialog.Builder(getActivity())
                        .setView(view)
                        .setTitle("Extra keys config")
                        .setPositiveButton("OK",
                                (dialog, whichButton) -> {
                                    String text = config.getText().toString();
                                    prefs.extra_keys_config.put(!text.isEmpty() ? text : TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);
                                }
                        )
                        .setNeutralButton("Reset",
                                (dialog, whichButton) -> prefs.extra_keys_config.put(TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS))
                        .setNegativeButton("Cancel", (dialog, whichButton) -> dialog.dismiss())
                        .create()
                        .show();
            } else super.onDisplayPreferenceDialog(preference);
        }
    }

    public static class Receiver extends BroadcastReceiver {
        public Receiver() {
            super();
        }

        @Override
        public IBinder peekService(Context myContext, Intent service) {
            return super.peekService(myContext, service);
        }

        /** @noinspection StringConcatenationInLoop*/
        @SuppressLint("ApplySharedPref")
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent != null ? intent.getBundleExtra(null) : null;
            IBinder ibinder = bundle != null ? bundle.getBinder(null) : null;
            IRemoteCmdImterface remote = ibinder != null ? IRemoteCmdImterface.Stub.asInterface(ibinder) : null;

            try {
                if (intent != null && intent.getExtras() != null) {
                    Prefs p = (ControllerActivity.getInstance() != null) ? new Prefs() : (prefs != null ? prefs : new Prefs());
                    if (intent.getStringExtra("list") != null) {
                        String result = "";
                        for (PrefsProto.Preference pref : p.keys.values()) {
                            if (pref.type == String.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asString().get() + "\"\n";
                            else if (pref.type == int.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asInt().get() + "\"\n";
                            else if (pref.type == boolean.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asBoolean().get() + "\"\n";
                            else if (pref.type == String[].class) {
                                String[] entries = context.getResources().getStringArray(pref.asList().entries);
                                String[] values = context.getResources().getStringArray(pref.asList().values);
                                String value = pref.asList().get();
                                int index = Arrays.asList(values).indexOf(value);
                                if (index != -1)
                                    value = entries[index];
                                result += "\"" + pref.key + "\"=\"" + value + "\"\n";
                            }
                        }

                        sendResponse(remote, 0, 2, result.substring(0, result.length() - 1));
                        return;
                    }

                    SharedPreferences.Editor edit = p.get().edit();
                    for (String key : intent.getExtras().keySet()) {
                        if (key == null)
                            continue;
                        String newValue = intent.getStringExtra(key);
                        if (newValue == null)
                            continue;

                        switch (key) {
                            case "displayResolutionCustom": {
                                try {
                                    String[] resolution = newValue.split("x");
                                    int width = Integer.parseInt(resolution[0]);
                                    int height = Integer.parseInt(resolution[1]);
                                    if (width <= 0 || height <= 0)
                                        throw new NumberFormatException();
                                } catch (NumberFormatException | PatternSyntaxException ignored) {
                                    sendResponse(remote, 1, 1, "displayResolutionCustom: Wrong resolution format.");
                                    return;
                                }

                                edit.putString("displayResolutionCustom", newValue);
                                break;
                            }
                            case "enableAccessibilityServiceAutomatically": {
                                if (!"true".equals(newValue))
                                    KeyInterceptor.shutdown(false);
                                else if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PERMISSION_GRANTED) {
                                    sendResponse(remote, 1, 1, "Permission denied.\n" +
                                            "Android requires WRITE_SECURE_SETTINGS permission to change `enableAccessibilityServiceAutomatically` setting.\n" +
                                            "Please, launch this command using ADB:\n" +
                                            "adb shell pm grant com.termux.x11 android.permission.WRITE_SECURE_SETTINGS");
                                    return;
                                }

                                edit.putBoolean("enableAccessibilityServiceAutomatically", "true".contentEquals(newValue));
                                break;
                            }
                            case "extra_keys_config": {
                                edit.putString(key, newValue);
                                break;
                            }
                            default: {
                                PrefsProto.Preference pref = p.keys.get(key);
                                if (pref != null && pref.type == boolean.class) {
                                    edit.putBoolean(key, "true".contentEquals(newValue));
                                    if ("showAdditionalKbd".contentEquals(key) && "true".contentEquals(newValue))
                                        edit.putBoolean("additionalKbdVisible", true);
                                } else if (pref != null && pref.type == int.class) {
                                    try {
                                        edit.putInt(key, Integer.parseInt(newValue));
                                    } catch (NumberFormatException | PatternSyntaxException exception) {
                                        sendResponse(remote, 1, 4, key + ": failed to parse integer: " + exception);
                                        return;
                                    }
                                } else if (pref != null && pref.type == String[].class) {
                                    PrefsProto.ListPreference _p = (PrefsProto.ListPreference) pref;
                                    String[] entries = _p.getEntries();
                                    String[] values = _p.getValues();
                                    int index = Arrays.asList(entries).indexOf(newValue);

                                    if (index == -1 && _p.entries != _p.values)
                                        index = Arrays.asList(values).indexOf(newValue);

                                    if (index != -1) {
                                        edit.putString(key, values[index]);
                                        break;
                                    }

                                    sendResponse(remote, 1, 1, key + ": can not be set to \"" + newValue + "\", possible options are " + Arrays.toString(entries) + (_p.entries != _p.values ? " or " + Arrays.toString(values) : ""));
                                    return;
                                } else {
                                    sendResponse(remote, 1, 4, key + ": unrecognised option");
                                    return;
                                }
                            }
                        }

                        Intent intent0 = new Intent(ACTION_PREFERENCES_CHANGED);
                        intent0.putExtra("key", key);
                        intent0.putExtra("fromBroadcast", true);
                        intent0.setPackage(context.getPackageName());
                        context.sendBroadcast(intent0);
                    }
                    edit.commit();
                }

                sendResponse(remote, 0, 2, "Done");
            } catch (Exception e) {
                sendResponse(remote, 1, 4, e.toString());
            }
        }

        void sendResponse(IRemoteCmdImterface remote, int status, int oldStatus, String text) {
            if (remote != null) {
                try {
                    remote.exit(status, text);
                } catch (RemoteException ex) {
                    Log.e("LoriePreferences", "Failed to send response to commandline proxy", ex);
                }
            } else if (isOrderedBroadcast()) {
                setResultCode(oldStatus);
                setResultData(text);
            }
        }

        // For changing preferences from commandline
        private static final IBinder iface = new IRemoteCmdImterface.Stub() {
            @Override
            public void exit(int code, String output) {
                System.out.println(output);
                CmdEntryPoint.handler.post(() -> System.exit(code));
            }
        };

        private static void help() {
            System.err.print("termux-x11-preference [list] {key:value} [{key2:value2}]...");
            System.exit(0);
        }

        @Keep
        @SuppressLint("WrongConstant")
        public static void main(String[] args) {
            android.util.Log.i("LoriePreferences$Receiver", "commit " + BuildConfig.COMMIT);
            //noinspection resource
            ParcelFileDescriptor in = ParcelFileDescriptor.adoptFd(0);
            Intent i = new Intent("com.termux.x11.CHANGE_PREFERENCE");
            Bundle bundle = new Bundle();
            boolean inputIsFile = !android.system.Os.isatty(in.getFileDescriptor());

            in.detachFd();
            bundle.putBinder(null, iface);
            i.setPackage(BuildConfig.APPLICATION_ID);
            i.putExtra(null, bundle);
            if (getuid() == 0 || getuid() == 2000)
                i.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

            if (inputIsFile && System.in != null) {
                Scanner scanner = new Scanner(System.in);
                String line;
                String[] v;
                while (scanner.hasNextLine()) {
                    line = scanner.nextLine();
                    if (!line.contains("="))
                        help();

                    v = line.split("=");
                    if (v[0].startsWith("\"") && v[0].endsWith("\""))
                        v[0] = v[0].substring(1, v[0].length() - 1);
                    if (v[1].startsWith("\"") && v[1].endsWith("\""))
                        v[1] = v[1].substring(1, v[1].length() - 1);
                    i.putExtra(v[0], v[1]);
                }
            }

            for (String a: args) {
                if ("list".equals(a)) {
                    i.putExtra("list", "");
                } else if (a != null && a.contains(":")) {
                    String[] v = a.split(":");
                    i.putExtra(v[0], v[1]);
                } else
                    help();
            }

            CmdEntryPoint.handler.post(() -> CmdEntryPoint.sendBroadcast(i));
            CmdEntryPoint.handler.postDelayed(() -> {
                System.err.println("Failed to obtain response from app.");
                System.exit(1);
            }, 5000);
            Looper.loop();
        }
    }

    static Handler handler = Looper.getMainLooper() != null ? new Handler(Looper.getMainLooper()) : null;

    public void onClick(View view) {
        showFragment(new LoriePreferenceFragment("ekbar"));
    }

    /** @noinspection unused*/
    @SuppressLint("ApplySharedPref")
    public static class PrefsProto extends PreferenceDataStore {
        public static class Preference {
            protected final String key;
            protected final Class<?> type;
            protected final Object defValue;

            protected Preference(String key, Class<?> class_, Object default_) {
                this.key = key;
                this.type = class_;
                this.defValue = default_;
            }

            public ListPreference asList() {
                return (ListPreference) this;
            }

            public StringPreference asString() {
                return (StringPreference) this;
            }

            public IntPreference asInt() {
                return (IntPreference) this;
            }

            public BooleanPreference asBoolean() {
                return (BooleanPreference) this;
            }
        }

        public class BooleanPreference extends Preference {
            public BooleanPreference(String key, boolean defValue) {
                super(key, boolean.class, defValue);
            }

            public boolean get() {
                if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(key))
                    return builtInDisplayPreferences.getBoolean(key, (boolean) defValue);

                return preferences.getBoolean(key, (boolean) defValue);
            }

            public void put(boolean v) {
                if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(key)) {
                    builtInDisplayPreferences.edit().putBoolean(key, v).commit();
                    recheckStoringSecondaryDisplayPreferences();
                }

                preferences.edit().putBoolean(key, v).commit();
            }
        }

        public class IntPreference extends Preference {
            public IntPreference(String key, int defValue) {
                super(key, int.class, defValue);
            }

            public int get() {
                return preferences.getInt(key, (int) defValue);
            }

            public int defValue() {
                return preferences.getInt(key, (int) defValue);
            }
        }

        public class StringPreference extends Preference {
            public StringPreference(String key, String defValue) {
                super(key, String.class, defValue);
            }

            public String get() {
                return preferences.getString(key, (String) defValue);
            }

            public void put(String v) {
                preferences.edit().putString(key, v).commit();
            }
        }

        public class ListPreference extends Preference {
            private final int entries, values;

            public ListPreference(String key, String defValue, int entries, int values) {
                super(key, String[].class, defValue);
                this.entries = entries;
                this.values = values;
            }

            public String get() {
                return preferences.getString(key, (String) defValue);
            }

            public void put(String v) {
                preferences.edit().putString(key, v).commit();
            }

            public String[] getEntries() {
                return getArrayItems(entries, ctx.getResources());
            }

            public String[] getValues() {
                return getArrayItems(values, ctx.getResources());
            }

            private String[] getArrayItems(int resourceId, Resources resources) {
                ArrayList<String> itemList = new ArrayList<>();
                try (TypedArray typedArray = resources.obtainTypedArray(resourceId)) {
                    for (int i = 0; i < typedArray.length(); i++) {
                        int type = typedArray.getType(i);
                        if (type == TypedValue.TYPE_STRING) {
                            itemList.add(typedArray.getString(i));
                        } else if (type == TypedValue.TYPE_REFERENCE) {
                            int resIdOfArray = typedArray.getResourceId(i, 0);
                            itemList.addAll(Arrays.asList(resources.getStringArray(resIdOfArray)));
                        }
                    }
                }

                Object[] objectArray = itemList.toArray();
                return Arrays.copyOf(objectArray, objectArray.length, String[].class);
            }
        }

        static boolean storeSecondaryDisplayPreferencesSeparately = false;
        protected Context ctx;
        protected SharedPreferences preferences;
        protected SharedPreferences builtInDisplayPreferences;
        protected SharedPreferences secondaryDisplayPreferences;

        protected PrefsProto() {}

        protected PrefsProto(Context ctx) {
            this.ctx = ctx;
            builtInDisplayPreferences = PreferenceManager.getDefaultSharedPreferences(ctx);
            secondaryDisplayPreferences = ctx.getSharedPreferences("secondary", Context.MODE_PRIVATE);
            recheckStoringSecondaryDisplayPreferences();
        }

        protected void attach(Context ctx, SharedPreferences preferences) {
            this.ctx = ctx;
            this.preferences = preferences;
        }

        protected void recheckStoringSecondaryDisplayPreferences() {
            storeSecondaryDisplayPreferencesSeparately = builtInDisplayPreferences.getBoolean("storeSecondaryDisplayPreferencesSeparately", false);
            boolean isExternalDisplay = ((WindowManager) ctx.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY;
            preferences = (storeSecondaryDisplayPreferencesSeparately && isExternalDisplay) ? secondaryDisplayPreferences : builtInDisplayPreferences;
        }

        @Override
        public void putBoolean(String k, boolean v) {
            if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(k)) {
                builtInDisplayPreferences.edit().putBoolean(k, v).commit();
                recheckStoringSecondaryDisplayPreferences();
            } else {
                preferences.edit().putBoolean(k, v).commit();
            }
        }

        @Override
        public boolean getBoolean(String k, boolean d) {
            if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(k))
                return builtInDisplayPreferences.getBoolean(k, d);
            return preferences.getBoolean(k, d);
        }

        @Override
        public void putString(String k, @Nullable String v) {
            prefs.get().edit().putString(k, v).commit();
        }

        @Override
        public void putStringSet(String k, @Nullable Set<String> v) {
            prefs.get().edit().putStringSet(k, v).commit();
        }

        @Override
        public void putInt(String k, int v) {
            prefs.get().edit().putInt(k, v).commit();
        }

        @Override
        public void putLong(String k, long v) {
            prefs.get().edit().putLong(k, v).commit();
        }

        @Override
        public void putFloat(String k, float v) {
            prefs.get().edit().putFloat(k, v).commit();
        }

        @Nullable
        @Override
        public String getString(String k, @Nullable String d) {
            return prefs.get().getString(k, d);
        }

        @Nullable
        @Override
        public Set<String> getStringSet(String k, @Nullable Set<String> ds) {
            return prefs.get().getStringSet(k, ds);
        }

        @Override
        public int getInt(String k, int d) {
            return prefs.get().getInt(k, d);
        }

        @Override
        public long getLong(String k, long d) {
            return prefs.get().getLong(k, d);
        }

        @Override
        public float getFloat(String k, float d) {
            return prefs.get().getFloat(k, d);
        }

        public SharedPreferences get() {
            return preferences;
        }

        public boolean isSecondaryDisplayPreferences() {
            return preferences == secondaryDisplayPreferences;
        }
    }

    // Delegation to listener
    public void installX11ServerBridge() {
        if (termuxActivityListener != null)
            termuxActivityListener.reInstallX11StartScript(this);
    }

    public void stopDesktop() {
        if (termuxActivityListener != null)
            termuxActivityListener.stopDesktop();
    }

    public void openPreference(boolean open) {
        if (termuxActivityListener != null)
            termuxActivityListener.onX11PreferenceSwitchChange(open);
    }

    public void releaseSlider(boolean release) {
        if (termuxActivityListener != null)
            termuxActivityListener.releaseSlider(release);
    }

    // Input-control helpers
    public void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(ControllerActivity.getInstance(), R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- " + getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbLockCursor = dialog.findViewById(R.id.CBLockCursor);
        cbLockCursor.setChecked(xServer.cursorLocker.isEnabled());

        final CheckBox cbEnableTouchScreen = dialog.findViewById(R.id.CBTouchScreen);

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, InputControllerActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, InputControllerActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            if (termuxActivityListener == null) {
                Log.e("controls", "Failed to show control activity null");
                return;
            }
            xServer.cursorLocker.setEnabled(cbLockCursor.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                if (cbEnableTouchScreen.isChecked()) {
                    touchpadView.setTouchMode(TouchpadView.TouchMode.TOUCH_SCREEN);
                } else {
                    touchpadView.setTouchMode(TouchpadView.TouchMode.TRACK_PAD);
                }
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else {
                hideInputControls();
            }
        });

        if (isActivityValid()) {
            dialog.show();
        }
    }

    protected void showInputControls(ControlsProfile p) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(p);
        if (profile != null)
            touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setVisibility(View.VISIBLE);
        inputControlsView.invalidate();
        if (termuxActivityListener != null)
            termuxActivityListener.onX11PreferenceSwitchChange(false);
    }

    public void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);
        touchpadView.setVisibility(View.GONE);
        inputControlsView.invalidate();
    }

    // Bridge methods used by other classes
    public List<ProcessInfo> getTermuxProcessorInfo(String tag) {
        if (termuxActivityListener != null)
            return termuxActivityListener.collectProcessorInfo(tag);
        return new ArrayList<>();
    }

    public DownloadProgressDialog getPreloaderDialog() {
        return preloaderDialog;
    }

    public void setOpenFileCallback(Callback<Uri> callback) {
        this.openFileCallback = callback;
    }

    public boolean isActivityValid() {
        return !isFinishing() && !isDestroyed();
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }
}
