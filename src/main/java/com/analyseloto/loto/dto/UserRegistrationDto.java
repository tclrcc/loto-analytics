package com.analyseloto.loto.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserRegistrationDto {
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
