package com.example.shortlink;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only TCP relay: discard replies on the first established MySQL connection. */
final class MysqlReplyProxy implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0);
    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean discardFirstReplies;

    MysqlReplyProxy(String host, int port) throws IOException {
        workers.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    sockets.add(client);
                    Socket server = new Socket(host, port);
                    sockets.add(server);
                    int number = accepted.incrementAndGet();
                    workers.submit(() -> relay(client, server, false, number));
                    workers.submit(() -> relay(server, client, true, number));
                } catch (IOException failure) { if (!listener.isClosed()) throw new java.io.UncheckedIOException(failure); }
            }
        });
    }

    int port() { return listener.getLocalPort(); }
    void discardFirstReplies() { discardFirstReplies = true; }

    private void relay(Socket from, Socket to, boolean reply, int number) {
        try {
            byte[] bytes = new byte[8192];
            int size;
            while ((size = from.getInputStream().read(bytes)) >= 0) {
                if (reply && number == 1 && discardFirstReplies) continue;
                to.getOutputStream().write(bytes, 0, size);
                to.getOutputStream().flush();
            }
        } catch (IOException ignored) { // Closing a timed-out connection is expected.
        } finally {
            try { from.close(); } catch (IOException ignored) { }
            try { to.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void close() throws IOException {
        listener.close();
        for (Socket socket : sockets) socket.close();
        workers.shutdownNow();
    }
}
