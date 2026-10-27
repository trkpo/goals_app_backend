package ru.trkpo.goals;

import org.springframework.boot.SpringApplication;

public class TestGoalsAppBackendApplication {

  public static void main(String[] args) {
    SpringApplication.from(GoalsAppBackendApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
