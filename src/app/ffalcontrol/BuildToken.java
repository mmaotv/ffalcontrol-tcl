package app.ffalcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * API token。
 *
 * 默认行为（与上游一致）：每次安装随机生成一次，存在应用私有 SharedPreferences 里，
 * 只打一行 logcat，靠 `adb logcat -d | grep "FFalControl token"` 取。
 *
 * 本库额外支持：build.sh 生成 LauncherSpec.TOKEN（64 位十六进制）时直接使用它。
 * 这样没有 adb 的电视也能用 Termux / 脚本驱动 127.0.0.1:18742。
 * 服务本身只 bind 127.0.0.1 且拒绝非 loopback 来源，令牌只是防止同机其它应用误用。
 * 不想要固定令牌：`TOKEN=random ./build.sh cyt`。
 */
final class BuildToken {

    private static final String PREFS = "ffalcontrol";
    private static final String KEY = "api_token";
    private static final String TAG = "FFalControl";

    private static String cached;

    private BuildToken() {}

    static synchronized String value(Context context) {
        if (cached != null) return cached;
        if (LauncherSpec.TOKEN != null && LauncherSpec.TOKEN.length() == 64) {
            cached = LauncherSpec.TOKEN;
            Log.i(TAG, "FFalControl token: " + cached);
            return cached;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY, null);
        if (token == null || token.length() != 64) {
            token = randomHex();
            prefs.edit().putString(KEY, token).apply();
            Log.i(TAG, "FFalControl token: " + token);
        }
        cached = token;
        return cached;
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
