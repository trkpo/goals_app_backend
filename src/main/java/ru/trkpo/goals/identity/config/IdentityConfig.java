package ru.trkpo.goals.identity.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class IdentityConfig {

  @Bean
  PasswordEncoder passwordEncoder(IdentityProperties properties) {
    return new BCryptPasswordEncoder(properties.getBcryptStrength());
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
