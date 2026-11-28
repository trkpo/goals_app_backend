package ru.trkpo.goals.profiles.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ru.trkpo.goals.common.error.ApiException;
import ru.trkpo.goals.common.error.ErrorCode;
import ru.trkpo.goals.profiles.domain.Avatar;
import ru.trkpo.goals.profiles.persistence.AvatarStore;

@Service
public class AvatarsService {

  private static final Logger log = LoggerFactory.getLogger(AvatarsService.class);

  private final AvatarStore store;

  public AvatarsService(AvatarStore store) {
    this.store = store;
  }

  public List<Avatar> list() {
    try {
      return store.findAll();
    } catch (DataAccessException exception) {
      log.error("Не удалось прочитать набор аватаров", exception);
      throw new ApiException(ErrorCode.AVATARS_UNAVAILABLE);
    }
  }
}
