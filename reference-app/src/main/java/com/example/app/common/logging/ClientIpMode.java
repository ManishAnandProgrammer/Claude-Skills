package com.example.app.common.logging;

import com.example.app.common.security.PersonalDataHasher;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public enum ClientIpMode {

    RAW {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return address;
        }
    },

    TRUNCATED {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return truncate(address);
        }
    },

    HASHED {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return hasher.hash(address).substring(0, HASH_PREFIX_LENGTH);
        }
    },

    NONE {
        @Override
        String render(String address, PersonalDataHasher hasher) {
            return "";
        }
    };

    private static final int HASH_PREFIX_LENGTH = 16;
    private static final int IPV4_LAST_OCTET = 3;
    private static final int IPV6_SLASH_48_BYTES = 6;

    abstract String render(String address, PersonalDataHasher hasher);

    private static String truncate(String address) {
        try {
            InetAddress parsed = InetAddress.ofLiteral(address);
            byte[] bytes = parsed.getAddress();
            if (parsed instanceof Inet4Address) {
                bytes[IPV4_LAST_OCTET] = 0;
            } else if (parsed instanceof Inet6Address) {
                for (int index = IPV6_SLASH_48_BYTES; index < bytes.length; index++) {
                    bytes[index] = 0;
                }
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (IllegalArgumentException | UnknownHostException notAnAddress) {
            return "invalid";
        }
    }
}
