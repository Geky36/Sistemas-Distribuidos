import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

public class CentroImpresionServer {

    private static final int PORT = 8080;
    
    // Semáforos de sincronización para controlar la secuencia del pipeline
    private static Semaphore turnoDiseno;
    private static Semaphore turnoRevision;
    private static Semaphore turnoImpresion;

    // Estado del sistema
    private static final List<String> logEventos = new CopyOnWriteArrayList<>();
    private static final Map<String, Object> estadoActual = new ConcurrentHashMap<>();
    private static boolean simulacionEnEjecucion = false;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // API REST Endpoints
        server.createContext("/api/iniciar", new IniciarHandler());
        server.createContext("/api/estado", new EstadoHandler());
        server.createContext("/api/limpiar", new LimpiarHandler());

        // Servidor de Archivos Estáticos (HTML, CSS, JS) para la ruta raíz "/"
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("====================================================");
        System.out.println(" Servidor del Centro de Impresión Activo en el Puerto: " + PORT);
        System.out.println(" Accede a la interfaz en: http://localhost:" + PORT + "/");
        System.out.println("====================================================");
    }

    private static synchronized void iniciarSimulacion(int numDocumentos) {
        if (simulacionEnEjecucion) {
            return;
        }

        simulacionEnEjecucion = true;
        logEventos.clear();
        
        turnoDiseno = new Semaphore(1);
        turnoRevision = new Semaphore(0);
        turnoImpresion = new Semaphore(0);

        estadoActual.put("enDiseno", 0);
        estadoActual.put("enRevision", 0);
        estadoActual.put("impresos", 0);
        estadoActual.put("total", numDocumentos);
        estadoActual.put("estado", "EN_PROCESO");

        registrarEvento("SISTEMA", "Inicio de simulación para " + numDocumentos + " documento(s). Semáforos inicializados [D=1, R=0, I=0].");

        Thread disenoThread = new Thread(new ProcesoDiseno(numDocumentos), "Hilo-Diseno");
        Thread revisionThread = new Thread(new ProcesoRevision(numDocumentos), "Hilo-Revision");
        Thread impresionThread = new Thread(new ProcesoImpresion(numDocumentos), "Hilo-Impresion");

        disenoThread.start();
        revisionThread.start();
        impresionThread.start();
    }

    private static void registrarEvento(String origen, String mensaje) {
        String timestamp = String.format("%tT", System.currentTimeMillis());
        String evento = String.format("[%s] [%s]: %s", timestamp, origen, mensaje);
        logEventos.add(evento);
        System.out.println(evento);
    }

    // --- PROCESOS CONCURRENTES (HILOS) ---

    static class ProcesoDiseno implements Runnable {
        private final int totalDoc;

        public ProcesoDiseno(int totalDoc) {
            this.totalDoc = totalDoc;
        }

        @Override
        public void run() {
            for (int i = 1; i <= totalDoc; i++) {
                try {
                    registrarEvento("DISEÑO", "Documento #" + i + " solicitando permiso -> wait(turno_diseno)");
                    turnoDiseno.acquire();
                    
                    estadoActual.put("enDiseno", i);
                    registrarEvento("DISEÑO", "🎨 Permiso concedido. Diseñando Documento #" + i + "...");
                    Thread.sleep(1500);

                    registrarEvento("DISEÑO", "Documento #" + i + " diseñado. Cediendo paso -> signal(turno_revision)");
                    turnoRevision.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static class ProcesoRevision implements Runnable {
        private final int totalDoc;

        public ProcesoRevision(int totalDoc) {
            this.totalDoc = totalDoc;
        }

        @Override
        public void run() {
            for (int i = 1; i <= totalDoc; i++) {
                try {
                    registrarEvento("REVISIÓN", "Documento #" + i + " esperando turno -> wait(turno_revision)");
                    turnoRevision.acquire();

                    estadoActual.put("enDiseno", 0);
                    estadoActual.put("enRevision", i);
                    registrarEvento("REVISIÓN", "🔍 Permiso concedido. Revisando Documento #" + i + "...");
                    Thread.sleep(1500);

                    registrarEvento("REVISIÓN", "Documento #" + i + " revisado. Cediendo paso -> signal(turno_impresion)");
                    turnoImpresion.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static class ProcesoImpresion implements Runnable {
        private final int totalDoc;

        public ProcesoImpresion(int totalDoc) {
            this.totalDoc = totalDoc;
        }

        @Override
        public void run() {
            for (int i = 1; i <= totalDoc; i++) {
                try {
                    registrarEvento("IMPRESIÓN", "Documento #" + i + " esperando impresora -> wait(turno_impresion)");
                    turnoImpresion.acquire();

                    estadoActual.put("enRevision", 0);
                    estadoActual.put("impresos", i);
                    registrarEvento("IMPRESIÓN", "🖨️ Permiso concedido. Imprimiendo Documento #" + i + "...");
                    Thread.sleep(1500);

                    if (i < totalDoc) {
                        registrarEvento("IMPRESIÓN", "Documento #" + i + " impreso. Reiniciando ciclo -> signal(turno_diseno)");
                        turnoDiseno.release();
                    } else {
                        registrarEvento("SISTEMA", "✅ Pipeline completado con éxito para los " + totalDoc + " documentos.");
                        estadoActual.put("estado", "COMPLETADO");
                        simulacionEnEjecucion = false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- MANEJADOR DE ARCHIVOS ESTÁTICOS (HTML, CSS, JS) ---

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            // Redirigir la raíz / a /index.html
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File("." + path);
            if (!file.exists() || file.isDirectory()) {
                String response = "404 Not Found - El archivo " + path + " no existe en el directorio raíz del proyecto.";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "text/javascript";
                else if (path.endsWith(".html")) contentType = "text/html";
                else contentType = "application/octet-stream";
            }

            exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, file.length());

            try (InputStream is = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    // --- MANEJADORES DE LA API REST ---

    static class IniciarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            configurarCORS(exchange);
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                int numDoc = 3;
                
                if (body.contains("numDocumentos=")) {
                    try {
                        numDoc = Integer.parseInt(body.split("=")[1].trim());
                    } catch (Exception ignored) {}
                }

                iniciarSimulacion(numDoc);
                enviarRespuestaJSON(exchange, 200, "{\"mensaje\": \"Simulación iniciada\", \"documentos\": " + numDoc + "}");
            } else if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class EstadoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            configurarCORS(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                StringBuilder json = new StringBuilder("{");
                json.append("\"enDiseno\":").append(estadoActual.getOrDefault("enDiseno", 0)).append(",");
                json.append("\"enRevision\":").append(estadoActual.getOrDefault("enRevision", 0)).append(",");
                json.append("\"impresos\":").append(estadoActual.getOrDefault("impresos", 0)).append(",");
                json.append("\"total\":").append(estadoActual.getOrDefault("total", 0)).append(",");
                json.append("\"estado\":\"").append(estadoActual.getOrDefault("estado", "INACTIVO")).append("\",");
                json.append("\"log\":[");

                List<String> copiaLogs = new ArrayList<>(logEventos);
                for (int i = 0; i < copiaLogs.size(); i++) {
                    json.append("\"").append(copiaLogs.get(i).replace("\"", "\\\"")).append("\"");
                    if (i < copiaLogs.size() - 1) json.append(",");
                }
                json.append("]}");

                enviarRespuestaJSON(exchange, 200, json.toString());
            } else if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class LimpiarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            configurarCORS(exchange);
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                simulacionEnEjecucion = false;
                logEventos.clear();
                estadoActual.clear();
                estadoActual.put("estado", "INACTIVO");
                enviarRespuestaJSON(exchange, 200, "{\"mensaje\": \"Estado reiniciado\"}");
            } else if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static void configurarCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void enviarRespuestaJSON(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}