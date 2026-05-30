package com.compute.model;

/** Deployment lane / environment label. */
public enum Lane {
    DEV, SIT, UAT, PFIX, PROD;

    public String code() {
        return name().toLowerCase();
    }

    public static Lane fromCode(String s) {
        return valueOf(s.toUpperCase());
    }
}
