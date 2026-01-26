package com.analyseloto.loto.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class UserRegistrationDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String username;
    private String email;
    private String password;

    // Infos optionnelles
    private String firstName;
    private LocalDate birthDate;
    private String birthTime;
    private String birthCity;
    private String zodiacSign;
}
