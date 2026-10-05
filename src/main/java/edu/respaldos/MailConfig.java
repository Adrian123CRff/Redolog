package edu.respaldos;

import java.util.Optional;
import java.util.function.Function;

/** Configuracion SMTP tomada del entorno; la contrasena nunca se guarda en el catalogo ni en el repositorio. */
public record MailConfig(String host, int port, String user, String password, String from, boolean startTls) {
    public static Optional<MailConfig> fromEnvironment() { return from(System::getenv); }

    /** GESTOR_SMTP_HOST es obligatorio; sin el no hay correo. */
    public static Optional<MailConfig> from(Function<String, String> env) {
        String host = env.apply("GESTOR_SMTP_HOST");
        if (host == null || host.isBlank()) return Optional.empty();
        String user = blankToNull(env.apply("GESTOR_SMTP_USER"));
        String from = blankToNull(env.apply("GESTOR_SMTP_FROM"));
        if (from == null) from = user;
        Models.require(from != null, "Indica GESTOR_SMTP_FROM (o GESTOR_SMTP_USER) como remitente.");
        String port = blankToNull(env.apply("GESTOR_SMTP_PORT"));
        String tls = blankToNull(env.apply("GESTOR_SMTP_STARTTLS"));
        return Optional.of(new MailConfig(host.trim(), port == null ? 587 : Integer.parseInt(port.trim()), user,
            env.apply("GESTOR_SMTP_PASSWORD"), from, tls == null || Boolean.parseBoolean(tls.trim())));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
