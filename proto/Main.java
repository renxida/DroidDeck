package claude.vd;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Shell-uid helper, run with:
 *   CLASSPATH=/data/local/tmp/vd.dex app_process / claude.vd.Main <w> <h> <dpi> <flags> [name]
 * Creates a VirtualDisplay (a "workspace") backed by an ImageReader and reads commands on stdin:
 *   shot <path>              save the latest frame as PNG
 *   ime <displayId> <policy> set display IME policy (0=local,1=fallback,2=hide)
 *   move <taskId> <displayId> move a root task to a display
 *   info                     print display info as apps see it
 *   quit
 */
public class Main {
    static final String PKG = "com.android.shell";

    static class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }
        @Override public String getPackageName() { return PKG; }
        @Override public String getOpPackageName() { return PKG; }
        @Override public Context getApplicationContext() { return this; }
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field f = at.getDeclaredField("sCurrentActivityThread");
        f.setAccessible(true);
        f.set(null, thread);
        Method m = at.getDeclaredMethod("getSystemContext");
        return (Context) m.invoke(thread);
    }

    static volatile Image last;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        int w = Integer.parseInt(args[0]), h = Integer.parseInt(args[1]), dpi = Integer.parseInt(args[2]);
        int flags = (int) Long.parseLong(args[3]);
        String name = args.length > 4 ? args[4] : "claude-workspace";
        Context ctx = new FakeContext(systemContext());
        Constructor<DisplayManager> dmc = DisplayManager.class.getDeclaredConstructor(Context.class);
        dmc.setAccessible(true);
        DisplayManager dm = dmc.newInstance(ctx);

        HandlerThread ht = new HandlerThread("frames");
        ht.start();
        Handler hd = new Handler(ht.getLooper());
        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        final long[] frames = {0};
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                synchronized (lock) { if (last != null) last.close(); last = img; frames[0]++; }
            }
        }, hd);

        long t0 = SystemClock.elapsedRealtimeNanos();
        VirtualDisplay vd;
        try {
            vd = dm.createVirtualDisplay(name, w, h, dpi, reader.getSurface(), flags);
        } catch (Throwable t) {
            System.out.println("CREATE_FAILED " + t);
            System.out.flush();
            System.exit(2);
            return;
        }
        Display d = vd.getDisplay();
        System.out.println("DISPLAY_ID " + d.getDisplayId() + " createMs=" + (SystemClock.elapsedRealtimeNanos() - t0) / 1e6);
        printInfo(d);
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] p = line.trim().split("\\s+");
            try {
                switch (p[0]) {
                    case "shot": shot(p[1]); System.out.println("OK shot frames=" + frames[0]); break;
                    case "ime": setIme(Integer.parseInt(p[1]), Integer.parseInt(p[2])); break;
                    case "move": move(Integer.parseInt(p[1]), Integer.parseInt(p[2])); break;
                    case "info": printInfo(d); break;
                    case "swap": swap(vd, reader, w, h, hd); break;
                    case "uinput": uinput(); break;
                    case "assoc": assoc(); break;
                    case "quit": vd.release(); System.out.println("BYE"); System.out.flush(); System.exit(0);
                    default: System.out.println("ERR unknown " + p[0]);
                }
            } catch (Throwable t) {
                System.out.println("ERR " + p[0] + " " + t + (t.getCause() != null ? " cause=" + t.getCause() : ""));
            }
            System.out.flush();
        }
    }

    /** Point the display at a second surface and back; time until a frame lands on each. */
    static void swap(VirtualDisplay vd, ImageReader main, int w, int h, Handler hd) throws Exception {
        final ImageReader r2 = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        final Object got = new Object();
        final boolean[] seen = {false};
        r2.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image i = r.acquireLatestImage(); if (i != null) i.close();
                synchronized (got) { seen[0] = true; got.notifyAll(); }
            }
        }, hd);
        long t0 = SystemClock.elapsedRealtimeNanos();
        vd.setSurface(r2.getSurface());
        synchronized (got) { long end = System.currentTimeMillis() + 3000; while (!seen[0] && System.currentTimeMillis() < end) got.wait(100); }
        double ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
        System.out.println("OK swap toSecond frame=" + seen[0] + " ms=" + ms + " sameDisplayId=" + vd.getDisplay().getDisplayId());
        long t1 = SystemClock.elapsedRealtimeNanos();
        vd.setSurface(main.getSurface());
        System.out.println("OK swap back ms=" + (SystemClock.elapsedRealtimeNanos() - t1) / 1e6);
        r2.close();
    }

    static void uinput() {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile("/dev/uinput", "rw")) {
            System.out.println("OK uinput open rw");
        } catch (Throwable t) { System.out.println("ERR uinput " + t); }
    }

    /** Can the shell tie an input device to a display? Calls each addUniqueIdAssociation* with dummy ids. */
    static void assoc() throws Exception {
        Object im = service("input", "android.hardware.input.IInputManager$Stub");
        for (Method m : im.getClass().getMethods()) {
            if (!m.getName().startsWith("addUniqueIdAssociation") && !m.getName().startsWith("addPortAssociation")) continue;
            Class<?>[] pt = m.getParameterTypes();
            Object[] a = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) a[i] = pt[i] == String.class ? "claude-test-" + i : (pt[i] == int.class ? (Object) 0 : null);
            try { m.invoke(im, a); System.out.println("OK assoc " + m.getName() + java.util.Arrays.toString(pt)); }
            catch (java.lang.reflect.InvocationTargetException e) { System.out.println("ERR assoc " + m.getName() + " " + e.getCause()); }
            catch (Throwable t) { System.out.println("ERR assoc " + m.getName() + " " + t); }
        }
    }

    static void printInfo(Display d) {
        try {
            Method gt = Display.class.getMethod("getType");
            System.out.println("INFO id=" + d.getDisplayId() + " name=" + d.getName() + " flags=0x" + Integer.toHexString(d.getFlags())
                + " type=" + gt.invoke(d) + " state=" + d.getState() + " refresh=" + d.getRefreshRate());
        } catch (Throwable t) { System.out.println("INFO err " + t); }
    }

    static void shot(String path) throws Exception {
        synchronized (lock) {
            if (last == null) throw new IllegalStateException("no frame yet");
            Image.Plane pl = last.getPlanes()[0];
            int w = last.getWidth(), h = last.getHeight();
            int rowPad = pl.getRowStride() - pl.getPixelStride() * w;
            Bitmap b = Bitmap.createBitmap(w + rowPad / pl.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            ByteBuffer buf = pl.getBuffer(); buf.rewind();
            b.copyPixelsFromBuffer(buf);
            Bitmap c = Bitmap.createBitmap(b, 0, 0, w, h);
            try (FileOutputStream fo = new FileOutputStream(path)) { c.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        }
    }

    static Object service(String name, String stubClass) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        Class<?> stub = Class.forName(stubClass);
        return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    static void setIme(int displayId, int policy) throws Exception {
        Object wm = service("window", "android.view.IWindowManager$Stub");
        Method m = wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
        m.invoke(wm, displayId, policy);
        System.out.println("OK ime display=" + displayId + " policy=" + policy);
    }

    static void move(int taskId, int displayId) throws Exception {
        Object atm = service("activity_task", "android.app.IActivityTaskManager$Stub");
        long t0 = SystemClock.elapsedRealtimeNanos();
        Method m = atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
        m.invoke(atm, taskId, displayId);
        System.out.println("OK move task=" + taskId + " display=" + displayId + " ms=" + (SystemClock.elapsedRealtimeNanos() - t0) / 1e6);
    }
}
