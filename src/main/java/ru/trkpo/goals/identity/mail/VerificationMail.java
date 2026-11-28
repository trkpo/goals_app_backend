package ru.trkpo.goals.identity.mail;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class VerificationMail {

  private static final Logger log = LoggerFactory.getLogger(VerificationMail.class);
  private static final Pattern CODE = Pattern.compile("[0-9]{6}");

  private final ObjectProvider<JavaMailSender> senders;
  private final String from;

  public VerificationMail(
      ObjectProvider<JavaMailSender> senders, @Value("${MAIL_FROM:}") String from) {
    this.senders = senders;
    this.from = from;
  }

  /** Call after saving the code. Returns true when the SMTP server accepts the message. */
  public boolean sendConfirmation(String recipient, String code) {
    return send(
        recipient,
        code,
        "Подтверждение e-mail в Goals App",
        "Введите этот код, чтобы подтвердить e-mail в Goals App:");
  }

  /** Keep the public recovery response uniform regardless of the delivery result. */
  public boolean sendPasswordReset(String recipient, String code) {
    return send(
        recipient,
        code,
        "Восстановление пароля в Goals App",
        "Введите этот код, чтобы восстановить пароль в Goals App:");
  }

  private boolean send(String recipient, String code, String subject, String instruction) {
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Verification code must contain six ASCII digits");
    }
    JavaMailSender sender = senders.getIfAvailable();
    if (sender == null || from.isBlank()) {
      log.debug("SMTP для кодов подтверждения отключён");
      return false;
    }

    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(recipient);
    message.setSubject(subject);
    message.setText(
        instruction
            + "\n\n"
            + code
            + "\n\nКод действует 10 минут. Если вы не запрашивали код, проигнорируйте письмо.\n");

    try {
      sender.send(message);
      return true;
    } catch (MailException exception) {
      // SMTP error details can contain recipient addresses.
      log.warn("SMTP не принял письмо с кодом ({})", exception.getClass().getSimpleName());
      return false;
    }
  }
}
