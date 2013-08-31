// Modified by Lasse Oorni and Yao Wei Tjong for Urho3D

package org.libsdl.app;

import java.io.File;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsoluteLayout;
import android.os.*;
import android.util.Log;
import android.graphics.*;
import android.media.*;
import android.hardware.*;


/**
    SDL Activity
*/
public class SDLActivity extends Activity {
    // Urho3D: adjust log level, no verbose in production code, first check log level for non-trivial message construction
    private static final String TAG = "SDL";

    // Keep track of the paused state
    public static boolean mIsPaused = false, mIsSurfaceReady = false, mHasFocus = true;

    // Main components
    protected static SDLActivity mSingleton;
    protected static SDLSurface mSurface;
    protected static View mTextEdit;
    protected static ViewGroup mLayout;

    // This is what SDL runs in. It invokes SDL_main(), eventually
    protected static Thread mSDLThread;

    // Audio
    protected static Thread mAudioThread;
    protected static AudioTrack mAudioTrack;

    // EGL objects
    protected static EGLContext  mEGLContext;
    protected static EGLSurface  mEGLSurface;
    protected static EGLDisplay  mEGLDisplay;
    protected static EGLConfig   mEGLConfig;
    protected static int mGLMajor, mGLMinor;

    // Urho3D: flag to load the .so
    private static boolean mIsSharedLibraryLoaded = false;

    // Setup
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        
        // So we can call stuff from static callbacks
        mSingleton = this;
        
        // Urho3D: auto load all the shared libraries available in the library path
        // FIXME: use getApplicationInfo().nativeLibraryDir directly when min target API is 9 or above
        String libraryPath = getApplicationInfo().dataDir + "/lib";
        //Log.v(TAG, "library path: " + libraryPath);
        if (!mIsSharedLibraryLoaded) {
            for (final File libraryFilename : new File(libraryPath).listFiles()) {
                String name = libraryFilename.getName().replaceAll("^lib(.*)\\.so$", "$1");
                //Log.v(TAG, "library name: " + name);
                System.loadLibrary(name);
            }            
            mIsSharedLibraryLoaded = true;
        }

        // Set up the surface
        mEGLSurface = EGL10.EGL_NO_SURFACE;
        mSurface = new SDLSurface(getApplication());
        mEGLContext = EGL10.EGL_NO_CONTEXT;

        mLayout = new AbsoluteLayout(this);
        mLayout.addView(mSurface);

