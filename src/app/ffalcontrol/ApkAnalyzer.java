package app.ffalcontrol;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.ResolveInfo;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 把一个候选 APK 读成「判定需要的事实」，并算出它会被哪几条厂商策略拦。
 *
 * 拆两层：
 *   AxmlReader   —— 二进制清单，拿 MAIN+HOME 与 com.tcl.app.type（PackageManager 给不了）
 *   ApkAnalyzer  —— 签名、版本、label、与已装版本的关系，然后交给 SecurityGate 判定
 */
final class ApkAnalyzer {

    private ApkAnalyzer() {}

    static SecurityGate.Facts facts(Context context, File apk) throws Exception {
        SecurityGate.Facts facts = new SecurityGate.Facts();
        AxmlReader.Manifest manifest = AxmlReader.read(apk);
        facts.packageName = manifest.packageName == null ? "" : manifest.packageName;
        facts.declaresHome = manifest.declaresHome;
        facts.tclUiMeta = "com.tcl.ui".equals(manifest.tclAppType);

        Signature[] apkSignatures = signaturesOfArchive(context, apk);
        Signature[] homeSignatures = defaultHomeSignatures(context);
        facts.tclSigned = apkSignatures != null && homeSignatures != null
                && sameSignatures(apkSignatures, homeSignatures);
        return facts;
    }

