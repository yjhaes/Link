package com.example.shortlink.service.error;

public class RedirectLoadBusyException extends RuntimeException {
    public RedirectLoadBusyException() { super("Redirect database load capacity is busy."); }
}
