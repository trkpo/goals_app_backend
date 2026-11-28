package ru.trkpo.goals.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.profiles.persistence.AvatarStore;
import ru.trkpo.goals.profiles.service.AvatarsService;

class AvatarsServiceTests {

  @Test
  void storeFailureBecomesServiceUnavailable() {
    var store = mock(AvatarStore.class);
    when(store.findAll()).thenThrow(new DataAccessResourceFailureException("database is down"));
    var service = new AvatarsService(store);

    assertThatThrownBy(service::list)
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AVATARS_UNAVAILABLE));
  }
}
