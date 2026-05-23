package com.mycompany.p2pchat.mailbox;

public class MailboxApp {
    public static void main(String[] args) {
        int port = 9100;
        String dbPath = "data/mailbox.db";

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--db".equals(args[i]) && i + 1 < args.length) {
                dbPath = args[++i];
            }
        }

        MailboxDatabase database = new MailboxDatabase(dbPath);
        database.init();
        MailboxRepository repository = new MailboxRepository(database);
        MailboxServer server = new MailboxServer(port, repository);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down mailbox server...");
            server.stop();
            database.close();
        }));

        server.start();
    }
}