        setContentView(mLayout);
    }

    // Events
    @Override
    protected void onPause() {
        //Log.v(TAG, "onPause()");
        super.onPause();
        SDLActivity.handlePause();
    }

    @Override
    protected void onResume() {
        //Log.v(TAG, "onResume()");
        super.onResume();
        SDLActivity.handleResume();
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        //Log.v(TAG, "onWindowFocusChanged(): " + hasFocus);
        super.onWindowFocusChanged(hasFocus);

        SDLActivity.mHasFocus = hasFocus;
        if (hasFocus) {
            SDLActivity.handleResume();
        }
    }

    @Override
    public void onLowMemory() {
        //Log.v(TAG, "onLowMemory()");
        super.onLowMemory();
        SDLActivity.nativeLowMemory();
    }

    @Override
    protected void onDestroy() {
        //Log.v(TAG, "onDestroy()");
        super.onDestroy();
        
        // Send a quit message to the application
        SDLActivity.nativeQuit();

        // Now wait for the SDL thread to quit
        if (mSDLThread != null) {
            try {
                mSDLThread.join();
            } catch(Exception e) {
                if (Log.isLoggable(TAG, Log.ERROR))
                    Log.e(TAG, "Problem stopping thread: " + e);
            }
            mSDLThread = null;

            //Log.v(TAG, "Finished waiting for SDL thread");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // Ignore volume keys so they're handled by Android
        // Urho3D: also ignore the Home key
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_HOME) {
            return false;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Called by onPause or surfaceDestroyed. Even if surfaceDestroyed
     *  is the first to be called, mIsSurfaceReady should still be set
     *  to 'true' during the call to onPause (in a usual scenario).
     */
    public static void handlePause() {
        if (!SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady) {
            SDLActivity.mIsPaused = true;
            SDLActivity.nativePause();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, false);
        }
    }

    /** Called by onResume or surfaceCreated. An actual resume should be done only when the surface is ready.
     * Note: Some Android variants may send multiple surfaceChanged events, so we don't need to resume
     * every time we get one of those events, only if it comes after surfaceDestroyed
     */
    public static void handleResume() {
        if (SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady && SDLActivity.mHasFocus) {
            SDLActivity.mIsPaused = false;
            SDLActivity.nativeResume();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, true);
        }
    }


    // Messages from the SDLMain thread
    static final int COMMAND_CHANGE_TITLE = 1;
    static final int COMMAND_UNUSED = 2;
    static final int COMMAND_TEXTEDIT_HIDE = 3;
    // Urho3D: added
    static final int COMMAND_FINISH = 4;

    protected static final int COMMAND_USER = 0x8000;

    /**
     * This method is called by SDL if SDL did not handle a message itself.
     * This happens if a received message contains an unsupported command.
     * Method can be overwritten to handle Messages in a different class.
     * @param command the command of the message.
     * @param param the parameter of the message. May be null.
     * @return if the message was handled in overridden method.
     */
    protected boolean onUnhandledMessage(int command, Object param) {
        return false;
    }

    /**
     * A Handler class for Messages from native SDL applications.
     * It uses current Activities as target (e.g. for the title).
     * static to prevent implicit references to enclosing object.
     */
    protected static class SDLCommandHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Context context = getContext();
            if (context == null) {
                Log.e(TAG, "error handling message, getContext() returned null");
                return;
            }
            switch (msg.arg1) {
            case COMMAND_CHANGE_TITLE:
                if (context instanceof Activity) {
                    ((Activity) context).setTitle((String)msg.obj);
                } else {
                    Log.e(TAG, "error handling message, getContext() returned no Activity");
                }
                break;
            case COMMAND_TEXTEDIT_HIDE:
                if (mTextEdit != null) {
                    mTextEdit.setVisibility(View.GONE);

                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(mTextEdit.getWindowToken(), 0);
                }
                break;
            // Urho3D: added
            case COMMAND_FINISH:
                if (context instanceof Activity)
                    ((Activity) context).finish();
                break;

            default:
                if ((context instanceof SDLActivity) && !((SDLActivity) context).onUnhandledMessage(msg.arg1, msg.obj)) {
                    if (Log.isLoggable(TAG, Log.ERROR))
                        Log.e(TAG, "error handling message, command is " + msg.arg1);
                }
            }
        }
    }

    // Handler for the messages
    Handler commandHandler = new SDLCommandHandler();

    // Send a message from the SDLMain thread
    boolean sendCommand(int command, Object data) {
        Message msg = commandHandler.obtainMessage();
        msg.arg1 = command;
        msg.obj = data;
        return commandHandler.sendMessage(msg);
    }

    // C functions we call
    public static native void nativeInit(String filesDir);
    public static native void nativeLowMemory();
    public static native void nativeQuit();
    public static native void nativePause();
    public static native void nativeResume();
    public static native void onNativeResize(int x, int y, int format);
    public static native void onNativeKeyDown(int keycode);
    public static native void onNativeKeyUp(int keycode);
    public static native void onNativeKeyboardFocusLost();
    public static native void onNativeTouch(int touchDevId, int pointerFingerId,
                                            int action, float x, 
                                            float y, float p);
    public static native void onNativeAccel(float x, float y, float z);

    // Java functions called from C

    public static boolean createGLContext(int majorVersion, int minorVersion, int[] attribs) {
        return initEGL(majorVersion, minorVersion, attribs);
    }
    
    public static void deleteGLContext() {
        if (SDLActivity.mEGLDisplay != null && SDLActivity.mEGLContext != EGL10.EGL_NO_CONTEXT) {
            EGL10 egl = (EGL10)EGLContext.getEGL();
            egl.eglMakeCurrent(SDLActivity.mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            egl.eglDestroyContext(SDLActivity.mEGLDisplay, SDLActivity.mEGLContext);
            SDLActivity.mEGLContext = EGL10.EGL_NO_CONTEXT;

            if (SDLActivity.mEGLSurface != EGL10.EGL_NO_SURFACE) {
                egl.eglDestroySurface(SDLActivity.mEGLDisplay, SDLActivity.mEGLSurface);
                SDLActivity.mEGLSurface = EGL10.EGL_NO_SURFACE;
            }
        }
    }

    public static void flipBuffers() {
        flipEGL();
    }

    public static boolean setActivityTitle(String title) {
        // Called from SDLMain() thread and can't directly affect the view
        return mSingleton.sendCommand(COMMAND_CHANGE_TITLE, title);
    }

    public static void finishActivity()
    {
        mSingleton.sendCommand(COMMAND_FINISH, null);
    }

    public static boolean sendMessage(int command, int param) {
        return mSingleton.sendCommand(command, Integer.valueOf(param));
    }

    public static Context getContext() {
        return mSingleton;
    }

    static class ShowTextInputTask implements Runnable {
        /*
         * This is used to regulate the pan&scan method to have some offset from
         * the bottom edge of the input region and the top edge of an input
         * method (soft keyboard)
         */
        static final int HEIGHT_PADDING = 15;

        public int x, y, w, h;

        public ShowTextInputTask(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public void run() {
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(
                    w, h + HEIGHT_PADDING, x, y);

            if (mTextEdit == null) {
                mTextEdit = new DummyEdit(getContext());

                mLayout.addView(mTextEdit, params);
            } else {
                mTextEdit.setLayoutParams(params);
            }

            mTextEdit.setVisibility(View.VISIBLE);
            mTextEdit.requestFocus();

            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mTextEdit, 0);
        }
    }

    public static boolean showTextInput(int x, int y, int w, int h) {
        // Transfer the task to the main thread as a Runnable
        return mSingleton.commandHandler.post(new ShowTextInputTask(x, y, w, h));
    }


    // EGL functions
    public static boolean initEGL(int majorVersion, int minorVersion, int[] attribs) {
        try {
            EGL10 egl = (EGL10)EGLContext.getEGL();
            
            if (SDLActivity.mEGLDisplay == null) {
                SDLActivity.mEGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                int[] version = new int[2];
                egl.eglInitialize(SDLActivity.mEGLDisplay, version);
            }
            
            if (SDLActivity.mEGLDisplay != null && SDLActivity.mEGLContext == EGL10.EGL_NO_CONTEXT) {
                // No current GL context exists, we will create a new one.
                if (Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "Starting up OpenGL ES " + majorVersion + "." + minorVersion);
                EGLConfig[] configs = new EGLConfig[128];
                int[] num_config = new int[1];
                if (!egl.eglChooseConfig(SDLActivity.mEGLDisplay, attribs, configs, 1, num_config) || num_config[0] == 0) {
                    Log.e("SDL", "No EGL config available");
                    return false;
                }
                EGLConfig config = null;
                int bestdiff = -1, bitdiff;
                int[] value = new int[1];
                
                // eglChooseConfig returns a number of configurations that match or exceed the requested attribs.
                // From those, we select the one that matches our requirements more closely
                if (Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "Got " + num_config[0] + " valid modes from egl");
                for(int i = 0; i < num_config[0]; i++) {
                    bitdiff = 0;
                    // Go through some of the attributes and compute the bit difference between what we want and what we get.
                    for (int j = 0; ; j += 2) {
                        if (attribs[j] == EGL10.EGL_NONE)
                            break;

                        if (attribs[j+1] != EGL10.EGL_DONT_CARE && (attribs[j] == EGL10.EGL_RED_SIZE ||
                            attribs[j] == EGL10.EGL_GREEN_SIZE ||
                            attribs[j] == EGL10.EGL_BLUE_SIZE ||
                            attribs[j] == EGL10.EGL_ALPHA_SIZE ||
                            attribs[j] == EGL10.EGL_DEPTH_SIZE ||
                            attribs[j] == EGL10.EGL_STENCIL_SIZE)) {
                            egl.eglGetConfigAttrib(SDLActivity.mEGLDisplay, configs[i], attribs[j], value);
                            bitdiff += value[0] - attribs[j + 1]; // value is always >= attrib
                        }
                    }
                    
                    if (bitdiff < bestdiff || bestdiff == -1) {
                        config = configs[i];
                        bestdiff = bitdiff;
                    }
                    
                    if (bitdiff == 0) break; // we found an exact match!
                }
                
                if (Log.isLoggable(TAG, Log.DEBUG))
                    Log.d("SDL", "Selected mode with a total bit difference of " + bestdiff);

                SDLActivity.mEGLConfig = config;
                SDLActivity.mGLMajor = majorVersion;
                SDLActivity.mGLMinor = minorVersion;
            }
            
            return SDLActivity.createEGLSurface();

        } catch(Exception e) {
            if (Log.isLoggable(TAG, Log.ERROR))
                Log.e(TAG, "initEGL(): " + e);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                for (StackTraceElement s : e.getStackTrace()) {
                    Log.d(TAG, s.toString());
                }
            }
            return false;
        }
    }

    public static boolean createEGLContext() {
        EGL10 egl = (EGL10)EGLContext.getEGL();
        int EGL_CONTEXT_CLIENT_VERSION=0x3098;
        int contextAttrs[] = new int[] { EGL_CONTEXT_CLIENT_VERSION, SDLActivity.mGLMajor, EGL10.EGL_NONE };
        SDLActivity.mEGLContext = egl.eglCreateContext(SDLActivity.mEGLDisplay, SDLActivity.mEGLConfig, EGL10.EGL_NO_CONTEXT, contextAttrs);
        if (SDLActivity.mEGLContext == EGL10.EGL_NO_CONTEXT) {
            Log.e("SDL", "Couldn't create context");
            return false;
        }
        return true;
    }

    public static boolean createEGLSurface() {
        if (SDLActivity.mEGLDisplay != null && SDLActivity.mEGLConfig != null) {
            EGL10 egl = (EGL10)EGLContext.getEGL();
            if (SDLActivity.mEGLContext == EGL10.EGL_NO_CONTEXT) createEGLContext();

            if (SDLActivity.mEGLSurface == EGL10.EGL_NO_SURFACE) {
                Log.d(TAG, "Creating new EGL Surface");
                SDLActivity.mEGLSurface = egl.eglCreateWindowSurface(SDLActivity.mEGLDisplay, SDLActivity.mEGLConfig, SDLActivity.mSurface, null);
                if (SDLActivity.mEGLSurface == EGL10.EGL_NO_SURFACE) {
                    Log.e("SDL", "Couldn't create surface");
                    return false;
                }
            }
            else Log.d(TAG, "EGL Surface remains valid");

            if (egl.eglGetCurrentContext() != SDLActivity.mEGLContext) {
                if (!egl.eglMakeCurrent(SDLActivity.mEGLDisplay, SDLActivity.mEGLSurface, SDLActivity.mEGLSurface, SDLActivity.mEGLContext)) {
                    Log.e("SDL", "Old EGL Context doesnt work, trying with a new one");
                    // TODO: Notify the user via a message that the old context could not be restored, and that textures need to be manually restored.
                    createEGLContext();
                    if (!egl.eglMakeCurrent(SDLActivity.mEGLDisplay, SDLActivity.mEGLSurface, SDLActivity.mEGLSurface, SDLActivity.mEGLContext)) {
                        Log.e("SDL", "Failed making EGL Context current");
                        return false;
                    }
                }
                else Log.d(TAG, "EGL Context made current");
            }
            else Log.d(TAG, "EGL Context remains current");

            return true;
        } else {
            if (Log.isLoggable(TAG, Log.ERROR))
                Log.e("SDL", "Surface creation failed, display = " + SDLActivity.mEGLDisplay + ", config = " + SDLActivity.mEGLConfig);
            return false;
        }
    }

    // EGL buffer flip
    public static void flipEGL() {
        try {
            EGL10 egl = (EGL10)EGLContext.getEGL();

            egl.eglWaitNative(EGL10.EGL_CORE_NATIVE_ENGINE, null);

            // drawing here

            egl.eglWaitGL();

            egl.eglSwapBuffers(SDLActivity.mEGLDisplay, SDLActivity.mEGLSurface);


        } catch(Exception e) {
            if (Log.isLoggable(TAG, Log.ERROR))
                Log.e(TAG, "flipEGL(): " + e);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                for (StackTraceElement s : e.getStackTrace()) {
                    Log.d(TAG, s.toString());
                }
            }
        }
    }

    // Audio
    public static int audioInit(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        int channelConfig = isStereo ? AudioFormat.CHANNEL_CONFIGURATION_STEREO : AudioFormat.CHANNEL_CONFIGURATION_MONO;
        int audioFormat = is16Bit ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);
        
        if (Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "SDL audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + (sampleRate / 1000f) + "kHz, " + desiredFrames + " frames buffer");
        
        // Let the user pick a larger buffer if they really want -- but ye
        // gods they probably shouldn't, the minimums are horrifyingly high
        // latency already
        desiredFrames = Math.max(desiredFrames, (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize - 1) / frameSize);
        
        if (mAudioTrack == null) {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    channelConfig, audioFormat, desiredFrames * frameSize, AudioTrack.MODE_STREAM);
            
            // Instantiating AudioTrack can "succeed" without an exception and the track may still be invalid
            // Ref: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioTrack.java
            // Ref: http://developer.android.com/reference/android/media/AudioTrack.html#getState()
            
            if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e("SDL", "Failed during initialization of Audio Track");
                mAudioTrack = null;
                return -1;
            }
            
            mAudioTrack.play();
        }

        if (Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "SDL audio: got " + ((mAudioTrack.getChannelCount() >= 2) ? "stereo" : "mono") + " " + ((mAudioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) ? "16-bit" : "8-bit") + " " + (mAudioTrack.getSampleRate() / 1000f) + "kHz, " + desiredFrames + " frames buffer");
        
        return 0;
    }
    
    public static void audioWriteShortBuffer(short[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(short)");
                return;
            }
        }
    }
    
    public static void audioWriteByteBuffer(byte[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(byte)");
                return;
            }
        }
    }

    public static void audioQuit() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
    }
}

