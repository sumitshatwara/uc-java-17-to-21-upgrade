package io.spring.application.profile;

import java.util.Locale;

public final class ActivityDescriber {
  private static final Locale UK = Locale.of("en", "GB");

  private ActivityDescriber() {}

  public static String describe(ProfileActivity activity) {
    if (activity instanceof AuthoredArticle) {
      AuthoredArticle article = (AuthoredArticle) activity;
      return String.format(
          UK, "Wrote \"%s\" (%,d favourites)", article.getTitle(), article.getFavoritesCount());
    } else if (activity instanceof FollowerTally) {
      FollowerTally tally = (FollowerTally) activity;
      return String.format(UK, "Followed by %,d users", tally.getFollowers());
    } else if (activity instanceof FavoriteTally) {
      FavoriteTally tally = (FavoriteTally) activity;
      return String.format(
          UK,
          "Favourited %,d articles, received %,d favourites",
          tally.getGiven(),
          tally.getReceived());
    } else {
      throw new IllegalArgumentException("Unknown activity type: " + activity.getClass().getName());
    }
  }
}
