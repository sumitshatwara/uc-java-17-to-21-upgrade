package io.spring.application.profile;

import java.util.Locale;

public final class ActivityDescriber {
  private static final Locale UK = Locale.of("en", "GB");

  private ActivityDescriber() {}

  public static String describe(ProfileActivity activity) {
    if (!(activity instanceof KnownActivity known)) {
      throw new IllegalArgumentException("Unknown activity type: " + activity.getClass().getName());
    }
    return switch (known) {
      case AuthoredArticle(var slug, var title, var favoritesCount) ->
          String.format(UK, "Wrote \"%s\" (%,d favourites)", title, favoritesCount);
      case FollowerTally(var followers) -> String.format(UK, "Followed by %,d users", followers);
      case FavoriteTally(var given, var received) ->
          String.format(UK, "Favourited %,d articles, received %,d favourites", given, received);
    };
  }
}
