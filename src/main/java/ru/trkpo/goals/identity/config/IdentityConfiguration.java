package ru.trkpo.goals.identity.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

  @Bean
  PasswordEncoder passwordEncoder(@Value("${identity.bcrypt-strength:10}") int strength) {
    return new BCryptPasswordEncoder(BCryptVersion.$2B, strength);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
