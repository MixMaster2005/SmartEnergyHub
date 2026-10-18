package com.smartenergy.collector.web;

// ========================================================================
// TODO(etape-12) - Limite de taille du corps (10 Ko) -> 413
// ========================================================================
// Guide : etape 12 (validation des entrees). CDC : section 5.2 (taille maximale 10 Ko, code PAYLOAD_TOO_LARGE).
//
// A faire :
//  - OncePerRequestFilter limite aux POST /api/measurements.
//  - Content-Length > 10240 -> repondre 413 en application/problem+json (code PAYLOAD_TOO_LARGE).
//  - Cas du corps SANS Content-Length (chunked) : envelopper la requete avec un flux qui compte les octets
//    et leve une erreur au-dela de la limite.
//  - Enregistrer le filtre (FilterRegistrationBean) apres RequestIdFilter, pour garder le traceId.
//  - Limite configurable : smartenergy.measurement.max-body-bytes (a ajouter a MeasurementProperties).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import jakarta.servlet.FilterChain;
// import jakarta.servlet.ServletException;
// import jakarta.servlet.http.HttpServletRequest;
// import jakarta.servlet.http.HttpServletResponse;
// import java.io.IOException;
// import org.springframework.web.filter.OncePerRequestFilter;
//
// public class BodySizeLimitFilter extends OncePerRequestFilter {
//     @Override
//     protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
//             throws ServletException, IOException {
//         // if (req.getContentLengthLong() > MAX) { ecrire un Problem Details 413 ; return; }
//         chain.doFilter(req, res);
//     }
// }
