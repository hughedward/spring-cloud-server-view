package com.ideaser.portviewer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Checks which ports have a live listener on the local machine.
 * Pure TCP connect probe — must never run on the EDT.
 */
final class PortLivenessChecker {

    private static final int CONNECT_TIMEOUT_MS = 300;

    private PortLivenessChecker() {
    }

    @NotNull
    static Set<Integer> check(@NotNull Collection<Integer> ports) {
        Set<Integer> listening = ConcurrentHashMap.newKeySet();
        for (int port : ports) {
            if (isListening(port)) {
                listening.add(port);
            }
        }
        return listening;
    }

    static boolean isListening(int port) {
        // try IPv4 loopback first, then IPv6
        return connect("127.0.0.1", port) || connect("::1", port);
    }

    private static boolean connect(@NotNull String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
