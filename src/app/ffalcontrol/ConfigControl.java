package app.ffalcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Reads or writes one named ConfigProvider row with an expected-SHA compare-and-set and a full
 * readback. Restricted to the rows this tool understands; no insert/delete.
 */
final class ConfigControl {

    private static final List<String> ALLOWED = Arrays.asList(
            "AppBootPolicy", "bootStagedStartupConfig", "InstallConfig", "Guard_disable_App");

    private final Context context;

    ConfigControl(Context context) { this.context = context.getApplicationContext(); }

    synchronized JSONObject apply(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        String id = req.getString("project_id");
        if (!ALLOWED.contains(id)) throw fail(400, "project_id_not_allowed");
        String expected = req.optString("expected_sha256", "");
        String content = req.optString("config_content", "");
        boolean write = req.optBoolean("write", false);

        Uri uri = Uri.parse("content://com.tcl.providers.config/" + id);
        String current = null;
        String sha = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{"project_id", "config_content"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!id.equals(cursor.getString(0))) throw fail(409, "unexpected_row_key");
                current = cursor.getString(1);
                sha = current == null ? null : AppBootPolicy.sha(current);
            }
        }

        JSONObject out = new JSONObject();
        out.put("project_id", id);
        out.put("current_sha256", sha);
        out.put("current_content", current);
        if (!write) { out.put("dry_run", true); return out; }
        if (sha == null || !sha.equals(expected)) throw fail(409, "expected_sha256_mismatch");

        ContentValues values = new ContentValues();
        values.put("config_content", content);
        int count = context.getContentResolver().update(uri, values, null, null);
        out.put("update_count", count);

        String after = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{"config_content"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) after = cursor.getString(0);
        }
        out.put("observed_sha256", after == null ? null : AppBootPolicy.sha(after));
        out.put("written", content.equals(after));
        out.put("persistence_verified", content.equals(after));
        return out;
    }

    private static GuardControl.ApiFailure fail(int status, String message) throws Exception {
        return new GuardControl.ApiFailure(status, message);
    }
}
