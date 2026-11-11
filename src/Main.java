import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Система «Лікарня»: пацієнти, діагнози, призначення, виписка. Дані зберігаються в пам'яті. */
public class Main {

    static class Patient {
        int id;
        String name;
        String diagnosis;
        String finalDiagnosis;
        boolean discharged;
    }

    static class Prescription {
        int id;
        int patientId;
        String type;
        String description;
        boolean done;
        String doneBy;
    }

    static final Map<Integer, Patient> patients = new ConcurrentHashMap<>();
    static final Map<Integer, Prescription> prescriptions = new ConcurrentHashMap<>();
    static final AtomicInteger patientIds = new AtomicInteger();
    static final AtomicInteger prescriptionIds = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Main::handle);
        server.start();
        System.out.println("Hospital service started on 8080");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Exception e) {
            send(ex, 500, error("internal error"));
        }
    }

    static void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String role = ex.getRequestHeaders().getFirst("X-Role");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m;

        if (path.equals("/patients")) {
            if (method.equals("POST")) {
                String name = field(body, "name");
                if (name == null || name.isBlank()) {
                    send(ex, 400, error("name is required"));
                    return;
                }
                Patient p = new Patient();
                p.id = patientIds.incrementAndGet();
                p.name = name;
                patients.put(p.id, p);
                send(ex, 201, json(p));
                return;
            }
            if (method.equals("GET")) {
                List<String> items = new ArrayList<>();
                patients.values().stream().sorted((a, b) -> a.id - b.id).forEach(p -> items.add(json(p)));
                send(ex, 200, "[" + String.join(",", items) + "]");
                return;
            }
        }

        m = Pattern.compile("/patients/(\\d+)").matcher(path);
        if (m.matches()) {
            Patient p = patients.get(Integer.parseInt(m.group(1)));
            if (p == null) {
                send(ex, 404, error("patient not found"));
                return;
            }
            switch (method) {
                case "GET" -> send(ex, 200, json(p));
                case "PUT" -> {
                    String name = field(body, "name");
                    if (name == null || name.isBlank()) {
                        send(ex, 400, error("name is required"));
                        return;
                    }
                    p.name = name;
                    send(ex, 200, json(p));
                }
                case "DELETE" -> {
                    patients.remove(p.id);
                    prescriptions.values().removeIf(x -> x.patientId == p.id);
                    send(ex, 204, "");
                }
                default -> send(ex, 405, error("method not allowed"));
            }
            return;
        }

        m = Pattern.compile("/patients/(\\d+)/diagnosis").matcher(path);
        if (m.matches() && method.equals("POST")) {
            Patient p = patients.get(Integer.parseInt(m.group(1)));
            if (p == null) {
                send(ex, 404, error("patient not found"));
                return;
            }
            if (!"DOCTOR".equals(role)) {
                send(ex, 403, error("only a doctor can set a diagnosis"));
                return;
            }
            String diagnosis = field(body, "diagnosis");
            if (diagnosis == null || diagnosis.isBlank()) {
                send(ex, 400, error("diagnosis is required"));
                return;
            }
            p.diagnosis = diagnosis;
            send(ex, 200, json(p));
            return;
        }

        m = Pattern.compile("/patients/(\\d+)/prescriptions").matcher(path);
        if (m.matches()) {
            Patient p = patients.get(Integer.parseInt(m.group(1)));
            if (p == null) {
                send(ex, 404, error("patient not found"));
                return;
            }
            if (method.equals("POST")) {
                if (!"DOCTOR".equals(role)) {
                    send(ex, 403, error("only a doctor can create prescriptions"));
                    return;
                }
                if (p.discharged) {
                    send(ex, 409, error("patient is already discharged"));
                    return;
                }
                String type = field(body, "type");
                if (type == null || !List.of("PROCEDURE", "MEDICINE", "OPERATION").contains(type)) {
                    send(ex, 400, error("type must be PROCEDURE, MEDICINE or OPERATION"));
                    return;
                }
                Prescription pr = new Prescription();
                pr.id = prescriptionIds.incrementAndGet();
                pr.patientId = p.id;
                pr.type = type;
                pr.description = field(body, "description");
                prescriptions.put(pr.id, pr);
                send(ex, 201, json(pr));
                return;
            }
            if (method.equals("GET")) {
                String status = query == null ? null : query.replaceAll(".*status=([a-z]+).*", "$1");
                List<String> items = new ArrayList<>();
                prescriptions.values().stream().filter(x -> x.patientId == p.id)
                        .filter(x -> status == null || !query.contains("status=")
                                || (status.equals("done") == x.done))
                        .sorted((a, b) -> a.id - b.id).forEach(x -> items.add(json(x)));
                send(ex, 200, "[" + String.join(",", items) + "]");
                return;
            }
        }

        m = Pattern.compile("/prescriptions/(\\d+)/execute").matcher(path);
        if (m.matches() && method.equals("POST")) {
            Prescription pr = prescriptions.get(Integer.parseInt(m.group(1)));
            if (pr == null) {
                send(ex, 404, error("prescription not found"));
                return;
            }
            boolean doctor = "DOCTOR".equals(role);
            boolean nurse = "NURSE".equals(role);
            if (!doctor && !nurse) {
                send(ex, 403, error("unknown role"));
                return;
            }
            if (pr.done) {
                send(ex, 409, error("prescription is already executed"));
                return;
            }
            pr.done = true;
            pr.doneBy = role;
            send(ex, 200, json(pr));
            return;
        }

        m = Pattern.compile("/prescriptions/(\\d+)").matcher(path);
        if (m.matches() && method.equals("DELETE")) {
            if (prescriptions.remove(Integer.parseInt(m.group(1))) == null) {
                send(ex, 404, error("prescription not found"));
                return;
            }
            send(ex, 204, "");
            return;
        }

        m = Pattern.compile("/patients/(\\d+)/discharge").matcher(path);
        if (m.matches() && method.equals("POST")) {
            Patient p = patients.get(Integer.parseInt(m.group(1)));
            if (p == null) {
                send(ex, 404, error("patient not found"));
                return;
            }
            if (!"DOCTOR".equals(role)) {
                send(ex, 403, error("only a doctor can discharge a patient"));
                return;
            }
            if (p.discharged) {
                send(ex, 409, error("patient is already discharged"));
                return;
            }
            String finalDiagnosis = field(body, "finalDiagnosis");
            p.finalDiagnosis = finalDiagnosis;
            p.discharged = true;
            send(ex, 200, json(p));
            return;
        }

        send(ex, 404, error("not found"));
    }

    static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : null;
    }

    static String esc(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String error(String message) {
        return "{\"error\":" + esc(message) + "}";
    }

    static String json(Patient p) {
        return "{\"id\":" + p.id + ",\"name\":" + esc(p.name) + ",\"diagnosis\":" + esc(p.diagnosis)
                + ",\"finalDiagnosis\":" + esc(p.finalDiagnosis) + ",\"discharged\":" + p.discharged + "}";
    }

    static String json(Prescription p) {
        return "{\"id\":" + p.id + ",\"patientId\":" + p.patientId + ",\"type\":" + esc(p.type) + ",\"description\":"
                + esc(p.description) + ",\"done\":" + p.done + ",\"doneBy\":" + esc(p.doneBy) + "}";
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