/**
    Simple nativeInit() runnable
*/
class SDLMain implements Runnable {
    @Override
    public void run() {
        // Runs SDL_main()
        // Urho3D: pass filesDir
        SDLActivity.nativeInit(((Activity)SDLActivity.getContext()).getFilesDir().getAbsolutePath());
        //Log.v("SDL", "SDL thread terminated");
        // Urho3D: finish activity when SDL_main returns
        SDLActivity.finishActivity();
    }
}


/**
    SDLSurface. This is what we draw on, so we need to know when it's created
    in order to do anything useful. 

    Because of this, that's where we set up the SDL thread
*/
class SDLSurface extends SurfaceView implements SurfaceHolder.Callback, 
    View.OnKeyListener, View.OnTouchListener, SensorEventListener  {

    private static final String TAG = "SDL";
    
    // Sensors
    protected static SensorManager mSensorManager;

    // Keep track of the surface size to normalize touch events
    protected static float mWidth, mHeight;

    // Startup    
    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this); 
    
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this); 
        setOnTouchListener(this);   

        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);

        // Some arbitrary defaults to avoid a potential division by zero
        mWidth = 1.0f;
        mHeight = 1.0f;
    }

    // Called when we have a valid drawing surface
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        //Log.v(TAG, "surfaceCreated()");
        holder.setType(SurfaceHolder.SURFACE_TYPE_GPU);
        // Set mIsSurfaceReady to 'true' *before* any call to handleResume
        SDLActivity.mIsSurfaceReady = true;
    }

    // Called when we lose the surface
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        //Log.v(TAG, "surfaceDestroyed()");
        // Call this *before* setting mIsSurfaceReady to 'false'
        SDLActivity.handlePause();
        SDLActivity.mIsSurfaceReady = false;

        /* We have to clear the current context and destroy the egl surface here
         * Otherwise there's BAD_NATIVE_WINDOW errors coming from eglCreateWindowSurface on resume
         * Ref: http://stackoverflow.com/questions/8762589/eglcreatewindowsurface-on-ics-and-switching-from-2d-to-3d
         */
        
        EGL10 egl = (EGL10)EGLContext.getEGL();
        egl.eglMakeCurrent(SDLActivity.mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        egl.eglDestroySurface(SDLActivity.mEGLDisplay, SDLActivity.mEGLSurface);
        SDLActivity.mEGLSurface = EGL10.EGL_NO_SURFACE;
    }

    // Called when the surface is resized
    @Override
    public void surfaceChanged(SurfaceHolder holder,
                               int format, int width, int height) {
        //Log.v(TAG, "surfaceChanged()");

        int sdlFormat = 0x15151002; // SDL_PIXELFORMAT_RGB565 by default
        switch (format) {
        case PixelFormat.A_8:
            Log.d(TAG, "pixel format A_8");
            break;
        case PixelFormat.LA_88:
            Log.d(TAG, "pixel format LA_88");
            break;
        case PixelFormat.L_8:
            Log.d(TAG, "pixel format L_8");
            break;
        case PixelFormat.RGBA_4444:
            Log.d(TAG, "pixel format RGBA_4444");
            sdlFormat = 0x15421002; // SDL_PIXELFORMAT_RGBA4444
            break;
        case PixelFormat.RGBA_5551:
            Log.d(TAG, "pixel format RGBA_5551");
            sdlFormat = 0x15441002; // SDL_PIXELFORMAT_RGBA5551
            break;
        case PixelFormat.RGBA_8888:
            Log.d(TAG, "pixel format RGBA_8888");
            sdlFormat = 0x16462004; // SDL_PIXELFORMAT_RGBA8888
            break;
        case PixelFormat.RGBX_8888:
            Log.d(TAG, "pixel format RGBX_8888");
            sdlFormat = 0x16261804; // SDL_PIXELFORMAT_RGBX8888
            break;
        case PixelFormat.RGB_332:
            Log.d(TAG, "pixel format RGB_332");
            sdlFormat = 0x14110801; // SDL_PIXELFORMAT_RGB332
            break;
        case PixelFormat.RGB_565:
            Log.d(TAG, "pixel format RGB_565");
            sdlFormat = 0x15151002; // SDL_PIXELFORMAT_RGB565
            break;
        case PixelFormat.RGB_888:
            Log.d(TAG, "pixel format RGB_888");
            // Not sure this is right, maybe SDL_PIXELFORMAT_RGB24 instead?
            sdlFormat = 0x16161804; // SDL_PIXELFORMAT_RGB888
            break;
        default:
            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "pixel format unknown " + format);
            break;
        }

        mWidth = width;
        mHeight = height;
        SDLActivity.onNativeResize(width, height, sdlFormat);
        if (Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "Window size: " + width + "x" + height);

        // Set mIsSurfaceReady to 'true' *before* making a call to handleResume
        SDLActivity.mIsSurfaceReady = true;

        if (SDLActivity.mSDLThread == null) {
            // This is the entry point to the C app.
            // Start up the C app thread and enable sensor input for the first time

            SDLActivity.mSDLThread = new Thread(new SDLMain(), "SDLThread");
            enableSensor(Sensor.TYPE_ACCELEROMETER, true);
            SDLActivity.mSDLThread.start();
        }
    }

    // unused
    @Override
    public void onDraw(Canvas canvas) {}


    // Key events
    @Override
    public boolean onKey(View  v, int keyCode, KeyEvent event) {
        
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            //Log.v(TAG, "key down: " + keyCode);
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        }
        else if (event.getAction() == KeyEvent.ACTION_UP) {
            //Log.v(TAG, "key up: " + keyCode);
            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }
        
        return false;
    }

    // Touch events
    @Override
    public boolean onTouch(View v, MotionEvent event) {
             final int touchDevId = event.getDeviceId();
             final int pointerCount = event.getPointerCount();
             // touchId, pointerId, action, x, y, pressure
             int actionPointerIndex = (event.getAction() & MotionEvent.ACTION_POINTER_ID_MASK) >> MotionEvent.ACTION_POINTER_ID_SHIFT; /* API 8: event.getActionIndex(); */
             int pointerFingerId = event.getPointerId(actionPointerIndex);
             int action = (event.getAction() & MotionEvent.ACTION_MASK); /* API 8: event.getActionMasked(); */

             float x = event.getX(actionPointerIndex) / mWidth;
             float y = event.getY(actionPointerIndex) / mHeight;
             float p = event.getPressure(actionPointerIndex);

             if (action == MotionEvent.ACTION_MOVE && pointerCount > 1) {
                // TODO send motion to every pointer if its position has
                // changed since prev event.
                for (int i = 0; i < pointerCount; i++) {
                    pointerFingerId = event.getPointerId(i);
                    x = event.getX(i) / mWidth;
                    y = event.getY(i) / mHeight;
                    p = event.getPressure(i);
                    SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
                }
             } else {
                SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
             }
      return true;
   } 

    // Sensor events
    public void enableSensor(int sensortype, boolean enabled) {
        // TODO: This uses getDefaultSensor - what if we have >1 accels?
        if (enabled) {
            mSensorManager.registerListener(this, 
                            mSensorManager.getDefaultSensor(sensortype), 
                            SensorManager.SENSOR_DELAY_GAME, null);
        } else {
            mSensorManager.unregisterListener(this, 
                            mSensorManager.getDefaultSensor(sensortype));
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            SDLActivity.onNativeAccel(event.values[0] / SensorManager.GRAVITY_EARTH,
                                      event.values[1] / SensorManager.GRAVITY_EARTH,
                                      event.values[2] / SensorManager.GRAVITY_EARTH);
        }
    }
    
}

