package com.arpay.notification;

/**
 * Result of a Firebase push attempt
 */
public class PushResult {
    private final boolean success;
    private final boolean tokenInvalid; // true if FCM says device unregistered/invalid
    private final String errorMessage;

    public PushResult(boolean success, boolean tokenInvalid, String errorMessage) {
        this.success = success;
        this.tokenInvalid = tokenInvalid;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public boolean isTokenInvalid() { return tokenInvalid; }
    public String getErrorMessage() { return errorMessage; }

    public static PushResult success() { return new PushResult(true, false, null); }
    public static PushResult tokenInvalid(String error) { return new PushResult(false, true, error); }
    public static PushResult failed(String error) { return new PushResult(false, false, error); }
}
