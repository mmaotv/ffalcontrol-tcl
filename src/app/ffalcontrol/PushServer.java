package app.ffalcontrol;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 局域网收包口（默认 0.0.0.0:18743）。
 *
 * 刻意的能力限制：这里**只能收文件，不能触发安装**。
 *   - 不需要令牌（手机浏览器没法带 Bearer 头）
 *   - 所以它不能改任何配置、不能装任何东西
 *   - 收到包只是躺在电视里等用户在电视上点「安装」
 * 最坏情况也就是往电视里塞一个待处理文件，装不装仍由电视前的人决定。
 *
 * 完整的写能力仍然只在 127.0.0.1:18742 上，需要构建期令牌。
 */
final class PushServer {

    static final int PORT = 18743;
    private static final long MAX_UPLOAD = PushInstall.MAX_APK;

    private final Context context;
    private final PushInstall push;
    private volatile boolean running;
    private volatile ServerSocket server;

    PushServer(Context context, PushInstall push) {
        this.context = context.getApplicationContext();
        this.push = push;
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "TVControl-push").start();
    }

    void stop() {
        running = false;
        ServerSocket socket = server;
        if (socket != null) try { socket.close(); } catch (Throwable ignored) { }
        server = null;
    }

    boolean running() { return running; }

    /** 电视在局域网里的 IPv4，拿不到返回 null。 */
    static String lanAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            String fallback = null;
            for (NetworkInterface nic : interfaces) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                String name = nic.getName() == null ? "" : nic.getName();
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return host;
                    if (fallback == null) fallback = host;
                }
            }
            return fallback;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** 给界面显示的一句话。 */
    static String hint() {
        String host = lanAddress();
        if (host == null) return "未连上局域网。手机可直接扫码页面不存在，请检查网络。";
        return "手机浏览器打开 http://" + host + ":" + PORT + " 上传 APK";
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket(PORT, 4, InetAddress.getByName("0.0.0.0"))) {
            server = listener;
            while (running) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(300000);
                    handle(socket);
                } catch (Throwable failure) {
                    if (running) android.util.Log.w("TVControl", "push request failed: " + failure);
                }
            }
        } catch (Throwable failure) {
            android.util.Log.w("TVControl", "push server 未启动：" + failure);
            running = false;
        } finally {
            server = null;
        }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        String requestLine = line(in);
        if (requestLine == null) return;
        String[] first = requestLine.split(" ");
        if (first.length != 3) { respond(socket, 400, "text/plain", "bad request"); return; }

        String method = first[0];
        String path = first[1];
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

        String contentType = null;
        long length = -1;
        for (;;) {
            String header = line(in);
            if (header == null) return;
            if (header.length() == 0) break;
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try { length = Long.parseLong(value); } catch (NumberFormatException ignored) { }
            } else if (name.equals("content-type")) {
                contentType = value;
            }
        }

        if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
            respond(socket, 200, "text/html; charset=utf-8", uploadPage());
            return;
        }
        if ("GET".equals(method) && path.equals("/jobs")) {
            JSONObject out = new JSONObject();
            out.put("jobs", push.jobs(12));
            respond(socket, 200, "application/json; charset=utf-8", out.toString());
            return;
        }
        if ("POST".equals(method) && path.equals("/upload")) {
            if (length <= 0) { respond(socket, 411, "application/json", error("missing_content_length")); return; }
            if (length > MAX_UPLOAD) {
                respond(socket, 413, "application/json", error("file_too_large"));
                return;
            }
            String name = firstParam(query, "name");
            try {
                JSONObject job = push.stage(in, name, length, "lan");
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("job_id", job.optString("id"));
                out.put("file_name", job.optString("file_name"));
                out.put("bytes", job.optLong("bytes"));
                out.put("sha256", job.optString("sha256"));
                out.put("next", "已收到。请在电视上打开「推送安装」列表，点这一项安装。");
                respond(socket, 200, "application/json; charset=utf-8", out.toString());
            } catch (GuardControl.ApiFailure failure) {
                respond(socket, failure.status, "application/json; charset=utf-8", failure.result.toString());
            } catch (Throwable failure) {
                respond(socket, 500, "application/json; charset=utf-8", error("upload_failed: " + failure));
            }
            return;
        }
        respond(socket, 404, "text/plain; charset=utf-8", "not found");
    }

    private static String firstParam(String query, String key) {
        if (query == null || query.length() == 0) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(pair.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Throwable failure) {
                return null;
            }
        }
        return null;
    }

    private void respond(Socket socket, int status, String contentType, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    private static String error(String text) {
        return "{\"ok\":false,\"error\":\"" + text.replace("\"", "'") + "\"}";
    }

    private static String line(InputStream in) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            int c = in.read();
            if (c < 0) return text.length() == 0 ? null : text.toString();
            if (c == '\n') return text.toString();
            if (c != '\r') text.append((char) c);
        }
        return text.toString();
    }

    /** 手机端用的上传页：深色、单页、无依赖。 */
    private String uploadPage() {
        String host = lanAddress();
        String current = host == null ? "" : host + ":" + PORT;
        JSONArray recent;
        String rows;
        try {
            recent = push.jobs(8);
            StringBuilder builder = new StringBuilder();
            if (recent.length() == 0) {
                builder.append("<div class=muted>还没有收到任何安装包</div>");
            } else {
                for (int i = 0; i < recent.length(); i++) {
                    JSONObject job = recent.getJSONObject(i);
                    builder.append("<div class=row><div><div class=name>")
                            .append(escape(job.optString("file_name", "?")))
                            .append("</div><div class=sub>")
                            .append(String.format(Locale.ROOT, "%.1f MB", job.optLong("bytes") / 1048576.0))
                            .append(" · ").append(escape(job.optString("state", "")))
                            .append("</div></div><div class=tag>")
                            .append(escape(job.optString("package", "")))
                            .append("</div></div>");
                }
            }
            rows = builder.toString();
        } catch (Throwable failure) {
            rows = "<div class=muted>列表读取失败</div>";
        }
        return PAGE_HEAD
                + "<div class=wrap>"
                + "<div class=chip>" + escape(current) + "</div>"
                + "<h1>把安装包发到电视</h1>"
                + "<p class=muted>选一个 APK，发过去。电视上会出现在「推送安装」列表里，点它才会安装。</p>"
                + "<label id=pick class=pick>"
                + "<input id=file type=file accept=\".apk,application/vnd.android.package-archive\">"
                + "<span id=pickText>选择 APK 文件</span>"
                + "</label>"
                + "<button id=go disabled>发送到电视</button>"
                + "<div id=status class=status></div>"
                + "<h2>电视上最近的包</h2>"
                + "<div class=list>" + rows + "</div>"
                + "<p class=hint>本页只能上传文件，不能安装、不能改任何设置。安装动作必须在电视上确认。</p>"
                + "</div>"
                + PAGE_SCRIPT
                + "</body></html>";
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String PAGE_HEAD =
            "<!DOCTYPE html><html lang=zh-CN><head><meta charset=utf-8>"
                    + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                    + "<title>发到电视</title><style>"
                    + "*{box-sizing:border-box}"
                    + "body{margin:0;background:#000;color:#fff;font:16px/1.6 -apple-system,system-ui,sans-serif}"
                    + ".wrap{max-width:560px;margin:0 auto;padding:28px 20px 60px}"
                    + ".chip{display:inline-block;padding:6px 14px;border-radius:20px;background:#2e787880;"
                    + "border:1px solid #1affffff;font-size:13px;color:#99ebebf5;margin-bottom:18px}"
                    + "h1{font-size:26px;font-weight:600;margin:0 0 10px;letter-spacing:-.02em}"
                    + "h2{font-size:15px;font-weight:500;margin:34px 0 12px;color:#99ebebf5}"
                    + ".muted{color:#99ebebf5;font-size:14px;margin:0 0 24px}"
                    + ".pick{display:block;padding:22px;border-radius:18px;border:1px dashed #52787880;"
                    + "background:#e6202022;text-align:center;color:#99ebebf5;cursor:pointer}"
                    + ".pick input{display:none}"
                    + "button{width:100%;margin-top:14px;padding:16px;border:0;border-radius:28px;"
                    + "background:#f5f5f7;color:#1d1d1f;font-size:17px;font-weight:600;cursor:pointer}"
                    + "button:disabled{background:#2e787880;color:#4debebf5;cursor:default}"
                    + ".status{margin-top:16px;font-size:14px;color:#ff9f0a;min-height:22px;word-break:break-all}"
                    + ".list{display:flex;flex-direction:column;gap:8px}"
                    + ".row{display:flex;justify-content:space-between;align-items:center;gap:12px;"
                    + "padding:14px 16px;border-radius:14px;background:#e6202022;border:1px solid #1affffff}"
                    + ".name{font-size:15px;word-break:break-all}"
                    + ".sub{font-size:12px;color:#4debebf5;margin-top:2px}"
                    + ".tag{font-size:11px;color:#30d158;text-align:right;word-break:break-all;max-width:45%}"
                    + ".hint{margin-top:38px;font-size:12px;color:#4debebf5;line-height:1.7}"
                    + "</style></head><body>";

    private static final String PAGE_SCRIPT =
            "<script>"
                    + "var f=document.getElementById('file'),go=document.getElementById('go'),"
                    + "st=document.getElementById('status'),pt=document.getElementById('pickText');"
                    + "f.onchange=function(){if(f.files.length){pt.textContent=f.files[0].name;"
                    + "go.disabled=false;st.textContent='';}};"
                    + "go.onclick=function(){if(!f.files.length)return;var file=f.files[0];"
                    + "go.disabled=true;st.style.color='#ff9f0a';st.textContent='发送中 …';"
                    + "var x=new XMLHttpRequest();"
                    + "x.open('POST','/upload?name='+encodeURIComponent(file.name));"
                    + "x.setRequestHeader('Content-Type','application/vnd.android.package-archive');"
                    + "x.upload.onprogress=function(e){if(e.lengthComputable){"
                    + "st.textContent='发送中 '+Math.round(e.loaded/e.total*100)+'%';}};"
                    + "x.onload=function(){go.disabled=false;try{var r=JSON.parse(x.responseText);"
                    + "if(r.ok){st.style.color='#30d158';st.textContent='已送达。请在电视上点「推送安装」里的这一项。';"
                    + "setTimeout(function(){location.reload();},1200);}"
                    + "else{st.style.color='#ff453a';st.textContent='失败：'+(r.error||x.status);}}"
                    + "catch(e){st.style.color='#ff453a';st.textContent='失败：'+x.status;}};"
                    + "x.onerror=function(){go.disabled=false;st.style.color='#ff453a';"
                    + "st.textContent='网络断了，确认手机和电视在同一个 WiFi。';};"
                    + "x.send(file);};"
                    + "</script>";
}
