package com.mycompany.p2pchat.peer;

public class PeerApp {

    public static void main(String[] args) {
        int webPort = com.mycompany.p2pchat.utils.Constants.DEFAULT_WEB_PORT;
        String bootstrap = null;
        String username = null;
        String host = null;
        int peerPort = 0;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--web") && i + 1 < args.length) {
                try {
                    webPort = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid web port: " + args[i] + ". Using default: " + com.mycompany.p2pchat.utils.Constants.DEFAULT_WEB_PORT);
                }
            } else if (args[i].equals("--bootstrap") && i + 1 < args.length) {
                bootstrap = args[++i];
            } else if (args[i].equals("--username") && i + 1 < args.length) {
                username = args[++i];
            } else if (args[i].equals("--host") && i + 1 < args.length) {
                host = args[++i];
            } else if (args[i].equals("--port") && i + 1 < args.length) {
                try {
                    peerPort = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid peer port: " + args[i]);
                }
            }
        }

        PeerNode peer = new PeerNode(webPort);
        peer.setConfig(username, host, peerPort, bootstrap);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down peer...");
        }));
        peer.start();
    }
}
