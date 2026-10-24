import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Заглушка почтового сервера получателя для опытов с темпом отправки. Принимает письма по SMTP и никуда
 * их не пересылает. Ограничивает темп: письмо сверх предела получает временный отказ на команду MAIL,
 * как у почтовых провайдеров. Считает принятые письма и письма, принятые повторно с тем же Message-ID.
 * <p>
 * Настройки — переменными окружения при старте и запросом {@code PUT /config?rate=5&burst=1&penalty=60}:
 * <ul>
 *   <li>{@code rate} — писем в секунду, 0 — без ограничения;</li>
 *   <li>{@code burst} — сколько писем можно принять залпом;</li>
 *   <li>{@code penalty} — на сколько секунд после превышения отказывать во всём (так наказывают некоторые провайдеры);</li>
 *   <li>{@code reply} — ответ на превышение.</li>
 * </ul>
 * Показатели — {@code GET /metrics} в формате Prometheus; {@code POST /reset} обнуляет счётчики.
 */
public class SmtpStub {

    private static final String DEFAULT_REPLY = "451 4.7.1 Rate limit exceeded, try again later";

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private static final Set<String> SEEN_MESSAGE_IDS = ConcurrentHashMap.newKeySet();

    private static final AtomicLong ACCEPTED = new AtomicLong();

    private static final AtomicLong REJECTED = new AtomicLong();

    private static final AtomicLong DUPLICATES = new AtomicLong();

    private static final AtomicLong RECIPIENTS = new AtomicLong();

    private static final AtomicLong CONNECTIONS = new AtomicLong();

    private static volatile double rate = parse(System.getenv("STUB_RATE"), 0);

    private static volatile double burst = parse(System.getenv("STUB_BURST"), 1);

    private static volatile double penaltySeconds = parse(System.getenv("STUB_PENALTY"), 0);

    private static volatile String reply = System.getenv().getOrDefault("STUB_REPLY", DEFAULT_REPLY);

    private static double tokens = 1;

    private static long refilledAt = System.nanoTime();

    private static long penaltyUntil;

