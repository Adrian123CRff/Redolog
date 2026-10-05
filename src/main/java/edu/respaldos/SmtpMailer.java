package edu.respaldos;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;

public final class SmtpMailer implements Mailer {
    private final MailConfig config;
    private final Session session;

    public SmtpMailer(MailConfig config) {
        this.config = config;
        var props = new Properties();
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.startTls()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        if (config.user() != null) {
            props.put("mail.smtp.auth", "true");
            session = Session.getInstance(props, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.user(), config.password() == null ? "" : config.password());
                }
            });
        } else session = Session.getInstance(props);
    }

    @Override public void send(String to, String subject, String body) throws Exception {
        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.from()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        Transport.send(message);
    }
}
