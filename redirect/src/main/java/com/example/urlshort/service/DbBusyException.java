package com.example.urlshort.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** DB 동시 조회 상한에 막혔다. 기다리게 두는 것보다 바로 503을 주는 편이 스레드를 지킨다. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DbBusyException extends RuntimeException {

    public DbBusyException() {
        super("db bulkhead full", null, false, false);
    }
}
