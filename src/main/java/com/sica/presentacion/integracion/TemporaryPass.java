package com.sica.presentacion.integracion;

import com.sica.presentacion.integracion.SicaData.Person;
import com.sica.presentacion.integracion.SicaData.Status;

import java.time.LocalDateTime;

public record TemporaryPass(int id, Person person, String reason, LocalDateTime start, LocalDateTime end,
                            String issuer, String reviewer, Status status) {
    public String code() { return "PT-%04d".formatted(id); }
    public boolean tieneComprobante() { return start != null && end != null; }
}
