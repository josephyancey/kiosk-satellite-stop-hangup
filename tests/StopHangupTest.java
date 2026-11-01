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
            state.put("enabled", true);
            state.put("state", "idle");
            callback.onResult(true, state, null);
        }
        public void status(String message, boolean error) { status = message; this.error = error; }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}
    }

    static final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admin.createContext("/", ex -> {
            String body = read(ex.getRequestBody());
            String path = ex.getRequestURI().getPath();
            int code = 200;
            String out = "{}";
            if (path.equals("/api/login")) {
                if (body.contains("\"password\":\"right\"")) out = "{\"token\":\"tok\"}"; else code = 401;
            } else if (!"Bearer tok".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                code = 401;
            }
            calls.add(path + " " + body + " " + code);
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        admin.start();
        int port = admin.getAddress().getPort();

        Host host = new Host();
        StopHangupPlugin plugin = new StopHangupPlugin();
        plugin.start(host, settings("wrong", port));
        waitFor(() -> host.error && host.status.contains("password"), "a wrong password is reported");
        assert host.subscribed.contains("intercom.state") && host.subscribed.contains("stopword.detected");

        plugin.configure(settings("right", port));
        waitFor(() -> !host.error && host.status.startsWith("Ready"), "logs in");

        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        plugin.onEvent("ks.intercom.state", state("ringing"));
        Thread.sleep(300);
        assert !has("intercomHangup") && !has("setStopWordArmed") : "nothing before a call: " + calls;

        plugin.onEvent("ks.intercom.state", state("in_call"));
        waitFor(() -> has("/api/commands/setStopWordArmed {\"armed\":true,\"holder\":\"stop-hangup-plugin\"} 200"), "arms in a call");

        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        waitFor(() -> has("/api/commands/intercomHangup {} 200"), "hangs up on stop");

        plugin.onEvent("ks.intercom.state", state("ended"));
        waitFor(() -> has("/api/commands/setStopWordArmed {\"armed\":false,\"holder\":\"stop-hangup-plugin\"} 200"), "disarms after");

        int hangups = count("intercomHangup");
        plugin.onEvent("ks.stopword.detected", Collections.<String, Object>emptyMap());
        Thread.sleep(300);
        assert count("intercomHangup") == hangups : "no hangup outside a call";

        plugin.stop();
        admin.stop(0);
        System.out.println("StopHangupTest passed");
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

    static boolean has(String s) {
        synchronized (calls) {
            for (String c : calls) if (c.contains(s)) return true;
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
