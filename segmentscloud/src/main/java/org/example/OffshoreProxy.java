package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;

public class OffshoreProxy {
    private static final int LISTEN_PORT = 9090;
    private static final Logger logger = LoggerFactory.getLogger(OffshoreProxy.class);
    public static void main(String[] args) throws IOException {
        logger.info("Offshore Proxy is starting...");
        ServerSocket serverSocket = new ServerSocket(LISTEN_PORT);
        Socket fromShip = serverSocket.accept();
        System.out.println("Connection established with ShipProxy!");

        DataInputStream fromShipStream = new DataInputStream(fromShip.getInputStream());
        DataOutputStream toShipStream = new DataOutputStream(fromShip.getOutputStream());
        logger.info("Offshore Proxy is listening on port {}", LISTEN_PORT);
        while (true) {
            try {
                int requestLength = fromShipStream.readInt();
                byte[] requestData = new byte[requestLength];
                fromShipStream.readFully(requestData);

                String requestLine = new String(requestData);
                if (requestLine.startsWith("CONNECT")) {
                    String[] parts = requestLine.split(" ");
                    String[] hostPort = parts[1].split(":");
                    String host = hostPort[0];
                    int port = Integer.parseInt(hostPort[1]);
                    System.out.println(">>> Offshore CONNECT to: " + host + ":" + port);

                    Socket targetSocket = new Socket(host, port);
                    OutputStream toTarget = targetSocket.getOutputStream();
                    InputStream fromTarget = targetSocket.getInputStream();

                    String okResp = "HTTP/1.1 200 Connection Established\r\n\r\n";
                    byte[] okBytes = okResp.getBytes();
                    toShipStream.writeInt(okBytes.length);
                    toShipStream.write(okBytes);
                    toShipStream.flush();

                    Thread t1 = new Thread(() -> pipeData(fromShipStream, toTarget));
                    Thread t2 = new Thread(() -> pipeData(fromTarget, toShipStream));
                    t1.start();
                    t2.start();
                    t1.join();
                    t2.join();
                    targetSocket.close();
                } else {
                    // HTTP flow
                    String requestStr = new String(requestData);
                    String hostLine = requestStr.lines()
                            .filter(line -> line.toLowerCase().startsWith("host:"))
                            .findFirst().orElseThrow();
                    String host = hostLine.split(":")[1].trim();

                    Socket target = new Socket(host, 80);
                    OutputStream toTarget = target.getOutputStream();
                    InputStream fromTarget = target.getInputStream();

                    toTarget.write(requestData);
                    toTarget.flush();

                    ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fromTarget.read(buffer)) != -1) {
                        responseBuffer.write(buffer, 0, read);
                        if (read < buffer.length) break;
                    }

                    byte[] responseData = responseBuffer.toByteArray();
                    toShipStream.writeInt(responseData.length);
                    toShipStream.write(responseData);
                    toShipStream.flush();

                    target.close();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
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
