package com.sica.model.domain.repository;

import com.sica.model.domain.entity.Visita;

public record SolicitudEnPorteria(Visita visita, String persona, String documento,
                                  String empresa, String funcionario, String estado) { }