    public static void main(String[] args) throws IOException {
        int smtpPort = (int) parse(System.getenv("STUB_SMTP_PORT"), 2525);
        int httpPort = (int) parse(System.getenv("STUB_HTTP_PORT"), 8080);
        startHttp(httpPort);
        System.out.printf("SMTP stub: SMTP on %d, HTTP on %d, rate %s/s, burst %s, penalty %ss%n",
                          smtpPort, httpPort, rate, burst, penaltySeconds);
        try (ServerSocket server = new ServerSocket(smtpPort);
             var connections = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                Socket socket = server.accept();
                CONNECTIONS.incrementAndGet();
                connections.submit(() -> serve(socket));
            }
        }
    }

    private static void serve(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8)) {
            send(out, "220 smtp-stub ESMTP ready");
            int recipients = 0;
            boolean mailAccepted = false;
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase(Locale.ROOT) : line.toUpperCase(Locale.ROOT);
                switch (command) {
                    case "EHLO" -> send(out, "250-smtp-stub\r\n250-SIZE 52428800\r\n250-8BITMIME\r\n250-SMTPUTF8\r\n250 AUTH PLAIN LOGIN");
                    case "HELO" -> send(out, "250 smtp-stub");
                    case "AUTH" -> authenticate(line, in, out);
                    case "MAIL" -> {
                        mailAccepted = tryAcquire();
                        recipients = 0;
                        if (mailAccepted) {
                            send(out, "250 2.1.0 Ok");
                        } else {
                            REJECTED.incrementAndGet();
                            send(out, reply);
                        }
                    }
                    case "RCPT" -> {
                        if (mailAccepted) {
                            recipients++;
                            send(out, "250 2.1.5 Ok");
                        } else {
                            send(out, "503 5.5.1 Error: need MAIL command");
                        }
                    }
                    case "DATA" -> {
                        if (!mailAccepted || recipients == 0) {
                            send(out, "503 5.5.1 Error: need RCPT command");
                            continue;
                        }
                        send(out, "354 End data with <CR><LF>.<CR><LF>");
                        String messageId = readMessage(in);
                        ACCEPTED.incrementAndGet();
                        RECIPIENTS.addAndGet(recipients);
                        if (messageId != null && !SEEN_MESSAGE_IDS.add(messageId)) {
                            DUPLICATES.incrementAndGet();
                        }
                        mailAccepted = false;
                        send(out, "250 2.0.0 Ok: queued");
                    }
                    case "RSET" -> {
                        mailAccepted = false;
                        recipients = 0;
                        send(out, "250 2.0.0 Ok");
                    }
                    case "NOOP" -> send(out, "250 2.0.0 Ok");
                    case "QUIT" -> {
                        send(out, "221 2.0.0 Bye");
                        return;
                    }
                    default -> send(out, "502 5.5.2 Error: command not recognized");
                }
            }
        } catch (IOException e) {
            // Клиент закрыл соединение
        }
    }

    // Принимается любая учётная запись: заглушка изображает сервер получателя, а не проверяет пароли
    private static void authenticate(String line, BufferedReader in, PrintWriter out) throws IOException {
        String[] parts = line.trim().split("\\s+");
        String mechanism = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
        if ("LOGIN".equals(mechanism)) {
            if (parts.length < 3) {
                send(out, "334 VXNlcm5hbWU6");
                in.readLine();
            }
            send(out, "334 UGFzc3dvcmQ6");
            in.readLine();
        } else if ("PLAIN".equals(mechanism) && parts.length < 3) {
            send(out, "334 ");
            in.readLine();
        }
        send(out, "235 2.7.0 Authentication successful");
    }

    private static String readMessage(BufferedReader in) throws IOException {
        String messageId = null;
        boolean headers = true;
        String line;
        while ((line = in.readLine()) != null && !".".equals(line)) {
            if (headers && line.isEmpty()) {
                headers = false;
            } else if (headers && messageId == null && line.toLowerCase(Locale.ROOT).startsWith("message-id:")) {
                messageId = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return messageId;
    }

    private static synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        if (now < penaltyUntil) {
            return false;
        }
        if (rate <= 0) {
            return true;
        }
        tokens = Math.min(burst, tokens + (now - refilledAt) / NANOS_PER_SECOND * rate);
        refilledAt = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        if (penaltySeconds > 0) {
            penaltyUntil = now + (long) (penaltySeconds * NANOS_PER_SECOND);
        }
        return false;
    }

    private static void send(PrintWriter out, String response) {
        out.print(response + "\r\n");
        out.flush();
    }

    private static void startHttp(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/metrics", exchange -> respond(exchange, 200, metrics()));
        http.createContext("/reset", exchange -> {
            ACCEPTED.set(0);
            REJECTED.set(0);
            DUPLICATES.set(0);
            RECIPIENTS.set(0);
            SEEN_MESSAGE_IDS.clear();
            respond(exchange, 200, "reset\n");
        });
        http.createContext("/config", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                configure(query(exchange.getRequestURI().getRawQuery()));
            }
            respond(exchange, 200, "rate=%s burst=%s penalty=%s reply=%s%n".formatted(rate, burst, penaltySeconds, reply));
        });
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
    }

    private static synchronized void configure(Map<String, String> settings) {
        rate = parse(settings.get("rate"), rate);
        burst = Math.max(1, parse(settings.get("burst"), burst));
        penaltySeconds = parse(settings.get("penalty"), penaltySeconds);
        reply = settings.getOrDefault("reply", reply);
        tokens = Math.min(tokens, burst);
        penaltyUntil = 0;
        System.out.printf("SMTP stub reconfigured: rate %s/s, burst %s, penalty %ss, reply %s%n",
                          rate, burst, penaltySeconds, reply);
    }

    private static String metrics() {
        return """
                # TYPE smtp_stub_messages_accepted_total counter
                smtp_stub_messages_accepted_total %d
                # TYPE smtp_stub_messages_rejected_total counter
                smtp_stub_messages_rejected_total %d
                # TYPE smtp_stub_duplicate_messages_total counter
                smtp_stub_duplicate_messages_total %d
                # TYPE smtp_stub_recipients_accepted_total counter
                smtp_stub_recipients_accepted_total %d
                # TYPE smtp_stub_connections_total counter
                smtp_stub_connections_total %d
                # TYPE smtp_stub_rate_limit gauge
                smtp_stub_rate_limit %s
                """.formatted(ACCEPTED.get(), REJECTED.get(), DUPLICATES.get(), RECIPIENTS.get(), CONNECTIONS.get(),
                              rate);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                result.put(pair.substring(0, separator),
                           URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static double parse(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
