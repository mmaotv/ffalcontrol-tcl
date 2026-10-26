package app.ffalcontrol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 精简的二进制 AndroidManifest.xml (AXML) 读取器。
 *
 * 为什么需要它：厂商的 LauncherStrategy 是用 PackageParser.parsePackage() 读 intent-filter
 * 来判断「是不是桌面」的，而 PackageManager.getPackageArchiveInfo() 返回的 ActivityInfo
 * 不带 intent-filter。PackageParser 是 @hide，普通应用碰不到。所以这里自己解 AXML。
 *
 * 只做三件事，别的一概不管：
 *   1. 有没有某个 activity 的某个 intent-filter 同时声明 MAIN + HOME
 *   2. application 级 meta-data 里 com.tcl.app.type 是什么
 *   3. manifest 的 package / versionName / versionCode（兜底用）
 */
final class AxmlReader {

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int RES_XML_END_ELEMENT = 0x0103;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;

    static final class Manifest {
        String packageName;
        String versionName;
        int versionCode = -1;
        /** 至少有一个 activity 的 intent-filter 同时声明 MAIN + HOME。 */
        boolean declaresHome;
        /** application 级 com.tcl.app.type 的值，没有则为 null。 */
        String tclAppType;
        /** 有几个 activity 声明了 LAUNCHER（仅用于展示）。 */
        int launcherActivities;
    }

    private AxmlReader() {}

    static Manifest read(File apk) throws IOException {
        return read(extractManifest(apk));
    }

