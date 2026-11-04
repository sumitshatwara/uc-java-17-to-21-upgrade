package io.spring.application.profile;

public record AuthoredArticle(String slug, String title, int favoritesCount)
    implements KnownActivity {
  public String getSlug() {
    return slug;
  }
}
