package app.andy.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.List;

public class Helper {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: Helper [list|icon <package>]");
            System.exit(1);
        }

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object thread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(thread);
            PackageManager pm = context.getPackageManager();

            String cmd = args[0];
            if ("list".equals(cmd)) {
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo app : apps) {
                    CharSequence label = pm.getApplicationLabel(app);
                    System.out.println(app.packageName + "\t" + label);
                }
            } else if ("icon".equals(cmd)) {
                if (args.length < 2) {
                    System.err.println("Usage: Helper icon <package>");
                    System.exit(1);
                }
                String pkg = args[1];
                Drawable drawable = pm.getApplicationIcon(pkg);
                Bitmap bitmap;
                if (drawable instanceof BitmapDrawable) {
                    bitmap = ((BitmapDrawable) drawable).getBitmap();
                } else {
                    int w = drawable.getIntrinsicWidth();
                    int h = drawable.getIntrinsicHeight();
                    if (w <= 0) w = 128;
                    if (h <= 0) h = 128;
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, w, h);
                    drawable.draw(canvas);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                byte[] bytes = out.toByteArray();
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                System.out.println(base64);
            } else {
                System.err.println("Unknown command: " + cmd);
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
