package ru.trkpo.goals.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Настройки восстановления пароля; значения по умолчанию соответствуют F_Auth_12–F_Auth_14. */
@Component
@ConfigurationProperties(prefix = "identity")
public class IdentityProperties {

  private String codePepper = "dev-code-pepper-change-me";
  private String resetTokenPepper = "dev-reset-token-pepper-change-me";
  private Duration codeTtl = Duration.ofMinutes(10);
  private Duration resetTokenTtl = Duration.ofMinutes(10);
  private Duration requestCooldown = Duration.ofMinutes(5);
  private int verifyLimit = 3;
  private Duration verifyWindow = Duration.ofMinutes(1);
  private int bcryptStrength = 12;
  private Email email = new Email();

  public String getCodePepper() {
    return codePepper;
  }

  public void setCodePepper(String codePepper) {
    this.codePepper = codePepper;
  }

  public String getResetTokenPepper() {
    return resetTokenPepper;
  }

  public void setResetTokenPepper(String resetTokenPepper) {
    this.resetTokenPepper = resetTokenPepper;
  }

  public Duration getCodeTtl() {
    return codeTtl;
  }

  public void setCodeTtl(Duration codeTtl) {
    this.codeTtl = codeTtl;
  }

  public Duration getResetTokenTtl() {
    return resetTokenTtl;
  }

  public void setResetTokenTtl(Duration resetTokenTtl) {
    this.resetTokenTtl = resetTokenTtl;
  }

  public Duration getRequestCooldown() {
    return requestCooldown;
  }

  public void setRequestCooldown(Duration requestCooldown) {
    this.requestCooldown = requestCooldown;
  }

  public int getVerifyLimit() {
    return verifyLimit;
  }

  public void setVerifyLimit(int verifyLimit) {
    this.verifyLimit = verifyLimit;
  }

  public Duration getVerifyWindow() {
    return verifyWindow;
  }

  public void setVerifyWindow(Duration verifyWindow) {
    this.verifyWindow = verifyWindow;
  }

  public int getBcryptStrength() {
    return bcryptStrength;
  }

  public void setBcryptStrength(int bcryptStrength) {
    this.bcryptStrength = bcryptStrength;
  }

  public Email getEmail() {
    return email;
  }

  public void setEmail(Email email) {
    this.email = email;
  }

  /** Настройки отправки письма. Без SMTP код выводится в журнал (локальная разработка). */
  public static class Email {

    private String backend = "console";
    private String from = "no-reply@goals.local";
    private String host = "localhost";
    private int port = 25;
    private String username = "";
    private String password = "";
    private boolean starttls = true;

    public String getBackend() {
      return backend;
    }

    public void setBackend(String backend) {
      this.backend = backend;
    }

    public String getFrom() {
      return from;
    }

    public void setFrom(String from) {
      this.from = from;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public boolean isStarttls() {
      return starttls;
    }

    public void setStarttls(boolean starttls) {
      this.starttls = starttls;
    }
  }
}
