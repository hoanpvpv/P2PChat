package com.mycompany.p2pchat.peer;

public class PeerApp {

    public static void main(String[] args) {
        int webPort = com.mycompany.p2pchat.utils.Constants.DEFAULT_WEB_PORT;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--web") && i + 1 < args.length) {
                try {
                    webPort = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid web port: " + args[i + 1] + ". Using default: " + com.mycompany.p2pchat.utils.Constants.DEFAULT_WEB_PORT);
                }
            }
        }

        PeerNode peer = new PeerNode(webPort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down peer...");
        }));
        peer.start();
    }
}
