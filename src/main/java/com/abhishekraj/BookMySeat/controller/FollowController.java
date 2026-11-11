package com.abhishekraj.BookMySeat.controller;

import com.abhishekraj.BookMySeat.film.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Following films", description = "Follow a film to be emailed when it gets new showtimes.")
@RestController
@RequiredArgsConstructor
public class FollowController {

	private final FollowService followService;

	@Operation(summary = "Follow a film", description = "You are emailed once when new showtimes are added (a few "
			+ "minutes after an admin adds them, all of them in one email). Following twice is the same as once.")
	@PutMapping("/api/films/{id}/follow")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void follow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		followService.follow(userId(jwt), id);
	}

	@Operation(summary = "Stop following a film")
	@DeleteMapping("/api/films/{id}/follow")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		followService.unfollow(userId(jwt), id);
	}

	@Operation(summary = "The films you follow", description = "Newest follow first, with how many showtimes each has coming up.")
	@GetMapping("/api/films/following")
	public List<FollowService.Followed> following(@AuthenticationPrincipal Jwt jwt) {
		return followService.followedBy(userId(jwt));
	}

	private static Long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}
