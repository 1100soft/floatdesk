/** Supply framework initialization absent from plain app_process, including Choreographer's IDs controller. */
public final class DeviceSmokeMain {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        systemMain.invoke(null);
        Class.forName(args[0]).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
}
