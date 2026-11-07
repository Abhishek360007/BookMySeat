package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.entity.WaitlistStatus;
import com.abhishekraj.BookMySeat.notification.OutboxEmailRepository;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.abhishekraj.BookMySeat.repository.WaitlistRepository;
import com.abhishekraj.BookMySeat.service.BookingService;
import com.abhishekraj.BookMySeat.service.WaitlistOffers;
import io.micrometer.core.instrument.MeterRegistry;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The waitlist of a sold-out show: joining, leaving, offers of seats that come free, and how offers end. Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WaitlistApiTest {

	@Autowired MockMvc mvc;
	@Autowired WaitlistRepository waitlistRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;
	@Autowired OutboxEmailRepository outboxRepository;
	@Autowired BookingService bookingService;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired WaitlistOffers offers;
	@Autowired MeterRegistry meterRegistry;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long showId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		long venueId = ((Number) JsonPath.read(adminCall(post("/api/venues"), "{\"name\":\"Waitlist Venue\",\"city\":\"Pune\"}")
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showId = ((Number) JsonPath.read(adminCall(post("/api/shows"),
						"{\"title\":\"Waitlist Show\",\"startTime\":\"" + start + "\",\"price\":200.00,\"venueId\":" + venueId + "}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	@AfterEach
	void cleanUp() {
		waitlistRepository.deleteAll();
		outboxRepository.deleteAll();
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void onlyASoldOutShow_hasAWaitlist_andEachPersonGetsThePlaceTheyJoinedAt() throws Exception {
		seats(2);
		String buyer = registerAndLogin("waitlist-buyer@example.com");
		String first = registerAndLogin("waitlist-first@example.com");
		String second = registerAndLogin("waitlist-second@example.com");

		join(first, 2).andExpect(status().isConflict()); // seats are still available
		bookAll(buyer);

		join(first, 2).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WAITING"))
				.andExpect(jsonPath("$.position").value(1))
				.andExpect(jsonPath("$.seats").value(2))
				.andExpect(jsonPath("$.showTitle").value("Waitlist Show"))
				.andExpect(jsonPath("$.offeredSeatIds").isEmpty());
		join(second, 1).andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(2));
		join(first, 1).andExpect(status().isConflict()); // once per show

		mine(second).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].position").value(2));

		// Leaving moves everyone behind up; leaving twice is a 404; joining again goes to the back
		mvc.perform(delete("/api/waitlist/" + showId).header("Authorization", "Bearer " + first)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/waitlist/" + showId).header("Authorization", "Bearer " + first)).andExpect(status().isNotFound());
		mine(first).andExpect(jsonPath("$").isEmpty());
		mine(second).andExpect(jsonPath("$[0].position").value(1));
		join(first, 2).andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(2));
	}

	@Test
	void invalidRequests_areRefused() throws Exception {
		seats(2);
		String user = registerAndLogin("waitlist-invalid@example.com");
		bookAll(user);

		join(user, 0).andExpect(status().isBadRequest());
		join(user, 7).andExpect(status().isBadRequest());
		mvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seats\":1}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/waitlist").header("Authorization", "Bearer " + user).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":999999,\"seats\":1}")).andExpect(status().isNotFound());

		adminCall(post("/api/shows/" + showId + "/cancel"), "").andExpect(status().isOk());
		join(user, 1).andExpect(status().isConflict()); // a cancelled show has no waitlist
	}

	@Test
	void twoJoinsAtOnce_bySamePerson_giveOnePlaceInLine() throws Exception {
		seats(2);
		String buyer = registerAndLogin("waitlist-buyer2@example.com");
		String user = registerAndLogin("waitlist-twice@example.com");
		bookAll(buyer);

		int attempts = 6;
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			Callable<Integer> call = () -> {
				go.await();
				var response = join(user, 1).andReturn().getResponse();
				if (response.getStatus() == 409) {
					assertThat(response.getContentAsString()).contains("already on the waitlist");
				}
				return response.getStatus();
			};
			results.add(pool.submit(call));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		assertThat(statuses).containsOnly(201, 409);
		assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
		mine(user).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void aCancellation_holdsTheFreedSeats_forTheFirstPersonInLineWhoseRequestFits_untilTheyBook() throws Exception {
		List<Integer> seats = seats(4);
		String buyer = registerAndLogin("waitlist-buyer3@example.com");
		int firstPair = book(buyer, seats.get(0), seats.get(1));
		book(buyer, seats.get(2), seats.get(3));
		String wantsThree = registerAndLogin("waitlist-three@example.com");
		String wantsTwo = registerAndLogin("waitlist-two@example.com");
		String wantsOne = registerAndLogin("waitlist-one@example.com");
		join(wantsThree, 3).andExpect(status().isCreated());
		join(wantsTwo, 2).andExpect(status().isCreated());
		join(wantsOne, 1).andExpect(status().isCreated());

		Instant cancelled = Instant.now();
		cancel(buyer, firstPair);

		// Two seats came free: too few for the first person, exactly right for the second, none left for the third
		String offer = mine(wantsTwo).andExpect(jsonPath("$[0].status").value("OFFERED"))
				.andExpect(jsonPath("$[0].position").doesNotExist())
				.andExpect(jsonPath("$[0].offeredSeatIds", hasSize(2)))
				.andExpect(jsonPath("$[0].offeredSeatNumbers[0]").value("A1"))
				.andReturn().getResponse().getContentAsString();
		Instant expires = Instant.parse(JsonPath.read(offer, "$[0].offerExpiresAt"));
		assertThat(expires).isBetween(cancelled.plusSeconds(14 * 60), Instant.now().plusSeconds(15 * 60));
		mine(wantsThree).andExpect(jsonPath("$[0].status").value("WAITING")).andExpect(jsonPath("$[0].position").value(1));
		mine(wantsOne).andExpect(jsonPath("$[0].status").value("WAITING")).andExpect(jsonPath("$[0].position").value(2));

		// The seats are held for them (for the whole window), so nobody else can take them
		assertThat(redis.getExpire("hold:show:" + showId + ":seat:" + seats.get(0))).isGreaterThan(14 * 60);
		hold(wantsOne, seats.get(0)).andExpect(status().isConflict());
		// Holding them again themselves (as the web app does) doesn't shorten the offer to the usual 5 minutes
		hold(wantsTwo, seats.get(0), seats.get(1)).andExpect(status().isCreated());
		assertThat(redis.getExpire("hold:show:" + showId + ":seat:" + seats.get(0))).isGreaterThan(14 * 60);

		assertThat(outboxRepository.findAll()).filteredOn(e -> e.getKind().equals("WAITLIST_OFFER")).singleElement()
				.satisfies(e -> {
					assertThat(e.getRecipient()).isEqualTo("waitlist-two@example.com");
					assertThat(e.getSubject()).isEqualTo("Seats for you: Waitlist Show");
					assertThat(e.getBodyText()).contains("Seats A1, A2 are held for you until").contains("/#/show/" + showId);
				});

		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + wantsTwo).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seats.get(0), seats.get(1)) + "}"))
				.andExpect(status().isCreated());
		mine(wantsTwo).andExpect(jsonPath("$").isEmpty()); // claimed: off the waitlist
		assertThat(waitlistRepository.findAll()).filteredOn(e -> e.getStatus() == WaitlistStatus.CLAIMED).hasSize(1);
	}

	@Test
	void aCancellationThatRollsBack_leavesNoOfferAndNoHold() throws Exception {
		List<Integer> seats = seats(1);
		String buyer = registerAndLogin("waitlist-buyer4@example.com");
		int bookingId = book(buyer, seats.get(0));
		String waiting = registerAndLogin("waitlist-rollback@example.com");
		join(waiting, 1).andExpect(status().isCreated());
		long buyerId = userRepository.findByEmail("waitlist-buyer4@example.com").orElseThrow().getId();

		new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
			bookingService.cancel(buyerId, (long) bookingId);
			assertThat(redis.hasKey("hold:show:" + showId + ":seat:" + seats.get(0))).isTrue(); // offered inside
			tx.setRollbackOnly();
		});

		assertThat(redis.hasKey("hold:show:" + showId + ":seat:" + seats.get(0))).isFalse();
		mine(waiting).andExpect(jsonPath("$[0].status").value("WAITING"));
		assertThat(outboxRepository.findAll()).noneMatch(e -> e.getKind().equals("WAITLIST_OFFER"));
	}

	@RepeatedTest(5)
	void twoCancellationsAtOnce_offerToTwoDifferentPeople() throws Exception {
		List<Integer> seats = seats(2);
		String buyer = registerAndLogin("waitlist-buyer5@example.com");
		int first = book(buyer, seats.get(0));
		int second = book(buyer, seats.get(1));
		String a = registerAndLogin("waitlist-a@example.com");
		String b = registerAndLogin("waitlist-b@example.com");
		join(a, 1).andExpect(status().isCreated());
		join(b, 1).andExpect(status().isCreated());

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int bookingId : List.of(first, second)) {
			results.add(pool.submit(() -> {
				go.await();
				return mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + buyer))
						.andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		for (Future<Integer> result : results) {
			assertThat(result.get()).isEqualTo(200);
		}
		pool.shutdown();

		List<Integer> offeredToA = JsonPath.read(mine(a).andReturn().getResponse().getContentAsString(), "$[0].offeredSeatIds");
		List<Integer> offeredToB = JsonPath.read(mine(b).andReturn().getResponse().getContentAsString(), "$[0].offeredSeatIds");
		assertThat(offeredToA).hasSize(1);
		assertThat(offeredToB).hasSize(1);
		assertThat(List.of(offeredToA.getFirst(), offeredToB.getFirst())).containsExactlyInAnyOrderElementsOf(seats);
	}

	@Test
	void anOfferThatRunsOut_goesToTheNextPersonInLine() throws Exception {
		List<Integer> seats = seats(1);
		String buyer = registerAndLogin("waitlist-buyer6@example.com");
		int bookingId = book(buyer, seats.get(0));
		String slow = registerAndLogin("waitlist-slow@example.com");
		String next = registerAndLogin("waitlist-next@example.com");
		join(slow, 1).andExpect(status().isCreated());
		join(next, 1).andExpect(status().isCreated());
		cancel(buyer, bookingId);
		mine(slow).andExpect(jsonPath("$[0].status").value("OFFERED"));
		double expiredBefore = offerCount("expired");

		// 15 minutes later: the offer's time is up and Redis has dropped its hold
		jdbc.update("update waitlist_entries set offer_expires_at = now() - interval '1 second' where status = 'OFFERED'");
		redis.delete("hold:show:" + showId + ":seat:" + seats.get(0));
		offers.sweep();

		mine(slow).andExpect(jsonPath("$").isEmpty());
		mine(next).andExpect(jsonPath("$[0].status").value("OFFERED"))
				.andExpect(jsonPath("$[0].offeredSeatIds[0]").value(seats.get(0)));
		assertThat(redis.opsForValue().get("hold:show:" + showId + ":seat:" + seats.get(0)))
				.isEqualTo(String.valueOf(userRepository.findByEmail("waitlist-next@example.com").orElseThrow().getId()));
		assertThat(offerCount("expired")).isEqualTo(expiredBefore + 1);
	}

	@Test
	void decliningAnOffer_orBookingFewerSeats_passesTheRestOnAtOnce() throws Exception {
		List<Integer> seats = seats(2);
		String buyer = registerAndLogin("waitlist-buyer7@example.com");
		int bookingId = book(buyer, seats.get(0), seats.get(1));
		String decliner = registerAndLogin("waitlist-decliner@example.com");
		String partial = registerAndLogin("waitlist-partial@example.com");
		String last = registerAndLogin("waitlist-last@example.com");
		join(decliner, 2).andExpect(status().isCreated());
		join(partial, 2).andExpect(status().isCreated());
		join(last, 1).andExpect(status().isCreated());
		cancel(buyer, bookingId);
		mine(decliner).andExpect(jsonPath("$[0].status").value("OFFERED"));

		mvc.perform(delete("/api/waitlist/" + showId).header("Authorization", "Bearer " + decliner)).andExpect(status().isNoContent());
		mine(partial).andExpect(jsonPath("$[0].status").value("OFFERED")).andExpect(jsonPath("$[0].offeredSeatIds", hasSize(2)));

		// They book only one of the two: the other is released and offered to the next who fits
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + partial).contentType(MediaType.APPLICATION_JSON)
				.content("{\"showId\":" + showId + ",\"seatIds\":[" + seats.get(0) + "]}")).andExpect(status().isCreated());
		mine(last).andExpect(jsonPath("$[0].status").value("OFFERED")).andExpect(jsonPath("$[0].offeredSeatIds[0]").value(seats.get(1)));
	}

	@Test
	void aHeldSeatThatComesFree_withoutACancellation_goesToTheLine() throws Exception {
		List<Integer> seats = seats(1);
		String holder = registerAndLogin("waitlist-holder@example.com");
		String waiting = registerAndLogin("waitlist-patient@example.com");
		hold(holder, seats.get(0)).andExpect(status().isCreated());
		join(waiting, 1).andExpect(status().isCreated()); // held by someone = sold out for now

		redis.delete("hold:show:" + showId + ":seat:" + seats.get(0)); // they never paid: the hold ran out
		offers.sweep();
		mine(waiting).andExpect(jsonPath("$[0].status").value("OFFERED"));
	}

	@Test
	void aCancelledShow_closesItsLineAndTellsEveryone_aStartedShow_closesItQuietly() throws Exception {
		List<Integer> seats = seats(1);
		String buyer = registerAndLogin("waitlist-buyer8@example.com");
		book(buyer, seats.get(0));
		String waiting = registerAndLogin("waitlist-closed@example.com");
		join(waiting, 1).andExpect(status().isCreated());

		adminCall(post("/api/shows/" + showId + "/cancel"), "").andExpect(status().isOk());
		mine(waiting).andExpect(jsonPath("$").isEmpty());
		assertThat(outboxRepository.findAll()).filteredOn(e -> e.getKind().equals("WAITLIST_CLOSED")).singleElement()
				.satisfies(e -> assertThat(e.getRecipient()).isEqualTo("waitlist-closed@example.com"));

		// Another sold-out show that simply starts: the sweep closes its line, no email
		long otherShow = showId;
		setUp();
		List<Integer> others = seats(1);
		book(buyer, others.get(0));
		join(waiting, 1).andExpect(status().isCreated());
		jdbc.update("update shows set start_time = now() - interval '1 minute' where id = ?", showId);
		offers.sweep();
		mine(waiting).andExpect(jsonPath("$").isEmpty());
		assertThat(waitlistRepository.findAll()).extracting(e -> e.getStatus()).containsOnly(WaitlistStatus.CLOSED);
		assertThat(outboxRepository.findAll()).filteredOn(e -> e.getKind().equals("WAITLIST_CLOSED")).hasSize(1);
		assertThat(otherShow).isNotEqualTo(showId);
	}

	// ---- helpers ----

	double offerCount(String result) {
		return meterRegistry.counter("waitlist.offers", "result", result).count();
	}

	List<Integer> seats(int count) throws Exception {
		adminCall(post("/api/shows/" + showId + "/seats"), "{\"rows\":1,\"seatsPerRow\":" + count + "}").andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats")).andReturn().getResponse().getContentAsString(), "$[*].id");
	}

	ResultActions hold(String token, Integer... seatIds) throws Exception {
		return mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"));
	}

	int book(String token, Integer... seatIds) throws Exception {
		hold(token, seatIds).andExpect(status().isCreated());
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seatIds\":" + List.of(seatIds) + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	void cancel(String token, int bookingId) throws Exception {
		mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	ResultActions join(String token, int seats) throws Exception {
		return mvc.perform(post("/api/waitlist").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"showId\":" + showId + ",\"seats\":" + seats + "}"));
	}

	ResultActions mine(String token) throws Exception {
		return mvc.perform(get("/api/waitlist/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

	void bookAll(String token) throws Exception {
		List<Integer> seatIds = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + seatIds + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
	}

	ResultActions adminCall(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Waitlist Fan\",\"email\":\"" + email + "\",\"password\":\"Waitlist-Pw-123\"}"));
		return login(email, "Waitlist-Pw-123");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}
