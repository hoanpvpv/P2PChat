package com.mycompany.p2pchat.peer;

import com.mycompany.p2pchat.utils.Constants;

public class PeerApp {

    public static void main(String[] args) {
        int port = Constants.DEFAULT_PEER_PORT;
        int webPort = Constants.DEFAULT_WEB_PORT;
        String username = null;
        String host = null;
        String bootstrap = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    if (i + 1 < args.length) { port = Integer.parseInt(args[++i]); }
                    break;
                case "--web":
                    if (i + 1 < args.length) { webPort = Integer.parseInt(args[++i]); }
                    break;
                case "--username":
                    if (i + 1 < args.length) { username = args[++i]; }
                    break;
                case "--host":
                    if (i + 1 < args.length) { host = args[++i]; }
                    break;
                case "--bootstrap":
                    if (i + 1 < args.length) { bootstrap = args[++i]; }
                    break;
            }
        }

        PeerNode peer = new PeerNode(port, webPort);
        peer.setConfig(username, host, bootstrap);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down peer...");
        }));
        peer.start();
    }
}
