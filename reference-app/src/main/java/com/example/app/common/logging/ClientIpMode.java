package com.example.app.common.logging;

import com.example.app.common.security.PersonalDataHasher;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * How the client's address appears in the logs ({@code app.logging.client-ip}). An address is
 * personal data; the mode trades tracing an abuser against how much of it is kept.
 */
public enum ClientIpMode {

    /** The full address. */
    RAW {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return address;
        }
    },

    /** IPv4 with the last octet zeroed, IPv6 cut to its /48 network: enough to spot one network. */
    TRUNCATED {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return truncate(address);
        }
    },

    /** A keyed-hash prefix: correlates one client's requests without keeping the address. */
    HASHED {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return hasher.hash(address).substring(0, HASH_PREFIX_LENGTH);
        }
    },

    /** Not logged. */
    NONE {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return "";
        }
    };

    private static final int HASH_PREFIX_LENGTH = 16;
    private static final int IPV4_LAST_OCTET = 3;
    /** A /48 network: the first 6 of an IPv6 address's 16 bytes. */
    private static final int IPV6_NETWORK_BYTES = 6;

    abstract String render(String address, PersonalDataHasher hasher);

    private static String truncate(String address) {
        try {
            InetAddress parsed = InetAddress.ofLiteral(address);
            byte[] bytes = parsed.getAddress();
            if (parsed instanceof Inet4Address) {
                bytes[IPV4_LAST_OCTET] = 0;
            } else if (parsed instanceof Inet6Address) {
                for (int index = IPV6_NETWORK_BYTES; index < bytes.length; index++) {
                    bytes[index] = 0;
                }
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (IllegalArgumentException | UnknownHostException notAnAddress) {
            return "invalid";
        }
    }
}
