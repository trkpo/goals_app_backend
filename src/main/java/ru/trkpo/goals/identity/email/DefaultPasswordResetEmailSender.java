package ru.trkpo.goals.identity.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import ru.trkpo.goals.identity.config.IdentityProperties;

/**
 * Отправка кода на e-mail. По умолчанию пишет код в журнал, чтобы локальная разработка не требовала
 * SMTP; при {@code identity.email.backend=smtp} отправляет письмо.
 */
@Component
public class DefaultPasswordResetEmailSender implements PasswordResetEmailSender {

  private static final Logger log = LoggerFactory.getLogger(DefaultPasswordResetEmailSender.class);

  private final IdentityProperties properties;
  private final JavaMailSenderImpl smtpSender;

  public DefaultPasswordResetEmailSender(IdentityProperties properties) {
    this.properties = properties;
    this.smtpSender = createSmtpSender(properties);
  }

  @Override
  public void send(PasswordResetEmail message) {
    IdentityProperties.Email email = properties.getEmail();
    if (!"smtp".equalsIgnoreCase(email.getBackend())) {
      log.info(
          "PASSWORD RESET CODE email={} code={} expires_minutes={}",
          message.email(),
          message.code(),
          message.expiresMinutes());
      return;
    }
    try {
      MimeMessage mimeMessage = smtpSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
      helper.setFrom(email.getFrom());
      helper.setTo(message.email());
      helper.setSubject("Восстановление пароля");
      helper.setText(
          """
          Вы запросили восстановление пароля.

          Код подтверждения: %s

          Код действует %d минут. Если вы не запрашивали восстановление,
          просто проигнорируйте письмо.
          """
              .formatted(message.code(), message.expiresMinutes()),
          false);
      smtpSender.send(mimeMessage);
    } catch (MessagingException ex) {
      throw new IllegalStateException("Не удалось отправить письмо", ex);
    }
  }

  private static JavaMailSenderImpl createSmtpSender(IdentityProperties properties) {
    IdentityProperties.Email email = properties.getEmail();
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(email.getHost());
    sender.setPort(email.getPort());
    if (email.getUsername() != null && !email.getUsername().isBlank()) {
      sender.setUsername(email.getUsername());
      sender.setPassword(email.getPassword());
    }
    Properties mailProperties = sender.getJavaMailProperties();
    mailProperties.put("mail.smtp.auth", Boolean.toString(!email.getUsername().isBlank()));
    mailProperties.put("mail.smtp.starttls.enable", Boolean.toString(email.isStarttls()));
    return sender;
  }
}
