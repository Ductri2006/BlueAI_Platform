package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves the caller address without trusting spoofable forwarding headers by
 * default. Deployments may opt in only when a trusted reverse proxy is the
 * sole network path to the backend, replaces X-Forwarded-For itself, and its
 * source CIDR is configured explicitly.
 */
@Service
public class WidgetClientAddressResolver {

    private final WidgetProtectionProperties properties;

    public WidgetClientAddressResolver(WidgetProtectionProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizedAddress(request.getRemoteAddr());
        if (!properties.isTrustForwardedHeaders()
                || !isTrustedProxy(remoteAddress, properties.getTrustedProxyCidrs())) {
            return remoteAddress;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String first = forwarded.split(",", 2)[0].trim();
            if (isLiteralIpAddress(first)) {
                return first;
            }
        }
        return remoteAddress;
    }

    private String normalizedAddress(String address) {
        return address == null || address.isBlank() ? "unknown" : address.trim();
    }

    private boolean isTrustedProxy(String remoteAddress, List<String> cidrs) {
        if (!isLiteralIpAddress(remoteAddress) || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        return cidrs.stream().anyMatch(cidr -> isWithinCidr(remoteAddress, cidr));
    }

    private boolean isWithinCidr(String address, String cidr) {
        if (cidr == null) {
            return false;
        }
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2 || !isLiteralIpAddress(parts[0].trim())) {
            return false;
        }
        try {
            InetAddress candidate = InetAddress.getByName(address);
            InetAddress network = InetAddress.getByName(parts[0].trim());
            byte[] candidateBytes = candidate.getAddress();
            byte[] networkBytes = network.getAddress();
            if (candidateBytes.length != networkBytes.length) {
                return false;
            }
            int prefixLength = Integer.parseInt(parts[1].trim());
            if (prefixLength < 0 || prefixLength > candidateBytes.length * Byte.SIZE) {
                return false;
            }
            int completeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < completeBytes; index++) {
                if (candidateBytes[index] != networkBytes[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (candidateBytes[completeBytes] & mask) == (networkBytes[completeBytes] & mask);
        } catch (UnknownHostException | NumberFormatException exception) {
            return false;
        }
    }

    private boolean isLiteralIpAddress(String address) {
        if (address == null || address.length() > 64 || !address.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
