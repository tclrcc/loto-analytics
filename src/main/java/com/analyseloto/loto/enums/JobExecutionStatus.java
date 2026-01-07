package com.analyseloto.loto.enums;

import lombok.Getter;

@Getter
public enum JobExecutionStatus {
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    WARNING("WARNING");

    private final String code;

    JobExecutionStatus(String code) {
        this.code = code;
    }
}
