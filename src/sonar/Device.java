package sonar;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.*;
import java.lang.reflect.Method;

/** Android 9 device-local screen and touch, using AOSP system interfaces. */
final class Device {
    final File root;
    final Object input;
    final Method inject, screenshot;
    Device(File root) throws Exception {
        this.root = root;
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        input = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        screenshot = Class.forName("android.view.SurfaceControl").getMethod("screenshot", Rect.class, int.class, int.class, int.class);
    }
    Bitmap capture() throws Exception {
        Bitmap hardware = (Bitmap)screenshot.invoke(null, new Rect(), 1280, 720, 0);
        if (hardware == null) throw new IOException("截图失败");
        Bitmap bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
        hardware.recycle();
        return bitmap;
    }
    void tap(int x, int y) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,x,y,0);
        down.setSource(0x1002);
        try {
            if (!(Boolean)inject.invoke(input, down, 2))
                throw new IOException("触控未被系统接受");
            // A future UP timestamp does not hold the finger down. Separate
            // DOWN and UP by the actual gesture duration.
            SystemClock.sleep(35);
            MotionEvent up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);
            up.setSource(0x1002);
            try {if(!(Boolean)inject.invoke(input,up,2))throw new IOException("触控未被系统接受");}
            finally {up.recycle();}
        } finally { down.recycle(); }
    }
    void back() throws Exception {
        long downTime=SystemClock.uptimeMillis();
        for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
            KeyEvent event=new KeyEvent(downTime,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_BACK,0);
            if(!(Boolean)inject.invoke(input,event,2))throw new IOException("Android 返回键未被系统接受");
            if(action==KeyEvent.ACTION_DOWN)SystemClock.sleep(35);
        }
    }
    void save(Bitmap bitmap,String name) throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(root,name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
    }
    static boolean foreground(String pkg)throws Exception{
        // During an activity transition Android may have no resumed activity.
        // That is a state, not a failed dumpsys command or permission to tap.
        for(String line:shell("dumpsys activity activities").split("\n"))
            if(line.trim().startsWith("mResumedActivity:")&&line.contains(pkg+"/"))return true;
        return false;
    }
    static String shell(String command) throws Exception {
        Process p = new ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true).start();
        ByteArrayOutputStream data=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n;
        try(InputStream in=p.getInputStream()) { while((n=in.read(buf))!=-1)data.write(buf,0,n); }
        int code=p.waitFor();String result=data.toString("UTF-8");
        if(code!=0)throw new IOException("设备命令失败 ("+code+"): "+command+"\n"+result);
        return result;
    }
}
