package ru.trkpo.goals.profiles.web;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.trkpo.goals.profiles.domain.Avatar;
import ru.trkpo.goals.profiles.service.AvatarsService;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

@RestController
@RequestMapping("/api/v1/profiles/avatars")
class AvatarsController {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

  private final AvatarsService avatars;

  AvatarsController(AvatarsService avatars) {
    this.avatars = avatars;
  }

  @GetMapping
  ResponseEntity<?> list(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    List<Avatar> items = avatars.list();
    String etag = etag(items);
    if (notModified(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CACHE).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(CACHE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AvatarListResponse(items.stream().map(AvatarItem::from).toList()));
  }

  private static String etag(List<Avatar> items) {
    String versions =
        items.stream()
            .map(avatar -> avatar.id() + ":" + avatar.version())
            .collect(Collectors.joining("|"));
    return "\"avatars-" + Integer.toHexString(versions.hashCode()) + "\"";
  }

  private static boolean notModified(String header, String etag) {
    if (header == null || header.isBlank()) {
      return false;
    }
    return Arrays.stream(header.split(","))
        .map(String::trim)
        .anyMatch(value -> "*".equals(value) || etag.equals(value));
  }

  @JsonNaming(SnakeCaseStrategy.class)
  record AvatarListResponse(List<AvatarItem> items) {}

  @JsonNaming(SnakeCaseStrategy.class)
  record AvatarItem(String avatarId, String url, long version) {

    static AvatarItem from(Avatar avatar) {
      return new AvatarItem(avatar.id(), avatar.url(), avatar.version());
    }
  }
}