    /** 完整分析，产出给界面和 API 用的 JSON。 */
    static JSONObject analyze(Context context, File apk, SecurityGate.Policy policy,
                              String stagedId, String originalName) throws Exception {
        JSONObject out = new JSONObject();
        out.put("staged_id", stagedId == null ? JSONObject.NULL : stagedId);
        out.put("file_name", originalName == null ? apk.getName() : originalName);
        out.put("bytes", apk.length());
        out.put("sha256", sha256(apk));

        AxmlReader.Manifest manifest = AxmlReader.read(apk);
        String pkg = manifest.packageName == null ? "" : manifest.packageName;
        out.put("package", pkg);
        out.put("version_name", manifest.versionName == null ? JSONObject.NULL : manifest.versionName);
        out.put("version_code", manifest.versionCode);
        out.put("declares_home", manifest.declaresHome);
        out.put("launcher_activities", manifest.launcherActivities);
        out.put("tcl_app_type", manifest.tclAppType == null ? JSONObject.NULL : manifest.tclAppType);

        PackageManager pm = context.getPackageManager();
        Signature[] apkSignatures = signaturesOfArchive(context, apk);
        Signature[] homeSignatures = defaultHomeSignatures(context);
        boolean tclSigned = apkSignatures != null && homeSignatures != null
                && sameSignatures(apkSignatures, homeSignatures);
        out.put("tcl_signed", tclSigned);
        out.put("signature_sha256", apkSignatures == null ? JSONObject.NULL : signatureDigest(apkSignatures));

        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA;
        if (android.os.Build.VERSION.SDK_INT < 33) flags |= PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive != null) {
            if (archive.applicationInfo != null) {
                archive.applicationInfo.sourceDir = apk.getAbsolutePath();
                archive.applicationInfo.publicSourceDir = apk.getAbsolutePath();
                try { out.put("label", pm.getApplicationLabel(archive.applicationInfo).toString()); }
                catch (Throwable ignored) { out.put("label", JSONObject.NULL); }
            }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                out.put("min_sdk", archive.applicationInfo == null ? JSONObject.NULL : archive.applicationInfo.minSdkVersion);
                out.put("target_sdk", archive.applicationInfo == null ? JSONObject.NULL : archive.applicationInfo.targetSdkVersion);
            }
        }

        // 已装版本：判断要不要先卸载（签名不同会 CONFLICT）
        JSONObject installed = new JSONObject();
        try {
            PackageInfo present = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            installed.put("present", true);
            installed.put("version_code", android.os.Build.VERSION.SDK_INT >= 28
                    ? present.getLongVersionCode() : present.versionCode);
            installed.put("version_name", present.versionName);
            installed.put("same_signature", sameSignatures(apkSignatures, present.signatures));
            if (android.os.Build.VERSION.SDK_INT >= 28
                    && present.getLongVersionCode() > manifest.versionCode) {
                installed.put("downgrade", true);
            }
        } catch (PackageManager.NameNotFoundException absent) {
            installed.put("present", false);
        }
        out.put("installed", installed);

        // 判定
        SecurityGate.Facts facts = new SecurityGate.Facts();
        facts.packageName = pkg;
        facts.declaresHome = manifest.declaresHome;
        facts.tclSigned = tclSigned;
        facts.tclUiMeta = "com.tcl.ui".equals(manifest.tclAppType);

        java.util.List<SecurityGate.Hit> hits = SecurityGate.hits(facts, policy);
        java.util.List<SecurityGate.Patch> patches = SecurityGate.plan(hits, pkg);

        out.put("blocked_by", new JSONArray(SecurityGate.names(hits)));
        out.put("hits", renderHits(hits));
        out.put("patches", renderPatches(patches));
        out.put("if_manual_install", renderHits(SecurityGate.ifManualInstall(facts, policy)));
        out.put("if_adb_install", renderHits(SecurityGate.ifAdbInstall(facts, policy)));
        out.put("policy_loaded", policy.loaded);
        out.put("verdict", hits.isEmpty() ? "pass"
                : patches.isEmpty() ? "blocked_but_no_patch" : "needs_patch");

        if (SecurityGate.validPackageName(pkg)) out.put("package", pkg);
        return out;
    }

    /** 只读：当前默认桌面是谁。 */
    static String defaultHome(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved == null || resolved.activityInfo == null ? null : resolved.activityInfo.packageName;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static JSONArray renderHits(java.util.List<SecurityGate.Hit> hits) throws Exception {
        JSONArray rows = new JSONArray();
        for (SecurityGate.Hit hit : hits) {
            JSONObject row = new JSONObject();
            row.put("strategy", hit.strategy);
            row.put("reason", hit.reason);
            row.put("fix", hit.fix);
            rows.put(row);
        }
        return rows;
    }

    private static JSONArray renderPatches(java.util.List<SecurityGate.Patch> patches) throws Exception {
        JSONArray rows = new JSONArray();
        for (SecurityGate.Patch patch : patches) {
            JSONObject row = new JSONObject();
            row.put("strategy", patch.strategy);
            row.put("package", patch.packageName);
            row.put("action", patch.action());
            row.put("add", patch.add);
            row.put("describe", patch.describe());
            rows.put(row);
        }
        return rows;
    }

    private static Signature[] signaturesOfArchive(Context context, File apk) {
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            return info == null ? null : info.signatures;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Signature[] defaultHomeSignatures(Context context) {
        PackageManager pm = context.getPackageManager();
        String home = defaultHome(context);
        String[] candidates = home == null
                ? new String[]{"com.tcl.cyberui", "com.android.providers.settings"}
                : new String[]{home, "com.tcl.cyberui", "com.android.providers.settings"};
        for (String candidate : candidates) {
            if (candidate == null) continue;
            try {
                PackageInfo info = pm.getPackageInfo(candidate, PackageManager.GET_SIGNATURES);
                if (info != null && info.signatures != null) return info.signatures;
            } catch (Throwable missing) {
                // 试下一个
            }
        }
        return null;
    }

    static boolean sameSignatures(Signature[] x, Signature[] y) {
        if (x == null || y == null || x.length != y.length || x.length == 0) return false;
        for (int i = 0; i < x.length; i++) {
            boolean found = false;
            for (int j = 0; j < y.length; j++) {
                if (x[i].equals(y[j])) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String signatureDigest(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(signatures[0].toByteArray());
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 16; i++) builder.append(String.format("%02x", bytes[i] & 0xff));
            return builder.toString();
        } catch (Throwable failure) {
            return null;
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = in.read(chunk)) > 0) digest.update(chunk, 0, count);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xf, 16));
            builder.append(Character.forDigit(value & 0xf, 16));
        }
        return builder.toString();
    }
}
