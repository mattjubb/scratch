package com.compute.model;

public record PortSpec(String name, int port, String protocol) {
    public PortSpec(String name, int port) {
        this(name, port, "TCP");
    }
}
