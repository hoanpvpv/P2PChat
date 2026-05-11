package com.mycompany.p2pchat.bootstrap;

import com.mycompany.p2pchat.utils.Constants;

public class BootstrapApp {

    public static void main(String[] args) {
        int port = Constants.DEFAULT_BOOTSTRAP_PORT;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        BootstrapServer server = new BootstrapServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down bootstrap server...");
            server.stop();
        }));
        server.start();
    }
}
