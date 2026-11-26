package com.sica.model.domain.repository;

import java.time.LocalDateTime;

// Datos de una cita programada que necesita el Inicio de portería.
public record VisitaPrevista(int id, String invitado, String documento, String empresa,
        String funcionario, LocalDateTime fechaProgramada) { }
