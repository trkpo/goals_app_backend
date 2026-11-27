package ru.trkpo.goals.identity.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class VerificationMailTests {

  private final JavaMailSender sender = mock(JavaMailSender.class);
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sendsTheExpectedTemplateAndPreservesLeadingZeros(boolean confirmation) {
    var mail = configuredMail();
    boolean accepted =
        confirmation
            ? mail.sendConfirmation("user@example.org", "012345")
            : mail.sendPasswordReset("user@example.org", "012345");

    assertThat(accepted).isTrue();
    var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(captured.capture());
    var message = captured.getValue();
    assertThat(message.getFrom()).isEqualTo("goals@example.org");
    assertThat(message.getTo()).containsExactly("user@example.org");
    assertThat(message.getSubject())
        .isEqualTo(
            confirmation
                ? "Подтверждение e-mail в Goals App"
                : "Восстановление пароля в Goals App");
    assertThat(message.getText()).contains("012345", "10 минут");
  }

  @Test
  void transportFailureReturnsFalse() {
    var mail = configuredMail();
    doThrow(new MailSendException("SMTP unavailable"))
        .when(sender)
        .send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    assertThat(mail.sendPasswordReset("user@example.org", "012345")).isFalse();
  }

  @Test
  void missingSmtpConfigurationReturnsFalse() {
    var mail =
        new VerificationMail(beans.getBeanProvider(JavaMailSender.class), "goals@example.org");
    assertThat(mail.sendConfirmation("user@example.org", "012345")).isFalse();
  }

  @Test
  void rejectsInvalidCodeBeforeSending() {
    var mail = configuredMail();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> mail.sendConfirmation("user@example.org", "12345"));
    verifyNoInteractions(sender);
  }

  private VerificationMail configuredMail() {
    beans.addBean("mailSender", sender);
    return new VerificationMail(beans.getBeanProvider(JavaMailSender.class), "goals@example.org");
  }
}
