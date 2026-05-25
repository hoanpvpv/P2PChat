package com.mycompany.p2pchat.bootstrap;

import com.mycompany.p2pchat.utils.Constants;

public class BootstrapApp {

    public static void main(String[] args) {
        int port = Constants.DEFAULT_BOOTSTRAP_PORT;
        int dashboardPort = Constants.DEFAULT_DASHBOARD_PORT;
        String mailboxHost = "localhost";
        int mailboxPort = 9100;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--dashboard-port".equals(args[i]) && i + 1 < args.length) {
                dashboardPort = Integer.parseInt(args[++i]);
            } else if ("--mailbox".equals(args[i]) && i + 1 < args.length) {
                String[] parts = args[++i].split(":");
                mailboxHost = parts[0];
                if (parts.length > 1) {
                    mailboxPort = Integer.parseInt(parts[1]);
                }
            }
        }

        BootstrapServer server = new BootstrapServer(port, mailboxHost, mailboxPort);
        BootstrapDashboardServer dashboardServer = new BootstrapDashboardServer(dashboardPort, server);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down bootstrap server...");
            dashboardServer.stop();
            server.stop();
        }));
        dashboardServer.start();
        server.start();
    }
}
