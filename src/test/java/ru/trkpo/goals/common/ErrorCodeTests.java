package ru.trkpo.goals.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.trkpo.goals.common.error.ErrorCode;

class ErrorCodeTests {

  @Test
  void everyCodeHasUserMessage() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.defaultMessage()).as(code.name()).isNotBlank();
    }
  }

  @Test
  void forStatusFallsBackToGenericCodes() {
    assertThat(ErrorCode.forStatus(400)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(ErrorCode.forStatus(406)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(ErrorCode.forStatus(503)).isEqualTo(ErrorCode.INTERNAL_ERROR);
  }
}
