package edu.respaldos;

/** Envia un mensaje de texto. Existe para que el aviso al DBA no dependa de SMTP. */
public interface Mailer {
    void send(String to, String subject, String body) throws Exception;
}