/* This is a fake invisible editor view that receives the input and defines the
 * pan&scan region
 */
class DummyEdit extends View implements View.OnKeyListener {
    InputConnection ic;

    public DummyEdit(Context context) {
        super(context);
        setFocusableInTouchMode(true);
        setFocusable(true);
        setOnKeyListener(this);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {

        // This handles the hardware keyboard input
        if (event.isPrintingKey()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                ic.commitText(String.valueOf((char) event.getUnicodeChar()), 1);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }

        return false;
    }
        
    //
    @Override
    public boolean onKeyPreIme (int keyCode, KeyEvent event) {
        // As seen on StackOverflow: http://stackoverflow.com/questions/7634346/keyboard-hide-event
        // FIXME: Discussion at http://bugzilla.libsdl.org/show_bug.cgi?id=1639
        // FIXME: This is not a 100% effective solution to the problem of detecting if the keyboard is showing or not
        // FIXME: A more effective solution would be to change our Layout from AbsoluteLayout to Relative or Linear
        // FIXME: And determine the keyboard presence doing this: http://stackoverflow.com/questions/2150078/how-to-check-visibility-of-software-keyboard-in-android
        // FIXME: An even more effective way would be if Android provided this out of the box, but where would the fun be in that :)
        if (event.getAction()==KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
            if (SDLActivity.mTextEdit != null && SDLActivity.mTextEdit.getVisibility() == View.VISIBLE) {
                SDLActivity.onNativeKeyboardFocusLost();
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        ic = new SDLInputConnection(this, true);

        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | 33554432 /* API 11: EditorInfo.IME_FLAG_NO_FULLSCREEN */;

        return ic;
    }
}

class SDLInputConnection extends BaseInputConnection {

    public SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {

        /*
         * This handles the keycodes from soft keyboard (and IME-translated
         * input from hardkeyboard)
         */
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.isPrintingKey()) {
                commitText(String.valueOf((char) event.getUnicodeChar()), 1);
            }
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {

            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {

        nativeCommitText(text.toString(), newCursorPosition);

        return super.commitText(text, newCursorPosition);
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {

        nativeSetComposingText(text.toString(), newCursorPosition);

        return super.setComposingText(text, newCursorPosition);
    }

    public native void nativeCommitText(String text, int newCursorPosition);

    public native void nativeSetComposingText(String text, int newCursorPosition);

}