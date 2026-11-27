package ru.trkpo.goals.profiles.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.trkpo.goals.profiles.domain.Avatar;

@Repository
public class AvatarStore {

  private final JdbcClient db;

  public AvatarStore(JdbcClient db) {
    this.db = db;
  }

  public List<Avatar> findAll() {
    return db.sql(
            """
            select avatar_id, url, version
            from profiles.avatars
            order by avatar_id
            """)
        .query(
            (rs, row) ->
                new Avatar(rs.getString("avatar_id"), rs.getString("url"), rs.getLong("version")))
        .list();
  }
}
