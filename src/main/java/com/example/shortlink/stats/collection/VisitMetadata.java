package com.example.shortlink.stats.collection;


import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

public final class VisitMetadata {
    private VisitMetadata() {}

    public static String peerNetwork(String input) {
        if (input == null) return null;
        String address = input.split("%", 2)[0];
        try {
            if (!address.contains(":")) {
                String[] parts = address.split("\\.", -1);
                if (parts.length != 4) return null;
                for (String part : parts) {
                    if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return null;
                }
                return Integer.parseInt(parts[0])
                        + "."
                        + Integer.parseInt(parts[1])
                        + "."
                        + Integer.parseInt(parts[2])
                        + ".0/24";
            }
            if (!address.matches("[0-9A-Fa-f:.]+")) return null;
            byte[] bytes =
                    InetAddress.getByName(address).getAddress(); // Numeric literal only; no DNS.
            if (bytes.length == 4)
                return peerNetwork(InetAddress.getByAddress(bytes).getHostAddress());
            Arrays.fill(bytes, 6, bytes.length, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress() + "/48";
        } catch (Exception failure) {
            return null;
        }
    }

    public static String userAgent(String input) {
        if (input == null) return null;
        StringBuilder result = new StringBuilder();
        input.codePoints()
                .filter(cp -> !Character.isISOControl(cp) && !(cp >= 0xD800 && cp <= 0xDFFF))
                .limit(512)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    public static String refererHost(String input) {
        if (input == null) return null;
        try {
            URI uri = new URI(input);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return null;
            String authority = uri.getRawAuthority();
            if (authority == null) return null;
            String host = authority.substring(authority.lastIndexOf('@') + 1);
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                if (close < 0) return null;
                String suffix = host.substring(close + 1);
                if (!validPort(suffix)) return null;
                uri.parseServerAuthority();
                String literal = host.substring(1, close);
                if (!literal.matches("[0-9a-fA-F:.]+") || !literal.contains(":")) return null;
                return InetAddress.getByName(literal).getHostAddress().toLowerCase(Locale.ROOT);
            }
            int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                if (!validPort(host.substring(colon))) return null;
                host = host.substring(0, colon);
            }
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.length() > 253) return null;
            for (String label : host.split("\\.", -1)) if (label.isEmpty()) return null;
            String userInfo = authority.substring(0, authority.lastIndexOf('@') + 1);
            String port = colon >= 0 ? authority.substring(authority.lastIndexOf(':')) : "";
            new URI(uri.getScheme() + "://" + userInfo + host + port).parseServerAuthority();
            return host;
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean validPort(String suffix) {
        return suffix.isEmpty()
                || (suffix.matches(":[0-9]{1,5}")
                        && Integer.parseInt(suffix.substring(1)) <= 65535);
    }
}
