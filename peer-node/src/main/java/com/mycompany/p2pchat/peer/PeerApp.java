package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.utils.Constants;

public class PeerApp {

    public static void main(String[] args) {
        String username = "peer";
        int port = Constants.DEFAULT_PEER_PORT;
        String bootstrapHost = "localhost";
        int bootstrapPort = Constants.DEFAULT_BOOTSTRAP_PORT;
        String host = "localhost";
        int webPort = Constants.DEFAULT_WEB_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--username":
                    if (i + 1 < args.length) { username = args[++i]; }
                    break;
                case "--port":
                    if (i + 1 < args.length) { port = Integer.parseInt(args[++i]); }
                    break;
                case "--host":
                    if (i + 1 < args.length) { host = args[++i]; }
                    break;
                case "--bootstrap":
                    if (i + 1 < args.length) {
                        String[] bp = args[++i].split(":");
                        bootstrapHost = bp[0];
                        if (bp.length > 1) bootstrapPort = Integer.parseInt(bp[1]);
                    }
                    break;
                case "--web":
                    if (i + 1 < args.length) { webPort = Integer.parseInt(args[++i]); }
                    break;
            }
        }

        PeerNode peer = new PeerNode(username, host, port, bootstrapHost, bootstrapPort, webPort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down peer...");
        }));
        peer.start();
    }
}
