package io.spring.application.profile;

import io.spring.application.data.UserData;
import io.spring.infrastructure.mybatis.readservice.ProfileSummaryReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds profile summaries with virtual thread per read; pool-size remains for constructor and
 * configuration compatibility. Each read carries a simulated remote-call delay.
 */
@Service
public class ProfileSummaryService implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(ProfileSummaryService.class);

  private final UserReadService userReadService;
  private final ProfileSummaryReadService profileSummaryReadService;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("profile-summary-", 0).factory());
  private final long simulatedLatencyMs;
  private final String publicBaseUrl;

  public ProfileSummaryService(
      UserReadService userReadService,
      ProfileSummaryReadService profileSummaryReadService,
      @Value("${conduit.profile-summary.pool-size:4}") int poolSize,
      @Value("${conduit.profile-summary.simulated-latency-ms:50}") long simulatedLatencyMs,
      @Value("${conduit.public-base-url:https://conduit.example.com}") String publicBaseUrl) {
    this.userReadService = userReadService;
    this.profileSummaryReadService = profileSummaryReadService;
    this.simulatedLatencyMs = simulatedLatencyMs;
    this.publicBaseUrl = publicBaseUrl;
  }

  public Optional<ProfileSummaryData> summarize(String username) {
    UserData user = userReadService.findByUsername(username);
    if (user == null) {
      return Optional.empty();
    }
    String userId = user.getId();
    CompletableFuture<List<AuthoredArticle>> articlesFuture =
        fetch(() -> profileSummaryReadService.authoredArticles(userId));
    CompletableFuture<Integer> followersFuture =
        fetch(() -> profileSummaryReadService.countFollowers(userId));
    CompletableFuture<Integer> favoritesGivenFuture =
        fetch(() -> profileSummaryReadService.countFavoritesGiven(userId));

    List<AuthoredArticle> articles = articlesFuture.join();
    int followers = followersFuture.join();
    int favoritesGiven = favoritesGivenFuture.join();

    int favoritesReceived = 0;
    for (AuthoredArticle article : articles) {
      favoritesReceived += article.favoritesCount();
    }

    AuthoredArticle latestArticle = null;
    if (!articles.isEmpty()) {
      latestArticle = articles.getLast();
    }

    List<ProfileActivity> activities = new ArrayList<>(articles);
    activities.add(new FollowerTally(followers));
    activities.add(new FavoriteTally(favoritesGiven, favoritesReceived));
    List<String> activity =
        activities.stream().map(ActivityDescriber::describe).collect(Collectors.toList());

    return Optional.of(
        new ProfileSummaryData(
            user.getUsername(),
            articles.size(),
            followers,
            favoritesGiven,
            favoritesReceived,
            latestArticle,
            activity,
            profileUrl(user.getUsername())));
  }

  private <T> CompletableFuture<T> fetch(Supplier<T> call) {
    return CompletableFuture.supplyAsync(
        () -> {
          log.debug("profile summary read on thread {}", Thread.currentThread().threadId());
          simulateRemoteCall();
          return call.get();
        },
        executor);
  }

  private void simulateRemoteCall() {
    if (simulatedLatencyMs <= 0) {
      return;
    }
    try {
      Thread.sleep(simulatedLatencyMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during profile summary read", e);
    }
  }

  private String profileUrl(String username) {
    try {
      return URI.create(publicBaseUrl + "/profiles/" + username).toURL().toString();
    } catch (IllegalArgumentException | MalformedURLException e) {
      throw new IllegalStateException("Bad public base URL: " + publicBaseUrl, e);
    }
  }

  @Override
  public void destroy() {
    executor.close();
  }
}
