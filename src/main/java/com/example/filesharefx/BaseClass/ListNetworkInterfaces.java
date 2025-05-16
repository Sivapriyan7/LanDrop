package com.example.filesharefx.BaseClass;
import java.net.*;
import java.util.*;

public class ListNetworkInterfaces {
    public static void main(String[] args) throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();

            System.out.println("Name: " + ni.getName());
            System.out.println("Display Name: " + ni.getDisplayName());
            System.out.println("Is Up: " + ni.isUp());
            System.out.println("Supports Multicast: " + ni.supportsMulticast());
            System.out.println("Is Loopback: " + ni.isLoopback());
            System.out.println("Is Virtual: " + ni.isVirtual());

            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                System.out.println("  IP Address: " + addr.getHostAddress());
            }

            System.out.println("------------------------------------------------");
        }
    }
}
