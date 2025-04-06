package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class ShipProxy {
    private static final Logger logger = LoggerFactory.getLogger(ShipProxy.class);
//    private static final String OFFSHORE_HOST = "localhost"; // localhost or (offshore-proxy)Docker container name
    private static final String OFFSHORE_HOST = "offshoreproxy"; // ✅ Use service name
    private static final int OFFSHORE_PORT = 9090;
    private static final int SHIP_PROXY_PORT = 8080;

    private static Socket offshoreSocket;
    private static DataOutputStream offshoreOut;
    private static DataInputStream offshoreIn;

    public static void main(String[] args) {
        logger.info("Ship Proxy is starting...");

        ExecutorService executor = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(SHIP_PROXY_PORT)) {
            logger.info("Ship Proxy is listening on port {}", SHIP_PROXY_PORT);

            connectToOffshore();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("Accepted client: {}", clientSocket.getRemoteSocketAddress());

                executor.submit(() -> handleClient(clientSocket));
            }

        } catch (IOException e) {
            logger.error("Failed to start Ship Proxy: {}", e.getMessage());
        }
    }

    private static void connectToOffshore() {
        while (true) {
            try {
                offshoreSocket = new Socket(OFFSHORE_HOST, OFFSHORE_PORT);
                offshoreOut = new DataOutputStream(offshoreSocket.getOutputStream());
                offshoreIn = new DataInputStream(offshoreSocket.getInputStream());
                logger.info("Connected to Offshore Proxy.");
                break;
            } catch (IOException e) {
                logger.warn("Retrying connection to Offshore Proxy...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (
                InputStream clientIn = clientSocket.getInputStream();
                OutputStream clientOut = clientSocket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn))
        ) {
            String line = reader.readLine();
            if (line == null) return;

            if (line.startsWith("CONNECT")) {
                // HTTPS Tunnel
                logger.info("Handling HTTPS CONNECT: {}", line);

                synchronized (offshoreOut) {
                    offshoreOut.writeInt(line.length());
                    offshoreOut.write(line.getBytes(StandardCharsets.UTF_8));
                    offshoreOut.flush();
                }

                int respLen = offshoreIn.readInt();
                byte[] okBytes = new byte[respLen];
                offshoreIn.readFully(okBytes);
                clientOut.write(okBytes);
                clientOut.flush();

                new Thread(() -> pipeData(clientIn, offshoreOut)).start();
                new Thread(() -> pipeData(offshoreIn, clientOut)).start();

            } else {
                // HTTP Request
                logger.info("Handling HTTP request...");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baos.write(line.getBytes(StandardCharsets.UTF_8));
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                String l;
                while (!(l = reader.readLine()).isEmpty()) {
                    baos.write(l.getBytes(StandardCharsets.UTF_8));
                    baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                byte[] requestBytes = baos.toByteArray();

                synchronized (offshoreOut) {
                    offshoreOut.writeInt(requestBytes.length);
                    offshoreOut.write(requestBytes);
                    offshoreOut.flush();
                }

                int responseLength = offshoreIn.readInt();
                byte[] responseBytes = new byte[responseLength];
                offshoreIn.readFully(responseBytes);

                clientOut.write(responseBytes);
                clientOut.flush();
            }

        } catch (Exception e) {
            logger.error("Client handler error: {}", e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void pipeData(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {}
    }
}