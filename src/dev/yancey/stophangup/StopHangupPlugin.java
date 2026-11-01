package dev.yancey.stophangup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Says "stop" during an intercom call to hang it up. While a call is being placed, is live, or an
 * announcement is going either way, it asks this kiosk to listen for the stop word; when the stop
 * word is heard then, it hangs the call up. Both requests go to this kiosk's own remote admin API,
 * since the plugin API has neither.
 */
public final class StopHangupPlugin implements KioskPlugin {
    /** The intercom states a call can be hung up in, the same ones the hang-up button ends. */
    static final Set<String> HANGUP_STATES = new HashSet<>(Arrays.asList("calling", "in_call", "broadcasting", "listening"));
    static final String HOLDER = "stop-hangup-plugin";

    private PluginHost host;
    private ExecutorService worker;
    private String password = "";
    private int port = 2324;
    private boolean https;
    private String token = "";
    private String state = "idle";
    private boolean armed;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stop-hangup");
            t.setDaemon(true);
            return t;
        });
        configure(settings);
        host.subscribe("intercom.state");
        host.subscribe("stopword.detected");
        host.executeCommand("getIntercomState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok && data instanceof Map) onState(String.valueOf(((Map<?, ?>) data).get("state")));
        });
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        String nextPassword = String.valueOf(settings.get("adminPassword"));
        int nextPort = ((Number) settings.get("adminPort")).intValue();
        boolean nextHttps = Boolean.TRUE.equals(settings.get("adminHttps"));
        if (!nextPassword.equals(password) || nextPort != port || nextHttps != https) token = "";
        password = nextPassword;
        port = nextPort;
        https = nextHttps;
        if (password.isEmpty()) host.status("Enter this kiosk's remote admin password.", true);
        else if (token.isEmpty()) submit(() -> run(null)); // log in now, so a wrong password shows before a call
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("ks.intercom.state".equals(event)) {
            onState(String.valueOf(payload.get("state")));
        } else if ("ks.stopword.detected".equals(event) && armed && HANGUP_STATES.contains(state)) {
            submit(() -> run("intercomHangup"));
        }
    }

    private synchronized void onState(String next) {
        state = next;
        boolean want = HANGUP_STATES.contains(next);
        if (want == armed) return;
        armed = want;
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("armed", want);
        params.put("holder", HOLDER);
        submit(() -> run("setStopWordArmed", params));
    }

    @Override
    public synchronized void stop() {
        if (armed && !token.isEmpty()) {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("armed", false);
            params.put("holder", HOLDER);
            String t = token;
            // Disarm on the way out, without holding up the stop.
            Thread off = new Thread(() -> { try { command(t, "setStopWordArmed", params); } catch (IOException ignored) {} });
            off.setDaemon(true);
            off.start();
        }
        armed = false;
        if (worker != null) worker.shutdownNow();
        worker = null;
        host = null;
    }

    private synchronized void submit(Runnable r) {
        if (worker != null) worker.execute(r);
    }

    private void run(String command) {
        run(command, Collections.<String, Object>emptyMap());
    }

    /** Runs a command on this kiosk, logging in first when there is no token yet. */
    private void run(String command, Map<String, Object> params) {
        String t;
        synchronized (this) {
            if (host == null || password.isEmpty()) return;
            t = token;
        }
        try {
            if (t.isEmpty()) t = login();
            if (command == null) {
                ok();
                return;
            }
            int code = command(t, command, params);
            if (code == 401) { // the token was turned down: log in once more
                t = login();
                code = command(t, command, params);
            }
            if (code != 200) throw new IOException(command + " answered HTTP " + code);
            if ("intercomHangup".equals(command)) log("Heard the stop word: hung up the call");
            ok();
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    private String login() throws IOException {
        String body;
        synchronized (this) {
            body = "{\"password\":" + quote(password) + ",\"ttl_days\":3650}";
        }
        Response r = post("/api/login", null, body);
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body);
        if (r.code != 200 || !m.find()) {
            throw new IOException(r.code == 401 || r.code == 403 ? "The remote admin password was turned down." : "Remote admin login answered HTTP " + r.code + ".");
        }
        synchronized (this) {
            token = m.group(1);
        }
        return m.group(1);
    }

    private int command(String t, String name, Map<String, Object> params) throws IOException {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(quote(e.getKey())).append(':');
            b.append(e.getValue() instanceof String ? quote((String) e.getValue()) : String.valueOf(e.getValue()));
        }
        return post("/api/commands/" + name, t, b.append('}').toString()).code;
    }

    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    private Response post(String path, String bearer, String json) throws IOException {
        boolean secure;
        int p;
        synchronized (this) {
            secure = https;
            p = port;
        }
        HttpURLConnection c = (HttpURLConnection) new URL((secure ? "https" : "http") + "://127.0.0.1:" + p + path).openConnection();
        try {
            if (c instanceof HttpsURLConnection) trustThisKiosk((HttpsURLConnection) c);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0 && b.size() < 65536; ) b.write(buf, 0, n);
                in.close();
            }
            return new Response(code, new String(b.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Cannot reach remote admin on port " + p + (secure ? " (HTTPS)" : "") + ". Is it on?");
        } finally {
            c.disconnect();
        }
    }

    /** The kiosk's own certificate is self-signed, and this only ever talks to 127.0.0.1. */
    private static void trustThisKiosk(HttpsURLConnection c) throws IOException {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) {}
                public void checkServerTrusted(X509Certificate[] chain, String auth) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            c.setSSLSocketFactory(ssl.getSocketFactory());
            c.setHostnameVerifier((name, session) -> "127.0.0.1".equals(name));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e.getMessage());
        }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    private synchronized void ok() {
        if (host != null) host.status("Ready. Say \"stop\" during a call to hang up.", false);
    }

    private synchronized void fail(String why) {
        if (host != null) host.status(why, true);
    }

    private synchronized void log(String message) {
        if (host != null) host.log(message);
    }
}
