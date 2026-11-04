package io.spring.application.profile;

/** Activity types the profile summary knows how to describe. */
public sealed interface KnownActivity extends ProfileActivity
    permits AuthoredArticle, FollowerTally, FavoriteTally {}