    /** 从 zip 里取出 AndroidManifest.xml 的原始字节。 */
    private static byte[] extractManifest(File apk) throws IOException {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
        try {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IOException("APK 里没有 AndroidManifest.xml");
            java.io.InputStream in = zip.getInputStream(entry);
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) > 0) buffer.write(chunk, 0, count);
                return buffer.toByteArray();
            } finally {
                try { in.close(); } catch (IOException ignored) { }
            }
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
    }

    /** 直接读文件字节（非 zip 场景，供测试用）。 */
    static Manifest read(byte[] data) throws IOException {
        Manifest out = new Manifest();
        if (data == null || data.length < 8) throw new IOException("AXML 太短");
        int magic = u16(data, 0);
        if (magic != 0x0003) throw new IOException("不是 AXML（magic=" + Integer.toHexString(magic) + "）");

        String[] pool = null;
        List<String> stack = new ArrayList<String>();
        Filter current = null;
        boolean inManifest = false;

        int offset = u16(data, 2);           // 文件头长度，通常是 8
        int total = Math.min(data.length, u32(data, 4));

        while (offset + 8 <= total) {
            int type = u16(data, offset);
            int headerSize = u16(data, offset + 2);
            int chunkSize = u32(data, offset + 4);
            if (chunkSize < 8 || offset + chunkSize > total) break;

            if (type == RES_STRING_POOL && pool == null) {
                pool = readPool(data, offset, headerSize, chunkSize);
            } else if (type == RES_XML_START_ELEMENT && pool != null) {
                String name = stringAt(pool, u32(data, offset + 20));
                stack.add(name);
                if (stack.size() == 1) inManifest = "manifest".equals(name);

                // <intent-filter> 的父元素决定它属于 activity / service / receiver
                if ("intent-filter".equals(name) && stack.size() >= 2) {
                    String parent = stack.get(stack.size() - 2);
                    boolean ownerIsActivity = "activity".equals(parent) || "activity-alias".equals(parent);
                    current = new Filter(ownerIsActivity);
                }

                if (current != null && stack.size() >= 3) {
                    String parent = stack.get(stack.size() - 2);
                    if ("intent-filter".equals(parent)) {
                        Attr attr = findAttribute(data, offset, headerSize, pool,
                                "http://schemas.android.com/apk/res/android", "name");
                        if (attr != null) {
                            if ("action".equals(name) && "android.intent.action.MAIN".equals(attr.value)) {
                                current.main = true;
                            } else if ("category".equals(name) && "android.intent.category.HOME".equals(attr.value)) {
                                current.home = true;
                            } else if ("category".equals(name) && "android.intent.category.LAUNCHER".equals(attr.value)) {
                                current.launcher = true;
                            }
                        }
                    }
                }

                if ("manifest".equals(name) || (stack.size() == 2 && "application".equals(name))
                        || (stack.size() == 3 && "meta-data".equals(name) && "application".equals(stack.get(1)))) {
                    applyAttributes(out, data, offset, headerSize, pool, name, stack);
                }
            } else if (type == RES_XML_END_ELEMENT && pool != null) {
                String name = stringAt(pool, u32(data, offset + 20));
                if ("intent-filter".equals(name) && current != null) {
                    if (current.ownerIsActivity && current.main && current.home) out.declaresHome = true;
                    if (current.ownerIsActivity && current.launcher) out.launcherActivities++;
                    current = null;
                }
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                if (stack.isEmpty()) inManifest = false;
            }
            offset += chunkSize;
        }
        if (!inManifest && out.packageName == null && pool == null) {
            throw new IOException("AXML 里没有找到字符串池");
        }
        return out;
    }

    private static void applyAttributes(Manifest out, byte[] data, int chunk, int headerSize,
                                        String[] pool, String element, List<String> stack) {
        if ("manifest".equals(element)) {
            Attr pkg = findAttribute(data, chunk, headerSize, pool, null, "package");
            if (pkg != null) out.packageName = pkg.value;
            Attr versionName = findAttribute(data, chunk, headerSize, pool,
                    "http://schemas.android.com/apk/res/android", "versionName");
            if (versionName != null) out.versionName = versionName.value;
            Attr versionCode = findAttribute(data, chunk, headerSize, pool,
                    "http://schemas.android.com/apk/res/android", "versionCode");
            if (versionCode != null) out.versionCode = versionCode.intValue;
            return;
        }
        if ("meta-data".equals(element)) {
            Attr key = findAttribute(data, chunk, headerSize, pool,
                    "http://schemas.android.com/apk/res/android", "name");
            if (key == null || !"com.tcl.app.type".equals(key.value)) return;
            Attr value = findAttribute(data, chunk, headerSize, pool,
                    "http://schemas.android.com/apk/res/android", "value");
            if (value != null) out.tclAppType = value.value;
        }
    }

    private static final class Filter {
        final boolean ownerIsActivity;
        boolean main;
        boolean home;
        boolean launcher;
        Filter(boolean ownerIsActivity) { this.ownerIsActivity = ownerIsActivity; }
    }

    private static final class Attr {
        final String value;
        final int intValue;
        Attr(String value, int intValue) { this.value = value; this.intValue = intValue; }
    }

    /**
     * 在 element chunk 的属性表里找某个属性。
     * namespace 传 null 表示「无命名空间」的属性；传 URI 表示该命名空间下的属性。
     *
     * 布局：ResXMLTree_node(headerSize=16: chunk 头 8 + lineNumber 4 + comment 4)
     *       + ResXMLTree_attrExt(ns 4 + name 4 + attributeStart 2 + attributeSize 2 + ...)
     * attributeStart 是相对 attrExt 起点的偏移（通常是 20），所以属性表起点 = chunk + headerSize + attributeStart。
     */
    private static Attr findAttribute(byte[] data, int chunk, int headerSize, String[] pool,
                                      String namespace, String name) {
        int nodeSize = u16(data, chunk + 2);
        if (nodeSize <= 0) nodeSize = 16;
        int attrStart = u16(data, chunk + nodeSize + 8);
        int attributeSize = u16(data, chunk + nodeSize + 10);
        int attributeCount = u16(data, chunk + nodeSize + 12);
        if (attributeSize < 20 || attributeCount <= 0) return null;
        int base = chunk + nodeSize + attrStart;
        int limit = chunk + Math.min(u32(data, chunk + 4), data.length - chunk);
        for (int i = 0; i < attributeCount; i++) {
            int at = base + i * attributeSize;
            if (at + 20 > limit) break;
            String attrNs = stringAt(pool, u32(data, at));
            String attrName = stringAt(pool, u32(data, at + 4));
            if (!name.equals(attrName)) continue;
            if (namespace == null) {
                if (attrNs != null && attrNs.length() != 0) continue;
            } else if (!namespace.equals(attrNs)) {
                continue;
            }
            int rawValue = u32(data, at + 8);
            int dataType = data[at + 15] & 0xFF;
            int typedData = u32(data, at + 16);
            if (rawValue != 0xFFFFFFFF) return new Attr(stringAt(pool, rawValue), -1);
            if (dataType == TYPE_STRING) return new Attr(stringAt(pool, typedData), -1);
            if (dataType == TYPE_INT_DEC) return new Attr(String.valueOf(typedData), typedData);
            return new Attr(null, typedData);
        }
        return null;
    }

    private static String[] readPool(byte[] data, int offset, int headerSize, int chunkSize) {
        int count = u32(data, offset + 8);
        int flags = u32(data, offset + 16);
        int stringsStart = u32(data, offset + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (count <= 0 || count > 100000) return new String[0];
        String[] pool = new String[count];
        int indexBase = offset + headerSize;
        int stringBase = offset + stringsStart;
        for (int i = 0; i < count; i++) {
            int at = indexBase + i * 4;
            if (at + 4 > offset + chunkSize) break;
            int rel = u32(data, at);
            int pos = stringBase + rel;
            if (pos < 0 || pos >= data.length) continue;
            try {
                pool[i] = utf8 ? readUtf8(data, pos) : readUtf16(data, pos);
            } catch (RuntimeException broken) {
                pool[i] = null;
            }
        }
        return pool;
    }

    /** UTF-8 池：1~2 字节字符长度，再 1~2 字节字节长度，再数据。 */
    private static String readUtf8(byte[] data, int pos) {
        int chars = data[pos] & 0xFF;
        pos += (chars & 0x80) != 0 ? 2 : 1;
        int bytes = data[pos] & 0xFF;
        if ((bytes & 0x80) != 0) {
            pos += 1;
            bytes = ((bytes & 0x7F) << 8) | (data[pos] & 0xFF);
        }
        pos += 1;
        if (pos + bytes > data.length) bytes = data.length - pos;
        return new String(data, pos, bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** UTF-16 池：2 字节长度 + UTF-16LE 数据。 */
    private static String readUtf16(byte[] data, int pos) {
        int len = u16(data, pos);
        pos += 2;
        int bytes = len * 2;
        if (pos + bytes > data.length) bytes = (data.length - pos) & ~1;
        return new String(data, pos, bytes, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    private static String stringAt(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) return null;
        return pool[index];
    }

    private static int u16(byte[] data, int at) {
        if (at + 2 > data.length) return 0;
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int at) {
        if (at + 4 > data.length) return 0;
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }
}
