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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
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
    static final String HOLDER_PREFIX = "stop-hangup-plugin-";
    /** setStopWordArmed answers data false when the kiosk has no stop word to listen with. */
    static final Pattern STOP_WORD_OFF = Pattern.compile("\"data\"\\s*:\\s*false");
    static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    static final String STOP_WORD_OFF_STATUS = "Turn on Stop word interruption under Voice Satellite > Wake Word.";
    static final long RETRY_SECONDS = 30;
    /** At most one re-arm per this long when the kiosk's wake word state changes during a call. */
    static final long REARM_MILLIS = 5000;

    /**
     * This instance's name in the kiosk's list of who wants the stop word, so a restarted plugin's
     * arm never races the old instance's disarm.
     */
    private final String holder = HOLDER_PREFIX + Long.toHexString(new SecureRandom().nextLong() & 0xffffffffL);
    private PluginHost host;
    private ScheduledThreadPoolExecutor worker;
    private String password = "";
    private int port = 2324;
    private boolean https;
    private String token = "";
    /** Set when remote admin turned the password down: no more logins until the settings change. */
    private boolean rejected;
    private String state = "idle";
    /** Whether the plugin wants the stop word armed, from the intercom state. */
    private boolean armed;
    /** What the kiosk last confirmed. Anything else gets sent again until it matches. */
    private boolean kioskArmed;
    private boolean retrying;
    private long lastRearm;
    /** The kiosk has no stop word to listen with: Stop word interruption is off. */
    private boolean stopWordOff;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        worker = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "stop-hangup");
            t.setDaemon(true);
            return t;
        });
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        configure(settings);
        host.subscribe("intercom.state");
        host.subscribe("stopword.detected");
        host.subscribe("wakeword.state");
        host.executeCommand("getIntercomState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok && data instanceof Map) onState(String.valueOf(((Map<?, ?>) data).get("state")));
        });
        readWakeWord();
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        Object p = settings.get("adminPassword");
        Object n = settings.get("adminPort");
        String nextPassword = p instanceof String ? (String) p : "";
        int nextPort = n instanceof Number ? ((Number) n).intValue() : 2324;
        boolean nextHttps = Boolean.TRUE.equals(settings.get("adminHttps"));
        // A token outlives a password change, so it is kept for a disarm still owed; the new password is checked below.
        if (nextPort != port || nextHttps != https) token = "";
        boolean changed = !nextPassword.equals(password) || nextPort != port || nextHttps != https;
        if (changed) rejected = false;
        password = nextPassword;
        port = nextPort;
        https = nextHttps;
        if (password.isEmpty()) host.status("Enter this kiosk's remote admin password.", true);
        else if (changed || token.isEmpty()) submit(this::verify); // log in now, so a wrong password shows before a call
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("ks.intercom.state".equals(event)) {
            onState(String.valueOf(payload.get("state")));
        } else if ("ks.stopword.detected".equals(event) && armed && HANGUP_STATES.contains(state)) {
            submit(this::hangup);
        } else if ("ks.wakeword.state".equals(event)) {
            readWakeWord();
            // A dashboard reload turns the stop word off without asking its holders: arm again.
            long now = System.currentTimeMillis();
            if (armed && now - lastRearm >= REARM_MILLIS) {
                lastRearm = now;
                submit(() -> sync(true));
            }
        }
    }

    private synchronized void onState(String next) {
        state = next;
        boolean want = HANGUP_STATES.contains(next);
        if (want == armed) return;
        armed = want;
        submit(() -> sync(false));
    }

    /** Whether Stop word interruption is on, for the status line. */
    private synchronized void readWakeWord() {
        if (host == null) return;
        host.executeCommand("getWakeWordState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (!ok || !(data instanceof Map)) return;
            Object available = ((Map<?, ?>) data).get("stopWordAvailable");
            if (available instanceof Boolean) {
                synchronized (this) {
                    stopWordOff = !(Boolean) available;
                }
            }
            refresh();
        });
    }

    @Override
    public synchronized void stop() {
        if (worker != null) {
            if ((armed || kioskArmed) && (!token.isEmpty() || (!password.isEmpty() && !rejected))) {
                // Disarm on the way out without holding up the stop, queued so it lands after an arm still on its way.
                worker.execute(() -> {
                    try {
                        call("setStopWordArmed", arming(false));
                    } catch (IOException ignored) {
                    }
                });
            }
            worker.shutdown();
        }
        armed = false;
        worker = null;
        host = null;
    }

    Map<String, Object> arming(boolean on) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("armed", on);
        params.put("holder", holder);
        return params;
    }

    private synchronized void submit(Runnable r) {
        if (worker != null) worker.execute(r);
    }

    /** Logs in with the password as it is now, then catches the kiosk up on anything owed. */
    private void verify() {
        try {
            synchronized (this) {
                if (host == null || password.isEmpty()) return;
            }
            login();
            report(null);
            sync(false);
        } catch (IOException e) {
            report(e.getMessage());
        }
    }

    /** Sends the arming the plugin wants when the kiosk hasn't confirmed it (or always, when forced), retrying on failure. */
    private void sync(boolean force) {
        boolean want;
        synchronized (this) {
            retrying = false;
            if (host == null || (!force && armed == kioskArmed)) return;
            want = armed;
        }
        try {
            Response r = call("setStopWordArmed", arming(want));
            if (r.code != 200) throw new IOException("setStopWordArmed answered HTTP " + r.code + ".");
            synchronized (this) {
                kioskArmed = want;
                if (want) stopWordOff = STOP_WORD_OFF.matcher(r.body).find();
            }
            report(null);
            sync(false); // the intercom state may have moved on meanwhile
        } catch (IOException e) {
            report(e.getMessage());
            synchronized (this) {
                if (worker != null && !retrying && armed != kioskArmed) {
                    retrying = true;
                    worker.schedule(() -> sync(false), RETRY_SECONDS, TimeUnit.SECONDS);
                }
            }
        }
    }

    private void hangup() {
        try {
            Response r = call("intercomHangup", Collections.<String, Object>emptyMap());
            // A 400 is "no call": it ended on its own as the stop word was heard.
            if (r.code != 200 && r.code != 400) throw new IOException("intercomHangup answered HTTP " + r.code + ".");
            if (r.code == 200) log("Heard the stop word: hung up the call");
            report(null);
        } catch (IOException e) {
            report(e.getMessage());
        }
    }

    /** Posts a command, logging in first when there is no token and once more when the token is turned down. */
    private Response call(String command, Map<String, Object> params) throws IOException {
        Response r = command(token(), command, params);
        if (r.code == 401) {
            synchronized (this) {
                token = "";
            }
            r = command(token(), command, params);
        }
        return r;
    }

    private String token() throws IOException {
        synchronized (this) {
            if (!token.isEmpty()) return token;
            if (password.isEmpty()) throw new IOException("Enter this kiosk's remote admin password.");
            if (rejected) throw new IOException("The remote admin password was turned down.");
        }
        return login();
    }

    private String login() throws IOException {
        String body;
        synchronized (this) {
            body = "{\"password\":" + quote(password) + "}";
        }
        Response r = post("/api/login", null, body);
        if (r.code == 401 || r.code == 403) {
            // Stop trying: remote admin locks 127.0.0.1 out after five wrong passwords in five minutes.
            synchronized (this) {
                rejected = true;
            }
            throw new IOException("The remote admin password was turned down.");
        }
        if (r.code == 429) throw new IOException("Remote admin is refusing logins after too many wrong passwords. Try again in five minutes.");
        Matcher m = TOKEN.matcher(r.body);
        if (r.code != 200 || !m.find()) throw new IOException("Remote admin login answered HTTP " + r.code + ".");
        synchronized (this) {
            token = m.group(1);
        }
        return m.group(1);
    }

    private Response command(String t, String name, Map<String, Object> params) throws IOException {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(quote(e.getKey())).append(':');
            b.append(e.getValue() instanceof String ? quote((String) e.getValue()) : String.valueOf(e.getValue()));
        }
        return post("/api/commands/" + name, t, b.append('}').toString());
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
        } catch (SSLException e) {
            throw new IOException("Remote admin on port " + p + " doesn't answer HTTPS. Check Remote admin uses HTTPS.");
        } catch (IOException e) {
            throw new IOException("Cannot reach remote admin on port " + p + (secure ? " over HTTPS" : "")
                    + ". Is it on, and does Remote admin uses HTTPS match?");
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

    /** The last error, or null after a success; kept so a stop word warning can show over Ready. */
    private String lastError;

    private synchronized void report(String error) {
        lastError = error;
        refresh();
    }

    private synchronized void refresh() {
        if (host == null) return;
        if (password.isEmpty()) host.status("Enter this kiosk's remote admin password.", true);
        else if (lastError != null) host.status(lastError, true);
        // Still working on a token from before the password changed: say the new one is wrong.
        else if (rejected) host.status("The remote admin password was turned down.", true);
        else if (stopWordOff) host.status(STOP_WORD_OFF_STATUS, true);
        else if (!token.isEmpty()) host.status("Ready. Say \"stop\" during a call to hang up.", false);
    }

    private synchronized void log(String message) {
        if (host != null) host.log(message);
    }
}
