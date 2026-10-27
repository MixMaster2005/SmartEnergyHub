package com.smartenergy.collector.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.platform.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite la taille du corps de {@code POST /api/measurements} a 10 Ko (CDC sections 5.2 et 10,
 * etape 12 / EF-18) : au-dela, 413 {@code PAYLOAD_TOO_LARGE} en application/problem+json
 * (RFC 9457, meme forme que toutes les autres erreurs de la plateforme).
 *
 * <p>Un filtre s'execute AVANT le DispatcherServlet : deux chemins sont necesaires pour
 * produire le 413.
 * <ul>
 *   <li>{@code Content-Length} annonce et superieur a la limite : la reponse est ecrite ici
 *       (ProblemDetail serialize avec l'ObjectMapper de l'application, traceId repris du MDC) ;</li>
 *   <li>corps SANS {@code Content-Length} (chunked) : la requete est enveloppee d'un flux qui
 *       compte les octets et leve {@link ApiException} {@code PAYLOAD_TOO_LARGE} au-dela de la
 *       limite. Resolue par le {@code GlobalExceptionHandler} (enregistre en
 *       {@code @ControllerAdvice}), elle produit exactement la meme reponse 413 ; le catch de
 *       {@code doFilterInternal} reste en place comme filet si la reponse n'est pas encore
 *       engagee. URI de type commune : {@link ErrorCode#typeUri()}.</li>
 * </ul>
 *
 * <p>Portee : uniquement {@code POST /api/measurements} ; les autres routes et les autres
 * methodes passent intactes. Limite parametrable : {@code smartenergy.measurement.max-body-bytes}
 * (voir {@link MeasurementProperties#maxBodyBytes()}).
 *
 * <p>Enregistre par {@code WebFilterConfig} juste apres {@link RequestIdFilter}
 * ({@code HIGHEST_PRECEDENCE + 1}) : le traceId est alors deja dans le MDC et dans l'en-tete
 * {@code X-Request-Id} de la reponse 413.
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private final MeasurementProperties props;
    private final ObjectMapper objectMapper;

    public BodySizeLimitFilter(MeasurementProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && ApiPaths.MEASUREMENTS.equals(pathOf(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long maxBytes = props.maxBodyBytes();
        long declared = request.getContentLengthLong();

        if (declared > maxBytes) {
            writePayloadTooLarge(request, response, maxBytes);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        // Corps sans Content-Length (chunked) : on ne peut pas tout verrouiller d'avance.
        try {
            chain.doFilter(new BoundedBodyRequest(request, maxBytes), response);
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.PAYLOAD_TOO_LARGE || response.isCommitted()) {
                throw ex;
            }
            writePayloadTooLarge(request, response, maxBytes);
        }
    }

    // ------------------------------------------------------------------ reponse 413

    /**
     * Ecrit le Problem Details 413 (RFC 9457) : meme structure que celle produite par le
     * GlobalExceptionHandler (type, title, status, detail, instance, code, traceId), puisque ce
     * filtre travaille hors du DispatcherServlet.
     */
    private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response, long maxBytes)
            throws IOException {
        ErrorCode code = ErrorCode.PAYLOAD_TOO_LARGE;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()),
                "Corps de requete trop volumineux : taille maximale " + maxBytes + " octets");
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", RequestIdFilter.currentRequestId());

        byte[] body = objectMapper.writeValueAsBytes(problem);
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** Chemin relatif au contexte : /api/measurements (le contexte est vide dans nos services). */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    // ------------------------------------------------------------------ corps sans Content-Length

    /** Requete sans Content-Length : le corps n'est lisible qu'a travers un flux borne. */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        private BoundedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Flux qui additionne les octets lus et signale le depassement de la limite. */
    private static final class BoundedInputStream extends ServletInputStream {

        private final InputStream delegate;
        private final long maxBytes;
        private long readBytes;
        private boolean finished;

        private BoundedInputStream(InputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b < 0) {
                finished = true;
            } else {
                account(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n < 0) {
                finished = true;
            } else if (n > 0) {
                account(n);
            }
            return n;
        }

        /**
         * Depassement de la limite : {@link ApiException} (unchecked) traverse Jackson et le
         * DispatcherServlet sans etre re-encapsulee en 400 MALFORMED_JSON, ce qui lui permet
         * d'atteindre le catch de {@code doFilterInternal}.
         */
        private void account(long n) {
            readBytes += n;
            if (readBytes > maxBytes) {
                throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE,
                        "Corps de requete trop volumineux : taille maximale " + maxBytes + " octets");
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Lecture asynchrone non supportee");
        }
    }
}
