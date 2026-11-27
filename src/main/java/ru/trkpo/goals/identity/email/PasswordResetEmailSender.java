package ru.trkpo.goals.identity.email;

public interface PasswordResetEmailSender {

  void send(PasswordResetEmail message);
}
