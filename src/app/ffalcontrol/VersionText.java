package app.ffalcontrol;

/**
 * 版本号比较。纯文本逻辑，不依赖 Android，所以能放进 JVM 自测里跑。
 *
 * 桌面版本号长这样：Aerial 是 `1.56`，CleanYourTV 是 `3.3.0`，tag 可能带 `v` 前缀
 * （`cyt-v3.3.0`）。统一拆成数字段逐个比，缺的段按 0 算 —— 1.2 与 1.2.0 视为相同。
 */
final class VersionText {

    /** a 比 b 新返回正数，旧返回负数，相同返回 0。任一边取不到数字时返回 0。 */
    static int compare(String a, String b) {
        int[] left = digits(a);
        int[] right = digits(b);
        if (left.length == 0 || right.length == 0) return 0;
        int size = Math.max(left.length, right.length);
        for (int i = 0; i < size; i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    /** 去掉 v 前缀与首尾空白，用于展示 */
    static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        return text;
    }

    /** 取出最多 4 段数字；"v3.3.0" → [3, 3, 0] */
    static int[] digits(String raw) {
        if (raw == null) return new int[0];
        String text = normalize(raw);
        StringBuilder number = new StringBuilder();
        int[] parts = new int[4];
        int count = 0;
        for (int i = 0; i < text.length() && count < 4; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                number.append(c);
            } else if (number.length() > 0) {
                parts[count++] = toInt(number.toString());
                number.setLength(0);
            }
        }
        if (number.length() > 0 && count < 4) parts[count++] = toInt(number.toString());
        int[] out = new int[count];
        System.arraycopy(parts, 0, out, 0, count);
        return out;
    }

    private static int toInt(String raw) {
        try { return Integer.parseInt(raw); } catch (Throwable tooBig) { return 0; }
    }

    private VersionText() {}
}
