import com.sun.net.httpserver.HttpServer;
import dev.yancey.stophangup.StopHangupPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against a stand-in for the kiosk's remote admin API. */
public final class StopHangupTest {
    static final class Host implements PluginHost {
        final List<String> subscribed = new ArrayList<>();
        volatile String status = "";
        volatile boolean error;
        public void subscribe(String event) { subscribed.add(event); }
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            Map<String, Object> state = new HashMap<>();
            if (command.equals("getWakeWordState")) {
                state.put("stopWordAvailable", stopWord);
            } else {
                state.put("enabled", true);
                state.put("state", "idle");
            }
            callback.onResult(true, state, null);
        }
        public void status(String message, boolean error) { status = message; this.error = error; }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}
    }

    static final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    /** Whether the stand-in kiosk has a stop word to listen with, as Stop word interruption does. */
    static volatile boolean stopWord = true;
    /** Whether the stand-in kiosk has a call to hang up. */
    static volatile boolean inCall = true;
    /** The token the stand-in accepts; changing it expires the plugin's. */
    static volatile String valid = "tok1";
    static volatile boolean throttled;
    static volatile boolean down;

    public static void main(String[] args) throws Exception {
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admin.createContext("/", ex -> {
            String body = read(ex.getRequestBody());
            String path = ex.getRequestURI().getPath();
            int code = 200;
            String out = "{\"ok\":true}";
            if (down) {
                code = 503;
            } else if (path.equals("/api/login")) {
                if (throttled) code = 429;
                else if (body.equals("{\"password\":\"right\"}")) out = "{\"token\":\"" + valid + "\"}";
                else code = 401;
            } else if (!("Bearer " + valid).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                code = 401;
            } else if (path.endsWith("/setStopWordArmed")) {
                out = "{\"ok\":true,\"data\":" + stopWord + "}";
            } else if (path.endsWith("/intercomHangup") && !inCall) {
                code = 400;
                out = "{\"ok\":false,\"error\":\"no call\"}";
            }
            calls.add(path + " " + body + " " + code);
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        admin.start();
        try {
            run(admin.getAddress().getPort());
        } finally {
            admin.stop(0); // a failed check must not leave the server holding the JVM open
        }
        System.out.println("StopHangupTest passed");
    }

    static void run(int port) throws Exception {
        String arm = "/api/commands/setStopWordArmed {\"armed\":true,\"holder\":\"stop-hangup-plugin-";
        String disarm = "/api/commands/setStopWordArmed {\"armed\":false,\"holder\":\"stop-hangup-plugin-";

        // No password: nothing is sent, not even when stopped mid-call.
        Host bare = new Host();
        StopHangupPlugin unset = new StopHangupPlugin();
        unset.start(bare, settings("", port));
        assert bare.error && bare.status.contains("Enter") : bare.status;
        unset.onEvent("ks.intercom.state", state("in_call"));
        unset.stop();
        Thread.sleep(300);
        assert calls.isEmpty() : "nothing sent without a password: " + calls;

        Host host = new Host();
        StopHangupPlugin plugin = new StopHangupPlugin();
        plugin.start(host, settings("wrong", port));
        waitFor(() -> host.error && host.status.contains("password"), "a wrong password is reported");
        assert host.subscribed.contains("intercom.state") && host.subscribed.contains("stopword.detected");

        // A turned-down password is not tried again on each call, which would lock out 127.0.0.1.
        plugin.onEvent("ks.intercom.state", state("in_call"));
        plugin.onEvent("ks.intercom.state", state("ended"));
        Thread.sleep(300);
        assert count("/api/login") == 1 : "one login with a wrong password: " + calls;

        throttled = true;
        plugin.configure(settings("right", port));
        waitFor(() -> host.error && host.status.contains("five minutes"), "reports a throttled login");
        throttled = false;
        plugin.configure(settings("wrong", port));
        plugin.configure(settings("right", port));
        waitFor(() -> !host.error && host.status.startsWith("Ready"), "logs in");

        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        plugin.onEvent("ks.intercom.state", state("ringing"));
        Thread.sleep(300);
        assert !has("intercomHangup") && !has("setStopWordArmed") : "nothing before a call: " + calls;

        plugin.onEvent("ks.intercom.state", state("in_call"));
        waitFor(() -> has(arm, " 200"), "arms in a call");

        // An expired token gets a fresh login.
        valid = "tok2";
        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        waitFor(() -> has("/api/commands/intercomHangup {} 200"), "hangs up on stop after logging in again");

        // A dashboard reload turns the stop word off behind the plugin: it arms again.
        int arms = count(arm);
        plugin.onEvent("ks.wakeword.state", Collections.<String, Object>emptyMap());
        waitFor(() -> count(arm) == arms + 1, "arms again after the wake word restarts");

        // A disarm that fails is sent again.
        down = true;
        plugin.onEvent("ks.intercom.state", state("ended"));
        waitFor(() -> host.error, "reports a failed disarm");
        down = false;
        assert !has(disarm, " 200") : "not disarmed yet";
        // The retry waits 30 s; the next intercom state change catches up instead.
        plugin.onEvent("ks.intercom.state", state("idle"));
        plugin.onEvent("ks.intercom.state", state("calling"));
        plugin.onEvent("ks.intercom.state", state("ended"));
        waitFor(() -> has(disarm, " 200") && !host.error, "disarms once remote admin is back");

        int hangups = count("intercomHangup");
        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        Thread.sleep(300);
        assert count("intercomHangup") == hangups : "no hangup outside a call";

        // A call that ends on its own just as "stop" is heard is not an error.
        plugin.onEvent("ks.intercom.state", state("calling"));
        inCall = false;
        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        waitFor(() -> has("/api/commands/intercomHangup {} 400"), "hangs up after the call ended");
        Thread.sleep(100);
        assert !host.error : "no error for a call that already ended: " + host.status;
        int ended = count(disarm);
        plugin.onEvent("ks.intercom.state", state("ended"));
        waitFor(() -> count(disarm) == ended + 1, "disarms after the call that ended");
        inCall = true;

        // Without Stop word interruption the kiosk cannot listen: say so instead of Ready, and keep saying it after the call.
        stopWord = false;
        plugin.onEvent("ks.intercom.state", state("in_call"));
        waitFor(() -> host.error && host.status.contains("Stop word interruption"), "reports the stop word is off");
        int disarms = count(disarm);
        plugin.onEvent("ks.intercom.state", state("ended"));
        waitFor(() -> count(disarm) == disarms + 1, "disarms after");
        Thread.sleep(100);
        assert host.status.contains("Stop word interruption") : "still warns after the call: " + host.status;
        stopWord = true;
        plugin.onEvent("ks.wakeword.state", Collections.<String, Object>emptyMap());
        waitFor(() -> host.status.startsWith("Ready"), "Ready once the stop word is back");

        // Stopping mid-call disarms on the way out.
        int armed = count(arm);
        plugin.onEvent("ks.intercom.state", state("in_call"));
        waitFor(() -> count(arm) == armed + 1, "arms");
        int before = count(disarm);
        plugin.stop();
        waitFor(() -> count(disarm) == before + 1, "disarms when stopped");
    }

    static Map<String, Object> settings(String password, int port) {
        Map<String, Object> s = new HashMap<>();
        s.put("adminPassword", password);
        s.put("adminPort", (double) port);
        s.put("adminHttps", false);
        return s;
    }

    static Map<String, Object> state(String s) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", s);
        m.put("kiosk", "Kitchen Portal");
        m.put("dnd", false);
        return m;
    }

    /** Whether one call contains every part. */
    static boolean has(String... parts) {
        synchronized (calls) {
            outer:
            for (String c : calls) {
                for (String p : parts) if (!c.contains(p)) continue outer;
                return true;
            }
        }
        return false;
    }

    static int count(String s) {
        int n = 0;
        synchronized (calls) {
            for (String c : calls) if (c.contains(s)) n++;
        }
        return n;
    }

    interface Check { boolean ok(); }

    static void waitFor(Check c, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (c.ok()) return;
            Thread.sleep(50);
        }
        throw new AssertionError(what + "; calls: " + calls);
    }

    static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
